package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Focus Launcher sessions and the person's own Nuclear Mode setting must not interfere.
 * Enforcement itself is switched off for these tests (see enforcementRunsForTest), so no
 * real process or firewall rule is touched.
 */
class NuclearModeHoldsTest {
    private lateinit var store: MapSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        store = MapSettingsStore()
        Database.settingsStoreForTest = store
        NuclearMode.resetForTest()
        NuclearMode.enforcementRunsForTest = false
    }

    @AfterEach
    fun tearDown() {
        NuclearMode.resetForTest()
        Database.resetForTest()
    }

    private fun settle() = NuclearMode.awaitCleanup(2_000)

    @Test
    fun aSessionHoldIsNotRememberedAsTheNuclearSetting() {
        NuclearMode.acquireSessionHold()
        settle()

        assertTrue(NuclearMode.isActive)
        assertTrue(NuclearMode.sessionHeld)
        assertFalse(NuclearMode.manualActive)
        assertEquals(null, store.values["nuclear_mode"], "a session must not write the manual flag")
    }

    @Test
    fun endingASessionTurnsNuclearOffWhenItWasOnlyTheSession() {
        NuclearMode.acquireSessionHold()
        NuclearMode.releaseSessionHold()
        settle()

        assertFalse(NuclearMode.isActive)
        assertFalse(NuclearMode.sessionHeld)
        assertEquals(null, store.values["nuclear_mode"])
    }

    @Test
    fun aManualSettingSurvivesTheEndOfASession() {
        NuclearMode.enable(silent = true)
        NuclearMode.acquireSessionHold()

        NuclearMode.releaseSessionHold()
        settle()

        assertTrue(NuclearMode.isActive)
        assertTrue(NuclearMode.manualActive)
        assertEquals("true", store.values["nuclear_mode"])
    }

    @Test
    fun turningTheManualSettingOffDuringASessionKeepsTheSessionsNuclear() {
        NuclearMode.enable(silent = true)
        NuclearMode.acquireSessionHold()

        NuclearMode.disable(silent = true)
        settle()

        assertTrue(NuclearMode.isActive, "the session still needs it")
        assertFalse(NuclearMode.manualActive)
        assertEquals("false", store.values["nuclear_mode"])
    }

    @Test
    fun aManualSettingTurnedOnDuringASessionStaysAfterwards() {
        NuclearMode.acquireSessionHold()
        NuclearMode.enable(silent = true)

        NuclearMode.releaseSessionHold()
        settle()

        assertTrue(NuclearMode.isActive)
        assertEquals("true", store.values["nuclear_mode"])
    }

    @Test
    fun anInterruptedSessionLeavesNothingBehindAfterARestart() {
        NuclearMode.acquireSessionHold()
        settle()

        // The process is gone; the next start only has what was written to storage.
        NuclearMode.resetForTest()
        NuclearMode.enforcementRunsForTest = false
        NuclearMode.loadFromDb()

        assertFalse(NuclearMode.isActive)
    }

    @Test
    fun aRememberedManualSettingComesBackAfterARestart() {
        NuclearMode.enable(silent = true)
        settle()

        NuclearMode.resetForTest()
        NuclearMode.enforcementRunsForTest = false
        NuclearMode.loadFromDb()

        assertTrue(NuclearMode.manualActive)
    }

    @Test
    fun loadingStorageNeverTouchesARunningSessionsHold() {
        NuclearMode.acquireSessionHold()

        NuclearMode.loadFromDb() // stored flag is absent => manual off

        assertTrue(NuclearMode.sessionHeld)
        assertTrue(NuclearMode.isActive)
    }

    @Test
    fun theElevationHandoffKeepsTheRememberedManualSetting() {
        NuclearMode.enable(silent = true)
        settle()

        NuclearMode.suspendForElevationHandoff()
        settle()

        assertFalse(NuclearMode.isActive)
        assertEquals("true", store.values["nuclear_mode"], "the new process resumes from this")
    }

    @Test
    fun rapidToggleLeavesTheStoredValueEqualToTheFinalState() {
        repeat(25) {
            NuclearMode.enable(silent = true)
            NuclearMode.disable(silent = true)
        }
        NuclearMode.enable(silent = true)
        NuclearMode.disable(silent = true)
        settle()

        assertFalse(NuclearMode.manualActive)
        assertEquals("false", store.values["nuclear_mode"])
    }

    @Test
    fun anOlderNuclearFlagIsKeptAsAManualSetting() {
        store.values["nuclear_mode"] = "true"

        NuclearMode.loadFromDb()

        assertTrue(NuclearMode.manualActive)
        assertEquals("2", store.values["nuclear_hold_model"])
    }

    @Test
    fun anUnreadableStoredSettingKeepsNuclearOnAndNeverWritesItOff() {
        store.unreadable = true

        NuclearMode.loadFromDb()
        settle()

        assertTrue(NuclearMode.manualActive, "unknown storage must not weaken protection")
        assertEquals(null, store.values["nuclear_mode"])
    }

    private class MapSettingsStore : SettingsStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var unreadable = false
        override fun readSetting(key: String): Database.SettingRead =
            if (unreadable) Database.SettingRead.Unreadable(IllegalStateException("injected")) else
            values[key]?.let { Database.SettingRead.Present(it) } ?: Database.SettingRead.Absent
        override fun writeSetting(key: String, value: String): Database.SettingWrite {
            values[key] = value
            return Database.SettingWrite.Ok
        }
        override fun writeSettings(pairs: Map<String, String>): Database.SettingWrite {
            values.putAll(pairs)
            return Database.SettingWrite.Ok
        }
    }
}
