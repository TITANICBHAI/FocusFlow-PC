package com.focusflow.services

import com.focusflow.AppPaths
import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.testutil.TempHome
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupRecoveryServiceTest {
    @AfterTest
    fun resetServices() {
        AutoBackupService.resetForTest()
    }

    @Test
    fun secondaryBackupIsVerifiedAndManualDeleteLeavesUnrelatedFilesAlone() =
        TempHome.withHome { home ->
            assertEquals(DbInitResult.Ready, Database.init())
            val secondary = File(home.toFile(), "secondary-backups").apply { mkdirs() }
            assertIs<SecondaryLocationUpdate.Saved>(
                BackupRecoveryService.configureSecondaryBackupDirectory(secondary)
            )
            val unrelated = File(secondary, "keep.txt").apply { writeText("leave me") }

            val backup = assertIs<AutoBackupService.BackupResult.Success>(
                AutoBackupService.runBackupNow()
            )
            val secondaryCopy = assertIs<SecondaryCopyStatus.Copied>(backup.secondaryCopy)
            assertIs<AutoBackupService.BackupVerification.Verified>(
                AutoBackupService.verifyBackup(secondaryCopy.file)
            )
            assertEquals(2, BackupRecoveryService.listBackupEntries().size)

            assertEquals(
                SecondaryBackupDeleteResult.Deleted,
                BackupRecoveryService.deleteSecondaryBackup(secondaryCopy.file)
            )
            assertTrue(unrelated.isFile)
            assertTrue(backup.file.isFile)
            assertEquals(1, BackupRecoveryService.listBackupEntries().size)
        }

    @Test
    fun restoreUsesVerifiedSafetyBackupAndRestoresTheSelectedSnapshotOnStartup() =
        TempHome.withHome {
            assertEquals(DbInitResult.Ready, Database.init())
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "snapshot"))
            val selected = assertIs<AutoBackupService.BackupResult.Success>(
                AutoBackupService.runBackupNow()
            )
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "newer"))

            assertIs<RestoreScheduleResult.Scheduled>(
                BackupRecoveryService.scheduleRestore(selected.file)
            )
            assertTrue(BackupRecoveryService.hasPendingRestore())

            assertEquals(
                DbInitResult.Ready,
                BackupRecoveryService.initializeDatabaseForStartup()
            )
            assertEquals(
                Database.SettingRead.Present("snapshot"),
                Database.readSetting("restore_test_value")
            )
            assertFalse(BackupRecoveryService.hasPendingRestore())

            val safetyBackups = AutoBackupService.listBackups()
                .filter { AutoBackupService.verifyBackup(it) is AutoBackupService.BackupVerification.Verified }
            assertTrue(safetyBackups.size >= 2, "The selected backup and current-state safety backup must remain")
            val archives = AppPaths.restoreArchiveDir.listFiles().orEmpty()
            assertTrue(archives.any { it.isDirectory && it.name.startsWith("pre-restore-") })

            val notice = assertNotNull(RecoveryNoticeService.pendingNotice())
            assertEquals("Backup restored", notice.title)
        }

    @Test
    fun tamperedStagedBackupIsCancelledWithoutChangingTheCurrentDatabase() =
        TempHome.withHome {
            assertEquals(DbInitResult.Ready, Database.init())
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "current"))
            val backup = assertIs<AutoBackupService.BackupResult.Success>(
                AutoBackupService.runBackupNow()
            )
            assertIs<RestoreScheduleResult.Scheduled>(
                BackupRecoveryService.scheduleRestore(backup.file)
            )
            File(AppPaths.restoreStagingDir, "candidate.db").writeText("tampered")

            assertEquals(
                DbInitResult.Ready,
                BackupRecoveryService.initializeDatabaseForStartup()
            )
            assertEquals(
                Database.SettingRead.Present("current"),
                Database.readSetting("restore_test_value")
            )
            assertFalse(BackupRecoveryService.hasPendingRestore())
            assertEquals("Restore not applied", RecoveryNoticeService.pendingNotice()?.title)
        }

    @Test
    fun databaseInitializationFailureRollsBackToThePreRestoreDatabase() =
        TempHome.withHome {
            assertEquals(DbInitResult.Ready, Database.init())
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "current"))
            val validBackup = assertIs<AutoBackupService.BackupResult.Success>(
                AutoBackupService.runBackupNow()
            )
            val incompatibleBackup = File(AppPaths.backupDir, "focusflow_incompatible.db")
            Files.copy(validBackup.file.toPath(), incompatibleBackup.toPath())
            DriverManager.getConnection("jdbc:sqlite:${incompatibleBackup.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE tasks RENAME COLUMN skipped TO skipped_legacy")
                    statement.execute("PRAGMA user_version = 9")
                }
            }
            File(AppPaths.backupDir, "focusflow_incompatible.sha256")
                .writeText(sha256(incompatibleBackup))

            assertIs<RestoreScheduleResult.Scheduled>(
                BackupRecoveryService.scheduleRestore(incompatibleBackup)
            )
            assertEquals(
                DbInitResult.Ready,
                BackupRecoveryService.initializeDatabaseForStartup()
            )
            assertEquals(
                Database.SettingRead.Present("current"),
                Database.readSetting("restore_test_value")
            )
            assertTrue(incompatibleBackup.isFile, "The selected source backup must remain available")
            assertEquals("Restore rolled back", RecoveryNoticeService.pendingNotice()?.title)
        }

    @Test
    fun restoreSafetyBackupRetentionKeepsTheSelectedPrimarySource() = TempHome.withHome {
        assertEquals(DbInitResult.Ready, Database.init())
        val selected = assertIs<AutoBackupService.BackupResult.Success>(
            AutoBackupService.runBackupNow()
        )
        repeat(7) { index ->
            File(AppPaths.backupDir, "focusflow_test_newer_$index.db")
                .apply {
                    writeText("retention fixture")
                    setLastModified(selected.file.lastModified() + (index + 1) * 1_000L)
                }
        }

        assertIs<AutoBackupService.BackupResult.Success>(
            AutoBackupService.runBackupNowPreserving(selected.file)
        )

        assertTrue(selected.file.isFile, "The selected restore source must be protected from pruning")
        assertTrue(AutoBackupService.listBackups().size <= 7)
    }

    @Test
    fun recoveryNoticeIsAcknowledgedPerPreservedDatabaseArchive() {
        TempHome.withHome { home ->
            val first = File(home.toFile(), ".focusflow/focusflow.db.broken_first")
                .apply { parentFile.mkdirs(); writeText("preserved") }

            val notice = assertNotNull(RecoveryNoticeService.pendingNotice())
            assertEquals(first, notice.archiveFile)
            RecoveryNoticeService.acknowledge(notice)
            assertEquals(null, RecoveryNoticeService.pendingNotice())

            File(home.toFile(), ".focusflow/focusflow.db.broken_second").writeText("another preserved database")
            assertNotNull(RecoveryNoticeService.pendingNotice())
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
