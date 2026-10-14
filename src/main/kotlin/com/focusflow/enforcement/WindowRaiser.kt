package com.focusflow.enforcement

import com.focusflow.services.RaiseResult
import com.focusflow.services.WindowRef
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

object LauncherWindowRegistry {
    private val handles = ConcurrentHashMap.newKeySet<Long>()

    fun register(handle: Long) {
        if (handle != 0L) handles += handle
    }

    fun unregister(handle: Long) {
        handles -= handle
    }

    fun snapshot(): Set<Long> = handles.toSet()
}

/**
 * Finds real app windows and raises them above every Focus Launcher window.
 * All callers run from the launch coordinator's IO scope.
 */
object WindowRaiser {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val DWMWA_CLOAKED = 14
    private const val SW_RESTORE = 9
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_SHOWWINDOW = 0x0040
    private const val ASFW_ANY = -1
    private const val VK_MENU = 0x12
    private const val KEYEVENTF_KEYUP = 0x0002
    private const val FOREGROUND_VERIFY_DELAY_MS = 300L

    private val hwndTop = HWND(Pointer(0L))
    private val hwndNotTopmost = HWND(Pointer(-2L))

    private interface EnumWindowsCallback : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: HWND, data: Pointer?): Boolean
    }

    private interface User32ActivationApi : StdCallLibrary {
        fun EnumWindows(callback: EnumWindowsCallback, data: Pointer?): Boolean
        fun GetForegroundWindow(): HWND?
        fun GetWindowThreadProcessId(hwnd: HWND, processId: IntArray): Int
        fun IsWindowVisible(hwnd: HWND): Boolean
        fun IsIconic(hwnd: HWND): Boolean
        fun GetWindowLongW(hwnd: HWND, index: Int): Int
        fun ShowWindow(hwnd: HWND, command: Int): Boolean
        fun SetWindowPos(
            hwnd: HWND,
            insertAfter: HWND?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int
        ): Boolean
        fun SetForegroundWindow(hwnd: HWND): Boolean
        fun AllowSetForegroundWindow(processId: Int): Boolean
        fun AttachThreadInput(attachThreadId: Int, attachToThreadId: Int, attach: Boolean): Boolean
        fun keybd_event(key: Byte, scan: Byte, flags: Int, extraInfo: Pointer?)

        companion object {
            val INSTANCE: User32ActivationApi =
                Native.load("user32", User32ActivationApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private interface DwmApi : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int

        companion object {
            val INSTANCE: DwmApi =
                Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    fun findWindows(pids: Set<Long>): List<WindowRef> {
        if (!isWindows || pids.isEmpty()) return emptyList()

        val appWindows = mutableListOf<WindowRef>()
        val toolWindows = mutableListOf<WindowRef>()
        return try {
            val api = User32ActivationApi.INSTANCE
            api.EnumWindows(object : EnumWindowsCallback {
                override fun callback(hwnd: HWND, data: Pointer?): Boolean {
                    if (!api.IsWindowVisible(hwnd) || isCloaked(hwnd)) return true

                    val processId = IntArray(1)
                    if (api.GetWindowThreadProcessId(hwnd, processId) == 0) return true
                    val pid = processId[0].toLong() and 0xFFFF_FFFFL
                    if (pid !in pids) return true

                    val rawHandle = Pointer.nativeValue(hwnd.pointer)
                    if (rawHandle == 0L) return true
                    val window = WindowRef(rawHandle, pid)
                    if (api.GetWindowLongW(hwnd, GWL_EXSTYLE) and WS_EX_TOOLWINDOW != 0) {
                        toolWindows += window
                    } else {
                        appWindows += window
                    }
                    return true
                }
            }, null)

            (if (appWindows.isNotEmpty()) appWindows else toolWindows)
                .distinctBy(WindowRef::handle)
                .sortedWith(compareBy(WindowRef::pid, WindowRef::handle))
        } catch (failure: Throwable) {
            EnforcementLog.warn("Launcher", "top-level window enumeration failed", failure)
            emptyList()
        }
    }

    suspend fun raise(window: WindowRef): RaiseResult {
        if (!isWindows) return RaiseResult(false, "unsupported-platform")

        val api = User32ActivationApi.INSTANCE
        val hwnd = HWND(Pointer(window.handle))
        var lastTier = "SetForegroundWindow"
        try {
            demoteLauncherWindows(api)

            if (api.IsIconic(hwnd)) api.ShowWindow(hwnd, SW_RESTORE)
            api.SetWindowPos(
                hwnd,
                hwndTop,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_SHOWWINDOW
            )

            val strategies = listOf<Pair<String, () -> Boolean>>(
                "SetForegroundWindow" to { api.SetForegroundWindow(hwnd) },
                "AllowSetForegroundWindow" to {
                    api.AllowSetForegroundWindow(ASFW_ANY)
                    api.SetForegroundWindow(hwnd)
                },
                "AttachThreadInput" to { setForegroundWithAttachedInput(api, hwnd) },
                "SyntheticAlt" to {
                    api.keybd_event(VK_MENU.toByte(), 0, 0, null)
                    api.keybd_event(VK_MENU.toByte(), 0, KEYEVENTF_KEYUP, null)
                    api.SetForegroundWindow(hwnd)
                }
            )

            for ((tier, action) in strategies) {
                lastTier = tier
                if (!action()) continue
                delay(FOREGROUND_VERIFY_DELAY_MS)
                val foreground = api.GetForegroundWindow()
                if (foreground != null && Pointer.nativeValue(foreground.pointer) == window.handle) {
                    return RaiseResult(success = true, tier = tier)
                }
                EnforcementLog.info(
                    "Launcher",
                    "foreground verification failed pid=${window.pid} tier=$tier"
                )
            }
            return RaiseResult(success = false, tier = lastTier)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            EnforcementLog.warn(
                "Launcher",
                "window raise failed pid=${window.pid} tier=$lastTier",
                failure
            )
            return RaiseResult(success = false, tier = "$lastTier-error")
        }
    }

    private fun demoteLauncherWindows(api: User32ActivationApi) {
        LauncherWindowRegistry.snapshot().forEach { rawHandle ->
            val hwnd = HWND(Pointer(rawHandle))
            val demoted = api.SetWindowPos(
                hwnd,
                hwndNotTopmost,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE
            )
            if (!demoted) {
                EnforcementLog.warn("Launcher", "failed to demote launcher window hwnd=$rawHandle")
            }
        }
    }

    private fun setForegroundWithAttachedInput(api: User32ActivationApi, target: HWND): Boolean {
        val foreground = api.GetForegroundWindow() ?: return api.SetForegroundWindow(target)
        val foregroundThread = api.GetWindowThreadProcessId(foreground, IntArray(1))
        val targetThread = api.GetWindowThreadProcessId(target, IntArray(1))
        val currentThread = Kernel32.INSTANCE.GetCurrentThreadId()
        if (foregroundThread == 0 || targetThread == 0 || currentThread == 0) {
            return api.SetForegroundWindow(target)
        }

        val attachedForeground = api.AttachThreadInput(currentThread, foregroundThread, true)
        val attachedTarget = if (targetThread != foregroundThread) {
            api.AttachThreadInput(currentThread, targetThread, true)
        } else {
            false
        }
        return try {
            api.SetForegroundWindow(target)
        } finally {
            if (attachedTarget) api.AttachThreadInput(currentThread, targetThread, false)
            if (attachedForeground) api.AttachThreadInput(currentThread, foregroundThread, false)
        }
    }

    private fun isCloaked(hwnd: HWND): Boolean = try {
        val cloaked = IntByReference()
        val result = DwmApi.INSTANCE.DwmGetWindowAttribute(
            hwnd,
            DWMWA_CLOAKED,
            cloaked.pointer,
            Int.SIZE_BYTES
        )
        result == 0 && cloaked.value != 0
    } catch (_: Throwable) {
        false
    }
}
