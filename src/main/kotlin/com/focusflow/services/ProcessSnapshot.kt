package com.focusflow.services

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

/**
 * Captures process metadata in one ProcessHandle enumeration. Command strings
 * are cached by PID and exact start instant so PID reuse cannot return a stale
 * executable path.
 */
internal object ProcessSnapshot {
    private val commandCache = ProcessCommandCache()

    fun findInstances(app: FocusLauncherApp, nowMs: Long): List<RunningInstance> {
        val handles = ProcessHandle.allProcesses().use { stream -> stream.toList() }
        val liveKeys = HashSet<Pair<Long, Instant>>()
        val expectedName = app.processName.substringAfterLast('\\')
            .substringAfterLast('/')
            .lowercase()
        val expectedPath = app.exePath
            ?.replace('/', '\\')
            ?.lowercase()
        val matches = ArrayList<RunningInstance>()

        for (process in handles) {
            if (!process.isAlive) continue
            val info = process.info()
            val startInstant = info.startInstant().orElse(null)
            if (startInstant != null) liveKeys += process.pid() to startInstant
            val command = commandCache.command(process.pid(), startInstant) {
                info.command().orElse("")
            }
            if (command.isBlank()) continue

            val commandName = command.substringAfterLast('\\')
                .substringAfterLast('/')
                .lowercase()
            val normalizedCommand = command.replace('/', '\\').lowercase()
            if (commandName == expectedName || (expectedPath != null && normalizedCommand == expectedPath)) {
                matches += RunningInstance(
                    pid = process.pid(),
                    startedAtMs = startInstant?.toEpochMilli() ?: nowMs,
                    processName = commandName
                )
            }
        }

        commandCache.retainOnly(liveKeys)
        return matches
    }
}
