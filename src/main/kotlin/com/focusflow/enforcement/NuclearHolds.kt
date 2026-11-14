package com.focusflow.enforcement

/**
 * Who is keeping Nuclear Mode on.
 *
 * - **manual**: the person turned it on from FocusFlow. It is remembered across restarts
 *   and guarded by the Nuclear PIN.
 * - **session**: a Focus Launcher session needs it. It is never remembered by itself (an
 *   interrupted session is restored by the launcher's own session record) and it ends with
 *   the session.
 *
 * Nuclear enforcement runs while either hold is set, so ending a session can never switch
 * off a manual setting, and a manual setting never needs to be re-made after a session.
 * This class is pure state: it starts or stops nothing.
 */
internal class NuclearHolds {
    data class Snapshot(val manual: Boolean, val session: Boolean) {
        val active: Boolean get() = manual || session
    }

    data class Transition(val before: Snapshot, val after: Snapshot) {
        /** Enforcement must start: nothing held before, something held now. */
        val started: Boolean get() = !before.active && after.active

        /** Enforcement must stop: something held before, nothing held now. */
        val stopped: Boolean get() = before.active && !after.active

        val manualChanged: Boolean get() = before.manual != after.manual
        val sessionChanged: Boolean get() = before.session != after.session
    }

    private var current = Snapshot(manual = false, session = false)

    @Synchronized
    fun snapshot(): Snapshot = current

    @Synchronized
    fun setManual(held: Boolean): Transition = change { it.copy(manual = held) }

    @Synchronized
    fun setSession(held: Boolean): Transition = change { it.copy(session = held) }

    /** Drops both holds without any meaning for what is remembered (process hand-off). */
    @Synchronized
    fun clearAll(): Transition = change { Snapshot(manual = false, session = false) }

    private fun change(update: (Snapshot) -> Snapshot): Transition {
        val before = current
        current = update(before)
        return Transition(before, current)
    }
}
