package com.focusflow.services

import com.focusflow.data.Database
import java.security.MessageDigest

/**
 * GlobalPin
 *
 * Persistent, always-active PIN gate (8–28 characters).
 * Unlike SessionPin (which is session-scoped), this PIN is permanent and
 * required to REMOVE or DISABLE anything in FocusFlow.
 * Adding is always free; removing always costs the PIN.
 *
 * SHA-256 hashed. Plain text is never stored.
 */
object GlobalPin {

    private const val KEY          = "global_pin_hash"
    private const val DECLINED_KEY = "global_pin_skipped"

    fun isSet(): Boolean     = Database.getSetting(KEY)?.isNotBlank() == true
    fun isDeclined(): Boolean = Database.getSetting(DECLINED_KEY) == "true"
    fun setDeclined()         { Database.setSetting(DECLINED_KEY, "true") }

    fun set(rawPin: String) {
        require(rawPin.length in PinPolicy.MIN_LENGTH..PinPolicy.MAX_LENGTH) {
            "PIN must be between ${PinPolicy.MIN_LENGTH} and ${PinPolicy.MAX_LENGTH} characters"
        }
        Database.setSetting(KEY, sha256(rawPin))
    }

    /**
     * Auto-generate a random 20–28-character alphanumeric PIN, store its hash,
     * and return the plain-text once so the UI can display it.
     */
    fun autoGenerate(): String {
        val pin = PinPolicy.generate()
        Database.setSetting(KEY, sha256(pin))
        return pin
    }

    /** Returns true if PIN is correct OR if no PIN is set (unguarded). */
    fun verify(rawPin: String): Boolean {
        val stored = Database.getSetting(KEY)
        if (stored.isNullOrBlank()) return true
        return stored == sha256(rawPin)
    }

    fun clear(rawPin: String): Boolean {
        if (!verify(rawPin)) return false
        Database.setSetting(KEY, "")
        return true
    }

    /**
     * Emergency recovery reset — clears the PIN hash without requiring the old PIN.
     * Intended for the "Forgot PIN" flow where the user confirms with a typed phrase.
     * Also resets the "declined" flag so the setup dialog will be offered again.
     */
    fun resetWithoutPin() {
        Database.setSetting(KEY, "")
        Database.setSetting(DECLINED_KEY, "false")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
