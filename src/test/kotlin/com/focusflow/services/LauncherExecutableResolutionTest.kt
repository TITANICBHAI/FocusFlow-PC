package com.focusflow.services

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LauncherExecutableResolutionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun selectedExecutablePathHasPriority() {
        val tileExecutable = createFile("tile/FocusApp.exe")
        var fallbackWasRead = false

        val resolved = resolveLauncherExecutableFile(
            tilePath = tileExecutable.path,
            processName = "FocusApp.exe",
            isWindowsHost = true,
            appPathsLookup = {
                fallbackWasRead = true
                null
            },
            pathValue = null,
            runningInstancePaths = {
                fallbackWasRead = true
                emptySequence()
            }
        )

        assertEquals(tileExecutable.absoluteFile, resolved)
        assertFalse(fallbackWasRead)
    }

    @Test
    fun staleTilePathFallsBackToAppPathsBeforePath() {
        val appPathsExecutable = createFile("Program Files/Focus App/FocusApp.exe")
        val pathExecutable = createFile("path/FocusApp.exe")
        val calls = mutableListOf<String>()

        val resolved = resolveLauncherExecutableFile(
            tilePath = tempDir.resolve("removed/FocusApp.exe").toString(),
            processName = "FocusApp.exe",
            isWindowsHost = true,
            appPathsLookup = { name ->
                calls += "app-paths:$name"
                "\"${appPathsExecutable.path}\" -registered-argument"
            },
            pathValue = pathExecutable.parentFile.path,
            runningInstancePaths = {
                calls += "running"
                emptySequence()
            }
        )

        assertEquals(appPathsExecutable.absoluteFile, resolved)
        assertEquals(listOf("app-paths:FocusApp.exe"), calls)
    }

    @Test
    fun pathSearchPrecedesRunningProcessFallback() {
        val pathExecutable = createFile("bin/FocusApp.exe")
        val runningExecutable = createFile("running/FocusApp.exe")

        val resolved = resolveLauncherExecutableFile(
            tilePath = null,
            processName = "FocusApp.exe",
            isWindowsHost = true,
            appPathsLookup = { null },
            pathValue = pathExecutable.parentFile.path,
            runningInstancePaths = { sequenceOf(runningExecutable.path) }
        )

        assertEquals(pathExecutable.absoluteFile, resolved)
    }

    @Test
    fun runningInstancePathIsUsedAsLastFallback() {
        val runningExecutable = createFile("running/FocusApp.exe")

        val resolved = resolveLauncherExecutableFile(
            tilePath = tempDir.resolve("missing/FocusApp.exe").toString(),
            processName = "FocusApp.exe",
            isWindowsHost = true,
            appPathsLookup = { null },
            pathValue = null,
            runningInstancePaths = { sequenceOf(runningExecutable.path) }
        )

        assertEquals(runningExecutable.absoluteFile, resolved)
    }

    @Test
    fun unreadableProcessNameOnlyMatchesWhenResolvedPathHasTheExpectedImageName() {
        val processes = listOf(
            ProcessSnapshotEntry(pid = 1, processName = "", executablePath = "C:\\Other\\Helper.exe"),
            ProcessSnapshotEntry(pid = 2, processName = "Other.exe", executablePath = "C:\\Apps\\FocusApp.exe"),
            ProcessSnapshotEntry(pid = 3, processName = "", executablePath = "C:\\Apps\\FocusApp.exe"),
            ProcessSnapshotEntry(pid = 4, processName = "FocusApp.exe", executablePath = "C:\\Apps\\FocusApp.exe")
        )

        val paths = matchingLauncherExecutablePaths(
            expectedProcessName = "FocusApp.exe",
            processes = processes,
            resolvePath = ProcessSnapshotEntry::executablePath
        )

        assertEquals(
            listOf("C:\\Apps\\FocusApp.exe"),
            paths
        )
    }

    @Test
    fun sessionAppResolutionSeparatesResolvedAppsFromUnavailableApps() {
        val available = FocusLauncherApp("focus.exe", "Focus", "C:\\Apps\\focus.exe")
        val missing = FocusLauncherApp("gone.exe", "Gone", "C:\\Removed\\gone.exe")

        val result = resolveLauncherAppsForSession(listOf(available, missing)) { app ->
            if (app.processName == "focus.exe") app.copy(exePath = "C:\\Current\\focus.exe") else null
        }

        assertEquals(listOf("focus.exe"), result.resolved.map { it.processName })
        assertEquals("C:\\Current\\focus.exe", result.resolved.single().exePath)
        assertEquals(listOf("gone.exe"), result.unavailable.map { it.processName })
    }

    @Test
    fun missingOrUnsupportedCandidatesDoNotResolve() {
        assertNull(
            resolveLauncherExecutableFile(
                tilePath = tempDir.resolve("missing/FocusApp.exe").toString(),
                processName = "FocusApp.exe",
                isWindowsHost = true,
                appPathsLookup = { null },
                pathValue = null,
                runningInstancePaths = { emptySequence() }
            )
        )
        assertTrue(isUnsupportedLauncherExecutableName("C:\\Apps\\unins000.exe"))
        assertTrue(isUnsupportedLauncherExecutableName("C:\\Apps\\Update.exe"))
        assertTrue(isUnsupportedLauncherExecutableName("C:\\Apps\\FocusApp-Setup.exe"))
        assertFalse(isUnsupportedLauncherExecutableName("C:\\Apps\\FocusApp.exe"))
    }

    @Test
    fun appPathsValuesSupportQuotedPathsAndArguments() {
        val pathWithSpaces = "C:\\Program Files\\Focus App\\FocusApp.exe"

        assertEquals(
            pathWithSpaces,
            executablePathFromAppPathsValue("\"$pathWithSpaces\" --launch")
        )
        assertEquals(
            "C:\\Apps\\FocusApp.exe",
            executablePathFromAppPathsValue("C:\\Apps\\FocusApp.exe,0")
        )
        assertNull(executablePathFromAppPathsValue("  "))
    }

    @Test
    fun workingDirectoryIsTheExecutableDirectory() {
        val executable = createFile("Program Files/Focus App/FocusApp.exe")

        assertEquals(executable.parentFile.absoluteFile, launcherWorkingDirectory(executable))
    }

    @Test
    fun elevatedWindowsUsesTheInteractiveUserTokenAndOtherCasesLaunchDirectly() {
        assertEquals(
            LauncherSpawnMode.InteractiveUserToken,
            launcherSpawnMode(isWindowsHost = true, focusFlowElevated = true)
        )
        assertEquals(
            LauncherSpawnMode.Direct,
            launcherSpawnMode(isWindowsHost = true, focusFlowElevated = false)
        )
        assertEquals(
            LauncherSpawnMode.Direct,
            launcherSpawnMode(isWindowsHost = false, focusFlowElevated = true)
        )
    }

    @Test
    fun unelevatedLaunchStructuresAreAvailableToJna() {
        assertTrue(WindowsUnelevatedLauncher.LauncherStartupInfo().size() > 0)
        assertTrue(WindowsUnelevatedLauncher.LauncherProcessInformation().size() > 0)
    }

    private fun createFile(relativePath: String): File {
        val file = tempDir.resolve(relativePath).toFile()
        file.parentFile?.mkdirs()
        file.writeText("test executable fixture")
        return file
    }
}
