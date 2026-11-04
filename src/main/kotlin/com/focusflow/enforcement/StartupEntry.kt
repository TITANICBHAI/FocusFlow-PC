package com.focusflow.enforcement

/**
 * What Windows will actually do with FocusFlow at login.
 *
 * Windows keeps "is this startup entry allowed to run" apart from the entry itself:
 * Task Manager's Disable button writes a flag under
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run`
 * and leaves the `Run` value in place. Writing the `Run` value again does not undo it.
 */
enum class StartupState {
    /** No `Run` entry. */
    Off,

    /** `Run` entry present and Windows allows it to run. */
    On,

    /** `Run` entry present but the user turned it off in Task Manager or Windows Settings. */
    DisabledInWindows
}

enum class StartupEnableResult {
    /** Written and confirmed by reading it back. */
    Enabled,

    /** Written, but Windows still has the entry switched off and we could not clear that. */
    DisabledByWindows,

    /** No usable launch command (the executable could not be located). Nothing was written. */
    NoLaunchCommand,

    /** The registry write failed. */
    WriteFailed,

    /** Not Windows. */
    NotSupported
}

/** The registry operations [StartupEntry] needs; JNA-backed in production, faked in tests. */
internal interface StartupRegistry {
    fun runValueExists(): Boolean

    /** Throws when the value cannot be written. */
    fun writeRunValue(command: String)

    /** Throws when an existing value cannot be removed. No-op when absent. */
    fun deleteRunValue()

    /** The raw `StartupApproved\Run` value, or null when there is none. */
    fun approvalFlag(): ByteArray?

    /** Throws when an existing flag cannot be removed. No-op when absent. */
    fun clearApprovalFlag()
}

/**
 * `StartupApproved` values are 12 bytes. The first byte is even (02, 06) for an
 * enabled entry and odd (03) for one the user disabled; the rest is a timestamp.
 * Observed Windows behaviour rather than a documented API, so anything unexpected
 * is treated as enabled: we would rather show ON than wrongly claim it is blocked.
 */
internal fun isApprovalFlagEnabled(flag: ByteArray?): Boolean =
    flag == null || flag.isEmpty() || (flag[0].toInt() and 1) == 0

internal class StartupEntry(private val registry: StartupRegistry) {

    fun state(): StartupState {
        val present = runCatching { registry.runValueExists() }.getOrDefault(false)
        if (!present) return StartupState.Off
        val flag = runCatching { registry.approvalFlag() }.getOrNull()
        return if (isApprovalFlagEnabled(flag)) StartupState.On else StartupState.DisabledInWindows
    }

    /**
     * Only call this because the user asked for autostart (toggle, onboarding or the
     * startup prompt). It clears Windows' "disabled" flag, which the user may have set
     * on purpose, so it must never run silently.
     *
     * The outcome is read back rather than assumed.
     */
    fun enable(command: String?): StartupEnableResult {
        if (command.isNullOrBlank()) return StartupEnableResult.NoLaunchCommand
        try {
            registry.writeRunValue(command)
        } catch (_: Exception) {
            return StartupEnableResult.WriteFailed
        }
        runCatching { registry.clearApprovalFlag() }
        return when (state()) {
            StartupState.On -> StartupEnableResult.Enabled
            StartupState.DisabledInWindows -> StartupEnableResult.DisabledByWindows
            StartupState.Off -> StartupEnableResult.WriteFailed
        }
    }

    /** Returns true when the entry is really gone afterwards. */
    fun disable(): Boolean {
        runCatching { registry.deleteRunValue() }
        return state() == StartupState.Off
    }
}
