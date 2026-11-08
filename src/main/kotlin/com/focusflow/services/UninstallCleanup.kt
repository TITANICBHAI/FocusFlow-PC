package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.WatchdogInstaller

/**
 * What FocusFlow takes off the PC when an uninstall goes ahead: its two scheduled tasks and the
 * small folder of scripts they run. An app that has been removed should leave nothing running on
 * someone's own computer.
 *
 * It is called from [UninstallCheck.run], which the installer runs only for a real uninstall
 * (REMOVE="ALL", never for an upgrade) and only gets as far as this once the answer is "go ahead".
 * Two properties matter more than the cleanup itself:
 *
 *  - It can never change the answer. A failure here is logged and the uninstall still continues.
 *  - It is safe to undo. If someone authorizes the uninstall and then cancels it, FocusFlow puts
 *    both tasks back the next time it starts, because it registers them again at every launch.
 *
 * Not covered: the Microsoft Store build (Windows removes that package itself, so no check runs)
 * and the autostart entry, which only the Settings switch removes.
 */
internal object UninstallCleanup {
    private const val LOG_TAG = "UninstallCleanup"

    /** Replaced in tests so they never delete the real machine's scheduled tasks. */
    internal var removeForTest: (() -> Unit)? = null

    /**
     * The installer refuses a removal only on exactly [UninstallCheck.EXIT_DENY], so every other
     * outcome means the removal goes ahead and the cleanup should run with it.
     */
    fun shouldCleanUp(exitCode: Int, isDirectInstall: Boolean): Boolean =
        isDirectInstall && exitCode != UninstallCheck.EXIT_DENY

    /** Returns true when the cleanup ran without a problem. Never throws. */
    fun runIfNeeded(exitCode: Int, isDirectInstall: Boolean): Boolean {
        if (!shouldCleanUp(exitCode, isDirectInstall)) return false
        return try {
            (removeForTest ?: WatchdogInstaller::uninstall)()
            EnforcementLog.info(LOG_TAG, "scheduled tasks and scripts removed")
            true
        } catch (failure: Throwable) {
            EnforcementLog.warn(LOG_TAG, "could not remove the scheduled tasks; the uninstall continues", failure)
            false
        }
    }

    internal fun resetForTest() {
        removeForTest = null
    }
}
