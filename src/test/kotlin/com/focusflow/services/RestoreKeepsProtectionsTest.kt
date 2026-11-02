package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.testutil.TempHome
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RestoreKeepsProtectionsTest {
    @AfterTest
    fun resetServices() {
        AutoBackupService.resetForTest()
    }

    @Test
    fun restoringAnOlderBackupKeepsTheProtectionsYouHaveNow() =
        TempHome.withHome {
            assertEquals(DbInitResult.Ready, Database.init())
            // The backup is taken while there is no PIN and Always-On is off.
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "from-backup"))
            val backup = assertIs<AutoBackupService.BackupResult.Success>(AutoBackupService.runBackupNow())

            // Later the user turns protections on.
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("restore_test_value", "newer"))
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("global_pin_hash", "kept-hash"))
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting("always_on_enforcement", "true"))

            assertIs<RestoreScheduleResult.Scheduled>(BackupRecoveryService.scheduleRestore(backup.file))
            assertEquals(DbInitResult.Ready, BackupRecoveryService.initializeDatabaseForStartup())

            // The data comes from the backup ...
            assertEquals(Database.SettingRead.Present("from-backup"), Database.readSetting("restore_test_value"))
            // ... but the protections are NOT weaker than they were before the restore.
            assertEquals(Database.SettingRead.Present("kept-hash"), Database.readSetting("global_pin_hash"))
            assertEquals(Database.SettingRead.Present("true"), Database.readSetting("always_on_enforcement"))
            assertEquals("Backup restored", RecoveryNoticeService.pendingNotice()?.title)
        }
}
