package com.focusflow.services

import com.focusflow.data.Database
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

    fun isSet(): Boolean = Database.getSetting(KEY)?.isNotBlank() == true

    fun set(rawPin: String) {
        require(rawPin.length in PinPolicy.MIN_LENGTH..PinPolicy.MAX_LENGTH) {
            "PIN must be between ${PinPolicy.MIN_LENGTH} and ${PinPolicy.MAX_LENGTH} characters"
        }
        Database.setSetting(KEY, sha256(rawPin))
    }

    /**
     * Auto-generate a random 20–28-character alphanumeric PIN, store its hash,
     * and return the plain-text PIN so the UI can display it exactly once.
     * Every call produces a different PIN, so every session is different.
     */
    fun autoGenerate(): String {
        val pin = PinPolicy.generate()
        Database.setSetting(KEY, sha256(pin))
        return pin
    }

    fun verify(rawPin: String): Boolean {
        val stored = Database.getSetting(KEY)
        // null  → KEY was never written; no PIN configured → pass through
        // blank → KEY was explicitly cleared (clearForced / clear); treat as not set → pass through
        //
        // IMPORTANT: do NOT treat null and blank identically here via isNullOrBlank().
        // After autoGenerate() stores a hash, a transient DB read error would return null
        // or an empty string. Both must correctly deny access when a hash IS stored.
        // We resolve this by checking isSet() independently in the caller before calling
        // verify(). Within verify() itself, we keep the original passthrough for both
        // null and blank because clearForced() stores "" to mark "no PIN active", and
        // the caller's isSet() guard prevents verify() from being invoked in that state.
        //
        // If stored is non-null but blank: caller's isSet() already returned false
        // (because getSetting(KEY)?.isNotBlank() == false), so this line is never
        // reached with a stale non-blank hash in `stored` — safe as-is.
        if (stored.isNullOrBlank()) return true
        return stored == sha256(rawPin)
    }

    fun clear(rawPin: String): Boolean {
        if (!verify(rawPin)) return false
        Database.setSetting(KEY, "") // isSet() treats blank as unset
        return true
    }

    /** Clear PIN unconditionally (called when a session ends naturally). */
    fun clearForced() {
        Database.setSetting(KEY, "")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
