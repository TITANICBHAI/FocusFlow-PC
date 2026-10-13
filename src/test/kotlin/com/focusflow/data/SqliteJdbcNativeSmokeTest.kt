package com.focusflow.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

class SqliteJdbcNativeSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun sqliteJdbcOpensAndQueriesATemporaryDatabaseOnThisRuntime() {
        val dbFile = tempDir.resolve("sqlite-native-smoke.db").toAbsolutePath()
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:$dbFile"
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE native_smoke (value TEXT NOT NULL)")
                statement.executeUpdate("INSERT INTO native_smoke(value) VALUES ('loaded')")
                statement.executeQuery("SELECT value FROM native_smoke").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("loaded", rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun sqliteJdbcJarContainsAnX64WindowsNativeLibrary() {
        val resourcePath = "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll"
        val stream = assertNotNull(
            SQLiteDataSource::class.java.classLoader.getResourceAsStream(resourcePath),
            "sqlite-jdbc is missing its Windows x64 native library resource"
        )
        val dll = stream.use { it.readBytes() }

        assertTrue(dll.size > 0x40, "Windows native library resource is unexpectedly small")
        assertEquals("MZ", String(dll, 0, 2, StandardCharsets.US_ASCII), "Missing PE/DOS signature")

        val peOffset = ByteBuffer.wrap(dll, 0x3C, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        assertTrue(peOffset >= 0 && peOffset + 6 <= dll.size, "Invalid PE header offset")
        assertEquals(
            "PE\u0000\u0000",
            String(dll, peOffset, 4, StandardCharsets.ISO_8859_1),
            "Missing PE signature"
        )

        val machine = ByteBuffer.wrap(dll, peOffset + 4, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xFFFF
        assertEquals(0x8664, machine, "Windows native library is not AMD64/x64")
    }
}
