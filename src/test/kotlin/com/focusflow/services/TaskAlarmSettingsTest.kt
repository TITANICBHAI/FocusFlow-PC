package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class TaskAlarmSettingsTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        TaskAlarmService.resetForTest()
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        TaskAlarmService.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun unreadableDateDoesNotOverwritePersistenceOrClearMemory() {
        TaskAlarmService.addFiredIdForTest("existing-id")
        store.reads["alarm_fired_date"] =
            Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))

        assertFalse(TaskAlarmService.loadFiredIdsFromDb())
        assertEquals(setOf("existing-id"), TaskAlarmService.firedIdsForTest())
        assertTrue(store.batches.isEmpty())
    }

    @Test
    fun currentDateRestoresFiredIdsFromTypedSettings() {
        store.reads["alarm_fired_date"] =
            Database.SettingRead.Present(LocalDate.now().toString())
        store.reads["alarm_fired_ids"] = Database.SettingRead.Present("one,two")

        assertTrue(TaskAlarmService.loadFiredIdsFromDb())
        assertEquals(setOf("one", "two"), TaskAlarmService.firedIdsForTest())
    }

    @Test
    fun dateRolloverPersistsBeforeClearingMemory() {
        TaskAlarmService.addFiredIdForTest("existing-id")
        store.reads["alarm_fired_date"] =
            Database.SettingRead.Present(LocalDate.now().minusDays(1).toString())
        store.writeResult = Database.SettingWrite.Failed(IllegalStateException("injected write failure"))

        assertFalse(TaskAlarmService.loadFiredIdsFromDb())
        assertEquals(setOf("existing-id"), TaskAlarmService.firedIdsForTest())
        assertEquals(1, store.batches.size)
    }

    @Test
    fun successfulDateRolloverAtomicallyClearsSavedIdsAndMemory() {
        TaskAlarmService.addFiredIdForTest("existing-id")
        store.reads["alarm_fired_date"] =
            Database.SettingRead.Present(LocalDate.now().minusDays(1).toString())

        assertTrue(TaskAlarmService.loadFiredIdsFromDb())
        assertTrue(TaskAlarmService.firedIdsForTest().isEmpty())
        assertEquals(
            linkedMapOf(
                "alarm_fired_ids" to "",
                "alarm_fired_date" to LocalDate.now().toString()
            ),
            store.batches.single()
        )
    }

    private class MutableSettingsStore : SettingsStore {
        val reads = mutableMapOf<String, Database.SettingRead>()
        val batches = mutableListOf<Map<String, String>>()
        var writeResult: Database.SettingWrite = Database.SettingWrite.Ok

        override fun readSetting(key: String) =
            reads[key] ?: Database.SettingRead.Absent

        override fun writeSetting(key: String, value: String) = writeResult

        override fun writeSettings(pairs: Map<String, String>): Database.SettingWrite {
            batches += pairs.toMap()
            return writeResult
        }
    }
}
