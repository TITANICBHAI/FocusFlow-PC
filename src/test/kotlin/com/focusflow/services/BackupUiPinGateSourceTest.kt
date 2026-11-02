package com.focusflow.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cheap structural guard. It reads source files; it does not run the UI.
 * The Backup & Recovery screen must keep sending every restore / delete / folder action through
 * withBackupPin, and no other file may start calling those functions without a review.
 */
class BackupUiPinGateSourceTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin")
    private val contactScreen: Path = sourceRoot.resolve("com/focusflow/ui/screens/ContactScreen.kt")

    private fun kotlinFilesContaining(vararg needles: String): Set<String> =
        Files.walk(sourceRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { path ->
                    val text = Files.readString(path)
                    needles.any { text.contains(it) }
                }
                .map { it.fileName.toString() }
                .toSet()
        }

    @Test
    fun everyBackupActionInTheScreenGoesThroughTheBackupPinHelper() {
        val text = Files.readString(contactScreen)
        assertEquals(
            5,
            Regex("withBackupPin \\{").findAll(text).count(),
            "Expected exactly 5 'withBackupPin {' call sites: choose folder, remove folder, restore, delete backup, delete archive"
        )
        assertTrue(text.contains("BackupActionPolicy.requiresPin("), "The helper must ask BackupActionPolicy")
        assertTrue(text.contains("allowReset = false"), "The backup PIN dialog must not offer a reset link")
    }

    @Test
    fun noNewCallersOfTheRestoreAndBackupManagementFunctions() {
        val callers = kotlinFilesContaining(
            "scheduleRestore(",
            "configureSecondaryBackupDirectory(",
            "deleteSecondaryBackup(",
            "deleteSafetyBackup(",
            "deleteRestoreArchive("
        )
        assertEquals(
            setOf("BackupRecoveryService.kt", "ContactScreen.kt"),
            callers,
            "A new file calls a restore / backup-management function. Gate it with BackupActionPolicy and ask the owner."
        )
    }
}
