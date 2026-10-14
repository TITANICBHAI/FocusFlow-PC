package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class StandaloneBlockSettingsTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        StandaloneBlockService.resetForTest()
        store = MutableSettingsStore()
        val until = System.currentTimeMillis() + 3_600_000L
        store.reads["standalone_block_processes"] =
            Database.SettingRead.Present("editor.exe,browser.exe")
        store.reads["standalone_block_until"] = Database.SettingRead.Present(until.toString())
        store.reads["standalone_block_start"] = Database.SettingRead.Absent
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        StandaloneBlockService.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun unreadableRestoreRetainsTheLastKnownBlock() {
        StandaloneBlockService.loadFromDb()
        val restored = assertNotNull(StandaloneBlockService.block.value)
        assertEquals(listOf("editor.exe", "browser.exe"), restored.processNames)

        store.reads.clear()
        StandaloneBlockService.loadFromDb()

        assertEquals(restored, StandaloneBlockService.block.value)
        assertEquals(
            setOf("editor.exe", "browser.exe"),
            com.focusflow.enforcement.ProcessMonitor.standaloneBlockedProcesses
        )
    }

    @Test
    fun stopUsesOneAtomicBatchAndKeepsBlockWhenPersistenceFails() {
        StandaloneBlockService.loadFromDb()
        val restored = assertNotNull(StandaloneBlockService.block.value)
        store.writeResult = Database.SettingWrite.Failed(IllegalStateException("injected write failure"))

        assertFalse(StandaloneBlockService.stop())
        assertEquals(restored, StandaloneBlockService.block.value)
        assertEquals(1, store.batches.size)
        assertEquals(
            linkedMapOf(
                "standalone_block_processes" to "",
                "standalone_block_until" to "0",
                "standalone_block_start" to "0"
            ),
            store.batches.single()
        )
    }

    private class MutableSettingsStore : SettingsStore {
        val reads = mutableMapOf<String, Database.SettingRead>()
        val batches = mutableListOf<Map<String, String>>()
        var writeResult: Database.SettingWrite = Database.SettingWrite.Ok

        override fun readSetting(key: String): Database.SettingRead =
            reads[key] ?: Database.SettingRead.Unreadable(
                IllegalStateException("injected settings store unavailable")
            )

        override fun writeSetting(key: String, value: String) = writeResult

        override fun writeSettings(pairs: Map<String, String>): Database.SettingWrite {
            batches += pairs.toMap()
            return writeResult
        }
    }
}
