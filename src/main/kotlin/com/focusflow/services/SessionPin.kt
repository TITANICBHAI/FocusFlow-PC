package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.requireSuccess
import java.security.MessageDigest

/**
 * SessionPin
 *
 * SHA-256 PIN gate (8–28 characters) that must be satisfied before ending a session.
 * Every focus-mode session auto-generates a new PIN via autoGenerate().
 * The plain-text PIN is returned once (so the UI can show it) and never
 * stored — only the SHA-256 hash is persisted.
 */
object SessionPin {

    private const val KEY = "session_pin_hash"

    fun isSet(): Boolean = when (val stored = Database.readSetting(KEY)) {
        is Database.SettingRead.Present -> stored.value.isNotBlank()
        Database.SettingRead.Absent -> false
        is Database.SettingRead.Unreadable -> true
    }

    fun set(rawPin: String) {
        require(rawPin.length in PinPolicy.MIN_LENGTH..PinPolicy.MAX_LENGTH) {
            "PIN must be between ${PinPolicy.MIN_LENGTH} and ${PinPolicy.MAX_LENGTH} characters"
        }
        Database.writeSetting(KEY, sha256(rawPin)).requireSuccess("set Session PIN")
    }

    /**
     * Auto-generate a random 20–28-character alphanumeric PIN, store its hash,
     * and return the plain-text PIN so the UI can display it exactly once.
     * Every call produces a different PIN, so every session is different.
     */
    fun autoGenerate(): String {
        val pin = PinPolicy.generate()
        Database.writeSetting(KEY, sha256(pin)).requireSuccess("generate Session PIN")
        return pin
    }

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
        Database.writeSetting(KEY, "").requireSuccess("clear Session PIN")
        return true
    }

    /** Clear PIN unconditionally (called when a session ends naturally). */
    fun clearForced() {
        Database.writeSetting(KEY, "").requireSuccess("clear Session PIN")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
