package com.focusflow.enforcement

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * WinApiBindings
 *
 * JNA bindings for the Win32 APIs needed by the enforcement layer.
 * These are thin wrappers — no Android APIs, no React Native, pure JVM.
 *
 * APIs used:
 *   user32.GetForegroundWindow()           — handle to the active window
 *   user32.GetWindowThreadProcessId()      — PID from window handle
 *   psapi.GetProcessImageFileNameW()       — process executable path from handle
 *   kernel32.OpenProcess()                 — open a process handle by PID
 *   kernel32.CloseHandle()                 — release a process handle
 */
interface User32Extra : StdCallLibrary {
    companion object {
        val INSTANCE: User32Extra = Native.load("user32", User32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun GetForegroundWindow(): HWND
    fun GetWindowThreadProcessId(hWnd: HWND, lpdwProcessId: IntArray): Int
    fun GetWindowTextW(hWnd: HWND, lpString: CharArray, nMaxCount: Int): Int
    fun GetWindowTextLengthW(hWnd: HWND): Int
    fun IsWindowVisible(hWnd: HWND): Boolean
    fun IsIconic(hWnd: HWND): Boolean

    /** Find a top-level window by class name or window title. Returns null if not found. */
    fun FindWindowW(lpClassName: String?, lpWindowName: String?): HWND?

    /**
     * Find the next window of a given class after [hwndChildAfter] in Z-order.
     * Pass null for [hwndParent] to search top-level windows.
     * Pass null for [hwndChildAfter] to start from the beginning.
     * Returns null when there are no more matching windows.
     * Use this in a loop to enumerate ALL instances of a window class
     * (e.g. Shell_SecondaryTrayWnd on multi-monitor setups).
     */
    fun FindWindowExW(hwndParent: HWND?, hwndChildAfter: HWND?, lpszClass: String?, lpszWindow: String?): HWND?

    /** Show, hide, or change the state of a window. SW_HIDE=0, SW_SHOW=5. */
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean
}

interface Psapi : StdCallLibrary {
    companion object {
        val INSTANCE: Psapi = Native.load("psapi", Psapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun GetProcessImageFileNameW(hProcess: HANDLE, lpImageFileName: CharArray, nSize: Int): Int
}

/**
 * Get the title text of the currently active foreground window.
 * Returns null if there is no foreground window or the call fails.
 * Used by keyword blocking: browser tab titles appear in window titles.
 */
fun getForegroundWindowTitle(): String? {
    if (!isWindows) return null
    return try {
        val user32 = User32Extra.INSTANCE
        val hwnd = user32.GetForegroundWindow()
        val len = user32.GetWindowTextLengthW(hwnd)
        if (len <= 0) return null
        val buf = CharArray(len + 1)
        val read = user32.GetWindowTextW(hwnd, buf, buf.size)
        if (read <= 0) null else String(buf, 0, read)
    } catch (_: Exception) { null }
}

/**
 * Get the process name (e.g. "chrome.exe") of the currently active foreground window.
 * Returns null if the window handle is invalid or access is denied.
 */
fun getForegroundProcessName(): String? {
    return try {
        val user32 = User32Extra.INSTANCE
        val hwnd = user32.GetForegroundWindow()

        val pidArr = IntArray(1)
        user32.GetWindowThreadProcessId(hwnd, pidArr)
        val pid = pidArr[0].toLong()
        if (pid == 0L) return null

        // Use ProcessHandle (JVM 9+) to get the executable name — no native call needed
        val ph = ProcessHandle.of(pid).orElse(null) ?: return null
        ph.info().command().orElse(null)
            ?.substringAfterLast("\\")
            ?.substringAfterLast("/")
            ?.lowercase()
    } catch (_: Exception) {
        null
    }
}

/**
 * Kill a process by name. Returns true if at least one matching process was killed.
 *
 * On Windows: uses taskkill /F /IM as the PRIMARY method — avoids the JVM restriction
 * of "destroy of current process not allowed" and handles elevated processes better.
 *
 * On other platforms: uses ProcessHandle (cross-platform JVM 9+), skipping own PID.
 */
fun killProcessByName(processName: String): Boolean {
    if (isWindows) {
        // Fire-and-forget: don't block the enforcement coroutine waiting for taskkill
        // to exit. The kill signal is sent immediately; the process terminates asynchronously.
        return try {
            ProcessBuilder("taskkill", "/F", "/IM", processName)
                .redirectErrorStream(true)
                .start()   // intentionally no waitFor()
            true
        } catch (_: Exception) { false }
    }

    // Non-Windows fallback: ProcessHandle (cross-platform)
    val ownPid = ProcessHandle.current().pid()
    var killed = false
    ProcessHandle.allProcesses().filter { ph ->
        ph.pid() != ownPid && ph.info().command().orElse("").let { cmd ->
            cmd.substringAfterLast("\\").substringAfterLast("/")
                .equals(processName, ignoreCase = true)
        }
    }.forEach { ph ->
        try {
            ph.destroyForcibly()
            killed = true
        } catch (_: Exception) {}
    }
    return killed
}

/**
 * Kill a specific process by PID. More targeted than killProcessByName —
 * only terminates the one window/tab group associated with this PID rather
 * than every instance of the browser.
 *
 * On Windows: taskkill /F /PID <pid>
 * On other platforms: ProcessHandle.destroyForcibly()
 */
fun killProcessByPid(pid: Long): Boolean {
    if (pid <= 0L) return false
    if (isWindows) {
        // Fire-and-forget — same as killProcessByName; no waitFor() so the
        // enforcement coroutine is not blocked while taskkill exits.
        return try {
            ProcessBuilder("taskkill", "/F", "/PID", pid.toString())
                .redirectErrorStream(true).start()   // intentionally no waitFor()
            true
        } catch (_: Exception) { false }
    }
    return try {
        ProcessHandle.of(pid).orElse(null)?.destroyForcibly() != null
    } catch (_: Exception) { false }
}

/**
 * Get both the process name and PID for the currently active foreground window.
 * Returns null if there is no foreground window or if the call fails.
 * Using both together allows targeted per-PID kills instead of name-based kills.
 */
fun getForegroundProcessNameAndPid(): Pair<String, Long>? {
    return try {
        val user32 = User32Extra.INSTANCE
        val hwnd = user32.GetForegroundWindow()
        val pidArr = IntArray(1)
        user32.GetWindowThreadProcessId(hwnd, pidArr)
        val pid = pidArr[0].toLong()
        if (pid == 0L) return null
        val ph = ProcessHandle.of(pid).orElse(null) ?: return null
        val name = ph.info().command().orElse(null)
            ?.substringAfterLast("\\")?.substringAfterLast("/")
            ?: return null
        Pair(name, pid)
    } catch (_: Exception) { null }
}

/**
 * Returns true only when the current foreground window is visible, not
 * minimised, and belongs to [pid]. This is intentionally stricter than a
 * process-name check: background enforcement can kill without showing an
 * overlay, while the overlay path requires a real visible foreground window.
 */
fun isVisibleForegroundWindowForPid(pid: Long): Boolean {
    if (!isWindows || pid <= 0L) return false
    return try {
        val user32 = User32Extra.INSTANCE
        val hwnd = user32.GetForegroundWindow()
        if (!user32.IsWindowVisible(hwnd) || user32.IsIconic(hwnd)) {
            return false
        }

        val pidArr = IntArray(1)
        if (user32.GetWindowThreadProcessId(hwnd, pidArr) == 0) return false
        pidArr[0].toLong() == pid
    } catch (_: Exception) {
        false
    }
}

/**
 * Check if we are running on Windows.
 */
val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("windows")

/**
 * Returns true if the current process is running with administrator privileges.
 *
 * Uses PowerShell's WindowsPrincipal.IsInRole check — the standard Windows
 * elevation test.  Runs synchronously (call from a background thread).
 *
 * Returns false on non-Windows platforms or if the check fails for any reason,
 * so callers should show the Run-as-Admin button on false (safe default).
 */
fun isRunningAsAdmin(): Boolean {
    if (!isWindows) return false
    return try {
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command",
            "([Security.Principal.WindowsPrincipal]" +
            "[Security.Principal.WindowsIdentity]::GetCurrent())" +
            ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        output.equals("True", ignoreCase = true)
    } catch (_: Throwable) {
        false
    }
}
