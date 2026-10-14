package com.focusflow

import com.focusflow.data.DbInitResult
import java.io.File
import java.io.IOException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertContains

class StartupFailureDetailsTest {
    @Test
    fun startupGateDetailsIncludeDeepCauseRetainedDatabaseAndLoaderDiagnostics() {
        val databasePath = File("/scratch/focusflow/focusflow.db")
        val reportPath = File("/scratch/focusflow/FocusFlow-crash-startup.log")
        val rootCause = IOException("native library access denied")
        val failure = DbInitResult.Failed(
            SQLException("Error opening connection", rootCause),
            databasePath
        )

        val details = formatStartupFailureDetails(
            failure = failure,
            reportPath = reportPath.absolutePath,
            additionalDiagnostics = "sqlite-jdbc JUL log file=/scratch/focusflow/logs/sqlite-jdbc-0.log"
        )

        assertContains(details, SQLException::class.java.name)
        assertContains(details, "Caused by: ${IOException::class.java.name}: native library access denied")
        assertContains(details, "File retained at: ${databasePath.absolutePath}")
        assertContains(details, "Local startup report: ${reportPath.absolutePath}")
        assertContains(details, "sqlite-jdbc JUL log file=/scratch/focusflow/logs/sqlite-jdbc-0.log")
    }
}
