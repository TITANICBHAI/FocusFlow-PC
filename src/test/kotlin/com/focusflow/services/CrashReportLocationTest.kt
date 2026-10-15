package com.focusflow.services

import com.focusflow.AppPaths
import com.focusflow.testutil.TempHome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashReportLocationTest {
    @Test
    fun newReportsGoToAppLogFolderAndLegacyLocationsRemainSearchable() =
        TempHome.withHome { home ->
            val homeFile = home.toFile()
            val desktop = File(homeFile, "Desktop").apply { mkdirs() }
            val legacyAppData = File(homeFile, ".focusflow").apply { mkdirs() }

            val report = assertNotNull(
                CrashReporter.reportStartupIssue(
                    source = "CrashReportLocationTest",
                    message = "test report location",
                    throwable = IllegalStateException("unique test failure"),
                    sendTelemetry = false
                )
            )

            assertEquals(AppPaths.crashReportDir.canonicalFile, report.parentFile.canonicalFile)
            assertTrue(report.isFile)
            assertTrue(
                desktop.listFiles().orEmpty().none { it.name.startsWith("FocusFlow-crash-") },
                "New crash reports must not be written to the Desktop"
            )

            val legacyDesktopLog =
                File(desktop, "FocusFlow-crash-legacy-desktop.log").apply { writeText("legacy") }
            val legacyAppDataLog =
                File(legacyAppData, "FocusFlow-crash-legacy-appdata.log").apply { writeText("legacy") }

            val discovered = CrashReporter.findCrashLogs().map { it.canonicalFile }.toSet()
            assertTrue(report.canonicalFile in discovered)
            assertTrue(legacyDesktopLog.canonicalFile in discovered)
            assertTrue(legacyAppDataLog.canonicalFile in discovered)
        }
}
