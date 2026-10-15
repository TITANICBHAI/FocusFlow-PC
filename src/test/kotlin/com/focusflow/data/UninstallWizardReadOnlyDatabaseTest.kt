package com.focusflow.data

import com.focusflow.data.models.FocusSession
import com.focusflow.testutil.TempHome
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

class UninstallWizardReadOnlyDatabaseTest {

    @Test
    fun readOnlyOpenLeavesDatabaseAndSidecarsByteAndTimestampIdentical() = withDatabase { home, dbFile ->
        assertEquals(
            Database.SettingWrite.Ok,
            Database.writeSetting("always_on_enforcement", "true")
        )
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { writer ->
            writer.createStatement().use { statement ->
                assertEquals("wal", statement.executeQuery("PRAGMA journal_mode=WAL").use { rs ->
                    assertTrue(rs.next())
                    rs.getString(1)
                })
            }
            writer.createStatement().use {
                it.executeUpdate(
                    "UPDATE settings SET value = 'true' WHERE key = 'always_on_enforcement'"
                )
            }
            val wal = File("${dbFile.absolutePath}-wal")
            val shm = File("${dbFile.absolutePath}-shm")
            assertTrue(wal.isFile, "the test fixture must have a WAL sidecar")
            assertTrue(shm.isFile, "the test fixture must have an SHM sidecar")
            val before = snapshotDatabaseSet(dbFile)

            assertIs<DbInitResult.Ready>(Database.initReadOnlyForWizard(dbFile))
            assertEquals(
                Database.SettingRead.Present("true"),
                Database.readSetting("always_on_enforcement")
            )
            Database.close()

            assertDatabaseSetUnchanged(before, snapshotDatabaseSet(dbFile))
        }
        assertTrue(home.toFile().isDirectory)
    }

    @Test
    @Tag("stress")
    fun threeHundredReadOnlyWizardOpensSucceedDuringRapidWrites() = withDatabase { _, dbFile ->
        assertEquals(
            Database.SettingWrite.Ok,
            Database.writeSetting("always_on_enforcement", "true")
        )
        Database.close()

        val stopWriter = AtomicBoolean(false)
        val writerFailure = AtomicReference<Throwable?>(null)
        val writerStarted = CountDownLatch(1)
        val writer = Thread({
            try {
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                    connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                    writerStarted.countDown()
                    var revision = 0
                    while (!stopWriter.get()) {
                        connection.prepareStatement(
                            "UPDATE settings SET value = ? WHERE key = ?"
                        ).use { statement ->
                            statement.setString(1, if (revision++ % 2 == 0) "true" else "false")
                            statement.setString(2, "always_on_enforcement")
                            statement.executeUpdate()
                        }
                        Thread.sleep(1)
                    }
                }
            } catch (failure: Throwable) {
                writerFailure.set(failure)
                writerStarted.countDown()
            }
        }, "F11-test-writer").apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS), "writer did not start")
            repeat(300) { index ->
                assertIs<DbInitResult.Ready>(
                    Database.initReadOnlyForWizard(dbFile),
                    "wizard open $index should succeed while the writer is active"
                )
                assertIs<Database.SettingRead.Present>(
                    Database.readSetting("always_on_enforcement")
                )
                Database.close()
            }
        } finally {
            stopWriter.set(true)
            writer.join(5_000)
        }

        assertFalse(writer.isAlive, "writer did not stop")
        writerFailure.get()?.let { throw AssertionError("concurrent writer failed", it) }
    }

    @Test
    fun readOnlyOpenReadsProtectionAndPinSettingsWithoutMigratingV9Fixture() = withDatabase { _, dbFile ->
        val protectedSettings = linkedMapOf(
            "standalone_block_until" to "123456789",
            "standalone_block_start" to "123450000",
            "standalone_block_processes" to "editor.exe",
            "always_on_enforcement" to "true",
            "nuclear_mode" to "true",
            "global_pin_hash" to "global-hash",
            "session_pin_hash" to "session-hash",
            "nuclear_pin_hash" to "nuclear-hash"
        )
        protectedSettings.forEach { (key, value) ->
            assertEquals(Database.SettingWrite.Ok, Database.writeSetting(key, value))
        }
        val startTime = LocalDateTime.now().minusHours(1)
        Database.insertSession(
            FocusSession(
                id = "wizard-session",
                taskId = null,
                taskName = "active focus session",
                startTime = startTime,
                endTime = null,
                plannedMinutes = 25,
                actualMinutes = 0,
                completed = false,
                interrupted = false
            )
        )
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 9") }
        }

        assertIs<DbInitResult.Ready>(Database.initReadOnlyForWizard(dbFile))
        protectedSettings.forEach { (key, value) ->
            assertEquals(Database.SettingRead.Present(value), Database.readSetting(key), key)
        }
        assertTrue(Database.hasUnfinishedFocusSession())
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(9, resultSet.getInt(1))
                }
            }
        }
    }

    @Test
    fun missingDatabaseIsAllowedWithoutCreatingDatabaseOrParentDirectory() = TempHome.withHome { home ->
        val dbFile = home.resolve("missing/profile/focusflow.db").toFile()

        assertIs<DbInitResult.Ready>(Database.initReadOnlyForWizard(dbFile))

        assertFalse(dbFile.exists())
        assertFalse(dbFile.parentFile.exists())
        assertFalse(Database.isReady)
    }

    private fun withDatabase(test: (java.nio.file.Path, File) -> Unit) {
        TempHome.withHome { home ->
            val dbFile = home.resolve("focusflow.db").toFile()
            assertIs<DbInitResult.Ready>(
                Database.init(dbFile = dbFile, allowRecovery = false)
            )
            test(home, dbFile)
        }
    }

    private data class FileSnapshot(
        val bytes: ByteArray,
        val modified: FileTime
    )

    private fun snapshotDatabaseSet(dbFile: File): Map<String, FileSnapshot> =
        listOf(
            dbFile,
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm")
        ).filter(File::isFile).associate { file ->
            file.name to FileSnapshot(
                bytes = Files.readAllBytes(file.toPath()),
                modified = Files.getLastModifiedTime(file.toPath())
            )
        }

    private fun assertDatabaseSetUnchanged(
        before: Map<String, FileSnapshot>,
        after: Map<String, FileSnapshot>
    ) {
        assertEquals(before.keys, after.keys, "database, WAL, and SHM file set changed")
        before.forEach { (name, snapshot) ->
            val afterSnapshot = assertNotNull(after[name])
            assertContentEquals(snapshot.bytes, afterSnapshot.bytes, "$name bytes changed")
            assertEquals(snapshot.modified, afterSnapshot.modified, "$name modification time changed")
        }
    }
}
