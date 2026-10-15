package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.testutil.TempHome
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AutoBackupServiceTest {

    @AfterTest
    fun resetService() {
        AutoBackupService.resetForTest()
    }

    @Test
    fun f15a_validSnapshotVerifiesAndInvalidSnapshotIsDeleted() = TempHome.withHome {
        assertEquals(DbInitResult.Ready, Database.init())

        val valid = assertIs<AutoBackupService.BackupResult.Success>(
            AutoBackupService.runBackupNow()
        )
        assertIs<AutoBackupService.BackupVerification.Verified>(
            AutoBackupService.verifyBackup(valid.file)
        )

        AutoBackupService.snapshotWriterForTest = { destination ->
            destination.writeText("not a SQLite database")
        }
        assertIs<AutoBackupService.BackupResult.VerificationFailed>(
            AutoBackupService.runBackupNow()
        )

        assertEquals(listOf(valid.file), AutoBackupService.listBackups())
        assertTrue(valid.file.exists())
    }

    @Test
    fun f15b_backupWithoutHashIsUnverified() = TempHome.withHome { home ->
        val backup = File(
            home.toFile(),
            ".focusflow/backups/focusflow_legacy.db"
        ).apply {
            parentFile.mkdirs()
            writeText("legacy backup")
        }

        assertEquals(
            AutoBackupService.BackupVerification.Unverified,
            AutoBackupService.verifyBackup(backup)
        )
    }

    @Test
    fun f15c_failureIsLoggedAndPersisted() = TempHome.withHome {
        assertEquals(DbInitResult.Ready, Database.init())
        val warnings = mutableListOf<String>()
        AutoBackupService.warningSinkForTest = { message, _ -> warnings += message }
        AutoBackupService.snapshotWriterForTest = {
            throw IllegalStateException("injected snapshot failure")
        }

        assertIs<AutoBackupService.BackupResult.Error>(
            AutoBackupService.runBackupNow()
        )

        assertTrue(warnings.any { it.contains("Backup did not complete (error)") })
        assertEquals(
            Database.SettingRead.Present("error"),
            Database.readSetting("last_backup_status")
        )
    }

    @Test
    fun f15d_sameSecondBackupsHaveDistinctVerifiedSidecars() = TempHome.withHome {
        assertEquals(DbInitResult.Ready, Database.init())

        val first = assertIs<AutoBackupService.BackupResult.Success>(
            AutoBackupService.runBackupNow()
        )
        val second = assertIs<AutoBackupService.BackupResult.Success>(
            AutoBackupService.runBackupNow()
        )

        assertNotEquals(first.file, second.file)
        for (backup in listOf(first.file, second.file)) {
            val hashSidecar = File(
                backup.parentFile,
                "${backup.nameWithoutExtension}.sha256"
            )
            val metaSidecar = File(
                backup.parentFile,
                "${backup.nameWithoutExtension}.meta.json"
            )
            assertTrue(hashSidecar.isFile)
            assertTrue(metaSidecar.isFile)
            assertIs<AutoBackupService.BackupVerification.Verified>(
                AutoBackupService.verifyBackup(backup)
            )
        }

        assertEquals(2, AutoBackupService.listBackups().size)
        assertEquals(6, first.file.parentFile.listFiles()?.size)
    }
}
