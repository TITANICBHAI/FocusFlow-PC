package com.focusflow.enforcement

/**
 * Builds the watchdog's PowerShell check. Pure text — no Windows calls — so it is unit-tested.
 *
 * The old watchdog only asked "is there a process named FocusFlow?", so any program renamed to
 * FocusFlow.exe kept it quiet. The script below also compares the process's file path with the
 * real FocusFlow.exe. If Windows will not tell it the path (access denied), it assumes the
 * process is FocusFlow, so it can never start a second copy because of a path it could not read.
 */
internal object WatchdogScript {

    /** `schtasks /tr` accepts at most 261 characters. */
    const val MAX_TASK_COMMAND_LENGTH = 260

    /** PowerShell single-quoted string escaping. */
    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"

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

    fun taskCommandForScript(scriptPath: String): String =
        "powershell -WindowStyle Hidden -NonInteractive -ExecutionPolicy Bypass -File \"$scriptPath\""

    /** The previous behaviour, kept as the fallback. */
    fun legacyTaskCommand(exePath: String): String {
        val psScript = "if (-not (Get-Process -Name 'FocusFlow' -ErrorAction SilentlyContinue))" +
            " { Start-Process ${quote(exePath)} }"
        return "powershell -WindowStyle Hidden -NonInteractive -Command \"$psScript\""
    }

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
