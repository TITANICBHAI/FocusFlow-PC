package com.focusflow.services

object SessionPinInput {
    fun normalize(raw: String): String = buildString(raw.length) {
        raw.forEach { character ->
            if (!character.isWhitespace() && character != '-') {
                append(character)
            }
        }
    }

    fun matches(raw: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false
        val normalized = normalize(raw)
        if (normalized.isBlank()) return false
        return sha256(normalized) == storedHash
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
