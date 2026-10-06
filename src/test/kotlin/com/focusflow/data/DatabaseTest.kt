package com.focusflow.data

import com.focusflow.data.models.DailyAllowance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatabaseTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun initUsesProvidedFileIsIdempotentAndCanResetForTest() {
        val dbFile = tempDir.resolve("isolated-focusflow.db").toFile()
        try {
            assertIs<DbInitResult.Ready>(Database.init(dbFile = dbFile, allowRecovery = false))
            assertTrue(Database.isReady)
            assertTrue(dbFile.exists())

            assertIs<DbInitResult.Ready>(Database.init(dbFile = dbFile, allowRecovery = false))
            assertTrue(Database.isReady)
        } finally {
            Database.resetForTest()
        }

        assertFalse(Database.isReady)
    }

    @Test
    fun accessWithoutAnInitializedConnectionThrowsTypedUnavailableException() {
        Database.resetForTest()

        val error = runCatching { Database.getDailyAllowances() }.exceptionOrNull()
        assertIs<DatabaseUnavailableException>(error)
    }

    @Test
    fun classifiesPrimaryAndExtendedSqliteResultCodesWithoutTreatingIoAsCorruption() {
        val cases = listOf(
            5 to SqliteFailureKind.BUSY,
            261 to SqliteFailureKind.BUSY,
            6 to SqliteFailureKind.LOCKED,
            262 to SqliteFailureKind.LOCKED,
            11 to SqliteFailureKind.CORRUPT,
            26 to SqliteFailureKind.NOTADB,
            10 to SqliteFailureKind.IOERR,
            14 to SqliteFailureKind.CANTOPEN,
            13 to SqliteFailureKind.FULL,
            8 to SqliteFailureKind.OTHER
        )

        cases.forEach { (code, expected) ->
            assertEquals(expected, classifySqliteFailure(SQLException("test", "SQLITE_TEST", code)), "code=$code")
        }
        assertEquals(
            SqliteFailureKind.BUSY,
            classifySqliteFailure(SQLException("SQLITE_BUSY: database is locked"))
        )
    }

    @Test
    fun exclusiveLockReturnsBusyWithoutChangingDatabaseOrCreatingRecoveryCopies() {
        val dbFile = tempDir.resolve("locked.db").toFile()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { holder ->
            holder.createStatement().use { it.execute("PRAGMA journal_mode=DELETE") }
            holder.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
            val before = snapshotDatabaseSet(dbFile)

            try {
                val result = Database.init(
                    dbFile = dbFile,
                    policy = InitPolicy(
                        busyTimeoutMs = 20,
                        maxAttempts = 2,
                        backoffMs = listOf(0),
                        totalDeadlineMs = 1_000
                    ),
                    allowRecovery = true
                )

                val busy = assertIs<DbInitResult.Busy>(result)
                assertEquals(2, busy.attempts)
                assertFalse(Database.isReady)
                assertEquals(before, snapshotDatabaseSet(dbFile))
                assertTrue(tempDir.toFile().listFiles().orEmpty().none { ".broken_" in it.name })
            } finally {
                holder.createStatement().use { it.execute("ROLLBACK") }
                Database.resetForTest()
            }
        }
    }

    @Test
    fun releasingExclusiveLockDuringRetryAllowsInitialization() {
        val dbFile = tempDir.resolve("released-lock.db").toFile()
        val holder = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        holder.createStatement().use { it.execute("PRAGMA journal_mode=DELETE") }
        holder.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
        val released = CountDownLatch(1)
        val releaser = Thread({
            try {
                Thread.sleep(100)
                holder.close()
            } finally {
                released.countDown()
            }
        }, "Test-SQLite-Lock-Releaser").apply { start() }

        try {
            val result = Database.init(
                dbFile = dbFile,
                policy = InitPolicy(
                    busyTimeoutMs = 25,
                    maxAttempts = 4,
                    backoffMs = listOf(40, 40, 40),
                    totalDeadlineMs = 2_000
                ),
                allowRecovery = false
            )

            assertIs<DbInitResult.Ready>(result)
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertTrue(Database.isReady)
        } finally {
            holder.close()
            releaser.join(2_000)
            Database.resetForTest()
        }
    }

    @Test
    fun failedMigrationNeverPublishesOrLeavesAClosedConnectionReady() {
        val dbFile = tempDir.resolve("migration-fails.db").toFile()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY)") }
            connection.createStatement().use { it.execute("PRAGMA user_version=0") }
        }
        try {
            val result = Database.init(
                dbFile = dbFile,
                policy = InitPolicy(busyTimeoutMs = 50, maxAttempts = 1, backoffMs = emptyList()),
                allowRecovery = false
            )

            val failed = assertIs<DbInitResult.Failed>(result)
            assertEquals(dbFile.absoluteFile, failed.untouchedPath.absoluteFile)
            assertFalse(Database.isReady)
            assertTrue(dbFile.exists())
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                assertEquals("ok", connection.createStatement()
                    .executeQuery("PRAGMA quick_check").use { rs -> rs.getString(1) })
            }
            assertIs<DatabaseUnavailableException>(runCatching { Database.getDailyAllowances() }.exceptionOrNull())
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun recoveryPreservesDatabaseWalAndShmAsOneVerifiedSet() {
        val dbFile = tempDir.resolve("corrupt-set.db").toFile()
        val originals = mapOf(
            dbFile to "invalid database".toByteArray(),
            java.io.File("${dbFile.absolutePath}-wal") to "wal evidence".toByteArray(),
            java.io.File("${dbFile.absolutePath}-shm") to "shm evidence".toByteArray()
        )
        originals.forEach { (file, bytes) -> Files.write(file.toPath(), bytes) }

        try {
            assertIs<DbInitResult.Ready>(
                Database.init(
                    dbFile = dbFile,
                    policy = InitPolicy(busyTimeoutMs = 50, maxAttempts = 1, backoffMs = emptyList()),
                    allowRecovery = true
                )
            )

            val backups = tempDir.toFile().listFiles().orEmpty()
                .filter { ".broken_" in it.name }
                .associateBy { it.name.substringBefore(".broken_") }
            assertEquals(originals.keys.map { it.name }.toSet(), backups.keys)
            originals.forEach { (original, bytes) ->
                assertTrue(bytes.contentEquals(backups.getValue(original.name).readBytes()), original.name)
            }
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun recoveryCopyFailureLeavesEveryOriginalDatabaseFileUntouched() {
        val dbFile = tempDir.resolve("copy-failure.db").toFile()
        val originals = mapOf(
            dbFile to "invalid database".toByteArray(),
            java.io.File("${dbFile.absolutePath}-wal") to "wal evidence".toByteArray(),
            java.io.File("${dbFile.absolutePath}-shm") to "shm evidence".toByteArray()
        )
        originals.forEach { (file, bytes) -> Files.write(file.toPath(), bytes) }
        val before = originals.keys.associateWith { file -> sha256(file.toPath()) }
        var copies = 0
        Database.recoveryFileOpsForTest = object : RecoveryFileOps {
            override fun copy(source: java.io.File, destination: java.io.File) {
                copies++
                if (copies == 2) throw java.io.IOException("simulated copy failure")
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            }

            override fun move(source: java.io.File, destination: java.io.File) {
                Files.move(source.toPath(), destination.toPath())
            }

            override fun delete(file: java.io.File) {
                Files.deleteIfExists(file.toPath())
            }
        }

        try {
            val result = Database.init(
                dbFile = dbFile,
                policy = InitPolicy(busyTimeoutMs = 50, maxAttempts = 1, backoffMs = emptyList()),
                allowRecovery = true
            )

            assertIs<DbInitResult.Failed>(result)
            originals.forEach { (file, _) ->
                assertEquals(before.getValue(file), sha256(file.toPath()), file.name)
            }
            assertTrue(tempDir.toFile().listFiles().orEmpty().none { ".broken_" in it.name })
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun recoveryArchivesCorruptDatabaseWithoutDeletingItsContents() {
        val dbFile = tempDir.resolve("corrupt.db").toFile()
        val originalBytes = "not a SQLite database".toByteArray()
        Files.write(dbFile.toPath(), originalBytes)

        try {
            val result = Database.init(
                dbFile = dbFile,
                policy = InitPolicy(busyTimeoutMs = 50, maxAttempts = 1, backoffMs = emptyList()),
                allowRecovery = true
            )

            assertIs<DbInitResult.Ready>(result)
            val archived = tempDir.toFile().listFiles().orEmpty()
                .firstOrNull { it.name.startsWith("corrupt.db.broken_") }
            assertNotNull(archived, "Expected a preserved copy of the corrupt database")
            assertTrue(originalBytes.contentEquals(archived.readBytes()))
            assertTrue(dbFile.length() > 0)
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun disabledRecoveryLeavesCorruptFileUntouchedAndReportsFailure() {
        val dbFile = tempDir.resolve("wizard-corrupt.db").toFile()
        val originalBytes = "unreadable database bytes".toByteArray()
        Files.write(dbFile.toPath(), originalBytes)

        try {
            val result = Database.init(
                dbFile = dbFile,
                policy = InitPolicy(busyTimeoutMs = 50, maxAttempts = 1, backoffMs = emptyList()),
                allowRecovery = false
            )

            val failed = assertIs<DbInitResult.Failed>(result)
            assertEquals(dbFile.absoluteFile, failed.untouchedPath.absoluteFile)
            assertTrue(originalBytes.contentEquals(dbFile.readBytes()))
            assertTrue(tempDir.toFile().listFiles().orEmpty().none { ".broken_" in it.name })
            assertFalse(Database.isReady)
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun migrationV9NormalizesAndDeduplicatesAllowanceAndUsageProcessNames() {
        val dbFile = tempDir.resolve("v8-case-variants.db").toFile()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE daily_allowances (
                        process_name TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        allowance_minutes INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE daily_usage (
                        date TEXT NOT NULL,
                        process_name TEXT NOT NULL,
                        seconds_used INTEGER NOT NULL,
                        PRIMARY KEY (date, process_name)
                    )
                    """.trimIndent()
                )
                statement.execute("INSERT INTO daily_allowances VALUES ('Discord.exe', 'Discord relaxed', 30)")
                statement.execute("INSERT INTO daily_allowances VALUES ('discord.exe', 'Discord strict', 10)")
                statement.execute("INSERT INTO daily_usage VALUES ('2026-01-02', 'Discord.exe', 45)")
                statement.execute("INSERT INTO daily_usage VALUES ('2026-01-02', 'discord.exe', 90)")
                statement.execute("PRAGMA user_version=8")
            }
        }

        try {
            assertIs<DbInitResult.Ready>(
                Database.init(dbFile = dbFile, allowRecovery = false)
            )

            assertEquals(
                listOf(DailyAllowance("discord.exe", "Discord strict", 10)),
                Database.getDailyAllowances()
            )
            assertEquals(
                mapOf("discord.exe" to 90L),
                Database.getDailyUsage(java.time.LocalDate.of(2026, 1, 2))
            )
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun allowanceUpsertStoresTheNormalizedProcessKey() {
        val dbFile = tempDir.resolve("normalized-allowance.db").toFile()
        try {
            assertIs<DbInitResult.Ready>(Database.init(dbFile = dbFile, allowRecovery = false))

            Database.upsertDailyAllowance(DailyAllowance(" Discord.EXE ", "Discord", 30))

            assertEquals(
                listOf(DailyAllowance("discord.exe", "Discord", 30)),
                Database.getDailyAllowances()
            )
        } finally {
            Database.resetForTest()
        }
    }

    @Test
    fun staleDailyUsageUpsertCannotLowerThePersistedTotal() {
        val dbFile = tempDir.resolve("monotonic-usage.db").toFile()
        val date = java.time.LocalDate.of(2026, 1, 2)
        try {
            assertIs<DbInitResult.Ready>(Database.init(dbFile = dbFile, allowRecovery = false))

            Database.upsertDailyUsage(date, "sample.exe", 120L)
            Database.upsertDailyUsage(date, "sample.exe", 90L)

            assertEquals(mapOf("sample.exe" to 120L), Database.getDailyUsage(date))
        } finally {
            Database.resetForTest()
        }
    }

    private fun snapshotDatabaseSet(dbFile: java.io.File): Map<String, String> =
        listOf(dbFile, java.io.File("${dbFile.absolutePath}-wal"), java.io.File("${dbFile.absolutePath}-shm"))
            .filter { it.exists() }
            .associate { it.name to sha256(it.toPath()) }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }
}
