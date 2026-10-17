package com.focusflow.enforcement

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class InstalledAppsScannerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun classifiesStoreInstallersMissingFilesAndSystemUtilities() {
        assertEquals(
            LauncherAppIssue.StoreApp,
            InstalledAppsScanner.classifyLauncherCandidate(
                "calculator.exe",
                "C:\\Program Files\\WindowsApps\\Calculator.exe",
                isReadableFile = { false }
            )
        )
        assertEquals(
            LauncherAppIssue.InstallerOrUpdater,
            InstalledAppsScanner.classifyLauncherCandidate(
                "Update.exe",
                "C:\\Apps\\Update.exe",
                isReadableFile = { true }
            )
        )
        assertEquals(
            LauncherAppIssue.MissingExecutable,
            InstalledAppsScanner.classifyLauncherCandidate("Chrome.exe", null)
        )
        assertEquals(
            LauncherAppIssue.SystemUtility,
            InstalledAppsScanner.classifyLauncherCandidate(
                "taskmgr.exe",
                "C:\\Windows\\System32\\taskmgr.exe",
                isReadableFile = { true }
            )
        )
        assertTrue(InstalledAppsScanner.isHiddenSystemUtility("C:\\Windows\\System32\\cmd.exe"))
        assertFalse(InstalledAppsScanner.isHiddenSystemUtility("C:\\Apps\\Chrome.exe"))
    }

    @Test
    fun acceptsAReadableNormalExecutable() {
        val executable = createFile("Apps/FocusApp.exe")

        assertNull(
            InstalledAppsScanner.classifyLauncherCandidate(
                executable.name,
                executable.absolutePath
            )
        )
    }

    @Test
    fun installerIconCanResolveToTheKnownMainExecutable() {
        val updater = createFile("Discord/Update.exe")
        val discord = createFile("Discord/app-1.2.3/Discord.exe")

        val resolved = InstalledAppsScanner.findMainExecutableCandidate(
            displayName = "Discord",
            candidates = sequenceOf(updater, discord)
        )

        assertEquals(discord.absoluteFile, resolved)
    }

    @Test
    fun parsesQuotedAndIndexedDisplayIconPaths() {
        assertEquals(
            "C:\\Program Files\\Example App\\example.exe",
            executablePathFromDisplayIcon("\"C:\\Program Files\\Example App\\example.exe\",0")
        )
        assertEquals(
            "D:\\Apps\\example.exe",
            executablePathFromDisplayIcon("D:\\Apps\\example.exe,0")
        )
        assertNull(executablePathFromDisplayIcon("C:\\Apps\\missing.ico"))
    }

    private fun createFile(relativePath: String): java.io.File {
        val file = tempDir.resolve(relativePath).toFile()
        file.parentFile?.mkdirs()
        file.writeText("test executable fixture")
        return file
    }
}
