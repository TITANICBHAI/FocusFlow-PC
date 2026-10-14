package com.focusflow

import com.focusflow.testutil.ForkedJvm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal object CrashReporterVersionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println(com.focusflow.services.CrashReporter.APP_VERSION)
    }
}

class TestJvmConfigurationTest {
    @Test
    fun testWorkerReceivesGradleProjectVersion() {
        assertEquals(
            System.getProperty("focusflow.test.expected-app-version"),
            System.getProperty("focusflow.app.version")
        )
    }

    @Test
    fun crashReporterStillFailsFastWhenVersionPropertyIsMissing() {
        val result = ForkedJvm.run(
            mainClass = CrashReporterVersionProbe::class.java.name,
            appVersion = null
        )

        assertNotEquals(0, result.exitCode, "CrashReporter should fail without the packaged-app version")
        assertTrue(
            result.stderr.contains("Missing focusflow.app.version JVM property"),
            result.stderr
        )
    }
}
