package com.focusflow.data

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatabaseTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun initUsesProvidedFileAndCanResetForTest() {
        val dbFile = tempDir.resolve("isolated-focusflow.db").toFile()
        try {
            Database.init(dbFile = dbFile, allowRecovery = false)

            assertTrue(Database.isReady)
            assertTrue(dbFile.exists())
        } finally {
            Database.resetForTest()
        }

        assertFalse(Database.isReady)
    }
}
