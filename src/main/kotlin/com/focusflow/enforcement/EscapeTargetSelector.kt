package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry

/**
 * Chooses which running processes Nuclear Mode ends, one entry per process id.
 *
 * Selecting by process (not by image name) is what lets enforcement end a person's `powershell.exe`
 * while leaving a PowerShell that FocusFlow itself started, or one of its own scheduled-task runs,
 * alone: `taskkill /IM powershell.exe` cannot tell them apart, a process id can. Which processes
 * count as FocusFlow's own is decided by [isTrusted] (see [OwnedProcessRegistry] and
 * [TaskRunTrust]); nothing here exempts a program by name, folder or parent.
 */
internal object EscapeTargetSelector {

    /**
     * @param escapeNames lower-case image names that are escape tools
     * @param escapePathSuffixes lower-case `\`-separated path endings of the known system copies
     * @param resolvePath the executable path of a process, or null when it cannot be read
     * @param isNameBlockable false for an image that must not be ended right now (for example an
     *   installer while Store removal is allowed)
     * @param isTrusted true for a process that is provably one of FocusFlow's own helpers; it
     *   receives the resolved image path so the decision never has to look it up a second time
     * @return process id → the running image name (lower case)
     */
    fun select(
        snapshot: List<ProcessSnapshotEntry>,
        ownPid: Long,
        escapeNames: Set<String>,
        escapePathSuffixes: Set<String>,
        resolvePath: (ProcessSnapshotEntry) -> String?,
        isNameBlockable: (String) -> Boolean,
        isTrusted: (ProcessSnapshotEntry, String?) -> Boolean
    ): Map<Long, String> {
        val targets = LinkedHashMap<Long, String>()
        for (process in snapshot) {
            if (process.pid == ownPid) continue
            val path = resolvePath(process)
            val name = imageName(process.processName.ifBlank { path.orEmpty() })
            if (name.isBlank()) continue

            val byName = name in escapeNames
            val byPath = path
                ?.lowercase()
                ?.replace('/', '\\')
                ?.let { normalized -> escapePathSuffixes.any(normalized::endsWith) } == true
            if (!byName && !byPath) continue
            if (!isNameBlockable(name)) continue
            if (isTrusted(process, path)) continue

            targets[process.pid] = name
        }
        return targets
    }

    private fun imageName(image: String): String =
        image.substringAfterLast('\\').substringAfterLast('/').lowercase()
}
