package com.focusflow.services

import com.focusflow.data.Database
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BackupService {

    private val sessionCsvHeaders = listOf(
        "id", "task_name", "start_time", "end_time", "planned_minutes",
        "actual_minutes", "completed", "interrupted", "notes"
    )
    private val sessionFormulaColumns = setOf(1, 8)

    private val taskCsvHeaders = listOf(
        "id", "title", "description", "duration_minutes", "scheduled_date",
        "scheduled_time", "completed", "priority", "tags", "created_at"
    )
    private val taskFormulaColumns = setOf(1, 2, 4, 5, 8)

    fun exportToCsv(): CsvExportResult = try {
        exportSessionsTo(chooseCsvTarget(
            title = "Save Session Export",
            filename = "focusflow_sessions_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.csv"
        ))
    } catch (failure: Exception) {
        CsvExportResult.Failed("Could not open the session export dialog: ${failure.message ?: failure.javaClass.simpleName}")
    }

    fun exportTasksToCsv(): CsvExportResult = try {
        exportTasksTo(chooseCsvTarget(
            title = "Save Tasks Export",
            filename = "focusflow_tasks_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.csv"
        ))
    } catch (failure: Exception) {
        CsvExportResult.Failed("Could not open the task export dialog: ${failure.message ?: failure.javaClass.simpleName}")
    }

    fun exportSessionsTo(target: File?): CsvExportResult {
        if (target == null) return CsvExportResult.Cancelled
        return try {
            val sessions = Database.getRecentSessions(Int.MAX_VALUE)
            CsvExport.write(
                target = target,
                headers = sessionCsvHeaders,
                rows = sessions.map { session ->
                    listOf(
                        session.id,
                        session.taskName,
                        session.startTime.toString(),
                        session.endTime?.toString().orEmpty(),
                        session.plannedMinutes.toString(),
                        session.actualMinutes.toString(),
                        session.completed.toString(),
                        session.interrupted.toString(),
                        session.notes
                    )
                },
                formulaProtectedColumns = sessionFormulaColumns
            )
        } catch (failure: Exception) {
            CsvExportResult.Failed(
                "Could not read focus sessions: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    fun exportTasksTo(target: File?): CsvExportResult {
        if (target == null) return CsvExportResult.Cancelled
        return try {
            val tasks = Database.getTasks()
            CsvExport.write(
                target = target,
                headers = taskCsvHeaders,
                rows = tasks.map { task ->
                    listOf(
                        task.id,
                        task.title,
                        task.description,
                        task.durationMinutes.toString(),
                        task.scheduledDate?.toString().orEmpty(),
                        task.scheduledTime.orEmpty(),
                        task.completed.toString(),
                        task.priority,
                        task.tags.joinToString("|"),
                        task.createdAt.toString()
                    )
                },
                formulaProtectedColumns = taskFormulaColumns
            )
        } catch (failure: Exception) {
            CsvExportResult.Failed(
                "Could not read tasks: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    private fun chooseCsvTarget(title: String, filename: String): File? {
        val frame = Frame()
        try {
            val dialog = FileDialog(frame, title, FileDialog.SAVE)
            try {
                dialog.file = filename
                dialog.isVisible = true
                val directory = dialog.directory ?: return null
                val selectedName = dialog.file ?: return null
                return File(directory, selectedName).absoluteFile
            } finally {
                dialog.dispose()
            }
        } finally {
            frame.dispose()
        }
    }
}
