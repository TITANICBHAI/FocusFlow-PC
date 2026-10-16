package com.focusflow.services

import com.focusflow.enforcement.WindowsProcessImageApi
import com.focusflow.enforcement.isWindows
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class ProcessCommandCache {
    private data class Key(val pid: Long, val startInstant: Instant)

    private val commands = ConcurrentHashMap<Key, String>()

    fun command(pid: Long, startInstant: Instant?, readCommand: () -> String): String {
        if (startInstant == null) return readCommand()
        return commands.computeIfAbsent(Key(pid, startInstant)) { readCommand() }
    }

    fun retainOnly(liveKeys: Set<Pair<Long, Instant>>) {
        commands.keys.removeIf { key -> (key.pid to key.startInstant) !in liveKeys }
    }
}

internal class ProcessSnapshotCache(
    private val ttlMs: Long,
    private val nowMs: () -> Long,
    private val load: () -> List<ProcessSnapshotEntry>
) {
    @Volatile private var cachedAtMs: Long? = null
    @Volatile private var cachedEntries: List<ProcessSnapshotEntry> = emptyList()

    @Synchronized
    fun snapshot(): List<ProcessSnapshotEntry> {
        val now = nowMs()
        val capturedAt = cachedAtMs
        if (capturedAt != null && now >= capturedAt && now - capturedAt < ttlMs) {
            return cachedEntries
        }
        val fresh = load().toList()
        cachedEntries = fresh
        cachedAtMs = now
        return fresh
    }
}

/**
 * One short-lived process snapshot is shared by launch discovery and lineage
 * tracking. Paths are queried with limited rights only when needed and are
 * cached by PID plus creation time so PID reuse cannot inherit stale metadata.
 */
internal object ProcessSnapshot {
    private const val SNAPSHOT_TTL_MS = 250L

    private val commandCache = ProcessCommandCache()
    private val fullPathCache = ProcessCommandCache()
    private val snapshotCache = ProcessSnapshotCache(
        ttlMs = SNAPSHOT_TTL_MS,
        nowMs = { System.nanoTime() / 1_000_000L },
        load = ::readSnapshot
    )

    fun snapshotProcesses(): List<ProcessSnapshotEntry> = snapshotCache.snapshot()

    fun findInstances(app: FocusLauncherApp, nowMs: Long): List<RunningInstance> {
        val expectedName = app.processName.substringAfterLast('\\')
            .substringAfterLast('/')
            .lowercase()
        val expectedPath = normalizePath(app.exePath)
        val matches = ArrayList<RunningInstance>()

        for (process in snapshotProcesses()) {
            val fullPath = process.executablePath ?: resolveExecutablePath(process)
            val commandName = (process.processName.ifBlank { fullPath?.let(::imageName).orEmpty() })
                .substringAfterLast('\\')
                .substringAfterLast('/')
                .lowercase()
            if (commandName == expectedName ||
                (expectedPath != null && normalizePath(fullPath) == expectedPath)
            ) {
                matches += RunningInstance(
                    pid = process.pid,
                    startedAtMs = process.creationTimeMs ?: nowMs,
                    processName = commandName,
                    parentPid = process.parentPid,
                    creationTimeMs = process.creationTimeMs,
                    executablePath = fullPath
                )
            }
        }

        return matches
    }

    fun resolveExecutablePath(process: ProcessSnapshotEntry): String? {
        process.executablePath?.takeIf(String::isNotBlank)?.let { return it }
        if (!isWindows || process.pid !in 1..Int.MAX_VALUE.toLong()) return null
        val startInstant = process.creationTimeMs?.let(Instant::ofEpochMilli)
        return fullPathCache.command(process.pid, startInstant) {
            WindowsProcessImageApi.queryFullImagePath(process.pid).orEmpty()
        }.takeIf(String::isNotBlank)
    }

    fun isAlive(pid: Long, creationTimeMs: Long?): Boolean? {
        return try {
            val process = ProcessHandle.of(pid).orElse(null) ?: return false
            if (!process.isAlive) return false
            if (creationTimeMs == null) return true
            val actualStart = process.info().startInstant().orElse(null)?.toEpochMilli()
                ?: return null
            actualStart == creationTimeMs
        } catch (_: Exception) {
            null
        }
    }

    private fun readSnapshot(): List<ProcessSnapshotEntry> {
        val handles = ProcessHandle.allProcesses().use { stream -> stream.toList() }
        val liveKeys = HashSet<Pair<Long, Instant>>()
        val entries = ArrayList<ProcessSnapshotEntry>(handles.size)

        for (process in handles) {
            try {
                if (!process.isAlive) continue
                val info = process.info()
                val startInstant = info.startInstant().orElse(null)
                if (startInstant != null) liveKeys += process.pid() to startInstant
                val command = commandCache.command(process.pid(), startInstant) {
                    info.command().orElse("")
                }.takeIf(String::isNotBlank)
                val parentPid = runCatching { process.parent().orElse(null)?.pid() }.getOrNull()
                entries += ProcessSnapshotEntry(
                    pid = process.pid(),
                    parentPid = parentPid,
                    creationTimeMs = startInstant?.toEpochMilli(),
                    processName = command?.let(::imageName).orEmpty(),
                    executablePath = command
                )
            } catch (_: Exception) {
                // One inaccessible or exiting process must not discard the rest
                // of the snapshot. Its PID can still be tracked through windows.
            }
        }

        commandCache.retainOnly(liveKeys)
        fullPathCache.retainOnly(liveKeys)
        return entries
    }

    private fun imageName(path: String): String =
        path.substringAfterLast('\\').substringAfterLast('/')

    private fun normalizePath(path: String?): String? =
        path?.replace('/', '\\')?.trimEnd('\\')?.lowercase()

}

internal data class ProcessIdentity(
    val pid: Long,
    val creationTimeMs: Long
)

/**
 * Keeps the live process chain for one launch attempt. A tracked PID is retained
 * only while the same process instance is present; creation time distinguishes
 * a reused PID from the process originally observed.
 */
internal class ProcessLineageTracker(
    private val app: FocusLauncherApp,
    private val chainStartedAtMs: Long,
    private val resolvePath: (ProcessSnapshotEntry) -> String? = { it.executablePath }
) {
    private val tracked = linkedMapOf<Long, ProcessSnapshotEntry>()
    private val expectedName = imageName(app.processName).lowercase()
    private val expectedPath = normalizePath(app.exePath)
    private val installRoot = installRoot(app.exePath)

    fun addSpawnPid(pid: Long) {
        if (pid > 0L) tracked.putIfAbsent(pid, ProcessSnapshotEntry(pid = pid))
    }

    fun update(snapshot: List<ProcessSnapshotEntry>): Set<Long> {
        val currentByPid = snapshot.associateBy(ProcessSnapshotEntry::pid)

        tracked.entries.toList().forEach { (pid, previous) ->
            val current = currentByPid[pid]
            if (current == null || isDifferentInstance(previous, current)) {
                tracked.remove(pid)
            } else {
                tracked[pid] = if (previous.creationTimeMs != null &&
                    current.creationTimeMs == null
                ) {
                    current.copy(creationTimeMs = previous.creationTimeMs)
                } else {
                    current
                }
            }
        }

        snapshot.forEach { process ->
            if (matchesApp(process)) tracked[process.pid] = process
        }

        var added = true
        while (added) {
            added = false
            snapshot.forEach { process ->
                if (process.pid in tracked) return@forEach
                val parentPid = process.parentPid ?: return@forEach
                val parent = tracked[parentPid] ?: return@forEach
                val currentParent = currentByPid[parentPid] ?: return@forEach
                if (isConfirmedSameInstance(parent, currentParent)) {
                    tracked[process.pid] = process
                    added = true
                }
            }
        }

        if (installRoot != null) {
            snapshot.forEach { process ->
                if (process.pid in tracked) return@forEach
                val createdAt = process.creationTimeMs ?: return@forEach
                if (createdAt < chainStartedAtMs) return@forEach
                val path = executablePath(process) ?: return@forEach
                if (isWithinRoot(path, installRoot)) tracked[process.pid] = process
            }
        }

        var addedDescendant = true
        while (addedDescendant) {
            addedDescendant = false
            snapshot.forEach { process ->
                if (process.pid in tracked) return@forEach
                val parentPid = process.parentPid ?: return@forEach
                val parent = tracked[parentPid] ?: return@forEach
                val currentParent = currentByPid[parentPid] ?: return@forEach
                if (isConfirmedSameInstance(parent, currentParent)) {
                    tracked[process.pid] = process
                    addedDescendant = true
                }
            }
        }

        return livePids()
    }

    fun liveProcesses(): List<ProcessSnapshotEntry> = tracked.values.toList()

    fun process(pid: Long): ProcessSnapshotEntry? = tracked[pid]

    fun livePids(): Set<Long> = tracked.keys.toSet()

    fun identities(): Set<ProcessIdentity> =
        tracked.values.mapNotNull { process ->
            process.creationTimeMs?.let { ProcessIdentity(process.pid, it) }
        }.toSet()

    fun appInstances(snapshot: List<ProcessSnapshotEntry>): List<ProcessSnapshotEntry> =
        snapshot.filter(::matchesApp)

    private fun matchesApp(process: ProcessSnapshotEntry): Boolean {
        val name = process.processName.takeIf(String::isNotBlank)
            ?.let(::imageName)
            ?.lowercase()
            .orEmpty()

        if (name == expectedName) return true
        val path = executablePath(process)
        return expectedPath != null && normalizePath(path) == expectedPath
    }

    private fun executablePath(process: ProcessSnapshotEntry): String? =
        process.executablePath?.takeIf(String::isNotBlank)
            ?: resolvePath(process)?.takeIf(String::isNotBlank)

    private fun isDifferentInstance(
        previous: ProcessSnapshotEntry,
        current: ProcessSnapshotEntry
    ): Boolean =
        previous.creationTimeMs != null &&
            current.creationTimeMs != null &&
            previous.creationTimeMs != current.creationTimeMs

    private fun isConfirmedSameInstance(
        previous: ProcessSnapshotEntry,
        current: ProcessSnapshotEntry
    ): Boolean =
        previous.creationTimeMs == null ||
            current.creationTimeMs == previous.creationTimeMs

    private fun imageName(path: String): String =
        path.substringAfterLast('\\').substringAfterLast('/')

    private fun normalizePath(path: String?): String? =
        path?.replace('/', '\\')?.trimEnd('\\')?.lowercase()

    private fun installRoot(path: String?): String? {
        val normalized = normalizePath(path) ?: return null
        val executableDirectory = normalized.substringBeforeLast('\\', missingDelimiterValue = "")
        if (executableDirectory.isBlank()) return null
        val directoryName = executableDirectory.substringAfterLast('\\')
        val root = if (directoryName.startsWith("app-")) {
            executableDirectory.substringBeforeLast('\\', missingDelimiterValue = "")
        } else {
            executableDirectory
        }
        return root.takeIf(String::isNotBlank)
    }

    private fun isWithinRoot(path: String, root: String): Boolean {
        val normalized = normalizePath(path) ?: return false
        return normalized == root || normalized.startsWith("$root\\")
    }
}
