package com.focusflow.enforcement

import com.focusflow.AppPaths
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * WatchdogInstaller — self-restart guardian for FocusFlow.
 *
 * Registers two Windows Scheduled Tasks:
 *
 *   1. FocusFlowWatchdog — fires every 2 minutes. Checks whether FocusFlow.exe
 *      is running and, if not, relaunches it. Handles force-kills, crashes, and
 *      OOM terminations. Runs in the user's interactive session — no admin rights needed.
 *
 *   2. FocusFlowTaskbarGuard — fires at every user logon. Unconditionally restores
 *      the Windows taskbar (primary + all secondary monitors) using a Win32
 *      ShowWindow call. This is the safety net for the "hard kill while kiosk is
 *      active" scenario: if FocusFlow is killed at the OS level and never relaunched,
 *      the user's taskbar is guaranteed to reappear on their next login, even if
 *      FocusFlow is uninstalled.
 *
 * Both tasks run PowerShell through `conhost.exe --headless` (Windows 10 1809+), so no console
 * window ever flashes — see [WatchdogScript]. They are registered from XML so the power options
 * are explicit: they also run on battery. The task names are unchanged, so every launch of a newer
 * FocusFlow replaces an older install's tasks in place.
 *
 * The scripts live in the data directory (`~/.focusflow`), which is outside AppData. A packaged
 * (MSIX) app has its AppData writes redirected into the package, where a scheduled task could not
 * find them; the data directory is not redirected, so the EXE, MSI and MSIX builds behave the same.
 */
object WatchdogInstaller {

    // This is crash recovery only. A Windows administrator or third-party
    // uninstaller can still remove scheduled tasks and application files.

    private const val TASK_NAME         = "FocusFlowWatchdog"
    private const val GUARD_TASK_NAME   = "FocusFlowTaskbarGuard"

    private const val WATCHDOG_SCRIPT_NAME = "watchdog.ps1"
    private const val GUARD_SCRIPT_NAME    = "taskbar-guard.ps1"

    private val startBoundaryFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

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
     * Safe to call on every launch — /f overwrites silently. That is also how an older install's
     * tasks get the no-flash launcher: the next launch of the new version replaces them.
     */
    fun install() {
        if (!isWindows) return
        installWatchdog()
        installTaskbarGuard()
    }

    private fun installWatchdog() {
        val exePath = WindowsStartupManager.resolveExePath()
        val systemRoot = systemRoot()
        val host = headlessHost(systemRoot)
        val scriptPath = pathAwareScript(exePath, headless = host != null)

        val action = if (scriptPath != null) {
            WatchdogScript.scriptAction(scriptPath, systemRoot, host)
        } else {
            WatchdogScript.legacyAction(exePath, systemRoot, host)
        }
        val fallbackCommand = if (scriptPath != null) {
            WatchdogScript.taskCommandForScript(scriptPath, headless = host != null)
        } else {
            WatchdogScript.legacyTaskCommand(exePath, headless = host != null)
        }

        val registered = register(
            taskName = TASK_NAME,
            description = "FocusFlow self-restart watchdog: relaunches FocusFlow if it stops unexpectedly.",
            trigger = WatchdogTaskXml.Trigger.EVERY_TWO_MINUTES,
            action = action,
            fallbackCommand = fallbackCommand,
            fallbackSchedule = listOf("/sc", "MINUTE", "/mo", "2")
        )
        // The old script lived under AppData. Remove it only once the task no longer points there.
        if (registered && scriptPath != null) removeOldAppDataScript(WATCHDOG_SCRIPT_NAME)
    }

    /**
     * Direct installs only: writes the path-aware check into the data directory and returns its
     * path. Returns null (caller uses the old name-only command) whenever anything is unusual —
     * see [WatchdogScript.shouldUsePathCheck].
     */
    private fun pathAwareScript(exePath: String, headless: Boolean): String? = try {
        val scriptFile = File(AppPaths.dataDir, WATCHDOG_SCRIPT_NAME)
        val command = WatchdogScript.taskCommandForScript(scriptFile.absolutePath, headless)
        if (!WatchdogScript.shouldUsePathCheck(
                exePath = exePath,
                exeExists = File(exePath).isFile,
                isDirectInstall = InstallVariant.isWindowsDirectInstall,
                taskCommand = command
            )
        ) {
            null
        } else {
            scriptFile.parentFile.mkdirs()
            scriptFile.writeText(WatchdogScript.script(File(exePath).absolutePath))
            scriptFile.absolutePath
        }
    } catch (failure: Exception) {
        EnforcementLog.warn("WatchdogInstaller", "Could not write the watchdog script; using the name-only check", failure)
        null
    }

    /**
     * Installs a logon-triggered scheduled task that restores the Windows taskbar.
     *
     * Writes a small .ps1 script to the data directory and creates a task that runs it at every
     * logon. The script uses Win32 ShowWindow to un-hide both the primary taskbar
     * (Shell_TrayWnd) and all secondary taskbars (Shell_SecondaryTrayWnd).
     *
     * This task is INDEPENDENT of FocusFlow — it runs even if FocusFlow is
     * uninstalled, ensuring users never get stuck with a permanently hidden taskbar.
     */
    private fun installTaskbarGuard() {
        try {
            val dataDir = AppPaths.dataDir
            dataDir.mkdirs()
            val scriptFile = File(dataDir, GUARD_SCRIPT_NAME)

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

            val systemRoot = systemRoot()
            val host = headlessHost(systemRoot)
            val registered = register(
                taskName = GUARD_TASK_NAME,
                description = "FocusFlow taskbar guard: brings the Windows taskbar back at sign-in if FocusFlow was killed while it was hidden.",
                trigger = WatchdogTaskXml.Trigger.AT_LOGON,
                action = WatchdogScript.scriptAction(scriptFile.absolutePath, systemRoot, host),
                fallbackCommand = WatchdogScript.taskCommandForScript(scriptFile.absolutePath, headless = host != null),
                fallbackSchedule = listOf("/sc", "ONLOGON")
            )
            if (registered) removeOldAppDataScript(GUARD_SCRIPT_NAME)
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $GUARD_TASK_NAME", e)
        }
    }

    /**
     * Registers [taskName] from XML (power options set explicitly). If that fails for any reason,
     * falls back to the older `schtasks /tr` form, which still avoids the console flash.
     * Returns true when either way succeeded.
     */
    private fun register(
        taskName: String,
        description: String,
        trigger: WatchdogTaskXml.Trigger,
        action: WatchdogScript.TaskAction,
        fallbackCommand: String,
        fallbackSchedule: List<String>
    ): Boolean {
        if (registerFromXml(taskName, description, trigger, action)) {
            EnforcementLog.info("WatchdogInstaller", "$taskName registered (action=${action.commandLine.take(300)})")
            return true
        }
        return try {
            val (exit, output) = schtasks(
                "/create", "/tn", taskName, "/tr", fallbackCommand,
                *fallbackSchedule.toTypedArray(), "/it", "/f"
            )
            if (exit != 0) {
                EnforcementLog.warn(
                    "WatchdogInstaller",
                    "schtasks /create $taskName (fallback) exited with code $exit" +
                        " | commandLength=${fallbackCommand.length} | output=${output.take(300)}"
                )
                false
            } else {
                EnforcementLog.info("WatchdogInstaller", "$taskName registered with the fallback command")
                true
            }
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $taskName", e)
            false
        }
    }

    private fun registerFromXml(
        taskName: String,
        description: String,
        trigger: WatchdogTaskXml.Trigger,
        action: WatchdogScript.TaskAction
    ): Boolean {
        var xmlFile: File? = null
        return try {
            val xml = WatchdogTaskXml.build(
                description = description,
                trigger = trigger,
                action = action,
                userId = WatchdogTaskXml.userId(System.getenv("USERDOMAIN"), System.getenv("USERNAME")),
                startBoundary = LocalDateTime.now().format(startBoundaryFormat)
            )
            xmlFile = File.createTempFile("focusflow-task-", ".xml")
            xmlFile.writeBytes(WatchdogTaskXml.encode(xml))
            val (exit, output) = schtasks("/create", "/tn", taskName, "/xml", xmlFile.absolutePath, "/f")
            if (exit != 0) {
                EnforcementLog.warn(
                    "WatchdogInstaller",
                    "schtasks /create $taskName from XML exited with code $exit — trying the fallback | output=${output.take(300)}"
                )
            }
            exit == 0
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Could not register $taskName from XML — trying the fallback", e)
            false
        } finally {
            try { xmlFile?.delete() } catch (_: Exception) { }
        }
    }

    private fun schtasks(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("schtasks", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to output
    }

    private fun systemRoot(): String? =
        System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
            ?: System.getenv("windir")?.takeIf { it.isNotBlank() }

    /** conhost.exe's full path when this Windows can run PowerShell with no window, else null. */
    private fun headlessHost(systemRoot: String?): String? = try {
        WatchdogScript.headlessHost(systemRoot, windowsBuild()) { File(it).isFile }
    } catch (_: Throwable) {
        null
    }

    private fun windowsBuild(): Int? = try {
        Advapi32Util.registryGetStringValue(
            WinReg.HKEY_LOCAL_MACHINE,
            "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
            "CurrentBuildNumber"
        ).trim().toIntOrNull()
    } catch (_: Throwable) {
        null
    }

    /** Best-effort: earlier versions kept the scripts in %APPDATA%\FocusFlow. */
    private fun removeOldAppDataScript(name: String) {
        try {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return
            val dir = File(appData, "FocusFlow")
            File(dir, name).delete()
            dir.delete() // only succeeds when the folder is empty
        } catch (_: Exception) { }
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
