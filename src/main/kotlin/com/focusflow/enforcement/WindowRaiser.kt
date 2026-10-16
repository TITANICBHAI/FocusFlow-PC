package com.focusflow.enforcement

import com.focusflow.services.RaiseResult
import com.focusflow.services.WindowRef
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
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
    private const val GW_OWNER = 4
    private const val DWMWA_CLOAKED = 14
    private const val SM_XVIRTUALSCREEN = 76
    private const val SM_YVIRTUALSCREEN = 77
    private const val SM_CXVIRTUALSCREEN = 78
    private const val SM_CYVIRTUALSCREEN = 79
    private const val SW_RESTORE = 9
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_SHOWWINDOW = 0x0040
    private const val SWP_ASYNCWINDOWPOS = 0x4000
    private const val VK_MENU = 0x12
    private const val KEYEVENTF_KEYUP = 0x0002
    private const val FOREGROUND_VERIFY_DELAY_MS = 200L

    private val hwndTop = HWND(Pointer(0L))
    private val hwndNotTopmost = HWND(Pointer(-2L))
    private val loggedAttemptIssues = ConcurrentHashMap.newKeySet<String>()

    fun finishAttempt(attemptId: String) {
        loggedAttemptIssues.removeIf { it.startsWith("$attemptId:") }
    }

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
        fun GetWindow(hwnd: HWND, command: Int): HWND?
        fun GetWindowRect(hwnd: HWND, rect: RECT): Boolean
        fun GetWindowTextLengthW(hwnd: HWND): Int
        fun GetWindowTextW(hwnd: HWND, text: CharArray, maxCount: Int): Int
        fun GetSystemMetrics(index: Int): Int
        fun ShowWindowAsync(hwnd: HWND, command: Int): Boolean
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

    fun findWindows(pids: Set<Long>): List<WindowRef> =
        findWindows(pids, attemptId = null)

    fun findWindows(pids: Set<Long>, attemptId: String?): List<WindowRef> {
        if (!isWindows || pids.isEmpty()) return emptyList()

        val appWindows = mutableListOf<WindowRef>()
        val toolWindows = mutableListOf<WindowRef>()
        return try {
            val api = User32ActivationApi.INSTANCE
            var zOrder = 0
            val virtualLeft = api.GetSystemMetrics(SM_XVIRTUALSCREEN)
            val virtualTop = api.GetSystemMetrics(SM_YVIRTUALSCREEN)
            val virtualWidth = api.GetSystemMetrics(SM_CXVIRTUALSCREEN)
            val virtualHeight = api.GetSystemMetrics(SM_CYVIRTUALSCREEN)
            api.EnumWindows(object : EnumWindowsCallback {
                override fun callback(hwnd: HWND, data: Pointer?): Boolean {
                    val currentZOrder = zOrder++
                    if (!api.IsWindowVisible(hwnd) || isCloaked(hwnd)) return true

                    val processId = IntArray(1)
                    if (api.GetWindowThreadProcessId(hwnd, processId) == 0) return true
                    val pid = processId[0].toLong() and 0xFFFF_FFFFL
                    if (pid !in pids) return true

                    val rawHandle = Pointer.nativeValue(hwnd.pointer)
                    if (rawHandle == 0L) return true
                    val bounds = RECT()
                    if (!api.GetWindowRect(hwnd, bounds)) return true
                    val width = bounds.right - bounds.left
                    val height = bounds.bottom - bounds.top
                    if (width <= 0 || height <= 0) return true
                    if (virtualWidth > 0 && virtualHeight > 0 &&
                        (bounds.right <= virtualLeft ||
                            bounds.left >= virtualLeft + virtualWidth ||
                            bounds.bottom <= virtualTop ||
                            bounds.top >= virtualTop + virtualHeight)
                    ) return true

                    val toolWindow = api.GetWindowLongW(hwnd, GWL_EXSTYLE) and
                        WS_EX_TOOLWINDOW != 0
                    val owner = api.GetWindow(hwnd, GW_OWNER)
                    val ownerless = owner == null ||
                        Pointer.nativeValue(owner.pointer) == 0L
                    val hasTitle = api.GetWindowTextLengthW(hwnd) > 0
                    val window = WindowRef(
                        handle = rawHandle,
                        pid = pid,
                        ownerless = ownerless,
                        hasTitle = hasTitle,
                        toolWindow = toolWindow,
                        reasonableSize = width >= 160 && height >= 100,
                        zOrder = currentZOrder
                    )
                    if (toolWindow) {
                        toolWindows += window
                    } else {
                        appWindows += window
                    }
                    return true
                }
            }, null)

            com.focusflow.services.orderLauncherWindows(
                if (appWindows.isNotEmpty()) appWindows else toolWindows
            )
        } catch (failure: Throwable) {
            warnAttemptOnce(
                attemptId,
                "enumeration",
                "top-level window enumeration failed",
                failure
            )
            emptyList()
        }
    }

    suspend fun raise(window: WindowRef): RaiseResult =
        raise(window, attemptId = null)

    suspend fun raise(window: WindowRef, attemptId: String?): RaiseResult {
        if (!isWindows) {
            return RaiseResult(false, "unsupported-platform", listOf("unsupported-platform=false"))
        }

        val api = User32ActivationApi.INSTANCE
        val hwnd = HWND(Pointer(window.handle))
        var lastTier = "SetForegroundWindow"
        val tierResults = mutableListOf<String>()
        var foreground = ForegroundObservation(null, null)
        try {
            demoteLauncherWindows(api, attemptId)

            if (api.IsIconic(hwnd)) api.ShowWindowAsync(hwnd, SW_RESTORE)
            api.SetWindowPos(
                hwnd,
                hwndTop,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_SHOWWINDOW or SWP_ASYNCWINDOWPOS
            )

            delay(FOREGROUND_VERIFY_DELAY_MS)
            foreground = foregroundObservation(api)
            if (foreground.processId == window.pid ||
                foreground.rootOwnerHandle == window.handle
            ) {
                tierResults += "already-foreground"
                return RaiseResult(
                    success = true,
                    tier = "AlreadyForeground",
                    tierResults = tierResults,
                    foregroundPid = foreground.processId,
                    foregroundRootOwnerHandle = foreground.rootOwnerHandle
                )
            }

            val strategies = listOf<Pair<String, () -> Boolean>>(
                "AllowTargetForeground" to {
                    api.AllowSetForegroundWindow(window.pid.toInt())
                },
                "SetForegroundWindow" to { api.SetForegroundWindow(hwnd) },
                "AttachThreadInput" to { setForegroundWithAttachedInput(api, hwnd) },
                "SyntheticAlt" to {
                    api.keybd_event(VK_MENU.toByte(), 0, 0, null)
                    api.keybd_event(VK_MENU.toByte(), 0, KEYEVENTF_KEYUP, null)
                    api.SetForegroundWindow(hwnd)
                },
            )

            // The kiosk foreground lock remains engaged throughout this method.
            // Grant foreground permission only to the selected app process; never
            // use LSFW_UNLOCK or ASFW_ANY in the raise path.
            for ((tier, action) in strategies) {
                lastTier = tier
                if (!action()) {
                    tierResults += "$tier=call-failed"
                    continue
                }
                delay(FOREGROUND_VERIFY_DELAY_MS)
                foreground = foregroundObservation(api)
                if (foreground.processId == window.pid ||
                    foreground.rootOwnerHandle == window.handle
                ) {
                    tierResults += "$tier=target-process-or-root-owner"
                    return RaiseResult(
                        success = true,
                        tier = tier,
                        tierResults = tierResults,
                        foregroundPid = foreground.processId,
                        foregroundRootOwnerHandle = foreground.rootOwnerHandle
                    )
                }
                tierResults += "$tier=not-target-process-or-root-owner"
            }
            return RaiseResult(
                success = false,
                tier = lastTier,
                tierResults = tierResults,
                foregroundPid = foreground.processId,
                foregroundRootOwnerHandle = foreground.rootOwnerHandle
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            warnAttemptOnce(
                attemptId,
                "raise",
                "window raise failed pid=${window.pid} tier=$lastTier",
                failure
            )
            tierResults += "$lastTier=error:${failure.javaClass.simpleName}"
            return RaiseResult(
                success = false,
                tier = "$lastTier-error",
                tierResults = tierResults,
                foregroundPid = foreground.processId,
                foregroundRootOwnerHandle = foreground.rootOwnerHandle
            )
        }
    }

    private fun demoteLauncherWindows(api: User32ActivationApi, attemptId: String?) {
        LauncherWindowRegistry.snapshot().forEach { rawHandle ->
            val hwnd = HWND(Pointer(rawHandle))
            val demoted = api.SetWindowPos(
                hwnd,
                hwndNotTopmost,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE or SWP_ASYNCWINDOWPOS
            )
            if (!demoted) {
                warnAttemptOnce(
                    attemptId,
                    "demote",
                    "failed to demote launcher window hwnd=$rawHandle"
                )
            }
        }
    }

    private fun warnAttemptOnce(
        attemptId: String?,
        category: String,
        message: String,
        failure: Throwable? = null
    ) {
        if (attemptId != null && !loggedAttemptIssues.add("$attemptId:$category")) return
        val prefix = attemptId?.let { "attemptId=$it " }.orEmpty()
        val detail = failure?.let {
            val safeMessage = it.message
                ?.replace(Regex("[\\r\\n\\t]+"), " ")
                ?.take(160)
                .orEmpty()
            " error=${it.javaClass.simpleName}" +
                if (safeMessage.isBlank()) "" else " detail=$safeMessage"
        }.orEmpty()
        EnforcementLog.warn("Launcher", "$prefix$message$detail")
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

    private data class ForegroundObservation(
        val processId: Long?,
        val rootOwnerHandle: Long?
    )

    private fun foregroundObservation(api: User32ActivationApi): ForegroundObservation {
        val hwnd = api.GetForegroundWindow() ?: return ForegroundObservation(null, null)
        val processId = IntArray(1)
        val threadId = api.GetWindowThreadProcessId(hwnd, processId)
        val pid = if (threadId == 0) null else processId[0].toLong() and 0xFFFF_FFFFL
        return ForegroundObservation(pid, rootOwnerHandle(api, hwnd))
    }

    private fun rootOwnerHandle(api: User32ActivationApi, hwnd: HWND): Long? {
        var root = hwnd
        val visited = mutableSetOf<Long>()
        while (true) {
            val rootHandle = Pointer.nativeValue(root.pointer)
            if (rootHandle == 0L || !visited.add(rootHandle)) return rootHandle.takeIf { it != 0L }
            val owner = api.GetWindow(root, GW_OWNER) ?: return rootHandle
            val ownerHandle = Pointer.nativeValue(owner.pointer)
            if (ownerHandle == 0L || ownerHandle in visited) return rootHandle
            root = owner
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
