package com.focusflow.enforcement

import java.util.concurrent.TimeUnit

/**
 * Ends processes by id, never by image name.
 *
 * `taskkill /IM powershell.exe` ends every PowerShell on the machine, including the ones FocusFlow
 * itself started for the firewall and the Watchdog. Killing by id lets the caller decide, per
 * process, what is an escape tool and what is FocusFlow's own work.
 *
 * Everything the OS or the filesystem touches is injected so the command shape can be tested
 * without running anything.
 */
internal object Taskkill {
    /** Keeps each command line far below the Windows limit however many processes are targeted. */
    const val MAX_PIDS_PER_COMMAND = 20
    private const val COMMAND_TIMEOUT_SECONDS = 5L

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /**
     * Ends [pids] (duplicates, non-positive ids and [ownPid] are ignored).
     * Returns true only when every process was asked to end successfully; false when there was
     * nothing to end or at least one command failed. A failed chunk never stops later chunks.
     */
    fun killPids(
        pids: Collection<Long>,
        ownPid: Long = ProcessHandle.current().pid(),
        windows: Boolean = isWindows,
        taskkillExe: String = SystemTools.path("taskkill.exe"),
        runner: (List<String>) -> Boolean = ::runCommand
    ): Boolean {
        val targets = pids.filter { it > 0 && it != ownPid }.distinct()
        if (targets.isEmpty()) return false

        if (!windows) {
            return targets.map { pid ->
                ProcessHandle.of(pid).map { it.destroyForcibly() }.orElse(false)
            }.all { it }
        }

        var allSucceeded = true
        for (chunk in targets.chunked(MAX_PIDS_PER_COMMAND)) {
            val command = buildList {
                add(taskkillExe)
                add("/F")
                chunk.forEach { add("/PID"); add(it.toString()) }
            }
            if (!runner(command)) allSucceeded = false
        }
        return allSucceeded
    }

    /** Runs [command] as a FocusFlow-owned child, so enforcement never mistakes it for an escape tool. */
    private fun runCommand(command: List<String>): Boolean = try {
        val process = OwnedProcesses.start(
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD),
            expectedImage = command.first()
        )
        if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            false
        }
    } catch (_: Exception) {
        false
    }
}
