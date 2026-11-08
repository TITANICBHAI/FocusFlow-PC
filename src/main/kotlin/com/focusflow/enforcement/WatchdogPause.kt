package com.focusflow.enforcement

/**
 * Decides when the watchdog is told to stand down. The watchdog (see [WatchdogInstaller]) exists
 * to bring FocusFlow back after a crash or a kill. It must not start FocusFlow again after the
 * user quit it on purpose: that would be annoying, and the Quit protections (a PIN, a Standalone
 * Block and the like) have already decided that this user may quit.
 *
 * "On purpose" means a Quit that went through the Quit protections. Hand-offs do not count: the
 * Restart-as-administrator hand-off skips the protections because a new FocusFlow is taking over,
 * and the watchdog should keep watching in case that new copy fails to start.
 *
 * Starting FocusFlow again switches the watchdog back on by itself, because every launch
 * registers the task afresh.
 */
internal object WatchdogPause {
    private const val LOG_TAG = "WatchdogPause"

    /** Replaced in tests so they never touch the real machine's scheduled tasks. */
    internal var pauseForTest: (() -> Unit)? = null

    fun isDeliberateQuit(skipQuitAuthorization: Boolean, preserveNuclearMode: Boolean): Boolean =
        !skipQuitAuthorization && !preserveNuclearMode

    /** Returns true when the watchdog was paused. Never throws, so it can never block a Quit. */
    fun pauseIfDeliberateQuit(skipQuitAuthorization: Boolean, preserveNuclearMode: Boolean): Boolean {
        if (!isDeliberateQuit(skipQuitAuthorization, preserveNuclearMode)) return false
        return try {
            (pauseForTest ?: WatchdogInstaller::pauseWatchdog)()
            true
        } catch (failure: Throwable) {
            EnforcementLog.warn(LOG_TAG, "could not pause the watchdog; quitting anyway", failure)
            false
        }
    }

    internal fun resetForTest() {
        pauseForTest = null
    }
}
