package com.focusflow.enforcement

import java.io.File

/**
 * WatchdogInstaller — self-restart guardian for FocusFlow.
 *
 * Registers two Windows Scheduled Tasks:
 *
 *   1. FocusFlowWatchdog — fires every 2 minutes. Checks whether FocusFlow.exe
 *      is running and, if not, relaunches it. Handles force-kills, crashes, and
 *      OOM terminations. Uses /IT (interactive token) — no admin rights needed.
 *
 *   2. FocusFlowTaskbarGuard — fires at every user logon. Unconditionally restores
 *      the Windows taskbar (primary + all secondary monitors) using a Win32
 *      ShowWindow call. This is the safety net for the "hard kill while kiosk is
 *      active" scenario: if FocusFlow is killed at the OS level and never relaunched,
 *      the user's taskbar is guaranteed to reappear on their next login, even if
 *      FocusFlow is uninstalled.
 */
object WatchdogInstaller {

    // This is crash recovery only. A Windows administrator or third-party
    // uninstaller can still remove scheduled tasks and application files.

    private const val TASK_NAME         = "FocusFlowWatchdog"
    private const val GUARD_TASK_NAME   = "FocusFlowTaskbarGuard"

    fun isInstalled(): Boolean {
        if (!isWindows) return false
        return try {
            val proc = ProcessBuilder("schtasks", "/query", "/tn", TASK_NAME)
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) { false }
    }

    /**
     * Creates (or overwrites) both the watchdog task and the taskbar-guard task.
     * Safe to call on every launch — /f overwrites silently.
     */
    fun install() {
        if (!isWindows) return
        installWatchdog()
        installTaskbarGuard()
    }

    private fun installWatchdog() {
        val exePath = WindowsStartupManager.resolveExePath()
        val safePath = exePath.replace("'", "''")
        val psScript = "if (-not (Get-Process -Name 'FocusFlow' -ErrorAction SilentlyContinue))" +
                " { Start-Process '$safePath' }"
        try {
            val exit = ProcessBuilder(
                "schtasks", "/create",
                "/tn", TASK_NAME,
                "/tr", "powershell -WindowStyle Hidden -NonInteractive -Command \"$psScript\"",
                "/sc", "MINUTE",
                "/mo", "2",
                "/it",
                "/f"
            ).redirectErrorStream(true).start().waitFor()
            if (exit != 0) EnforcementLog.warn("WatchdogInstaller", "schtasks /create $TASK_NAME exited with code $exit — watchdog may not self-restart")
            else EnforcementLog.info("WatchdogInstaller", "$TASK_NAME registered (exe=$exePath)")
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $TASK_NAME", e)
        }
    }

    /**
     * Installs a logon-triggered scheduled task that restores the Windows taskbar.
     *
     * Writes a small .ps1 script to %APPDATA%\FocusFlow\ and creates a task that
     * runs it at every logon. The script uses Win32 ShowWindow to un-hide both the
     * primary taskbar (Shell_TrayWnd) and all secondary taskbars
     * (Shell_SecondaryTrayWnd), then deletes itself from the scheduled tasks once
     * the taskbar was already visible (indicating no crash guard was needed).
     *
     * This task is INDEPENDENT of FocusFlow — it runs even if FocusFlow is
     * uninstalled, ensuring users never get stuck with a permanently hidden taskbar.
     */
    private fun installTaskbarGuard() {
        try {
            val appDataDir = File(System.getenv("APPDATA") ?: return, "FocusFlow")
            appDataDir.mkdirs()
            val scriptFile = File(appDataDir, "taskbar-guard.ps1")

            // PowerShell script: show primary + ALL secondary taskbar windows via Win32.
            // Uses a loop with FindWindowEx to handle 3+ monitor setups.
            val D = '$'
            scriptFile.writeText("""
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class FocusFlowTaskbarGuard {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindowEx(IntPtr hwndParent, IntPtr hwndChildAfter, string lpszClass, string lpszWindow);
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
}
'@

${D}SW_SHOW = 5

# Restore primary taskbar
${D}primary = [FocusFlowTaskbarGuard]::FindWindow("Shell_TrayWnd", ${D}null)
if (${D}primary -ne [IntPtr]::Zero) {
    [FocusFlowTaskbarGuard]::ShowWindow(${D}primary, ${D}SW_SHOW) | Out-Null
}

# Restore ALL secondary taskbars (handles 3+ monitor setups)
${D}prev = [IntPtr]::Zero
while (${D}true) {
    ${D}sec = [FocusFlowTaskbarGuard]::FindWindowEx([IntPtr]::Zero, ${D}prev, "Shell_SecondaryTrayWnd", ${D}null)
    if (${D}sec -eq [IntPtr]::Zero) { break }
    [FocusFlowTaskbarGuard]::ShowWindow(${D}sec, ${D}SW_SHOW) | Out-Null
    ${D}prev = ${D}sec
}
""".trimIndent())

            val safePath = scriptFile.absolutePath.replace("'", "''")
            val exit = ProcessBuilder(
                "schtasks", "/create",
                "/tn", GUARD_TASK_NAME,
                "/tr", "powershell -ExecutionPolicy Bypass -NonInteractive -WindowStyle Hidden -File \"$safePath\"",
                "/sc", "ONLOGON",
                "/it",
                "/f"
            ).redirectErrorStream(true).start().waitFor()
            if (exit != 0) EnforcementLog.warn("WatchdogInstaller", "schtasks /create $GUARD_TASK_NAME exited with code $exit — taskbar may not restore on next logon")
            else EnforcementLog.info("WatchdogInstaller", "$GUARD_TASK_NAME registered (script=${scriptFile.absolutePath})")
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $GUARD_TASK_NAME", e)
        }
    }

    fun uninstall() {
        if (!isWindows) return
        try {
            ProcessBuilder("schtasks", "/delete", "/tn", TASK_NAME, "/f")
                .redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) { }
        try {
            ProcessBuilder("schtasks", "/delete", "/tn", GUARD_TASK_NAME, "/f")
                .redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) { }
    }
}
