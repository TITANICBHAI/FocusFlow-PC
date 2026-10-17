package com.focusflow.enforcement

import com.focusflow.services.ProcessSnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class NuclearModeSnapshotTest {
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

        assertEquals(
            setOf("powershell.exe"),
            NuclearMode.getRunningEscapeProcesses(snapshot)
        )
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

        assertEquals(
            setOf("taskmgr.exe"),
            NuclearMode.getEscapeProcessesByPath(snapshot)
        )
    }
}
