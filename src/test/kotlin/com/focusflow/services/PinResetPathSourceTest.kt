package com.focusflow.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner decision O1-A: a PIN that can be reset from its own lock dialog is no protection.
 * This is a structural guard (it reads source files; it does not run the UI).
 */
class PinResetPathSourceTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin")

    private fun kotlinFilesContaining(needle: String): Set<String> =
        Files.walk(sourceRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains(needle) }
                .map { it.fileName.toString() }
                .toSet()
        }

    @Test
    fun theForgotPinLinkIsOffByDefault() {
        val dialog = Files.readString(sourceRoot.resolve("com/focusflow/ui/components/PinGateDialog.kt"))
        assertTrue(dialog.contains("allowReset: Boolean = false"))
    }

    @Test
    fun noCallerTurnsTheForgotPinLinkBackOn() {
        assertEquals(
            emptySet<String>(),
            kotlinFilesContaining("allowReset = true"),
            "A caller passes allowReset = true. That re-opens the PIN reset path; the owner must approve it first."
        )
    }

    @Test
    fun onlyTheKnownFlowsCallResetWithoutPin() {
        assertEquals(
            setOf("GlobalPin.kt", "PinGateDialog.kt", "SettingsScreen.kt"),
            kotlinFilesContaining("resetWithoutPin("),
            "A new file calls GlobalPin.resetWithoutPin(). Use the verified GlobalPin.clearResult(rawPin) instead."
        )
    }
}
