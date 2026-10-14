package com.focusflow.testutil

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempHomeTest {
    @Test
    fun setsScratchHomeAndRequiredPropertiesThenRestoresThem() {
        val previous = savedProperties()
        var scratchHome: Path? = null

        val result = TempHome.withHome { home ->
            scratchHome = home
            assertTrue(Files.isDirectory(home))
            assertEquals(home.toString(), System.getProperty("user.home"))
            assertEquals("test", System.getProperty("focusflow.app.version"))
            assertEquals("off", System.getProperty("focusflow.telemetry"))
            "block-result"
        }

        assertEquals("block-result", result)
        assertRestored(previous)
        assertFalse(Files.exists(requireNotNull(scratchHome)))
    }

    @Test
    fun restoresPropertiesAndRemovesScratchHomeWhenBlockThrows() {
        val previous = savedProperties()
        var scratchHome: Path? = null

        val failure = assertFailsWith<IllegalStateException> {
            TempHome.withHome { home ->
                scratchHome = home
                error("intentional helper smoke-test failure")
            }
        }

        assertEquals("intentional helper smoke-test failure", failure.message)
        assertRestored(previous)
        assertFalse(Files.exists(requireNotNull(scratchHome)))
    }

    private fun savedProperties(): Map<String, String?> =
        listOf("user.home", "focusflow.app.version", "focusflow.telemetry")
            .associateWith { System.getProperty(it) }

    private fun assertRestored(previous: Map<String, String?>) {
        previous.forEach { (name, value) ->
            assertEquals(value, System.getProperty(name), "Property $name was not restored")
        }
    }
}
