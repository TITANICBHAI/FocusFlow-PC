package com.focusflow.data

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import jdk.jfr.Recording
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatabaseMonitorDiagnosticsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun f16SummarizesDatabaseMonitorWaitsByCallsiteAndAwtStatus() {
        val recording = Recording()
        val recordingFile = tempDir.resolve("database-monitor.jfr")
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val contenderStarted = CountDownLatch(1)
        val owner = Thread(
            {
                synchronized(Database) {
                    ownerEntered.countDown()
                    releaseOwner.await()
                }
            },
            "F16-monitor-owner"
        )
        val contender = Thread(
            {
                contenderStarted.countDown()
                synchronized(Database) { }
            },
            "AWT-EventQueue-F16-test"
        )

        try {
            recording.enable("jdk.JavaMonitorEnter")
                .withThreshold(Duration.ZERO)
                .withStackTrace()
            recording.setToDisk(true)
            recording.start()

            owner.start()
            assertTrue(ownerEntered.await(5, TimeUnit.SECONDS), "Monitor owner did not start")
            contender.start()
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS), "Contender did not start")
            Thread.sleep(100)
            releaseOwner.countDown()
            owner.join(5_000)
            contender.join(5_000)
            assertTrue(!owner.isAlive, "Monitor owner did not finish")
            assertTrue(!contender.isAlive, "Contender did not finish")

            recording.stop()
            recording.dump(recordingFile)
            val summary = DatabaseMonitorDiagnostics.summarizeRecording(recordingFile)

            assertContains(summary, "Monitor: com.focusflow.data.Database")
            assertContains(summary, "Captured contended enters: 1")
            assertContains(summary, "AWT event-thread wait: 1 enters")
            assertContains(summary, "AWT 1 waits")
            assertContains(summary, "DatabaseMonitorDiagnosticsTest.f16Summarizes")
        } finally {
            releaseOwner.countDown()
            owner.join(1_000)
            contender.join(1_000)
            recording.close()
        }
    }
}
