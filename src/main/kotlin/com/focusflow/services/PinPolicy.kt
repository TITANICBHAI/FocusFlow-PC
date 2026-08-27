package com.focusflow.services

import java.security.SecureRandom

/**
 * Shared rules for user-configured PINs.
 *
 * Generated PINs intentionally use a larger range than the minimum custom
 * PIN length so they are long enough to be difficult to guess while still
 * fitting comfortably in the setup dialogs.
 */
object PinPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 28
    private const val GENERATED_MIN_LENGTH = 20
    private const val GENERATED_MAX_LENGTH = 28

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun generate(): String {
        val length = GENERATED_MIN_LENGTH +
            random.nextInt(GENERATED_MAX_LENGTH - GENERATED_MIN_LENGTH + 1)
        return buildString(length) {
            repeat(length) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }
    }
}