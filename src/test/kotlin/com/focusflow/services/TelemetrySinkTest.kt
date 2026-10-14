package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.testutil.FakeTelemetrySink
import com.focusflow.testutil.TempHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TelemetrySinkTest {
    @Test
    fun telemetryOffSelectsNoopSink() {
        val previousProperty = System.getProperty("focusflow.telemetry")
        val previousSink = CrashReporter.telemetrySinkOverrideForTest
        try {
            CrashReporter.telemetrySinkOverrideForTest = null
            System.setProperty("focusflow.telemetry", "off")
            assertSame(NoopTelemetrySink, CrashReporter.selectedTelemetrySink())
        } finally {
            CrashReporter.telemetrySinkOverrideForTest = previousSink
            restoreProperty("focusflow.telemetry", previousProperty)
        }
    }

    @Test
    fun criticalAndFatalReportsUseInjectedSinkWithoutNetworkAccess() {
        val previousSink = CrashReporter.telemetrySinkOverrideForTest
        val fake = FakeTelemetrySink(expectedPosts = 2)
        try {
            TempHome.withHome { home ->
                val dbFile = home.resolve(".focusflow/focusflow.db").toFile()
                assertIs<DbInitResult.Ready>(
                    Database.init(dbFile = dbFile, allowRecovery = false)
                )
                assertTrue(TelemetryConsent.save(TelemetryConsentState.GRANTED))

                CrashReporter.telemetrySinkOverrideForTest = fake
                CrashReporter.reportCritical("F4.0 test", "critical report")
                CrashReporter.sendToDiscord(IllegalStateException("fatal report"), "F4.0 test")

                assertTrue(fake.awaitPosts(), "Both automatic report paths should reach the fake sink")
                assertEquals(2, fake.payloads().size)
            }
        } finally {
            CrashReporter.telemetrySinkOverrideForTest = previousSink
        }
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }
}
