package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import com.focusflow.data.normalizeProcessKey

sealed interface AllowanceEditChange {
    data object Add : AllowanceEditChange
    data object Tighten : AllowanceEditChange
    data object Loosen : AllowanceEditChange
    data object Unchanged : AllowanceEditChange
    data object Delete : AllowanceEditChange
}

/** Pure policy for changes to one process' daily allowance. */
object AllowanceEditPolicy {
    fun classify(old: DailyAllowance?, new: DailyAllowance?): AllowanceEditChange {
        if (old == null) return if (new == null) AllowanceEditChange.Unchanged else AllowanceEditChange.Add
        if (new == null) return AllowanceEditChange.Delete

        if (normalizeProcessKey(old.processName) != normalizeProcessKey(new.processName)) {
            return AllowanceEditChange.Add
        }
        return when {
            new.allowanceMinutes > old.allowanceMinutes -> AllowanceEditChange.Loosen
            new.allowanceMinutes < old.allowanceMinutes -> AllowanceEditChange.Tighten
            else -> AllowanceEditChange.Unchanged
        }
    }

    fun requiresPin(change: AllowanceEditChange): Boolean =
        change == AllowanceEditChange.Loosen || change == AllowanceEditChange.Delete

    fun shouldPromptForPin(
        old: DailyAllowance?,
        new: DailyAllowance?,
        globalPinSet: Boolean
    ): Boolean = globalPinSet && requiresPin(classify(old, new))

    fun canMutate(databaseReady: Boolean, pinStateLoaded: Boolean, mutationInProgress: Boolean): Boolean =
        databaseReady && pinStateLoaded && !mutationInProgress
}

/** Normalizes typed process names and applies the Windows executable suffix rule. */
fun normalizeManualProcessName(raw: String, isWindows: Boolean): String {
    val normalized = normalizeProcessKey(raw)
    return if (isWindows && normalized.isNotEmpty() && !normalized.endsWith(".exe")) {
        "$normalized.exe"
    } else {
        normalized
    }
}
