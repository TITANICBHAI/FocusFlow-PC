package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.EnforcementLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted, opt-in allowance for processes launched by Focus Launcher apps.
 * Reads and writes are typed so an unreadable setting is never mistaken for
 * an explicit "off" value.
 */
object LauncherChainAllowance {
    private const val ENABLED_KEY = "launcher_chain_allowance_enabled"
    private const val EVER_ENABLED_KEY = "launcher_chain_allowance_ever_enabled"

    data class State(
        val enabled: Boolean = false,
        val everEnabled: Boolean = false,
        val readable: Boolean = true
    )

    enum class EnableResult {
        ENABLED,
        PIN_NOT_CONFIGURED,
        PIN_UNAVAILABLE,
        INCORRECT_PIN,
        STORAGE_UNAVAILABLE
    }

    enum class EntryResult {
        PIN_ACCEPTED,
        STRICT_ALLOW_LIST,
        INCORRECT_PIN,
        UNAVAILABLE
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    val isEnabled: Boolean
        get() = mutableState.value.let { it.readable && it.enabled }

    @Synchronized
    fun refresh(): State = refreshLocked()

    /**
     * Called only after the user submits the Global PIN in the Settings
     * enable flow. Verification and the atomic enabled/ever-enabled write
     * happen together here.
     */
    @Synchronized
    fun enable(rawPin: String): EnableResult {
        val current = refreshLocked()
        if (!current.readable) return EnableResult.STORAGE_UNAVAILABLE

        when (GlobalPin.state()) {
            PinState.NOT_SET -> return EnableResult.PIN_NOT_CONFIGURED
            PinState.UNKNOWN -> return EnableResult.PIN_UNAVAILABLE
            PinState.SET -> Unit
        }

        when (GlobalPin.verifyConfiguredResult(rawPin)) {
            PinVerificationResult.ACCEPTED -> Unit
            PinVerificationResult.INCORRECT -> return EnableResult.INCORRECT_PIN
            PinVerificationResult.UNAVAILABLE -> return EnableResult.PIN_UNAVAILABLE
        }

        return when (
            val write = Database.writeSettings(
                mapOf(ENABLED_KEY to "true", EVER_ENABLED_KEY to "true")
            )
        ) {
            Database.SettingWrite.Ok -> {
                mutableState.value = State(enabled = true, everEnabled = true, readable = true)
                EnableResult.ENABLED
            }
            is Database.SettingWrite.Failed -> {
                mutableState.value = current.copy(enabled = false, readable = false)
                EnforcementLog.warn(
                    "LauncherChainAllowance",
                    "Could not save launcher chain allowance",
                    write.cause
                )
                EnableResult.STORAGE_UNAVAILABLE
            }
        }
    }

    /** Turning the optional allowance off never requires a PIN. */
    @Synchronized
    fun disable(): Boolean {
        val previous = mutableState.value
        val everEnabled = previous.everEnabled || readEverEnabled() == true
        return when (val write = Database.writeSetting(ENABLED_KEY, "false")) {
            Database.SettingWrite.Ok -> {
                mutableState.value = State(enabled = false, everEnabled = everEnabled, readable = true)
                true
            }
            is Database.SettingWrite.Failed -> {
                mutableState.value = State(enabled = false, everEnabled = everEnabled, readable = false)
                EnforcementLog.warn(
                    "LauncherChainAllowance",
                    "Could not disable launcher chain allowance in storage",
                    write.cause
                )
                false
            }
        }
    }

    /**
     * Re-checks both the opt-in and Global PIN on every Enter Launcher click.
     * If the setting was disabled after the screen opened, the caller may
     * continue under the strict allow-list instead of retaining stale state.
     */
    @Synchronized
    fun verifyEntryPin(rawPin: String): EntryResult {
        val current = refreshLocked()
        if (!current.readable) return EntryResult.UNAVAILABLE
        if (!current.enabled) return EntryResult.STRICT_ALLOW_LIST

        when (GlobalPin.state()) {
            PinState.NOT_SET -> {
                val disabled = disable()
                return if (disabled) EntryResult.STRICT_ALLOW_LIST else EntryResult.UNAVAILABLE
            }
            PinState.UNKNOWN -> return EntryResult.UNAVAILABLE
            PinState.SET -> Unit
        }

        when (GlobalPin.verifyConfiguredResult(rawPin)) {
            PinVerificationResult.INCORRECT -> return EntryResult.INCORRECT_PIN
            PinVerificationResult.UNAVAILABLE -> return EntryResult.UNAVAILABLE
            PinVerificationResult.ACCEPTED -> Unit
        }

        val latest = refreshLocked()
        return when {
            !latest.readable -> EntryResult.UNAVAILABLE
            latest.enabled -> EntryResult.PIN_ACCEPTED
            else -> EntryResult.STRICT_ALLOW_LIST
        }
    }

    /**
     * Global PIN removal invalidates this opt-in. Keep the historical marker
     * so the Focus Launcher screen continues showing its live off status.
     */
    @Synchronized
    fun onGlobalPinRemoved() {
        disable()
    }

    @Synchronized
    private fun refreshLocked(): State {
        val enabled = readBoolean(ENABLED_KEY)
        val everEnabled = readBoolean(EVER_ENABLED_KEY)
        if (enabled == null || everEnabled == null) {
            return State(
                enabled = false,
                everEnabled = mutableState.value.everEnabled,
                readable = false
            ).also { mutableState.value = it }
        }

        if (!enabled) {
            return State(enabled = false, everEnabled = everEnabled, readable = true)
                .also { mutableState.value = it }
        }

        // The two flags are written atomically. An enabled flag without the
        // historical marker is inconsistent, so repair it to the safe state.
        if (!everEnabled) {
            val write = Database.writeSetting(ENABLED_KEY, "false")
            val readable = write == Database.SettingWrite.Ok
            if (!readable && write is Database.SettingWrite.Failed) {
                EnforcementLog.warn(
                    "LauncherChainAllowance",
                    "Could not disable inconsistent launcher chain allowance",
                    write.cause
                )
            }
            return State(enabled = false, everEnabled = false, readable = readable)
                .also { mutableState.value = it }
        }

        when (GlobalPin.state()) {
            PinState.SET -> State(enabled = true, everEnabled = true, readable = true)
                .also { mutableState.value = it }
            PinState.NOT_SET -> {
                val write = Database.writeSetting(ENABLED_KEY, "false")
                val readable = write == Database.SettingWrite.Ok
                if (!readable && write is Database.SettingWrite.Failed) {
                    EnforcementLog.warn(
                        "LauncherChainAllowance",
                        "Could not disable launcher chain allowance after Global PIN removal",
                        write.cause
                    )
                }
                State(enabled = false, everEnabled = true, readable = readable)
                    .also { mutableState.value = it }
            }
            PinState.UNKNOWN -> State(enabled = false, everEnabled = true, readable = false)
                .also { mutableState.value = it }
        }
        return mutableState.value
    }

    private fun readBoolean(key: String): Boolean? = when (val read = Database.readSetting(key)) {
        Database.SettingRead.Absent -> false
        is Database.SettingRead.Present -> when (read.value) {
            "true" -> true
            "false" -> false
            else -> null
        }
        is Database.SettingRead.Unreadable -> null
    }

    private fun readEverEnabled(): Boolean? = readBoolean(EVER_ENABLED_KEY)

    internal fun resetForTest() {
        mutableState.value = State()
    }
}
