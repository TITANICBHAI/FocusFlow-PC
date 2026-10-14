package com.focusflow.data

import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class SettingsReadWriteTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = emptyList(),
            defaultInitialize = { org.sqlite.SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    @Test
    fun databaseNotReadyIsUnreadableAndWritesReturnFailure() {
        val unavailable = assertIs<Database.SettingRead.Unreadable>(
            Database.readSetting("setting")
        )
        assertIs<DatabaseUnavailableException>(unavailable.cause)

        val singleWrite = assertIs<Database.SettingWrite.Failed>(
            Database.writeSetting("setting", "value")
        )
        assertIs<DatabaseUnavailableException>(singleWrite.cause)

        val batchWrite = assertIs<Database.SettingWrite.Failed>(
            Database.writeSettings(mapOf("setting" to "value"))
        )
        assertIs<DatabaseUnavailableException>(batchWrite.cause)
    }

    @Test
    fun readAndWriteDistinguishAbsentFromPresent() {
        val dbFile = tempDir.resolve("settings.db").toFile()
        init(dbFile)

        assertEquals(Database.SettingRead.Absent, Database.readSetting("not-created"))
        assertEquals(
            Database.SettingWrite.Ok,
            Database.writeSetting("one", "first")
        )
        assertEquals(Database.SettingRead.Present("first"), Database.readSetting("one"))

        assertEquals(
            Database.SettingWrite.Ok,
            Database.writeSettings(linkedMapOf("two" to "second", "three" to "third"))
        )
        assertEquals(Database.SettingRead.Present("second"), Database.readSetting("two"))
        assertEquals(Database.SettingRead.Present("third"), Database.readSetting("three"))
    }

    @Test
    fun batchWriteRollsBackEarlierPairsWhenALaterWriteFails() {
        val dbFile = tempDir.resolve("settings-atomic.db").toFile()
        init(dbFile)
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TRIGGER reject_f12_setting
                    BEFORE INSERT ON settings
                    WHEN NEW.key = 'reject'
                    BEGIN
                        SELECT RAISE(ABORT, 'injected settings write failure');
                    END
                    """.trimIndent()
                )
            }
        }

        val result = assertIs<Database.SettingWrite.Failed>(
            Database.writeSettings(linkedMapOf("first" to "must-rollback", "reject" to "fail"))
        )
        assertTrue(result.cause.message.orEmpty().contains("injected settings write failure"))
        assertNull(readSetting(dbFile, "first"))
        assertNull(readSetting(dbFile, "reject"))
    }

    @Test
    fun injectedUnavailableStoreIsUsedByTypedSettingsApi() {
        val unavailable = UnavailableSettingsStore()
        Database.settingsStoreForTest = unavailable

        assertEquals(
            Database.SettingRead.Unreadable(unavailable.failure),
            Database.readSetting("key")
        )
        assertEquals(
            Database.SettingWrite.Failed(unavailable.failure),
            Database.writeSetting("key", "value")
        )
        assertEquals(
            Database.SettingWrite.Failed(unavailable.failure),
            Database.writeSettings(mapOf("key" to "value"))
        )
    }

    private fun init(dbFile: java.io.File) {
        assertIs<DbInitResult.Ready>(
            Database.init(
                dbFile = dbFile,
                policy = InitPolicy(
                    busyTimeoutMs = 200,
                    maxAttempts = 1,
                    backoffMs = emptyList(),
                    totalDeadlineMs = 2_000
                ),
                allowRecovery = false
            )
        )
    }

    private fun readSetting(dbFile: java.io.File, key: String): String? =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.prepareStatement("SELECT value FROM settings WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString(1) else null
                }
            }
        }

    private class UnavailableSettingsStore : SettingsStore {
        val failure = DatabaseUnavailableException(null)

        override fun readSetting(key: String) =
            Database.SettingRead.Unreadable(failure)

        override fun writeSetting(key: String, value: String) =
            Database.SettingWrite.Failed(failure)

        override fun writeSettings(pairs: Map<String, String>) =
            Database.SettingWrite.Failed(failure)
    }
}
