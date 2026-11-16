package com.focusflow.enforcement

/**
 * Builds the watchdog's PowerShell check and the Task Scheduler actions that run it.
 * Pure text — no Windows calls — so it is unit-tested.
 *
 * The old watchdog only asked "is there a process named FocusFlow?", so any program renamed to
 * FocusFlow.exe kept it quiet. The script below also compares the process's file path with the
 * real FocusFlow.exe. If Windows will not tell it the path (access denied), it assumes the
 * process is FocusFlow, so it can never start a second copy because of a path it could not read.
 *
 * ## Why the tasks are not started with plain `powershell -WindowStyle Hidden`
 *
 * Task Scheduler starts powershell.exe as a console program. Windows creates the console window
 * first, and `-WindowStyle Hidden` only hides it after PowerShell has loaded, so a window flashes
 * for about a second. For a task that fires every 2 minutes that is a visible flash all day.
 * On Windows 10 1809 and newer, `conhost.exe --headless <program>` hosts the program in a console
 * that never gets a window, so nothing flashes. Older builds keep the plain hidden PowerShell.
 */
internal object WatchdogScript {

    /** File names of the two generated scripts in [com.focusflow.AppPaths.taskScriptsDir]. */
    const val WATCHDOG_SCRIPT_NAME = "watchdog.ps1"
    const val GUARD_SCRIPT_NAME = "taskbar-guard.ps1"

    /** `schtasks /tr` accepts at most 261 characters. Only the fallback registration is limited by it. */
    const val MAX_TASK_COMMAND_LENGTH = 260

    /** First Windows 10 build (version 1809) whose conhost.exe understands `--headless`. */
    const val MIN_HEADLESS_CONHOST_BUILD = 17763

    /** PowerShell single-quoted string escaping. */
    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

    /** Quotes a program path only when it contains a space. */
    internal fun quoteIfSpaced(value: String): String =
        if (value.contains(' ')) "\"$value\"" else value

    fun script(exePath: String): String = """
${'$'}exe = ${quote(exePath)}
${'$'}running = ${'$'}false
foreach (${'$'}p in @(Get-Process -Name 'FocusFlow' -ErrorAction SilentlyContinue)) {
    ${'$'}path = ${'$'}null
    try { ${'$'}path = ${'$'}p.Path } catch { }
    if ([string]::IsNullOrEmpty(${'$'}path) -or (${'$'}path -ieq ${'$'}exe)) { ${'$'}running = ${'$'}true; break }
}
if (-not ${'$'}running) { Start-Process -FilePath ${'$'}exe }
""".trimIndent() + "\n"

    /**
     * The taskbar guard that runs at every sign-in: shows the primary taskbar and every secondary
     * one again, so a hard kill while the Focus Launcher had it hidden never leaves it hidden.
     * It lives here, next to the other generated text, so the exact content FocusFlow writes is also
     * what [expectedTaskRuns] vouches for.
     */
    fun taskbarGuardScript(): String {
        val D = '$'
        return """
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
""".trimIndent()
    }

    /**
     * The bytes to write for a script file: UTF-8 with a byte-order mark. Windows PowerShell 5.1
     * reads a .ps1 that has no mark using the system's ANSI code page, which turns a profile name
     * such as "José" into garbage inside the quoted FocusFlow.exe path. The watchdog then can
     * neither recognise nor start FocusFlow.
     */
    fun scriptBytes(text: String): ByteArray = UTF8_BYTE_ORDER_MARK + text.toByteArray(Charsets.UTF_8)

    private val UTF8_BYTE_ORDER_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** A program and its arguments, in the shape Task Scheduler's `<Exec>` element wants them. */
    data class TaskAction(val command: String, val arguments: String) {
        /** The same action as one line, for `schtasks /tr` and for log messages. */
        val commandLine: String
            get() = "${quoteIfSpaced(command)} $arguments"
    }

    /**
     * Full path of conhost.exe when it can run a console program with no window at all, else null.
     * Null means "this Windows is too old, or conhost.exe is not where it should be": the caller
     * then falls back to plain hidden PowerShell.
     */
    fun headlessHost(
        systemRoot: String?,
        windowsBuild: Int?,
        fileExists: (String) -> Boolean
    ): String? {
        if (systemRoot.isNullOrBlank()) return null
        if (windowsBuild == null || windowsBuild < MIN_HEADLESS_CONHOST_BUILD) return null
        val conhost = systemRoot.trimEnd('\\', '/') + "\\System32\\conhost.exe"
        return if (fileExists(conhost)) conhost else null
    }

    fun powerShellExe(systemRoot: String?): String =
        if (systemRoot.isNullOrBlank()) {
            "powershell.exe"
        } else {
            systemRoot.trimEnd('\\', '/') + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        }

    /** Runs the path-aware watchdog script (or the taskbar guard script) without a console window. */
    fun scriptAction(scriptPath: String, systemRoot: String?, headlessHost: String?): TaskAction =
        action(systemRoot, headlessHost, scriptPowerShellArguments(scriptPath))

    /** Name-only check for Store/MSIX, development runs and very long paths. */
    fun legacyAction(exePath: String, systemRoot: String?, headlessHost: String?): TaskAction =
        action(systemRoot, headlessHost, legacyPowerShellArguments(exePath))

    /** What PowerShell is given to run a script file. */
    private fun scriptPowerShellArguments(scriptPath: String): String =
        "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$scriptPath\""

    /** What PowerShell is given to run the name-only check. */
    private fun legacyPowerShellArguments(exePath: String): String =
        "-NoProfile -NonInteractive -Command \"${legacyCheck(exePath)}\""

    private fun action(systemRoot: String?, headlessHost: String?, powerShellArguments: String): TaskAction =
        if (headlessHost != null) {
            TaskAction(
                command = headlessHost,
                arguments = "--headless ${quoteIfSpaced(powerShellExe(systemRoot))} $powerShellArguments"
            )
        } else {
            TaskAction(
                command = powerShellExe(systemRoot),
                arguments = "-WindowStyle Hidden $powerShellArguments"
            )
        }

    private fun legacyCheck(exePath: String): String =
        "if (-not (Get-Process -Name 'FocusFlow' -ErrorAction SilentlyContinue))" +
            " { Start-Process ${quote(exePath)} }"

    /**
     * One-line form for the `schtasks /tr` fallback, which is used only if the XML registration
     * fails. Bare program names keep it short enough for the 261-character limit.
     */
    fun taskCommandForScript(scriptPath: String, headless: Boolean = false): String =
        scriptAction(scriptPath, null, if (headless) "conhost.exe" else null).commandLine

    /** The previous behaviour, kept as the fallback. */
    fun legacyTaskCommand(exePath: String, headless: Boolean = false): String =
        legacyAction(exePath, null, if (headless) "conhost.exe" else null).commandLine

    /**
     * The path-aware script is used only for the direct (EXE/MSI) install, when the resolved
     * path really is FocusFlow.exe and the task command fits. Store/MSIX, development runs and
     * very long paths keep the old name-only check (a wrong path would relaunch every 2 minutes).
     */
    fun shouldUsePathCheck(
        exePath: String,
        exeExists: Boolean,
        isDirectInstall: Boolean,
        taskCommand: String
    ): Boolean =
        isDirectInstall &&
            exeExists &&
            exePath.substringAfterLast('\\').substringAfterLast('/').equals("FocusFlow.exe", ignoreCase = true) &&
            taskCommand.length <= MAX_TASK_COMMAND_LENGTH

    /**
     * Every PowerShell run FocusFlow's own scheduled tasks can produce, for [TaskRunTrust]: the
     * exact arguments, and for a script run the SHA-256 of the file as FocusFlow writes it.
     *
     * Both launch styles are listed (through `conhost --headless`, or plain hidden PowerShell)
     * because which one is registered depends on the Windows build. The text comes from the same
     * builders that create the tasks, so a change to a task cannot silently stop being trusted.
     *
     * @param exePath the FocusFlow.exe path as the name-only check embeds it
     * @param exeAbsolutePath the same path as the path-aware script embeds it
     */
    fun expectedTaskRuns(
        exePath: String,
        exeAbsolutePath: String,
        watchdogScriptPath: String,
        guardScriptPath: String
    ): List<TaskRunExpectation> {
        val watchdogHash = TaskRunTrust.sha256Hex(scriptBytes(script(exeAbsolutePath)))
        val guardHash = TaskRunTrust.sha256Hex(scriptBytes(taskbarGuardScript()))
        val runs = listOf(
            Triple(scriptPowerShellArguments(watchdogScriptPath), watchdogScriptPath, watchdogHash),
            Triple(scriptPowerShellArguments(guardScriptPath), guardScriptPath, guardHash),
            Triple(legacyPowerShellArguments(exePath), null, null)
        )
        return runs.flatMap { (arguments, scriptPath, hash) ->
            listOf(
                TaskRunExpectation(arguments, scriptPath, hash),
                TaskRunExpectation("-WindowStyle Hidden $arguments", scriptPath, hash)
            )
        }
    }
}
