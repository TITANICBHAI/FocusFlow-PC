package com.focusflow.services

/**
 * Decides whether a Backup & Recovery action needs the Global PIN first.
 *
 * Restoring an older backup, deleting backups or recovery archives, and changing the secondary
 * backup folder can all be used to get back to a weaker set of protections. They follow the same
 * fail-closed rule as the other PIN gates: only a confirmed "no PIN" lets the action run without
 * one, and an unreadable PIN state ([PinState.UNKNOWN]) counts as "set".
 */
object BackupActionPolicy {
    fun requiresPin(pinState: PinState): Boolean = pinState != PinState.NOT_SET
}
