package com.focusflow.enforcement

data class SessionActivityChange(val inactive: Boolean)

/**
 * Combines lock and suspend signals. Resuming the machine must not resume
 * tracking while the Windows session is still locked.
 */
class SessionActivityGate {
    private var locked = false
    private var suspended = false
    private var inactive = false

    @Synchronized
    fun setLocked(value: Boolean): SessionActivityChange? {
        locked = value
        return update()
    }

    @Synchronized
    fun setSuspended(value: Boolean): SessionActivityChange? {
        suspended = value
        return update()
    }

    @Synchronized
    fun reset(): SessionActivityChange? {
        locked = false
        suspended = false
        return update()
    }

    @Synchronized
    fun isInactive(): Boolean = inactive

    private fun update(): SessionActivityChange? {
        val next = locked || suspended
        if (next == inactive) return null
        inactive = next
        return SessionActivityChange(next)
    }
}
