package com.focusflow.data

import com.focusflow.testutil.ForkedJvm
import com.focusflow.testutil.TempHome
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteJDBCLoader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class NativeSqliteBootstrapTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun triesIndependentCandidateDirectoriesAndSucceedsOnThird() {
        TempHome.withHome { home ->
            val bytes = byteArrayOf(7, 11, 19, 23)
            val candidates = (1..3).map { index ->
                NativeSqliteCandidate("candidate-$index", File(home.toFile(), "native-$index"))
            }
            val loadPaths = mutableListOf<String>()
            NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
                candidates = candidates,
                resource = NativeSqliteResource(
                    directoryPath = "org/sqlite/native/test/x86_64",
                    fileName = "libsqlitejdbc.so",
                    bytes = bytes
                ),
                systemLoad = { path ->
                    loadPaths += path
                    if (loadPaths.size < 3) throw UnsatisfiedLinkError("Access is denied for $path")
                },
                candidateInitialize = { true }
            )

            val loaded = assertIs<NativeSqliteStatus.Loaded>(NativeSqlite.ensureLoaded())
            assertEquals(3, loaded.attempts.size)
            assertEquals(3, loadPaths.size)
            assertEquals("candidate-1", loaded.attempts[0].candidate)
            assertEquals("java.lang.UnsatisfiedLinkError", loaded.attempts[0].failureClass)
            assertTrue(loaded.attempts[0].failureMessage.orEmpty().startsWith("Access is denied"))
            assertEquals("candidate-2", loaded.attempts[1].candidate)
            assertEquals("java.lang.UnsatisfiedLinkError", loaded.attempts[1].failureClass)
            assertEquals("candidate-3", loaded.attempts[2].candidate)
            assertEquals(null, loaded.attempts[2].failureClass)
            assertEquals(loadPaths.last(), loaded.path)
            assertContentEquals(bytes, Files.readAllBytes(Path.of(loaded.path)))
        }
    }

    @Test
    fun writableCandidateLoadsBeforeUnavailableDefaultTempAndRunsSelectOne() {
        val unavailableDefaultTemp = tempDir.resolve("sqlite-temp-does-not-exist")
        assertFalse(Files.exists(unavailableDefaultTemp))

        val result = ForkedJvm.run(
            mainClass = NativeSqliteForkedProbe::class.java.name,
            jvmArgs = listOf("-Dorg.sqlite.tmpdir=$unavailableDefaultTemp"),
            args = listOf("candidate-ready"),
            timeout = Duration.ofSeconds(90)
        )

        assertForkedSuccess(result, "F1B_OK")
        assertFalse(Files.exists(unavailableDefaultTemp))
    }

    @Test
    fun defaultLoaderCanRecoverAfterExplicitCandidatesFail() {
        val result = ForkedJvm.run(
            mainClass = NativeSqliteForkedProbe::class.java.name,
            args = listOf("default-fallback"),
            timeout = Duration.ofSeconds(90)
        )

        assertForkedSuccess(result, "F1_DEFAULT_OK")
    }

    @Test
    fun failedCandidatesAndDefaultFallbackLeaveDatabaseAndNativeLatchUntouched() {
        val unavailableDefaultTemp = tempDir.resolve("sqlite-temp-does-not-exist")
        assertFalse(Files.exists(unavailableDefaultTemp))

        val result = ForkedJvm.run(
            mainClass = NativeSqliteForkedProbe::class.java.name,
            jvmArgs = listOf("-Dorg.sqlite.tmpdir=$unavailableDefaultTemp"),
            args = listOf("all-fail"),
            timeout = Duration.ofSeconds(90)
        )

        assertForkedSuccess(result, "F1C_OK")
        assertFalse(Files.exists(unavailableDefaultTemp))
    }

    @Test
    fun linuxNoExecCandidateReturnsTypedFailureWhenSuchMountIsAvailable() {
        assumeTrue(
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true),
            "Skipped: the noexec SQLite load check is Linux-only"
        )
        val noExecDirectory = createWritableNoExecDirectory()
        assumeTrue(noExecDirectory != null, "Skipped: no writable noexec mount is available")

        try {
            val result = ForkedJvm.run(
                mainClass = NativeSqliteForkedProbe::class.java.name,
                args = listOf("noexec", noExecDirectory!!.toString()),
                timeout = Duration.ofSeconds(90)
            )
            assertForkedSuccess(result, "F1D_OK")
        } finally {
            noExecDirectory?.let(::deleteTree)
        }
    }

    private fun assertForkedSuccess(result: ForkedJvm.Result, marker: String) {
        assertEquals(
            0,
            result.exitCode,
            "Forked JVM failed.\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
        assertTrue(result.stdout.contains(marker), "Missing $marker in stdout:\n${result.stdout}")
    }

    private fun createWritableNoExecDirectory(): Path? {
        val mountsFile = Path.of("/proc/mounts")
        if (!Files.isReadable(mountsFile)) return null

        val noExecMounts = runCatching { Files.readAllLines(mountsFile) }
            .getOrNull()
            .orEmpty()
            .mapNotNull { line ->
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 4 || "noexec" !in fields[3].split(',')) return@mapNotNull null
                fields[1]
                    .replace("\\040", " ")
                    .replace("\\011", "\t")
                    .replace("\\134", "\\")
                    .let { runCatching { Path.of(it) }.getOrNull() }
            }
            .sortedByDescending { it.nameCount }

        noExecMounts.forEach { mount ->
            if (!Files.isDirectory(mount) || !Files.isWritable(mount)) return@forEach
            var candidate: Path? = null
            var mountWriteProbe: Path? = null
            try {
                mountWriteProbe = Files.createTempFile(mount, "focusflow-noexec-write-probe-", ".tmp")
                Files.deleteIfExists(mountWriteProbe)
                mountWriteProbe = null
                candidate = Files.createTempDirectory(mount, "focusflow-native-noexec-")
                val writeProbe = Files.createTempFile(candidate, "write-probe-", ".tmp")
                Files.deleteIfExists(writeProbe)
                return candidate
            } catch (_: Exception) {
                mountWriteProbe?.let { runCatching { Files.deleteIfExists(it) } }
                candidate?.let(::deleteTree)
            }
        }
        return null
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}

internal object NativeSqliteForkedProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "candidate-ready" -> candidateReady()
            "default-fallback" -> defaultFallback()
            "all-fail" -> allFail()
            "noexec" -> noExec(args.getOrNull(1) ?: error("noexec candidate directory is missing"))
            else -> error("Unknown native SQLite probe mode")
        }
    }

    private fun candidateReady() {
        val home = File(System.getProperty("user.home"))
        val candidate = NativeSqliteCandidate(
            "forked user-home candidate",
            File(home, ".focusflow/native-candidate")
        )
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = listOf(candidate),
            cacheSuccessfulLoad = true
        )

        check(Database.init() == DbInitResult.Ready) { "Database did not become ready" }
        val loaded = assertIs<NativeSqliteStatus.Loaded>(NativeSqlite.ensureLoaded())
        check(loaded.how == candidate.label) { "Unexpected loader: ${loaded.how}" }
        check(selectOne() == 1) { "SELECT 1 did not return 1" }
        Database.close()
        println("F1B_OK")
    }

    private fun defaultFallback() {
        val home = File(System.getProperty("user.home"))
        val fallbackDirectory = File(home, ".focusflow/default-native-temp")
        check(fallbackDirectory.mkdirs() || fallbackDirectory.isDirectory)
        System.setProperty("org.sqlite.tmpdir", fallbackDirectory.absolutePath)

        val blocker = File(home, "native-candidate-blocker")
        blocker.writeText("not a directory")
        val candidates = listOf(
            NativeSqliteCandidate("blocked-1", File(blocker, "first")),
            NativeSqliteCandidate("blocked-2", File(blocker, "second"))
        )
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = candidates,
            defaultInitialize = { SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )

        check(Database.init() == DbInitResult.Ready) { "Database did not become ready via default loader" }
        val loaded = assertIs<NativeSqliteStatus.Loaded>(NativeSqlite.ensureLoaded())
        check(loaded.how == "sqlite-jdbc default loader") { "Unexpected loader: ${loaded.how}" }
        check(loaded.attempts.size == 3) { "Expected two candidates and one fallback" }
        check(selectOne() == 1) { "SELECT 1 did not return 1" }
        Database.close()
        println("F1_DEFAULT_OK")
    }

    private fun allFail() {
        val home = File(System.getProperty("user.home"))
        val dbDirectory = File(home, ".focusflow")
        check(dbDirectory.mkdirs() || dbDirectory.isDirectory)
        val dbFile = File(dbDirectory, "focusflow.db")
        val walFile = File(dbDirectory, "focusflow.db-wal")
        val shmFile = File(dbDirectory, "focusflow.db-shm")
        val dbBytes = byteArrayOf(1, 4, 9, 16)
        val walBytes = byteArrayOf(2, 3, 5)
        val shmBytes = byteArrayOf(8, 13, 21)
        dbFile.writeBytes(dbBytes)
        walFile.writeBytes(walBytes)
        shmFile.writeBytes(shmBytes)

        val blocker = File(home, "native-candidate-blocker")
        blocker.writeText("not a directory")
        val candidates = listOf(
            NativeSqliteCandidate("blocked-1", File(blocker, "first")),
            NativeSqliteCandidate("blocked-2", File(blocker, "second"))
        )
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = candidates,
            defaultInitialize = { SQLiteJDBCLoader.initialize() }
        )

        val first = assertIs<DbInitResult.Failed>(
            Database.init(dbFile = dbFile, allowRecovery = false)
        )
        val firstFailure = assertIs<NativeSqliteUnavailableException>(first.cause)
        check(firstFailure.requiresRestart)
        check(firstFailure.attempts.size >= 3) { "Expected two candidate attempts and a fallback attempt" }
        check(firstFailure.message.orEmpty().lineSequence().count() == 1) {
            "The short failure message must be one line"
        }
        check(!firstFailure.message.orEmpty().contains(home.absolutePath)) {
            "The failure message must redact the profile path"
        }

        val second = assertIs<DbInitResult.Failed>(
            Database.init(dbFile = dbFile, allowRecovery = false)
        )
        val secondFailure = assertIs<NativeSqliteUnavailableException>(second.cause)
        check(firstFailure !== secondFailure) { "A failed load must not be memoized" }
        check(secondFailure.attempts.size >= 3)
        check(!nativeDbIsLoaded()) { "A failed bootstrap must not set NativeDB.isLoaded" }

        check(dbFile.readBytes().contentEquals(dbBytes)) { "Database bytes changed on native-load failure" }
        check(walFile.readBytes().contentEquals(walBytes)) { "WAL bytes changed on native-load failure" }
        check(shmFile.readBytes().contentEquals(shmBytes)) { "SHM bytes changed on native-load failure" }
        println("F1C_OK")
    }

    private fun noExec(directory: String) {
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = listOf(NativeSqliteCandidate("Linux noexec mount", File(directory))),
            defaultInitialize = { false }
        )
        val unavailable = assertIs<NativeSqliteStatus.Unavailable>(NativeSqlite.ensureLoaded())
        check(unavailable.failure.requiresRestart)
        val candidateAttempt = unavailable.failure.attempts.first()
        check(candidateAttempt.failureClass == "java.lang.UnsatisfiedLinkError") {
            "Expected noexec UnsatisfiedLinkError; got ${candidateAttempt.failureClass}: " +
                "${candidateAttempt.failureMessage}\n${unavailable.failure.diagnostics}"
        }
        check(!nativeDbIsLoaded())
        println("F1D_OK")
    }

    private fun selectOne(): Int {
        val connectionField = Database::class.java.getDeclaredField("conn").apply {
            isAccessible = true
        }
        val connection = connectionField.get(Database) as java.sql.Connection
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1").use { resultSet ->
                check(resultSet.next())
                return resultSet.getInt(1)
            }
        }
    }

    private fun nativeDbIsLoaded(): Boolean {
        val nativeDbClass = Class.forName("org.sqlite.core.NativeDB")
        val loadedField = nativeDbClass.getDeclaredField("isLoaded").apply {
            isAccessible = true
        }
        return loadedField.getBoolean(null)
    }
}
