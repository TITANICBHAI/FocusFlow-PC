package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProcessLineageTrackerTest {
    private val app = FocusLauncherApp(
        processName = "sample.exe",
        displayName = "Sample",
        exePath = "C:\\Apps\\sample.exe"
    )

    @Test
    fun tracksAppProcessesAndTheirLiveDescendants() {
        val tracker = ProcessLineageTracker(app, chainStartedAtMs = 100L)
        val root = ProcessSnapshotEntry(
            pid = 10L,
            creationTimeMs = 100L,
            processName = "sample.exe",
            executablePath = app.exePath
        )
        tracker.update(listOf(root))

        val child = ProcessSnapshotEntry(
            pid = 11L,
            parentPid = 10L,
            creationTimeMs = 200L,
            processName = "helper.exe",
            executablePath = "C:\\Other\\helper.exe"
        )
        val grandchild = ProcessSnapshotEntry(
            pid = 12L,
            parentPid = 11L,
            creationTimeMs = 300L,
            processName = "render.exe",
            executablePath = "C:\\Other\\render.exe"
        )

        assertEquals(setOf(10L, 11L, 12L), tracker.update(listOf(root, child, grandchild)))
        assertEquals(setOf(10L, 12L), tracker.update(listOf(root, grandchild)))
    }

    @Test
    fun reusedPidWithDifferentCreationTimeIsNotKeptInTheOldChain() {
        val tracker = ProcessLineageTracker(app, chainStartedAtMs = 100L)
        tracker.update(
            listOf(
                ProcessSnapshotEntry(
                    pid = 10L,
                    creationTimeMs = 100L,
                    processName = "sample.exe",
                    executablePath = app.exePath
                )
            )
        )

        assertEquals(
            emptySet(),
            tracker.update(
                listOf(
                    ProcessSnapshotEntry(
                        pid = 10L,
                        creationTimeMs = 200L,
                        processName = "other.exe",
                        executablePath = "C:\\Other\\other.exe"
                    )
                )
            )
        )
    }

    @Test
    fun spawnedPidStaysTrackedWhenImageMetadataIsUnreadable() {
        val tracker = ProcessLineageTracker(app, chainStartedAtMs = 100L)
        tracker.addSpawnPid(50L)

        assertEquals(
            setOf(50L),
            tracker.update(
                listOf(
                    ProcessSnapshotEntry(
                        pid = 50L,
                        creationTimeMs = 150L,
                        processName = "",
                        executablePath = null
                    )
                )
            )
        )
        assertNotNull(tracker.process(50L))
        assertNull(tracker.process(51L))
    }

    @Test
    fun spawnedPidReuseBeforeFirstSnapshotIsRejectedByCreationTime() {
        val tracker = ProcessLineageTracker(app, chainStartedAtMs = 100L)
        tracker.addSpawnPid(50L, creationTimeMs = 150L)

        assertEquals(
            emptySet(),
            tracker.update(
                listOf(
                    ProcessSnapshotEntry(
                        pid = 50L,
                        creationTimeMs = 200L,
                        processName = "unrelated.exe",
                        executablePath = "C:\\Other\\unrelated.exe"
                    )
                )
            )
        )
    }

    @Test
    fun recognizesSquirrelHandoffUnderTheInstallRoot() {
        val discord = FocusLauncherApp(
            processName = "Discord.exe",
            displayName = "Discord",
            exePath = "C:\\Users\\User\\Discord\\app-1.0.0\\Discord.exe"
        )
        val tracker = ProcessLineageTracker(discord, chainStartedAtMs = 500L)

        assertEquals(
            setOf(88L),
            tracker.update(
                listOf(
                    ProcessSnapshotEntry(
                        pid = 88L,
                        creationTimeMs = 700L,
                        processName = "Update.exe",
                        executablePath = "C:\\Users\\User\\Discord\\Update.exe"
                    )
                )
            )
        )
    }
}
