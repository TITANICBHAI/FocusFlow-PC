package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach

class LauncherKioskImpostorTest {
    private val safeNames = setOf("explorer.exe", "svchost.exe", "java.exe")
    private val systemRoot = "C:\\Windows"

    @BeforeEach
    fun reset() {
        LauncherKioskPolicy.resetForTest()
    }

    private fun decide(name: String, path: String?): LauncherKioskPolicy.Decision {
        val process = ProcessSnapshotEntry(
            pid = 50L, parentPid = 4L, creationTimeMs = 100L, processName = name, executablePath = path
        )
        return LauncherKioskPolicy.decide(
            process = process,
            snapshot = listOf(process),
            allowedProcessNames = setOf("steam.exe"),
            allowedExecutablePaths = mapOf("steam.exe" to "C:\\Games\\Steam\\steam.exe"),
            safeProcessNames = safeNames,
            ownPid = 1L,
            systemRoot = systemRoot,
            chainAllowanceEnabled = false
        )
    }

    @Test
    fun aRenamedCopyOutsideTheWindowsFolderIsNotSafe() {
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide("explorer.exe", "C:\\Users\\me\\Downloads\\explorer.exe"))
        assertEquals(LauncherKioskPolicy.Decision.BLOCK, decide("svchost.exe", "D:\\tools\\svchost.exe"))
    }

    @Test
    fun realWindowsComponentsStaySafe() {
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("explorer.exe", "C:\\Windows\\explorer.exe"))
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("svchost.exe", "C:\\WINDOWS\\System32\\svchost.exe"))
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("svchost.exe", "c:/windows/syswow64/svchost.exe"))
    }

    @Test
    fun anUnknownOrUnusualPathKeepsTheOldBehaviour() {
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("explorer.exe", null))
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("explorer.exe", "\\Device\\HarddiskVolume3\\Users\\me\\explorer.exe"))
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("explorer.exe", "\\\\server\\share\\explorer.exe"))
    }

    @Test
    fun safeNamesThatAreNotWindowsComponentsAreUnaffected() {
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decide("java.exe", "C:\\Program Files\\FocusFlow\\runtime\\bin\\java.exe"))
    }

    @Test
    fun withoutAKnownWindowsFolderNothingChanges() {
        val process = ProcessSnapshotEntry(pid = 50L, parentPid = 4L, creationTimeMs = 100L, processName = "explorer.exe", executablePath = "C:\\Users\\me\\explorer.exe")
        val decision = LauncherKioskPolicy.decide(
            process, listOf(process), setOf("steam.exe"), emptyMap(), safeNames, 1L, null, false
        )
        assertEquals(LauncherKioskPolicy.Decision.ALLOW, decision)
    }
}
