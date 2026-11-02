package com.focusflow.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Timer bookkeeping for [LooseningCoolOff]: no database, no UI.
 *
 * Pending items live in memory only on purpose. If FocusFlow stops during a cool-off, the
 * protection simply stays on — a stop can never loosen anything.
 */
class CoolOffScheduler(private val nanoTime: () -> Long = System::nanoTime) {
    private class Entry(val future: ScheduledFuture<*>, val dueAtNanos: Long)

    private val lock = Any()
    private val pending = ConcurrentHashMap<String, Entry>()
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "CoolOffScheduler").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** Returns false (and does nothing) when [key] is already pending. */
    fun schedule(key: String, delayMs: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (pending.containsKey(key)) return false
        val dueAt = nanoTime() + delayMs * 1_000_000L
        val future = executor.schedule(
            {
                synchronized(lock) { pending.remove(key) }
                runCatching(action)
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
        pending[key] = Entry(future, dueAt)
        true
    }

    /** Returns true when something was pending and is now cancelled. */
    fun cancel(key: String): Boolean = synchronized(lock) {
        val entry = pending.remove(key) ?: return false
        entry.future.cancel(false)
        true
    }

    fun isPending(key: String): Boolean = pending.containsKey(key)

    fun remainingMs(key: String): Long {
        val entry = pending[key] ?: return 0L
        return ((entry.dueAtNanos - nanoTime()) / 1_000_000L).coerceAtLeast(0L)
    }
}
