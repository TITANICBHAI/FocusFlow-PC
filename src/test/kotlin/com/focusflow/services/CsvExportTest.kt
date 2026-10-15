package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.FocusSession
import com.focusflow.testutil.TempHome
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CsvExportTest {

    @Test
    fun f23aQuotesCsvFieldsProtectsFormulaTextAndRoundTripsUnicodeCommasAndQuotes() {
        val value = "Deep, \"focus\"\r\ncafé"
        val csv = CsvExport.encode(
            headers = listOf("text"),
            rows = listOf(listOf(value), listOf(" \t=2+2")),
            formulaProtectedColumns = setOf(0)
        )

        assertEquals(
            listOf(
                listOf("text"),
                listOf("Deep, \"focus\" café"),
                listOf("' \t=2+2")
            ),
            parseRfc4180(csv)
        )
        assertTrue(csv.contains("\"Deep, \"\"focus\"\" café\""))
        assertTrue(csv.startsWith("\"text\"\r\n"))
        assertFalse(csv.startsWith("\uFEFF"))
    }

    @Test
    fun f23bExportsAll1500SessionsAndReportsExactRowCount() {
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow/focusflow.db")
            Files.createDirectories(dbFile.parent)
            Database.init(dbFile.toFile(), allowRecovery = false)
            assertTrue(Database.isReady)

            val start = LocalDateTime.of(2026, 1, 1, 0, 0)
            repeat(1500) { index ->
                Database.insertSession(
                    FocusSession(
                        id = "session-$index",
                        taskId = null,
                        taskName = "Task $index",
                        startTime = start.plusMinutes(index.toLong()),
                        endTime = start.plusMinutes(index.toLong() + 25),
                        plannedMinutes = 25
                    )
                )
            }

            val target = home.resolve("sessions.csv").toFile()
            val result = BackupService.exportSessionsTo(target)

            val saved = assertIs<CsvExportResult.Saved>(result)
            assertEquals(1500, saved.rows)
            assertEquals(1501, parseRfc4180(Files.readString(target.toPath())).size)
            val bytes = Files.readAllBytes(target.toPath())
            assertFalse(bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        }
    }

    @Test
    fun f23cDistinguishesCancelledFromAnUnwritableTarget() {
        assertIs<CsvExportResult.Cancelled>(
            CsvExport.write(target = null, headers = listOf("value"), rows = emptyList())
        )

        TempHome.withHome { home ->
            val parentFile = home.resolve("not-a-directory")
            Files.writeString(parentFile, "occupied")
            val target = parentFile.resolve("sessions.csv").toFile()

            val result = CsvExport.write(
                target = target,
                headers = listOf("value"),
                rows = emptyList()
            )

            assertIs<CsvExportResult.Failed>(result)
        }
    }

    private fun parseRfc4180(csv: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < csv.length) {
            val char = csv[index]
            if (inQuotes) {
                if (char == '"') {
                    if (index + 1 < csv.length && csv[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(char)
                }
            } else {
                when (char) {
                    '"' -> inQuotes = true
                    ',' -> {
                        record.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {
                        record.add(field.toString())
                        field.setLength(0)
                        records.add(record)
                        record = mutableListOf()
                        if (index + 1 < csv.length && csv[index + 1] == '\n') index++
                    }
                    '\n' -> {
                        record.add(field.toString())
                        field.setLength(0)
                        records.add(record)
                        record = mutableListOf()
                    }
                    else -> field.append(char)
                }
            }
            index++
        }

        if (field.isNotEmpty() || record.isNotEmpty()) {
            record.add(field.toString())
            records.add(record)
        }
        return records
    }
}
