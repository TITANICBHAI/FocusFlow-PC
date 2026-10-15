package com.focusflow.services

import com.focusflow.testutil.TempHome
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrashReportRetentionTest {
    @Test
    fun f24aDeduplicatesRootCauseAcrossNewInstancesWithinOneHour() {
        TempHome.withHome { home ->
            val appData = File(home.toFile(), ".focusflow").apply { mkdirs() }
            val temporary = File(home.toFile(), "temp").apply { mkdirs() }
            val desktop = File(home.toFile(), "Desktop").apply { mkdirs() }
            val now = AtomicLong(1_000_000L)
            val logLines = mutableListOf<String>()
            var reportWrites = 0

            repeat(30) { index ->
                val cause = IllegalStateException("same root cause")
                val wrapped = RuntimeException("outer wrapper $index", cause)
                val result = CrashReportRetention(
                    appDataDirectory = appData,
                    temporaryDirectory = temporary,
                    desktopDirectory = desktop,
                    nowMillis = now::get,
                    logLine = logLines::add
                ).write(CrashReportRetention.signatureFor(wrapped)) {
                    reportWrites++
                    File(desktop, "FocusFlow-crash-$index.log").apply {
                        writeText("same crash report")
                    }
                }

                if (index == 0) {
                    assertIs<CrashReportRetention.WriteResult.Written>(result)
                } else {
                    assertEquals(CrashReportRetention.WriteResult.Duplicate, result)
                }
                now.addAndGet(2L * 60L * 1000L)
            }

            assertEquals(1, reportWrites)
            assertEquals(1, desktop.listFiles().orEmpty().count { it.name.startsWith("FocusFlow-crash-") })
            assertEquals(29, logLines.size)
            assertTrue(logLines.all { '\n' !in it && '\r' !in it })
        }
    }

    @Test
    fun f24bCapsOnlyAppDataAndTempReportsAndKeepsDesktopFiles() {
        TempHome.withHome { home ->
            val appData = File(home.toFile(), ".focusflow").apply { mkdirs() }
            val temporary = File(home.toFile(), "temp").apply { mkdirs() }
            val desktop = File(home.toFile(), "Desktop").apply { mkdirs() }

            val appReports = createReports(appData, lastModifiedBase = 10_000L)
            val tempReports = createReports(temporary, lastModifiedBase = 20_000L)
            val desktopReports = createReports(desktop, lastModifiedBase = 30_000L)
            val appUnrelated = File(appData, "keep-settings.json").apply { writeText("keep") }
            val tempUnrelated = File(temporary, "not-a-crash-report.log").apply { writeText("keep") }
            val desktopUnrelated = File(desktop, "keep-on-desktop.txt").apply { writeText("keep") }

            CrashReportRetention(
                appDataDirectory = appData,
                temporaryDirectory = temporary,
                desktopDirectory = desktop
            ).prune()

            assertEquals(20, appReports.count { it.exists() })
            assertEquals(20, tempReports.count { it.exists() })
            assertTrue(appReports.take(5).none { it.exists() })
            assertTrue(tempReports.take(5).none { it.exists() })
            assertTrue(appReports.drop(5).all { it.exists() })
            assertTrue(tempReports.drop(5).all { it.exists() })
            assertEquals(25, desktopReports.count { it.exists() })
            assertTrue(appUnrelated.exists())
            assertTrue(tempUnrelated.exists())
            assertTrue(desktopUnrelated.exists())
            assertTrue(File(appData, "FocusFlow-crash-999.log.bak").apply {
                writeText("keep")
            }.exists())
        }
    }

    private fun createReports(directory: File, lastModifiedBase: Long): List<File> =
        (0 until 25).map { index ->
            val file = File(
                directory,
                "FocusFlow-crash-${index.toString().padStart(3, '0')}.log"
            ).apply { writeText("report $index") }
            Files.setLastModifiedTime(
                file.toPath(),
                FileTime.fromMillis(lastModifiedBase + index)
            )
            file
        }
}
