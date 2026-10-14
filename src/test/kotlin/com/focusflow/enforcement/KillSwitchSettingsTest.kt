package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class KillSwitchSettingsTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        KillSwitchService.resetForTest()
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        KillSwitchService.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun unreadableBudgetDoesNotRefillOrPersistDefaultsAndCannotActivate() {
        store.reads["killswitch_reset_date"] =
            Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))
        store.reads["killswitch_remaining_today"] = Database.SettingRead.Present("9")

        assertFalse(KillSwitchService.loadFromDb())
        assertFalse(KillSwitchService.settingsAvailable.value)
        assertEquals(KillSwitchService.DAILY_BUDGET_SECONDS, KillSwitchService.remainingSecondsToday.value)
        assertFalse(KillSwitchService.toggle())
        assertTrue(store.batches.isEmpty())
        assertTrue(store.singleWrites.isEmpty())
    }

    @Test
    fun readableCurrentBudgetRestoresWithoutReplacingIt() {
        store.reads["killswitch_reset_date"] =
            Database.SettingRead.Present(LocalDate.now().toString())
        store.reads["killswitch_remaining_today"] = Database.SettingRead.Present("42")

        assertTrue(KillSwitchService.loadFromDb())
        assertTrue(KillSwitchService.settingsAvailable.value)
        assertEquals(42, KillSwitchService.remainingSecondsToday.value)
        assertTrue(store.batches.isEmpty())
    }

    @Test
    fun absentDateCreatesInitialBudgetAtomically() {
        store.reads["killswitch_reset_date"] = Database.SettingRead.Absent

        assertTrue(KillSwitchService.loadFromDb())
        assertTrue(KillSwitchService.settingsAvailable.value)
        assertEquals(KillSwitchService.DAILY_BUDGET_SECONDS, KillSwitchService.remainingSecondsToday.value)
        assertEquals(
            linkedMapOf(
                "killswitch_remaining_today" to KillSwitchService.DAILY_BUDGET_SECONDS.toString(),
                "killswitch_reset_date" to LocalDate.now().toString()
            ),
            store.batches.single()
        )
    }

    private class MutableSettingsStore : SettingsStore {
        val reads = mutableMapOf<String, Database.SettingRead>()
        val singleWrites = mutableListOf<Pair<String, String>>()
        val batches = mutableListOf<Map<String, String>>()
        var writeResult: Database.SettingWrite = Database.SettingWrite.Ok

        override fun readSetting(key: String): Database.SettingRead =
            reads[key] ?: Database.SettingRead.Absent

        override fun writeSetting(key: String, value: String): Database.SettingWrite {
            singleWrites += key to value
            return writeResult
        }

        override fun writeSettings(pairs: Map<String, String>): Database.SettingWrite {
            batches += pairs.toMap()
            return writeResult
        }
    }
}
