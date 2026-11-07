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
        action(systemRoot, headlessHost, "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"$scriptPath\"")

    /** Name-only check for Store/MSIX, development runs and very long paths. */
    fun legacyAction(exePath: String, systemRoot: String?, headlessHost: String?): TaskAction =
        action(systemRoot, headlessHost, "-NoProfile -NonInteractive -Command \"${legacyCheck(exePath)}\"")

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
}
