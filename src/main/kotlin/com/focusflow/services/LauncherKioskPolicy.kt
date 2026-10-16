package com.focusflow.services

import com.focusflow.enforcement.NuclearMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared process decision for Focus Launcher enforcement. The optional
 * descendant and install-root rules are only active after explicit opt-in.
 */
object LauncherKioskPolicy {
    enum class Decision {
        ALLOW,
        BLOCK,
        DEFER_ONE_SNAPSHOT
    }

    private data class ProcessIdentity(val pid: Long, val creationTimeMs: Long?)

    private val unresolvedFirstSightings = ConcurrentHashMap<ProcessIdentity, Long>()

    /** Prune stale first-sighting state once per new background process snapshot. */
    fun onNewSnapshot(snapshot: List<ProcessSnapshotEntry>) {
        val liveIdentities = snapshot.mapTo(HashSet()) { ProcessIdentity(it.pid, it.creationTimeMs) }
        unresolvedFirstSightings.keys.removeIf { it !in liveIdentities }
    }

    fun decide(
        process: ProcessSnapshotEntry,
        snapshot: List<ProcessSnapshotEntry>,
        allowedProcessNames: Set<String>,
        allowedExecutablePaths: Map<String, String>,
        safeProcessNames: Set<String>,
        ownPid: Long,
        systemRoot: String?,
        chainAllowanceEnabled: Boolean,
        snapshotVersion: Long = 0L,
        resolvePath: (ProcessSnapshotEntry) -> String? = { it.executablePath }
    ): Decision {
        val names = allowedProcessNames.mapTo(HashSet(), ::normalizeName)
        val safeNames = safeProcessNames.mapTo(HashSet(), ::normalizeName)
        val pathByName = allowedExecutablePaths.entries.associate {
            normalizeName(it.key) to it.value
        }
        val processName = normalizeName(
            process.processName.ifBlank { resolvePath(process)?.substringAfterLast('\\')?.substringAfterLast('/') ?: "" }
        )
        val executablePath = resolvePath(process)

        if (process.pid == ownPid) return Decision.ALLOW

        // FocusFlow may itself start a small Windows utility. Only exempt a
        // provable direct child in the system directory, and only in opt-in
        // mode; same-named tools from any other parent remain block targets.
        if (
            chainAllowanceEnabled &&
            isFocusFlowOwnedUtility(
                process = process,
                processName = processName,
                executablePath = executablePath,
                allowedNames = names,
                allowedPaths = pathByName,
                ownPid = ownPid,
                systemRoot = systemRoot
            )
        ) {
            return Decision.ALLOW
        }

        // Name and path-based escape-tool detection always wins over ordinary
        // app names, descendants, and install-root allowances.
        if (NuclearMode.isEscapeTool(processName, executablePath)) return Decision.BLOCK
        if (processName in safeNames) return Decision.ALLOW

        if (isAllowedApp(processName, executablePath, names, pathByName)) {
            return Decision.ALLOW
        }
        if (!chainAllowanceEnabled) return Decision.BLOCK

        val liveRoots = snapshot.filter { root ->
            val rootPath = resolvePath(root)
            isAllowedApp(
                normalizeName(
                    root.processName.ifBlank {
                        rootPath?.substringAfterLast('\\')?.substringAfterLast('/') ?: ""
                    }
                ),
                rootPath,
                names,
                pathByName
            )
        }

        if (liveRoots.isNotEmpty()) {
            val allowedRootsByPid = liveRoots.associateBy { it.pid }

            // A launcher-updater/helper may be placed beside the selected
            // executable. Squirrel's app-<version> directory is treated as a
            // single install root so Update.exe is covered too.
            if (
                executablePath != null &&
                liveRoots.any { root ->
                    val configuredPath = pathByName[normalizeName(root.processName)]
                        ?: resolvePath(root)
                    val installRoot = configuredPath?.let(::installRoot)
                    installRoot != null && isWithin(executablePath, installRoot)
                }
            ) {
                return Decision.ALLOW
            }

            when (isDescendantOfAllowedRoot(process, snapshot, allowedRootsByPid.keys)) {
                true -> return Decision.ALLOW
                false -> Unit
                null -> return deferOrBlock(process, snapshotVersion)
            }
        } else if (process.parentPid == null) {
            return deferOrBlock(process, snapshotVersion)
        }

        return Decision.BLOCK
    }

    fun isFocusFlowOwnedUtility(
        process: ProcessSnapshotEntry,
        processName: String = normalizeName(process.processName),
        executablePath: String?,
        allowedNames: Set<String>,
        allowedPaths: Map<String, String>,
        ownPid: Long,
        systemRoot: String?
    ): Boolean {
        if (process.parentPid != ownPid || executablePath == null || systemRoot.isNullOrBlank()) return false
        if (isAllowedApp(processName, executablePath, allowedNames, allowedPaths)) return false
        return isWithin(executablePath, systemRoot)
    }

    private fun isAllowedApp(
        processName: String,
        executablePath: String?,
        allowedNames: Set<String>,
        allowedPaths: Map<String, String>
    ): Boolean {
        if (processName.isNotBlank() && processName in allowedNames) return true
        if (executablePath == null) return false
        val path = normalizePath(executablePath)
        return allowedPaths.values.any { normalizePath(it) == path }
    }

    private fun isDescendantOfAllowedRoot(
        process: ProcessSnapshotEntry,
        snapshot: List<ProcessSnapshotEntry>,
        allowedRootPids: Set<Long>
    ): Boolean? {
        var child = process
        var parentPid = child.parentPid ?: return null
        val byPid = snapshot.associateBy { it.pid }
        val visited = HashSet<Long>()
        while (parentPid > 0 && visited.add(parentPid)) {
            val parent = byPid[parentPid] ?: return null
            val childCreatedAt = child.creationTimeMs ?: return null
            val parentCreatedAt = parent.creationTimeMs ?: return null
            // A PID can be reused after a launcher exits. If the current
            // process occupying that PID started after this child, it cannot
            // be the child's ancestor and must not grant a chain exemption.
            if (parentCreatedAt > childCreatedAt) return false
            if (parentPid in allowedRootPids) return true
            child = parent
            parentPid = parent.parentPid ?: return null
        }
        return false
    }

    private fun deferOrBlock(process: ProcessSnapshotEntry, snapshotVersion: Long): Decision {
        val identity = ProcessIdentity(process.pid, process.creationTimeMs)
        val firstSeenVersion = unresolvedFirstSightings.putIfAbsent(identity, snapshotVersion)
        return if (firstSeenVersion == null || snapshotVersion <= firstSeenVersion) {
            Decision.DEFER_ONE_SNAPSHOT
        } else {
            unresolvedFirstSightings.remove(identity)
            Decision.BLOCK
        }
    }

    private fun installRoot(executablePath: String): String? {
        val executable = normalizePath(executablePath)
        val lastSeparator = executable.lastIndexOf('\\')
        if (lastSeparator <= 2) return null
        val parent = executable.substring(0, lastSeparator)
        val parentName = parent.substringAfterLast('\\')
        return if (parentName.startsWith("app-")) {
            parent.substringBeforeLast('\\', parent)
        } else {
            parent
        }
    }

    private fun isWithin(path: String, directory: String): Boolean {
        val normalizedPath = normalizePath(path)
        val normalizedDirectory = normalizePath(directory).trimEnd('\\')
        return normalizedPath == normalizedDirectory ||
            normalizedPath.startsWith("$normalizedDirectory\\")
    }

    private fun normalizeName(value: String): String =
        value.trim().substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT)

    private fun normalizePath(value: String): String =
        value.trim().replace('/', '\\').trimEnd('\\').lowercase(Locale.ROOT)

    internal fun resetForTest() {
        unresolvedFirstSightings.clear()
    }
}
