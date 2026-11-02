package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/** Owner decision O4-B: the Store/MSIX build asks for the same PINs as the direct build when quitting. */
class UninstallProtectionQuitGateTest {
    private lateinit var store: StubSettingsStore
    private val messageTitles = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        store = StubSettingsStore()
        Database.settingsStoreForTest = store
        // Pretend to be the Store/MSIX build.
        UninstallProtectionService.isDirectInstallForTest = false
        UninstallProtectionService.messageDialogForTest = { title, _ -> messageTitles += title }
    }

    @AfterEach
    fun tearDown() {
        UninstallProtectionService.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun quitIsStillAllowedWhenNothingIsProtected() {
        assertTrue(UninstallProtectionService.authorizeQuit())
        assertEquals(emptyList<String>(), messageTitles)
    }

    @Test
    fun quitIsRefusedWhileNuclearModeIsOnWithoutANuclearPin() {
        store.reads["nuclear_mode"] = Database.SettingRead.Present("true")

        assertFalse(UninstallProtectionService.authorizeQuit())
        assertEquals(listOf("Action blocked"), messageTitles)
    }

    @Test
    fun quitNeedsTheGlobalPinWhileAlwaysOnIsOn() {
        store.reads["always_on_enforcement"] = Database.SettingRead.Present("true")
        store.reads["global_pin_hash"] = Database.SettingRead.Present(sha256("correct-pin-1234"))

        UninstallProtectionService.pinPromptForTest = { "wrong-pin" }
        assertFalse(UninstallProtectionService.authorizeQuit())
        assertEquals(listOf("Incorrect PIN"), messageTitles)

        UninstallProtectionService.pinPromptForTest = { "correct-pin-1234" }
        assertTrue(UninstallProtectionService.authorizeQuit())
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class StubSettingsStore : SettingsStore {
        val reads = mutableMapOf<String, Database.SettingRead>()
        override fun readSetting(key: String): Database.SettingRead =
            reads[key] ?: Database.SettingRead.Absent

        override fun writeSetting(key: String, value: String) = Database.SettingWrite.Ok
        override fun writeSettings(pairs: Map<String, String>) = Database.SettingWrite.Ok
    }
}
