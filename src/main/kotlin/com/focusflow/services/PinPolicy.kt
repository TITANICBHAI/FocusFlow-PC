package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingWriteException
import com.focusflow.data.requireSuccess
import java.security.SecureRandom

enum class PinState {
    NOT_SET,
    SET,
    UNKNOWN
}

enum class PinVerificationResult {
    ACCEPTED,
    INCORRECT,
    UNAVAILABLE
}

/**
 * Shared rules for user-configured PINs.
 *
 * DESIGN PRINCIPLE — strengthening is easy, loosening is hard (full text: Security/pin-policy.md).
 * The Global PIN guards only actions that WEAKEN protection: turning something OFF, removing,
 * pausing, shortening, loosening. Actions that ADD or STRENGTHEN protection (add a keyword / VPN
 * process / network rule, turn something ON) must stay pinless. Do not add a PIN to a
 * strengthening action, and do not remove or soften one from a weakening action. If an "add" could
 * end up loosening something (overwrite a stricter rule, shorten a duration), treat it as
 * weakening. Never add a reset/bypass path. PinOnlyForWeakeningSourceTest guards this; changing the
 * policy is an owner decision, not an agent decision.
 *

 * Generated PINs intentionally use a larger range than the minimum custom
 * PIN length so they are long enough to be difficult to guess while still
 * fitting comfortably in the setup dialogs.
 */
object PinPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 28
    const val SETTINGS_READ_ERROR = "Couldn't read PIN settings — try again."
    const val SETTINGS_WRITE_ERROR = "Couldn't save PIN settings — try again."
    private const val GENERATED_MIN_LENGTH = 20
    private const val GENERATED_MAX_LENGTH = 28

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun errorMessage(
        result: PinVerificationResult,
        incorrectPinMessage: String
    ): String? = when (result) {
        PinVerificationResult.ACCEPTED -> null
        PinVerificationResult.INCORRECT -> incorrectPinMessage
        PinVerificationResult.UNAVAILABLE -> SETTINGS_READ_ERROR
    }

    internal fun stateOf(read: Database.SettingRead): PinState = when (read) {
        is Database.SettingRead.Present ->
            if (read.value.isBlank()) PinState.NOT_SET else PinState.SET
        Database.SettingRead.Absent -> PinState.NOT_SET
        is Database.SettingRead.Unreadable -> PinState.UNKNOWN
    }

    internal fun verify(
        read: Database.SettingRead,
        rawPin: String,
        hash: (String) -> String
    ): PinVerificationResult = when (read) {
        is Database.SettingRead.Present -> when {
            read.value.isBlank() -> PinVerificationResult.ACCEPTED
            read.value == hash(rawPin) -> PinVerificationResult.ACCEPTED
            else -> PinVerificationResult.INCORRECT
        }
        Database.SettingRead.Absent -> PinVerificationResult.ACCEPTED
        is Database.SettingRead.Unreadable -> PinVerificationResult.UNAVAILABLE
    }

    internal fun writeGeneratedHashAndVerifyReadBack(
        key: String,
        hash: String,
        operation: String
    ) {
        Database.writeSetting(key, hash).requireSuccess(operation)

        when (val readBack = Database.readSetting(key)) {
            is Database.SettingRead.Present -> {
                if (readBack.value != hash) {
                    throw SettingWriteException(
                        operation,
                        IllegalStateException("PIN hash read-back did not match the stored value")
                    )
                }
            }
            Database.SettingRead.Absent -> throw SettingWriteException(
                operation,
                IllegalStateException("PIN hash was absent on read-back")
            )
            is Database.SettingRead.Unreadable ->
                throw SettingWriteException(operation, readBack.cause)
        }
    }

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