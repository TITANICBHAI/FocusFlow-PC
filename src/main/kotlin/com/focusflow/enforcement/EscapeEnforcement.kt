package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry

/**
 * The decision-and-kill flow of one Nuclear Mode sweep, with everything that touches the system
 * passed in, so it can be tested without ending a real process.
 *
 * Processes are chosen and ended by process id, never by image name: a name-wide kill of
 * `powershell.exe` also ends FocusFlow's own PowerShell (the firewall work, the Watchdog's
 * scheduled-task run), which is the failure this flow exists to prevent.
 */
internal object EscapeEnforcement {

    /**
     * Which processes to end this sweep.
     *
     * @param launcherBlocks null when no Focus Launcher policy applies (every untrusted escape
     *   process is a target); otherwise it is asked, for escape processes only, whether the
     *   launcher policy blocks that process. Trust is decided by [isTrusted] before this is asked.
     */
    fun plan(
        snapshot: List<ProcessSnapshotEntry>,
        ownPid: Long,
        escapeNames: Set<String>,
        escapePathSuffixes: Set<String>,
        resolvePath: (ProcessSnapshotEntry) -> String?,
        isNameBlockable: (String) -> Boolean,
        isTrusted: (ProcessSnapshotEntry, String?) -> Boolean,
        launcherBlocks: ((ProcessSnapshotEntry) -> Boolean)?
    ): Map<Long, String> {
        val targets = EscapeTargetSelector.select(
            snapshot = snapshot,
            ownPid = ownPid,
            escapeNames = escapeNames,
            escapePathSuffixes = escapePathSuffixes,
            resolvePath = resolvePath,
            isNameBlockable = isNameBlockable,
            isTrusted = isTrusted
        )
        if (launcherBlocks == null || targets.isEmpty()) return targets

        val byPid = snapshot.associateBy { it.pid }
        return targets.filterKeys { pid -> byPid[pid]?.let(launcherBlocks) ?: true }
    }

    /**
     * Ends [targets] and returns the ones that were actually attempted (for the escape-attempt log).
     *
     * A target whose process is gone, or whose id now belongs to a newer process, is dropped: the
     * snapshot is up to 500 ms old and an id can be reused. If the batched kill reports a failure
     * (for example because one process exited meanwhile) every process still running is ended
     * directly, so one stale id cannot protect the others.
     *
     * @param isAlive null means "cannot tell"; such a process is still ended.
     */
    fun kill(
        targets: Map<Long, String>,
        snapshot: List<ProcessSnapshotEntry>,
        isAlive: (pid: Long, creationTimeMs: Long?) -> Boolean?,
        killBatch: (Collection<Long>) -> Boolean,
        killOne: (Long) -> Unit
    ): Map<Long, String> {
        if (targets.isEmpty()) return emptyMap()
        val started = snapshot.associate { it.pid to it.creationTimeMs }

        fun stillThere(pid: Long) = isAlive(pid, started[pid]) != false

        val attempted = targets.filterKeys(::stillThere)
        if (attempted.isEmpty()) return emptyMap()

        if (!killBatch(attempted.keys.toList())) {
            for (pid in attempted.keys) {
                if (!stillThere(pid)) continue
                try {
                    killOne(pid)
                } catch (_: Exception) {
                    // Best effort: one process that cannot be ended must not spare the others.
                }
            }
        }
        return attempted
    }
}
