package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.requireSuccess
import java.security.MessageDigest

/**
 * NuclearPin
 *
 * Optional 4-character PIN gate exclusively for the Nuclear Mode off-switch.
 * Independent of GlobalPin (which guards settings removal, min 8 chars) and
 * SessionPin (which is session-scoped and auto-generated).
 *
 * Design contract:
 * - When set, the UI must verify this PIN before calling NuclearMode.disable().
 * - NuclearMode.disable() itself does NOT enforce the PIN — enforcement is in the UI
 *   so that silent=true callers (FocusLauncherService kiosk lifecycle) are unaffected.
 * - SHA-256 hashed. Plain text is never stored.
 * - Minimum length: 4 characters (shorter than GlobalPin intentionally — quick to type
 *   while still being a meaningful deterrent against impulsive Nuclear Mode disabling).
 */
object NuclearPin {

    private const val KEY = "nuclear_pin_hash"

    fun state(): PinState = PinPolicy.stateOf(Database.readSetting(KEY))

    /** Unknown storage is treated as configured so security gates remain closed. */
    fun isSet(): Boolean = state() != PinState.NOT_SET

    /**
     * Store a new PIN. Throws [IllegalArgumentException] if rawPin is shorter than 4 chars.
     * Only the SHA-256 hash is persisted — plain text is never stored.
     */
    fun set(rawPin: String) {
        require(rawPin.length >= 4) { "Nuclear Mode PIN must be at least 4 characters" }
        Database.writeSetting(KEY, sha256(rawPin)).requireSuccess("set Nuclear Mode PIN")
    }

    /** Unknown storage is distinguishable from an incorrect PIN and is denied. */
    fun verifyResult(rawPin: String): PinVerificationResult =
        PinPolicy.verify(Database.readSetting(KEY), rawPin, ::sha256)

    /** Returns true if no PIN is set or the stored hash matches. */
    fun verify(rawPin: String): Boolean =
        verifyResult(rawPin) == PinVerificationResult.ACCEPTED

    /**
     * Clear the PIN after verifying the current one.
     * Returns false if verification fails (caller should show an error).
     */
    fun clearResult(rawPin: String): PinVerificationResult =
        when (val result = verifyResult(rawPin)) {
            PinVerificationResult.ACCEPTED -> {
                Database.writeSetting(KEY, "").requireSuccess("clear Nuclear Mode PIN")
                PinVerificationResult.ACCEPTED
            }
            else -> result
        }

    fun clear(rawPin: String): Boolean =
        clearResult(rawPin) == PinVerificationResult.ACCEPTED

    /** Clear the PIN unconditionally (emergency recovery path only). */
    fun clearForced() {
        Database.writeSetting(KEY, "").requireSuccess("clear Nuclear Mode PIN")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
