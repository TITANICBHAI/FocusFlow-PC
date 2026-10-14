package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.testutil.FakeTelemetrySink
import com.focusflow.testutil.TempHome
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelemetryConsentTest {
    @Test
    fun unknownAndDeniedNeverSendWhenDatabaseIsReadyNotReadyOrThrowing() {
        for (databaseMode in DatabaseMode.entries) {
            for (consent in listOf(TelemetryConsentState.UNKNOWN, TelemetryConsentState.DENIED)) {
                TempHome.withHome { home ->
                    when (databaseMode) {
                        DatabaseMode.NOT_READY -> Unit
                        DatabaseMode.READY -> assertIs<DbInitResult.Ready>(
                            Database.init(
                                dbFile = home.resolve(".focusflow/test.db").toFile(),
                                allowRecovery = false,
                            )
                        )
                        DatabaseMode.THROWING -> {
                            Database.dataSourceFactoryForTest = {
                                throw SQLException("injected settings read failure")
                            }
                            assertIs<DbInitResult.Failed>(
                                Database.init(
                                    dbFile = home.resolve(".focusflow/test.db").toFile(),
                                    allowRecovery = false,
                                )
                            )
                        }
                    }

                    if (consent == TelemetryConsentState.DENIED) {
                        assertTrue(TelemetryConsent.save(TelemetryConsentState.DENIED))
                    }
                    val fake = FakeTelemetrySink(expectedPosts = 0)
                    val oldSink = CrashReporter.telemetrySinkOverrideForTest
                    try {
                        CrashReporter.telemetrySinkOverrideForTest = fake
                        CrashReporter.reportCritical("F4-a", "must not send")
                        CrashReporter.sendToDiscord(
                            IllegalStateException("must not send"),
                            "F4-a",
                        )
                        assertTrue(fake.payloads().isEmpty(), "$consent sent with DB $databaseMode")
                    } finally {
                        CrashReporter.telemetrySinkOverrideForTest = oldSink
                    }
                }
            }
        }
    }

    @Test
    fun grantedConsentSendsThroughFakeSinkEvenWhenDatabaseIsUnavailable() {
        TempHome.withHome {
            assertTrue(TelemetryConsent.save(TelemetryConsentState.GRANTED))
            assertFalse(Database.isReady)

            val fake = FakeTelemetrySink(expectedPosts = 2)
            val oldSink = CrashReporter.telemetrySinkOverrideForTest
            try {
                CrashReporter.telemetrySinkOverrideForTest = fake
                CrashReporter.reportCritical("F4-b critical", "permitted")
                CrashReporter.sendToDiscord(
                    IllegalStateException("permitted"),
                    "F4-b fatal",
                )

                assertTrue(fake.awaitPosts(), "Both opted-in paths should use the injected sink")
                assertEquals(2, fake.payloads().size)
            } finally {
                CrashReporter.telemetrySinkOverrideForTest = oldSink
            }
        }
    }

    @Test
    fun legacyConsentMigratesOnceAndMissingValueStaysUnknown() {
        TempHome.withHome { home ->
            assertTrue(
                TelemetryConsent.migrateLegacyOnce(
                    Database.SettingReadResult.Found("true")
                )
            )
            assertEquals(TelemetryConsentState.GRANTED, TelemetryConsent.state())
            assertTrue(Files.exists(home.resolve(".focusflow/telemetry-consent.migrated")))
        }

        TempHome.withHome {
            assertTrue(
                TelemetryConsent.migrateLegacyOnce(
                    Database.SettingReadResult.Found("false")
                )
            )
            assertEquals(TelemetryConsentState.DENIED, TelemetryConsent.state())
        }

        TempHome.withHome { home ->
            assertTrue(
                TelemetryConsent.migrateLegacyOnce(Database.SettingReadResult.Missing)
            )
            assertEquals(TelemetryConsentState.UNKNOWN, TelemetryConsent.state())
            assertTrue(Files.exists(home.resolve(".focusflow/telemetry-consent.migrated")))

            // A second migration attempt cannot convert the unknown state to opt-in.
            assertTrue(
                TelemetryConsent.migrateLegacyOnce(
                    Database.SettingReadResult.Found("true")
                )
            )
            assertEquals(TelemetryConsentState.UNKNOWN, TelemetryConsent.state())
        }
    }

    @Test
    fun failedLegacyReadDoesNotMarkMigrationComplete() {
        TempHome.withHome { home ->
            assertFalse(
                TelemetryConsent.migrateLegacyOnce(Database.SettingReadResult.NotReady)
            )
            assertEquals(TelemetryConsentState.UNKNOWN, TelemetryConsent.state())
            assertFalse(Files.exists(home.resolve(".focusflow/telemetry-consent.migrated")))

            assertFalse(
                TelemetryConsent.migrateLegacyOnce(
                    Database.SettingReadResult.Unreadable(SQLException("injected"))
                )
            )
            assertFalse(Files.exists(home.resolve(".focusflow/telemetry-consent.migrated")))
        }
    }

    @Test
    fun redactsUsernameHomePathAndHostFromMessageStackAndSource() {
        val context = TelemetryRedactionContext(
            userHome = "C:\\Users\\FocusUser",
            userName = "FocusUser",
            hostNames = listOf("FOCUS-PC"),
        )
        val components = mapOf(
            "message" to "failed at C:/Users/FocusUser/AppData/Local/state.db on FOCUS-PC",
            "stack" to "at C:\\Users\\FocusUser\\FocusFlow\\Database.kt (user=FocusUser)",
            "source" to "startup:C:\\Users\\FocusUser\\.focusflow on FOCUS-PC",
        )

        for ((name, text) in components) {
            val redacted = TelemetryRedactor.redact(text, context)
            assertFalse(redacted.contains("FocusUser", ignoreCase = true), "$name leaked username")
            assertFalse(redacted.contains("C:\\Users\\FocusUser", ignoreCase = true), "$name leaked home")
            assertFalse(redacted.contains("C:/Users/FocusUser", ignoreCase = true), "$name leaked slash variant")
            assertFalse(redacted.contains("FOCUS-PC", ignoreCase = true), "$name leaked host")
        }

        // The production sink receives JSON, where each Windows separator is
        // escaped. Redaction must still remove the full home path before send.
        val jsonPayload =
            """{"message":"failed at C:\\Users\\FocusUser\\AppData\\Local\\state.db","""" +
                """"stack":"at C:\\Users\\FocusUser\\FocusFlow\\Database.kt","""" +
                """"source":"startup:C:\\Users\\FocusUser\\.focusflow on FOCUS-PC"}"""
        val redactedJson = TelemetryRedactor.redact(jsonPayload, context)
        assertTrue(redactedJson.contains("<home>"), "escaped JSON home path was not replaced")
        assertFalse(redactedJson.contains("C:\\\\Users"), "escaped Windows home path leaked")
        assertFalse(redactedJson.contains("FocusUser", ignoreCase = true), "escaped username leaked")
        assertFalse(redactedJson.contains("FOCUS-PC", ignoreCase = true), "escaped host leaked")
    }

    @Test
    fun sinkRedactsCurrentHomeUsernameAndHostAcrossReportFields() {
        TempHome.withHome { home ->
            assertTrue(TelemetryConsent.save(TelemetryConsentState.GRANTED))
            val previousUser = System.getProperty("user.name")
            val userName = "FocusFlow-Privacy-Test-User"
            val hostName = System.getenv("COMPUTERNAME")
                ?: System.getenv("HOSTNAME")
            val homePath = home.resolve("private").toString()
            val fake = FakeTelemetrySink(expectedPosts = 2)
            val previousSink = CrashReporter.telemetrySinkOverrideForTest

            try {
                System.setProperty("user.name", userName)
                CrashReporter.telemetrySinkOverrideForTest = fake
                val source = "startup $homePath for $userName ${hostName.orEmpty()}"
                val message = "failed at $homePath for $userName ${hostName.orEmpty()}"
                val failure = IllegalStateException(message).apply {
                    stackTrace = arrayOf(
                        StackTraceElement("com.focusflow.Test", "open", homePath, 1)
                    )
                }

                CrashReporter.reportCritical(source, message, failure)
                CrashReporter.sendToDiscord(failure, source)

                assertTrue(fake.awaitPosts(), "Reports should reach the fake sink")
                val payloads = fake.payloads()
                assertEquals(2, payloads.size)
                payloads.forEach { payload ->
                    assertFalse(payload.contains(homePath), "home path leaked")
                    assertFalse(payload.contains(userName), "username leaked")
                    hostName?.let { assertFalse(payload.contains(it, ignoreCase = true), "host leaked") }
                    assertTrue(payload.contains("<home>"), "redacted path marker missing")
                }
            } finally {
                CrashReporter.telemetrySinkOverrideForTest = previousSink
                if (previousUser == null) System.clearProperty("user.name")
                else System.setProperty("user.name", previousUser)
            }
        }
    }

    private enum class DatabaseMode { READY, NOT_READY, THROWING }
}
