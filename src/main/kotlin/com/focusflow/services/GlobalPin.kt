package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.requireSuccess
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

    fun isSet(): Boolean = when (val stored = Database.readSetting(KEY)) {
        is Database.SettingRead.Present -> stored.value.isNotBlank()
        Database.SettingRead.Absent -> false
        is Database.SettingRead.Unreadable -> true
    }

    fun isDeclined(): Boolean = when (val stored = Database.readSetting(DECLINED_KEY)) {
        is Database.SettingRead.Present -> stored.value == "true"
        Database.SettingRead.Absent,
        is Database.SettingRead.Unreadable -> false
    }

    fun setDeclined() {
        Database.writeSetting(DECLINED_KEY, "true").requireSuccess("decline Global PIN setup")
    }

    fun set(rawPin: String) {
        require(rawPin.length in PinPolicy.MIN_LENGTH..PinPolicy.MAX_LENGTH) {
            "PIN must be between ${PinPolicy.MIN_LENGTH} and ${PinPolicy.MAX_LENGTH} characters"
        }
        Database.writeSetting(KEY, sha256(rawPin)).requireSuccess("set Global PIN")
    }

    /**
     * Auto-generate a random 20–28-character alphanumeric PIN, store its hash,
     * and return the plain-text once so the UI can display it.
     */
    fun autoGenerate(): String {
        val pin = PinPolicy.generate()
        Database.writeSetting(KEY, sha256(pin)).requireSuccess("generate Global PIN")
        return pin
    }

    /** Returns true if PIN is correct OR if no PIN is set (unguarded). */
    fun verify(rawPin: String): Boolean {
        return when (val stored = Database.readSetting(KEY)) {
            is Database.SettingRead.Present ->
                stored.value.isBlank() || stored.value == sha256(rawPin)
            Database.SettingRead.Absent -> true
            is Database.SettingRead.Unreadable -> false
        }
    }

    fun clear(rawPin: String): Boolean {
        if (!verify(rawPin)) return false
        Database.writeSetting(KEY, "").requireSuccess("clear Global PIN")
        return true
    }

    /**
     * Emergency recovery reset — clears the PIN hash without requiring the old PIN.
     * Intended for the "Forgot PIN" flow where the user confirms with a typed phrase.
     * Also resets the "declined" flag so the setup dialog will be offered again.
     */
    fun resetWithoutPin() {
        val pinWrite = Database.writeSetting(KEY, "")
        val declinedWrite = Database.writeSetting(DECLINED_KEY, "false")
        pinWrite.requireSuccess("reset Global PIN")
        declinedWrite.requireSuccess("reset Global PIN setup choice")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
