package com.focusflow.enforcement

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers where a program was found, for a limited time.
 *
 * A path must not be remembered forever: apps that update into a new versioned folder (the
 * Squirrel layout `app-1.2.3\App.exe` keeps the old folder next to the new one) would keep the old
 * path, and a firewall rule built from it would no longer match the program that actually runs.
 * So an entry also expires, not only when its file disappears, and "not found" is never remembered.
 */
internal class ResolvedPathCache(
    private val resolve: (name: String) -> String?,
    private val exists: (path: String) -> Boolean = { java.io.File(it).exists() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private class Entry(val path: String, val resolvedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(name: String): String? {
        val now = nowMs()
        val cached = entries[name]
        if (cached != null && now - cached.resolvedAtMs < ttlMs && exists(cached.path)) return cached.path

        val resolved = resolve(name)
        if (resolved == null) {
            entries.remove(name)
            return null
        }
        entries[name] = Entry(resolved, now)
        return resolved
    }

    companion object {
        /** Matches the rule recheck interval, so a recheck always sees a fresh lookup. */
        const val DEFAULT_TTL_MS = 10 * 60_000L
    }
}
