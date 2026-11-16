package com.focusflow.enforcement

import com.focusflow.AppPaths
import com.focusflow.services.ProcessSnapshot
import com.focusflow.services.ProcessSnapshotEntry
import java.io.File

/**
 * Answers one question for the enforcement loops: "is this process one of FocusFlow's own
 * helpers?". Nuclear Mode and the Focus Launcher ask it before ending a process that is on the
 * escape-tool list, so a person's PowerShell or cmd is still ended while FocusFlow's own
 * PowerShell, the one it needs for firewall work and the one Task Scheduler runs for the
 * watchdog, is left to finish.
 *
 * Two kinds of helper exist, and they are recognised differently because they are started
 * differently:
 *   - helpers FocusFlow started itself are recorded at spawn ([OwnedProcessRegistry]);
 *   - scheduled-task runs are started by Windows, so they are recognised by their exact program,
 *     arguments, parent and script content ([TaskRunTrust]).
 * Neither looks at the program name alone. A process that fits neither is not spared.
 */
internal object EnforcementTrust {

    /**
     * @return a check for the given snapshot: `(process, resolvedImagePath) -> trusted`. Parents
     *   are looked up in [snapshot] so a decision never costs an extra system call for them.
     */
    fun helperCheck(
        snapshot: List<ProcessSnapshotEntry>,
        registry: OwnedProcessRegistry = OwnedProcesses.registry,
        taskRunTrust: TaskRunTrust = scheduledTaskTrust,
        commandLineOf: (Long) -> String? = ::readCommandLine,
        imageOf: (ProcessSnapshotEntry) -> String? = ProcessSnapshot::resolveExecutablePath
    ): (ProcessSnapshotEntry, String?) -> Boolean {
        val byPid by lazy(LazyThreadSafetyMode.NONE) { snapshot.associateBy { it.pid } }
        // A tick can ask about the same process more than once (target selection, then the
        // launcher policy). The answer cannot change within one snapshot, and the command-line
        // read is the one costly step, so each process is decided once.
        val decided = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
        return { process, path ->
            decided.getOrPut(process.pid) {
                registry.isOwned(process.pid, process.creationTimeMs, process.parentPid, path) ||
                    (isPowerShell(path) && isScheduledTaskRun(process, path, byPid, taskRunTrust, commandLineOf, imageOf))
            }
        }
    }

    private fun isScheduledTaskRun(
        process: ProcessSnapshotEntry,
        path: String?,
        byPid: Map<Long, ProcessSnapshotEntry>,
        taskRunTrust: TaskRunTrust,
        commandLineOf: (Long) -> String?,
        imageOf: (ProcessSnapshotEntry) -> String?
    ): Boolean {
        val parent = process.parentPid?.let(byPid::get) ?: return false
        return taskRunTrust.isTrusted(
            TaskRunFacts(
                imagePath = path,
                commandLine = commandLineOf(process.pid),
                parentImagePath = imageOf(parent)
            )
        )
    }

    private fun isPowerShell(path: String?): Boolean =
        path != null && path.substringAfterLast('\\').substringAfterLast('/').equals("powershell.exe", ignoreCase = true)

    /** FocusFlow's own scheduled tasks, built from the same code that registers them. */
    private val scheduledTaskTrust: TaskRunTrust by lazy {
        val expectations by lazy {
            val exePath = WindowsStartupManager.resolveExePath()
            val scriptsDir = AppPaths.taskScriptsDir
            WatchdogScript.expectedTaskRuns(
                exePath = exePath,
                exeAbsolutePath = File(exePath).absolutePath,
                watchdogScriptPath = File(scriptsDir, WatchdogScript.WATCHDOG_SCRIPT_NAME).absolutePath,
                guardScriptPath = File(scriptsDir, WatchdogScript.GUARD_SCRIPT_NAME).absolutePath
            )
        }
        TaskRunTrust(
            systemRoot = SystemTools.systemRoot(),
            expectations = { expectations },
            fileSha256 = ::sha256OfScript
        )
    }

    /** Scripts are a few hundred bytes; anything large is not one of ours. */
    private const val MAX_SCRIPT_BYTES = 1L shl 20

    private fun sha256OfScript(path: String): String? = try {
        File(path).takeIf { it.isFile && it.length() <= MAX_SCRIPT_BYTES }
            ?.readBytes()
            ?.let(TaskRunTrust::sha256Hex)
    } catch (_: Exception) {
        null
    }

    private fun readCommandLine(pid: Long): String? = try {
        ProcessHandle.of(pid).flatMap { it.info().commandLine() }.orElse(null)
    } catch (_: Exception) {
        null
    }
}
