package com.focusflow.enforcement

import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.HMODULE
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives Windows lock/unlock and suspend/resume notifications on a private
 * message-only window. All platform calls are isolated here; callers consume
 * nullable foreground events and can use gap detection if registration fails.
 */
object WindowsSessionStateMonitor {
    private const val WM_QUIT = 0x0012
    private const val WM_POWERBROADCAST = 0x0218
    private const val WM_WTSSESSION_CHANGE = 0x02B1
    private const val PBT_APMSUSPEND = 0x0004
    private const val PBT_APMRESUMEAUTOMATIC = 0x0012
    private const val WTS_SESSION_LOCK = 0x7
    private const val WTS_SESSION_UNLOCK = 0x8
    private const val NOTIFY_FOR_THIS_SESSION = 0
    private val HWND_MESSAGE = HWND(Pointer(-3))
    private const val CLASS_NAME = "FocusFlowSessionStateWindow"

    @Volatile
    var isInactive: Boolean = false
        private set
    private val activityGate = SessionActivityGate()

    @Volatile
    private var running = false

    @Volatile
    private var threadId = 0

    @Volatile
    private var window: HWND? = null

    @Volatile
    private var registeredSessionNotifications = false

    private var pumpThread: Thread? = null

    @Synchronized
    fun start(): Boolean {
        if (!isWindows) return false
        if (running) return registeredSessionNotifications
        running = true
        val ready = CountDownLatch(1)
        var started = false
        pumpThread = Thread({
            val user32 = SessionUser32.INSTANCE
            val proc = object : WindowProcedure {
                override fun callback(hwnd: HWND?, message: Int, wParam: Long, lParam: Long): Long {
                    when {
                        message == WM_WTSSESSION_CHANGE && wParam == WTS_SESSION_LOCK.toLong() ->
                            applyActivityChange(activityGate.setLocked(true), "Windows session locked")
                        message == WM_WTSSESSION_CHANGE && wParam == WTS_SESSION_UNLOCK.toLong() ->
                            applyActivityChange(activityGate.setLocked(false), "Windows session unlocked")
                        message == WM_POWERBROADCAST && wParam == PBT_APMSUSPEND.toLong() ->
                            applyActivityChange(activityGate.setSuspended(true), "Windows suspending")
                        message == WM_POWERBROADCAST &&
                            wParam == PBT_APMRESUMEAUTOMATIC.toLong() ->
                            applyActivityChange(activityGate.setSuspended(false), "Windows resumed")
                        message == WM_QUIT -> return 0L
                    }
                    return user32.DefWindowProcW(hwnd, message, wParam, lParam)
                }
            }

            var classRegistered = false
            try {
                threadId = Kernel32.INSTANCE.GetCurrentThreadId()
                val instance = Kernel32.INSTANCE.GetModuleHandle(null)
                val klass = SessionWindowClass().apply {
                    windowProcedure = proc
                    hInstance = instance
                    className = WString(CLASS_NAME)
                }
                val atom = user32.RegisterClassW(klass)
                if (atom.toInt() != 0) {
                    classRegistered = true
                } else {
                    throw IllegalStateException("RegisterClassW failed for session monitor")
                }
                val hwnd = user32.CreateWindowExW(
                    0,
                    WString(CLASS_NAME),
                    WString(""),
                    0,
                    0, 0, 0, 0,
                    HWND_MESSAGE,
                    null,
                    instance,
                    null
                ) ?: throw IllegalStateException("CreateWindowExW failed for session monitor")
                window = hwnd
                registeredSessionNotifications =
                    runCatching {
                        SessionWtsApi.INSTANCE.WTSRegisterSessionNotification(
                            hwnd,
                            NOTIFY_FOR_THIS_SESSION
                        )
                    }.getOrDefault(false)
                started = true
                if (!registeredSessionNotifications) {
                    EnforcementLog.warn(
                        "WindowsSessionStateMonitor",
                        "WTSRegisterSessionNotification failed; using foreground-null and long-gap fallback"
                    )
                } else {
                    EnforcementLog.info("WindowsSessionStateMonitor", "Lock/unlock notifications registered")
                }
                ready.countDown()

                val message = WinUser.MSG()
                while (running) {
                    val result = user32.GetMessageW(message, null, 0, 0)
                    if (result <= 0) break
                    user32.TranslateMessage(message)
                    user32.DispatchMessageW(message)
                }
            } catch (failure: Throwable) {
                EnforcementLog.warn(
                    "WindowsSessionStateMonitor",
                    "Session notification setup failed; using foreground-null and long-gap fallback",
                    failure
                )
                ready.countDown()
            } finally {
                window?.let { hwnd ->
                    if (registeredSessionNotifications) {
                        runCatching { SessionWtsApi.INSTANCE.WTSUnRegisterSessionNotification(hwnd) }
                    }
                    runCatching { user32.DestroyWindow(hwnd) }
                }
                window = null
                registeredSessionNotifications = false
                if (classRegistered) {
                    runCatching {
                        user32.UnregisterClassW(WString(CLASS_NAME), Kernel32.INSTANCE.GetModuleHandle(null))
                    }
                }
                running = false
                threadId = 0
            }
        }, "FocusFlow-SessionStatePump").apply {
            isDaemon = true
            start()
        }

        if (!ready.await(3, TimeUnit.SECONDS)) {
            EnforcementLog.warn("WindowsSessionStateMonitor", "Message-pump startup timed out")
        }
        return started && registeredSessionNotifications
    }

    @Synchronized
    fun stop() {
        running = false
        val tid = threadId
        if (tid != 0) {
            runCatching { SessionUser32.INSTANCE.PostThreadMessageW(tid, WM_QUIT, 0L, 0L) }
        }
        pumpThread?.join(1_000)
        pumpThread = null
        threadId = 0
        registeredSessionNotifications = false
        applyActivityChange(activityGate.reset(), "Session monitor stopped")
    }

    private fun applyActivityChange(change: SessionActivityChange?, reason: String) {
        change ?: return
        isInactive = change.inactive
        EnforcementLog.info("WindowsSessionStateMonitor", reason)
        WinEventHook.publishSessionForeground(if (change.inactive) null else currentForegroundName(), 0L)
    }

    private fun currentForegroundName(): String? {
        val (name, _) = getForegroundProcessNameAndPidRobust() ?: return null
        if (name.equals("logonui.exe", ignoreCase = true) ||
            name.equals("lockapp.exe", ignoreCase = true)
        ) return null
        return name
    }

    private interface SessionUser32 : StdCallLibrary {
        fun RegisterClassW(windowClass: SessionWindowClass): Short
        fun UnregisterClassW(className: WString, instance: HMODULE?): Boolean
        fun CreateWindowExW(
            exStyle: Int,
            className: WString,
            windowName: WString,
            style: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            parent: HWND?,
            menu: Pointer?,
            instance: HMODULE?,
            parameter: Pointer?
        ): HWND?
        fun DefWindowProcW(hwnd: HWND?, message: Int, wParam: Long, lParam: Long): Long
        fun GetMessageW(message: WinUser.MSG, hwnd: HWND?, min: Int, max: Int): Int
        fun TranslateMessage(message: WinUser.MSG): Boolean
        fun DispatchMessageW(message: WinUser.MSG): Long
        fun DestroyWindow(hwnd: HWND): Boolean
        fun PostThreadMessageW(threadId: Int, message: Int, wParam: Long, lParam: Long): Boolean

        companion object {
            val INSTANCE: SessionUser32 =
                Native.load("user32", SessionUser32::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }

    private interface SessionWtsApi : StdCallLibrary {
        fun WTSRegisterSessionNotification(hwnd: HWND, flags: Int): Boolean
        fun WTSUnRegisterSessionNotification(hwnd: HWND): Boolean

        companion object {
            val INSTANCE: SessionWtsApi =
                Native.load("wtsapi32", SessionWtsApi::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }

    private interface WindowProcedure : Callback {
        fun callback(hwnd: HWND?, message: Int, wParam: Long, lParam: Long): Long
    }

    @Structure.FieldOrder(
        "style", "windowProcedure", "classExtra", "windowExtra", "hInstance",
        "icon", "cursor", "background", "menuName", "className"
    )
    private class SessionWindowClass : Structure() {
        @JvmField var style: Int = 0
        @JvmField var windowProcedure: WindowProcedure? = null
        @JvmField var classExtra: Int = 0
        @JvmField var windowExtra: Int = 0
        @JvmField var hInstance: HMODULE? = null
        @JvmField var icon: Pointer? = null
        @JvmField var cursor: Pointer? = null
        @JvmField var background: Pointer? = null
        @JvmField var menuName: WString? = null
        @JvmField var className: WString? = null
    }
}
