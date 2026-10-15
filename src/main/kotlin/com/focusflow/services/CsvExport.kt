package com.focusflow.services

import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

sealed interface CsvExportResult {
    data class Saved(val file: File, val rows: Int) : CsvExportResult
    data object Cancelled : CsvExportResult
    data class Failed(val message: String) : CsvExportResult
}

/**
 * Shared CSV writer for user exports.
 *
 * It quotes every field, doubles embedded quotes, uses RFC-4180 CRLF record
 * separators, and normalizes embedded newlines to spaces to preserve the
 * existing export convention. Files are written without a UTF-8 BOM.
 */
object CsvExport {
    private const val RECORD_SEPARATOR = "\r\n"

    fun encode(
        headers: List<String>,
        rows: Iterable<List<String>>,
        formulaProtectedColumns: Set<Int> = emptySet()
    ): String = buildString {
        appendRecord(this, headers, emptySet())
        rows.forEach { row ->
            require(row.size == headers.size) {
                "CSV row has ${row.size} fields but header has ${headers.size}"
            }
            appendRecord(this, row, formulaProtectedColumns)
        }
    }

    fun write(
        target: File?,
        headers: List<String>,
        rows: Iterable<List<String>>,
        formulaProtectedColumns: Set<Int> = emptySet()
    ): CsvExportResult {
        if (target == null) return CsvExportResult.Cancelled

        var temporary: java.nio.file.Path? = null
        return try {
            val destination = target.absoluteFile.toPath()
            val parent = destination.parent
                ?: return CsvExportResult.Failed("The export path has no parent directory.")
            Files.createDirectories(parent)
            val temporaryFile = Files.createTempFile(parent, ".focusflow-csv-", ".tmp")
            temporary = temporaryFile

            var rowCount = 0
            Files.newBufferedWriter(
                temporaryFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { writer ->
                writeRecord(writer, headers, emptySet())
                rows.forEach { row ->
                    require(row.size == headers.size) {
                        "CSV row has ${row.size} fields but header has ${headers.size}"
                    }
                    writeRecord(writer, row, formulaProtectedColumns)
                    rowCount++
                }
            }

            try {
                Files.move(
                    temporaryFile,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
            CsvExportResult.Saved(destination.toFile(), rowCount)
        } catch (failure: Exception) {
            val cleanupFailure = temporary?.let { temp ->
                try {
                    Files.deleteIfExists(temp)
                    null
                } catch (cleanup: Exception) {
                    cleanup
                }
            }
            val primaryMessage = failure.message ?: failure.javaClass.simpleName
            val cleanupMessage = cleanupFailure?.let {
                " Temporary-file cleanup also failed: ${it.javaClass.simpleName}: " +
                    (it.message ?: "no detail")
            }.orEmpty()
            CsvExportResult.Failed("$primaryMessage$cleanupMessage")
        }
    }

    private fun appendRecord(
        output: StringBuilder,
        fields: List<String>,
        formulaProtectedColumns: Set<Int>
    ) {
        fields.forEachIndexed { index, value ->
            if (index > 0) output.append(',')
            output.append(quotedField(value, index in formulaProtectedColumns))
        }
        output.append(RECORD_SEPARATOR)
    }

    private fun writeRecord(
        writer: BufferedWriter,
        fields: List<String>,
        formulaProtectedColumns: Set<Int>
    ) {
        fields.forEachIndexed { index, value ->
            if (index > 0) writer.write(",")
            writer.write(quotedField(value, index in formulaProtectedColumns))
        }
        writer.write(RECORD_SEPARATOR)
    }

    private fun quotedField(value: String, formulaProtectionEnabled: Boolean): String {
        val normalized = value
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
        val safeValue = if (formulaProtectionEnabled && startsWithFormulaAfterWhitespace(normalized)) {
            "'$normalized"
        } else {
            normalized
        }
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }

    private fun startsWithFormulaAfterWhitespace(value: String): Boolean {
        val firstSignificant = value.firstOrNull { character ->
            character.code > 0x20 && !character.isWhitespace() && character != '\uFEFF'
        }
        return firstSignificant == '=' || firstSignificant == '+' ||
            firstSignificant == '-' || firstSignificant == '@'
    }
}
