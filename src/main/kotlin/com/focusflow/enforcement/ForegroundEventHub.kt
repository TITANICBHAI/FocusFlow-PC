package com.focusflow.enforcement

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe listener registry used by the single Windows foreground hook. */
class ForegroundEventHub {
    private val nextId = AtomicLong()
    private val listeners = ConcurrentHashMap<Long, (ForegroundEvent) -> Unit>()

    fun addListener(listener: (ForegroundEvent) -> Unit): Long {
        val id = nextId.incrementAndGet()
        listeners[id] = listener
        return id
    }

    fun removeListener(id: Long) {
        listeners.remove(id)
    }

    fun publish(event: ForegroundEvent) {
        listeners.values.forEach { listener -> runCatching { listener(event) } }
    }
}
