package com.focusflow.services

import com.focusflow.data.Database
import kotlinx.coroutines.CancellationException
import java.io.File

internal object ClearUserActivityService {

    sealed interface Result {
        data class Cleared(val backupFile: File) : Result
        data class BackupFailed(val reason: String) : Result
        data class ClearFailed(val backupFile: File, val reason: String) : Result
    }

    fun clearAfterVerifiedBackup(
        backup: () -> AutoBackupService.BackupResult = AutoBackupService::runBackupNow
    ): Result = when (val result = backup()) {
        is AutoBackupService.BackupResult.Success -> {
            try {
                Database.clearUserActivity()
                Result.Cleared(result.file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.ClearFailed(
                    backupFile = result.file,
                    reason = failure.message ?: "The database clear operation failed."
                )
            }
        }
        AutoBackupService.BackupResult.NoDatabase ->
            Result.BackupFailed("The database file was unavailable.")
        AutoBackupService.BackupResult.VerificationFailed ->
            Result.BackupFailed("The backup failed its integrity verification.")
        is AutoBackupService.BackupResult.Error ->
            Result.BackupFailed(result.reason)
    }
}
