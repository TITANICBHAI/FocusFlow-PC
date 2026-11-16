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
 * The two scripts live in their own small folder, [AppPaths.taskScriptsDir] (`~/.focusflow-tasks`),
 * not in the data directory: that folder is the database and backups, people are told they may
 * delete it, and the tasks have to keep working after that and after an uninstall. It is also not
 * under AppData: a packaged (MSIX) app has its AppData writes redirected into a private per-app
 * folder that a scheduled task cannot see, while the rest of the profile is not redirected, so the
 * EXE, MSI and MSIX builds behave the same. Scripts are written with a UTF-8 byte-order mark so
 * Windows PowerShell 5.1 reads a profile name with accents correctly.
 */
object WatchdogInstaller {

    // This is crash recovery only. A Windows administrator or third-party
    // uninstaller can still remove scheduled tasks and application files.

    private const val TASK_NAME         = "FocusFlowWatchdog"
    private const val GUARD_TASK_NAME   = "FocusFlowTaskbarGuard"

    private const val WATCHDOG_SCRIPT_NAME = WatchdogScript.WATCHDOG_SCRIPT_NAME
    private const val GUARD_SCRIPT_NAME    = WatchdogScript.GUARD_SCRIPT_NAME

    /** Longest a quit or an uninstall waits on any one schtasks call (a stuck Task Scheduler must not hold either up). */
    private const val QUICK_STEP_TIMEOUT_SECONDS = 10L
    /** Creating or deleting a task can take a few seconds; anything beyond this is a hang. */
    private const val SCHTASKS_TIMEOUT_SECONDS = 30L

    private val startBoundaryFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun isInstalled(): Boolean {
        if (!isWindows) return false
        return try {
            SystemCommand.run(
                listOf(SystemTools.path("schtasks.exe"), "/query", "/tn", TASK_NAME),
                QUICK_STEP_TIMEOUT_SECONDS
            ).ok
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
        // Older scripts lived under AppData. Remove them only once the task no longer points there.
        if (registered && scriptPath != null) removeOldScript(WATCHDOG_SCRIPT_NAME)
    }

    /**
     * Direct installs only: writes the path-aware check into the task-scripts folder and returns its
     * path. Returns null (caller uses the old name-only command) whenever anything is unusual —
     * see [WatchdogScript.shouldUsePathCheck].
     */
    private fun pathAwareScript(exePath: String, headless: Boolean): String? = try {
        val scriptFile = File(AppPaths.taskScriptsDir, WATCHDOG_SCRIPT_NAME)
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
            scriptFile.writeBytes(WatchdogScript.scriptBytes(WatchdogScript.script(File(exePath).absolutePath)))
            scriptFile.absolutePath
        }
    } catch (failure: Exception) {
        EnforcementLog.warn("WatchdogInstaller", "Could not write the watchdog script; using the name-only check", failure)
        null
    }

    /**
     * Installs a logon-triggered scheduled task that restores the Windows taskbar.
     *
     * Writes a small .ps1 script to the task-scripts folder and creates a task that runs it at every
     * logon. The script uses Win32 ShowWindow to un-hide both the primary taskbar
     * (Shell_TrayWnd) and all secondary taskbars (Shell_SecondaryTrayWnd).
     *
     * This task is INDEPENDENT of FocusFlow — it runs even if FocusFlow is
     * uninstalled, ensuring users never get stuck with a permanently hidden taskbar.
     */
    private fun installTaskbarGuard() {
        try {
            val scriptsDir = AppPaths.taskScriptsDir
            scriptsDir.mkdirs()
            val scriptFile = File(scriptsDir, GUARD_SCRIPT_NAME)

            // PowerShell script: show primary + ALL secondary taskbar windows via Win32 (see
            // WatchdogScript.taskbarGuardScript, which also lets the trust check vouch for exactly this text).
            scriptFile.writeBytes(WatchdogScript.scriptBytes(WatchdogScript.taskbarGuardScript()))

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
            if (registered) removeOldScript(GUARD_SCRIPT_NAME)
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Failed to register $GUARD_TASK_NAME", e)
        }
    }

    /**
     * Registers [taskName] from XML (power options set explicitly). If that fails, tries the same
     * XML once more without a user name, and if that fails too, falls back to the older
     * `schtasks /tr` form, which still avoids the console flash. Returns true when any way worked.
     */
    private fun register(
        taskName: String,
        description: String,
        trigger: WatchdogTaskXml.Trigger,
        action: WatchdogScript.TaskAction,
        fallbackCommand: String,
        fallbackSchedule: List<String>
    ): Boolean {
        val user = WatchdogTaskXml.userId(System.getenv("USERDOMAIN"), System.getenv("USERNAME"))
        if (registerFromXml(taskName, description, trigger, action, user)) {
            EnforcementLog.info("WatchdogInstaller", "$taskName registered (action=${action.commandLine.take(300)})")
            return true
        }
        // Some accounts cannot be named from these two environment variables (a work or school
        // account, for example). With no name, Task Scheduler uses the account that registers the
        // task, and the explicit power options and time limit still apply. Only worth a second
        // try when a name was actually sent the first time.
        if (user != null && registerFromXml(taskName, description, trigger, action, null)) {
            EnforcementLog.info(
                "WatchdogInstaller",
                "$taskName registered without a user name (action=${action.commandLine.take(300)})"
            )
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
        action: WatchdogScript.TaskAction,
        userId: String?
    ): Boolean {
        var xmlFile: File? = null
        return try {
            val xml = WatchdogTaskXml.build(
                description = description,
                trigger = trigger,
                action = action,
                userId = userId,
                startBoundary = LocalDateTime.now().format(startBoundaryFormat)
            )
            xmlFile = File.createTempFile("focusflow-task-", ".xml")
            xmlFile.writeBytes(WatchdogTaskXml.encode(xml))
            val (exit, output) = schtasks("/create", "/tn", taskName, "/xml", xmlFile.absolutePath, "/f")
            if (exit != 0) {
                EnforcementLog.warn(
                    "WatchdogInstaller",
                    "schtasks /create $taskName from XML exited with code $exit — trying the next option | output=${output.take(300)}"
                )
            }
            exit == 0
        } catch (e: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "Could not register $taskName from XML — trying the next option", e)
            false
        } finally {
            try { xmlFile?.delete() } catch (_: Exception) { }
        }
    }

    /** Runs schtasks with a time limit (it used to wait forever); a timeout is reported as exit code -1. */
    private fun schtasks(vararg args: String): Pair<Int, String> {
        val result = SystemCommand.run(
            listOf(SystemTools.path("schtasks.exe"), *args),
            SCHTASKS_TIMEOUT_SECONDS
        )
        if (result.timedOut) return -1 to "schtasks timed out after ${SCHTASKS_TIMEOUT_SECONDS}s"
        return (result.exitCode ?: -1) to result.output.trim()
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

    /**
     * Best-effort clean-up once a task points at the new location. Earlier versions kept the
     * scripts in %APPDATA%\FocusFlow, and an interim build kept them next to the database. Only
     * these two script files are ever removed from the data directory, never the folder itself.
     */
    private fun removeOldScript(name: String) {
        try {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            if (appData != null) {
                val dir = File(appData, "FocusFlow")
                File(dir, name).delete()
                dir.delete() // only succeeds when the folder is empty
            }
        } catch (_: Exception) { }
        try {
            File(AppPaths.dataDir, name).delete()
        } catch (_: Exception) { }
    }

    /**
     * Takes everything this installer put on the PC off again: both scheduled tasks and the two
     * scripts, with their folder if nothing else is in it. Called when an uninstall goes ahead
     * (see [com.focusflow.services.UninstallCleanup]). Safe to call when nothing is installed.
     *
     * This runs inside the installer's uninstall check, which must neither fail nor hang, so each
     * step is time-limited and failures are logged, never thrown. Nothing else in the data
     * directory is touched, and the scripts folder is never deleted recursively.
     */
    fun uninstall() {
        if (!isWindows) return
        deleteTask(TASK_NAME)
        deleteTask(GUARD_TASK_NAME)
        for (name in listOf(WATCHDOG_SCRIPT_NAME, GUARD_SCRIPT_NAME)) {
            removeOldScript(name) // copies left by earlier versions
            try {
                File(AppPaths.taskScriptsDir, name).delete()
            } catch (_: Exception) { }
        }
        try {
            AppPaths.taskScriptsDir.delete() // only succeeds when the folder is empty
        } catch (_: Exception) { }
    }

    /**
     * Switches the watchdog off until FocusFlow is started again. Called when the user has quit
     * FocusFlow on purpose, after every Quit protection was satisfied: the watchdog is there to
     * bring back an app that crashed or was killed, not one the user chose to close. The next
     * launch registers the task again, switched on (see [install]), so nothing else is needed to
     * turn it back on. Time-limited and never throws; if it fails, the watchdog simply stays on.
     */
    fun pauseWatchdog() {
        if (!isWindows) return
        quickSchtasks("$TASK_NAME paused", "/change", "/tn", TASK_NAME, "/disable")
    }

    /** Best-effort and time-limited. Never throws. */
    private fun deleteTask(taskName: String) {
        quickSchtasks("$taskName deleted", "/delete", "/tn", taskName, "/f")
    }

    /**
     * Runs one short schtasks command for the quit and uninstall paths, which must never hang:
     * output is discarded and the wait is limited. Failures are logged, never thrown.
     */
    private fun quickSchtasks(what: String, vararg args: String) {
        try {
            val result = SystemCommand.run(
                listOf(SystemTools.path("schtasks.exe"), *args),
                QUICK_STEP_TIMEOUT_SECONDS
            )
            if (result.timedOut) {
                EnforcementLog.warn("WatchdogInstaller", "$what: timed out and was given up on")
                return
            }
            EnforcementLog.info("WatchdogInstaller", "$what (exit code ${result.exitCode})")
        } catch (failure: Exception) {
            EnforcementLog.warn("WatchdogInstaller", "$what: could not run schtasks", failure)
        }
    }
}
