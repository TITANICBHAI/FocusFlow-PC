package com.focusflow.enforcement

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Remembers the helper processes FocusFlow itself started (a PowerShell run for the firewall,
 * `taskkill`, `schtasks`, ...), so enforcement can tell them apart from the same programs started
 * by a person.
 *
 * Why identity and not a name: Nuclear Mode and the Focus Launcher must keep blocking
 * `powershell.exe` and `cmd.exe` when a person starts them, so "this is powershell.exe" can never
 * be a reason to spare a process. A helper is spared only when ALL of these match what was
 * recorded at the moment FocusFlow started it:
 *   - the process id, which the OS handed to the child FocusFlow just created;
 *   - its creation time, so a reused id is never mistaken for the helper;
 *   - its executable path, which must be the exact file FocusFlow asked for (normally the real
 *     program in the Windows folder), so a copy or a renamed program does not qualify.
 * A process that is not in this registry is never spared, whatever it is called.
 *
 * The gap between the OS creating the child and the caller learning its id is covered by
 * [beginSpawn]: while a spawn is in flight, a child of this process with the expected image that
 * was created after the spawn began is treated as owned. Entries are removed when the helper ends
 * ([release]) and also expire, so a lost release can never protect a process forever.
 */
internal class OwnedProcessRegistry(
    private val ownPid: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {
    private data class Owned(val image: String, val startMs: Long?, val registeredAtMs: Long)
    private data class InFlight(val image: String, val startedAtMs: Long)

    private val owned = ConcurrentHashMap<Long, Owned>()
    private val inFlight = ConcurrentHashMap<Long, InFlight>()
    private val nextToken = AtomicLong(1L)

    /** Call immediately before starting a helper. Returns a token for [completeSpawn] / [abortSpawn]. */
    fun beginSpawn(expectedImage: String): Long {
        pruneExpired()
        val token = nextToken.getAndIncrement()
        inFlight[token] = InFlight(normalize(expectedImage), nowMs())
        return token
    }

    /** The helper started: record its identity and close the in-flight window. */
    fun completeSpawn(token: Long, pid: Long, startMs: Long?) {
        val spawn = inFlight.remove(token) ?: return
        owned[pid] = Owned(spawn.image, startMs, nowMs())
    }

    /** The helper could not be started. */
    fun abortSpawn(token: Long) {
        inFlight.remove(token)
    }

    /** The helper ended. */
    fun release(pid: Long) {
        owned.remove(pid)
    }

    fun ownedCount(): Int = owned.size

    /**
     * True only when the process is a helper FocusFlow started. An unknown image path fails closed:
     * a process whose file cannot be identified is never spared.
     */
    fun isOwned(pid: Long, startMs: Long?, parentPid: Long?, imagePath: String?): Boolean {
        val image = imagePath?.takeIf(String::isNotBlank)?.let(::normalize) ?: return false
        val now = nowMs()

        owned[pid]?.let { entry ->
            if (now - entry.registeredAtMs <= maxAgeMs &&
                entry.image == image &&
                (entry.startMs == null || startMs == null || entry.startMs == startMs)
            ) {
                return true
            }
        }

        if (parentPid != ownPid || startMs == null) return false
        return inFlight.values.any { spawn ->
            now - spawn.startedAtMs <= maxAgeMs &&
                spawn.image == image &&
                startMs >= spawn.startedAtMs - SPAWN_CLOCK_SLACK_MS
        }
    }

    private fun pruneExpired() {
        val now = nowMs()
        owned.entries.removeIf { now - it.value.registeredAtMs > maxAgeMs }
        inFlight.entries.removeIf { now - it.value.startedAtMs > maxAgeMs }
    }

    internal companion object {
        /** Longest any single helper is expected to run (the firewall PowerShell timeout is 60 s). */
        const val DEFAULT_MAX_AGE_MS = 10 * 60_000L

        /** Process creation times and this clock are both wall-clock; allow for small differences. */
        private const val SPAWN_CLOCK_SLACK_MS = 1_000L

        fun normalize(path: String): String =
            path.trim().removePrefix("\\\\?\\").replace('/', '\\').lowercase()
    }
}
