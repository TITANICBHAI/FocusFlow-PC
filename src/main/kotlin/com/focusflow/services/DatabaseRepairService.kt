package com.focusflow.services

import com.focusflow.data.Database
import java.io.File

/**
 * Safe entry point for one-time database repairs.
 *
 * Dry-runs are read-only and do not create a backup. Every applied repair must
 * first receive a verified snapshot from [AutoBackupService]; a failed or
 * unverified backup prevents the database mutation.
 */
internal object DatabaseRepairService {

    sealed interface Outcome<out T> {
        data class DryRun<T>(val value: T) : Outcome<T>
        data class Applied<T>(val value: T, val backupFile: File) : Outcome<T>
        data class BackupFailed(val result: AutoBackupService.BackupResult) : Outcome<Nothing>
    }

    fun repairRecurringTaskParents(dryRun: Boolean = true): Outcome<Database.RecurringTaskRepairResult> =
        execute(
            dryRun = dryRun,
            preview = { Database.repairRecurringTaskParents(dryRun = true) },
            apply = { Database.repairRecurringTaskParents(dryRun = false) }
        )

    fun reconcileOrphanFocusSessions(dryRun: Boolean = true): Outcome<Int> =
        execute(
            dryRun = dryRun,
            preview = { Database.reconcileOrphanFocusSessions(dryRun = true) },
            apply = { Database.reconcileOrphanFocusSessions(dryRun = false) }
        )

    private fun <T> execute(
        dryRun: Boolean,
        preview: () -> T,
        apply: () -> T
    ): Outcome<T> {
        if (dryRun) return Outcome.DryRun(preview())

        val backup = AutoBackupService.runBackupNow()
        if (backup !is AutoBackupService.BackupResult.Success) {
            return Outcome.BackupFailed(backup)
        }

        return Outcome.Applied(
            value = apply(),
            backupFile = backup.file
        )
    }
}
