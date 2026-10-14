package com.focusflow.testutil

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object ForkedJvmProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val home = Path.of(System.getProperty("user.home"))
        val temp = Path.of(System.getProperty("java.io.tmpdir"))
        println("scratch-home=${home.startsWith(temp)}")
        println("app-version=${System.getProperty("focusflow.app.version")}")
        println("telemetry=${System.getProperty("focusflow.telemetry")}")
        println("argument=${args.firstOrNull().orEmpty()}")
    }
}

class ForkedJvmTest {
    @Test
    fun launchesFreshJvmWithScratchHomeAndRequiredProperties() {
        val parentHome = System.getProperty("user.home")
        val parentVersion = System.getProperty("focusflow.app.version")
        val parentTelemetry = System.getProperty("focusflow.telemetry")

        val result = ForkedJvm.run(
            mainClass = ForkedJvmProbe::class.java.name,
            args = listOf("smoke")
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("scratch-home=true"), result.stdout)
        assertTrue(result.stdout.contains("app-version=test"), result.stdout)
        assertTrue(result.stdout.contains("telemetry=off"), result.stdout)
        assertTrue(result.stdout.contains("argument=smoke"), result.stdout)
        assertFalse(result.stdout.contains(parentHome))
        assertEquals(parentVersion, System.getProperty("focusflow.app.version"))
        assertEquals(parentTelemetry, System.getProperty("focusflow.telemetry"))
    }
}
