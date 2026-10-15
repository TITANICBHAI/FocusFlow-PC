package com.focusflow

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AppPathsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun f25aPathGettersFollowChangingUserHome() {
        val previousHome = System.getProperty("user.home")
        val homes = listOf(
            tempDir.resolve("first-home").toString(),
            tempDir.resolve("second-home").toString()
        )

        try {
            homes.forEach { home ->
                System.setProperty("user.home", home)

                val expectedDataDir = File(home, ".focusflow")
                assertEquals(expectedDataDir, AppPaths.dataDir)
                assertEquals(
                    File(File(home, ".focusflow"), "focusflow.db"),
                    AppPaths.dbFile
                )
                assertEquals(File(expectedDataDir, "backups"), AppPaths.backupDir)
                assertEquals(expectedDataDir, AppPaths.logDir)
                assertEquals(File(expectedDataDir, "logs"), AppPaths.crashReportDir)
                assertEquals(File(expectedDataDir, "telemetry-consent"), AppPaths.consentFile)
                assertEquals(File(expectedDataDir, "native"), AppPaths.nativeDir)
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", previousHome)
            }
        }
    }

    @Test
    fun f25bAppDataDirectoryLiteralsAreCentralizedExceptTheSettingsDisplay() {
        val sourceRoot = Path.of("src/main/kotlin")
        assertTrue(Files.isDirectory(sourceRoot), "Kotlin source root is missing")

        val appDataDirectoryLiteral =
            Regex("""(?<![A-Za-z0-9_.-])\.focusflow(?=[/\\]|["']|$)""")
        val violations = mutableListOf<String>()

        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { sourceFile ->
                    val relativePath = sourceRoot.relativize(sourceFile)
                        .toString()
                        .replace('\\', '/')
                    if (relativePath == "com/focusflow/AppPaths.kt") return@forEach

                    Files.readAllLines(sourceFile).forEachIndexed { index, line ->
                        val isSettingsDisplay =
                            relativePath == "com/focusflow/ui/screens/SettingsScreen.kt" &&
                                line.contains("Database: SQLite at %USERPROFILE%")
                        if (!isSettingsDisplay && appDataDirectoryLiteral.containsMatchIn(line)) {
                            violations += "$relativePath:${index + 1}: $line"
                        }
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "App data directory path literals must use AppPaths:\n${violations.joinToString("\n")}"
        )
    }
}
