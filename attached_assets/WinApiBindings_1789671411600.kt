package com.focusflow.enforcement

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.TimeUnit

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
        val HWND_TOPMOST   = HWND(Pointer(-1))
        val HWND_NOTOPMOST = HWND(Pointer(-2))
        const val SWP_NOSIZE     = 0x0001
        const val SWP_NOMOVE     = 0x0002
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_SHOWWINDOW = 0x0040
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

    /** Show, hide, or change the state of a window. SW_HIDE=0, SW_SHOW=5, SW_RESTORE=9. */
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean

    /** Bring [hWnd] to the foreground and activate it. */
    fun SetForegroundWindow(hWnd: HWND): Boolean

    fun SetWindowPos(
        hWnd: HWND,
        hWndInsertAfter: HWND?,
        X: Int, Y: Int, cx: Int, cy: Int,
        uFlags: Int
    ): Boolean
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
    val targetName = processName.substringAfterLast("\\").substringAfterLast("/")
    if (targetName.isBlank() || targetName.equals(currentProcessName(), ignoreCase = true)) {
        return false
    }

    if (isWindows) {
        return runTaskkill(listOf("taskkill", "/F", "/IM", targetName))
    }

    // Non-Windows fallback: ProcessHandle (cross-platform)
    val ownPid = ProcessHandle.current().pid()
    var killed = false
    ProcessHandle.allProcesses().filter { ph ->
        ph.pid() != ownPid && ph.info().command().orElse("").let { cmd ->
             cmd.substringAfterLast("\\").substringAfterLast("/")
                 .equals(targetName, ignoreCase = true)
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
 * Kill several process images in one Windows command.
 *
 * This is used by Nuclear Mode to keep its single-scan/single-kill design while
 * still waiting for taskkill to finish and reaping the child process cleanly.
 */
fun killProcessesByName(processNames: Collection<String>): Boolean {
    val targets = processNames
        .map { it.substringAfterLast("\\").substringAfterLast("/") }
        .filter { it.isNotBlank() && !it.equals(currentProcessName(), ignoreCase = true) }
        .distinct()
    if (targets.isEmpty()) return false

    if (isWindows) {
        val args = mutableListOf("taskkill", "/F")
        targets.forEach { args += "/IM"; args += it }
        return runTaskkill(args)
    }

    return targets.any { killProcessByName(it) }
}

/**
 * Kill a specific process by PID. More targeted than killProcessByName —
 * only terminates the one window/tab group associated with this PID rather
 * than every instance of the browser.
 *
 * On Windows: taskkill /F /PID <pid>
 * On other platforms: ProcessHandle.destroyForcibly()
 */
fun killProcessByPid(pid: Long, expectedProcessName: String? = null): Boolean {
    if (pid <= 0L) return false
    val process = ProcessHandle.of(pid).orElse(null) ?: return false
    if (!process.isAlive || pid == ProcessHandle.current().pid()) return false

    val actualName = process.info().command().orElse(null)
        ?.substringAfterLast("\\")
        ?.substringAfterLast("/")
    if (expectedProcessName != null &&
        (actualName == null || !actualName.equals(expectedProcessName, ignoreCase = true))
    ) {
        return false
    }

    if (isWindows) {
        return runTaskkill(listOf("taskkill", "/F", "/PID", pid.toString()))
    }
    return try {
        process.destroyForcibly()
    } catch (_: Exception) { false }
}

/**
 * Execute taskkill without leaving child processes and pipe handles behind.
 *
 * The caller is always an enforcement background thread. Waiting briefly gives
 * callers an honest success value and lets the OS finish the kill before the
 * next enforcement action is logged or retried.
 */
private fun runTaskkill(args: List<String>): Boolean {
    return try {
        val process = ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }
}

private fun currentProcessName(): String? =
    ProcessHandle.current().info().command().orElse(null)
        ?.substringAfterLast("\\")
        ?.substringAfterLast("/")

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

/**
 * Enumerate all top-level windows that belong to [pid], restore the first
 * visible one from minimised state, and bring it to the foreground.
 *
 * Uses a FindWindowExW(null, lastHwnd, null, null) walk — no EnumWindows
 * callback required — so it works with plain JNA interface bindings.
 *
 * Returns true if at least one window was successfully activated.
 */
fun focusWindowByPid(pid: Long): Boolean {
    if (!isWindows) return false
    return try {
        val user32 = User32Extra.INSTANCE
        val SW_RESTORE = 9
        var hwnd = user32.FindWindowExW(null, null, null, null)
        while (hwnd != null) {
            val pidArr = IntArray(1)
            user32.GetWindowThreadProcessId(hwnd, pidArr)
            if (pidArr[0].toLong() == pid && user32.IsWindowVisible(hwnd)) {
                if (user32.IsIconic(hwnd)) user32.ShowWindow(hwnd, SW_RESTORE)
                user32.SetForegroundWindow(hwnd)
                return true
            }
            hwnd = user32.FindWindowExW(null, hwnd, null, null)
        }
        false
    } catch (_: Exception) {
        false
    }
}
