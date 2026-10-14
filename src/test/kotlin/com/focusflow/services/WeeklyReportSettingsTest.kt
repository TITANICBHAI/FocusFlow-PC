package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.NativeSqlite
import com.focusflow.data.NativeSqliteTestHooks
import com.focusflow.data.SettingsStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class WeeklyReportSettingsTest {
    @TempDir
    lateinit var tempDir: Path
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        WeeklyReportService.resetForTest()
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = emptyList(),
            defaultInitialize = { org.sqlite.SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )
        assertIs<DbInitResult.Ready>(
            Database.init(dbFile = tempDir.resolve("weekly-report.db").toFile(), allowRecovery = false)
        )
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        WeeklyReportService.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun unreadableLastGeneratedDateDefersWithoutPublishing() = runBlocking {
        store.read = Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))
        var callbackCount = 0
        WeeklyReportService.onReportReady = { callbackCount++ }

        WeeklyReportService.checkAndGenerateForTest()

        assertNull(WeeklyReportService.latestReport)
        assertFalse(WeeklyReportService.hasNewReport.value)
        assertEquals(0, callbackCount)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun failedDateWriteDoesNotPublishTheGeneratedReport() = runBlocking {
        store.writeResult = Database.SettingWrite.Failed(IllegalStateException("injected write failure"))
        var callbackCount = 0
        WeeklyReportService.onReportReady = { callbackCount++ }

        WeeklyReportService.checkAndGenerateForTest()

        assertNull(WeeklyReportService.latestReport)
        assertFalse(WeeklyReportService.hasNewReport.value)
        assertEquals(0, callbackCount)
        assertEquals(1, store.writes.size)
    }

    @Test
    fun successfulDateWritePublishesOnlyAfterPersistence() = runBlocking {
        var callbackCount = 0
        WeeklyReportService.onReportReady = { callbackCount++ }

        WeeklyReportService.checkAndGenerateForTest()

        assertTrue(WeeklyReportService.latestReport != null)
        assertTrue(WeeklyReportService.hasNewReport.value)
        assertEquals(1, callbackCount)
        assertEquals(1, store.writes.size)
    }

    private class MutableSettingsStore : SettingsStore {
        var read: Database.SettingRead = Database.SettingRead.Absent
        val writes = mutableListOf<Pair<String, String>>()
        var writeResult: Database.SettingWrite = Database.SettingWrite.Ok

        override fun readSetting(key: String) = read
        override fun writeSetting(key: String, value: String): Database.SettingWrite {
            writes += key to value
            return writeResult
        }
        override fun writeSettings(pairs: Map<String, String>) = writeResult
    }
}
