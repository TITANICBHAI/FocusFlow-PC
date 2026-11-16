package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the selector against Nuclear Mode's real escape lists. */
class NuclearModeSnapshotTest {
    private fun select(snapshot: List<ProcessSnapshotEntry>) = EscapeTargetSelector.select(
        snapshot = snapshot,
        ownPid = 1L,
        escapeNames = NuclearMode.escapeProcessNames,
        escapePathSuffixes = NuclearMode.escapePathSuffixes,
        resolvePath = { it.executablePath },
        isNameBlockable = { true },
        isTrusted = { _, _ -> false }
    )

    @Test
    fun detectsNamedEscapeToolsFromTheSharedSnapshot() {
        val snapshot = listOf(
            ProcessSnapshotEntry(
                pid = 123L,
                processName = "PowerShell.EXE",
                executablePath = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
            ),
            ProcessSnapshotEntry(pid = 124L, processName = "ordinary.exe")
        )

        assertEquals(mapOf(123L to "powershell.exe"), select(snapshot))
    }

    @Test
    fun keepsPathBasedEscapeDetectionWhenTheProcessNameDiffers() {
        val snapshot = listOf(
            ProcessSnapshotEntry(
                pid = 125L,
                processName = "renamed.exe",
                executablePath = "C:\\Windows\\System32\\taskmgr.exe"
            )
        )

        assertEquals(mapOf(125L to "renamed.exe"), select(snapshot))
    }
}
