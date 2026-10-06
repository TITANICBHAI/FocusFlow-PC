package com.focusflow.services

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SingleInstanceGuardTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun secondLaunchRequestsFocusAndExits() {
        val lockFile = tempDir.resolve("instance.lock").toFile()
        val focusRequested = CountDownLatch(1)
        val first = assertIs<InstanceAcquireResult.Acquired>(
            SingleInstanceGuard.acquire(lockFile) { focusRequested.countDown() }
        )

        try {
            assertIs<InstanceAcquireResult.AlreadyRunning>(SingleInstanceGuard.acquire(lockFile))
            assertTrue(focusRequested.await(2, TimeUnit.SECONDS), "The existing instance did not receive SHOW")
        } finally {
            first.handle.close()
        }
    }

    @Test
    fun instanceLockIsReleasedWhenTheHolderCloses() {
        val lockFile = tempDir.resolve("instance.lock").toFile()
        val first = assertIs<InstanceAcquireResult.Acquired>(SingleInstanceGuard.acquire(lockFile))
        first.handle.close()

        val second = assertIs<InstanceAcquireResult.Acquired>(SingleInstanceGuard.acquire(lockFile))
        second.handle.close()
    }

    @Test
    fun lockedInstanceWithoutResponsiveHandoffIsReportedInsteadOfSilentlyExiting() {
        val lockFile = tempDir.resolve("instance.lock")
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ).use { channel ->
            val lock = channel.lock()
            try {
                assertIs<InstanceAcquireResult.HolderUnresponsive>(
                    SingleInstanceGuard.acquire(lockFile.toFile())
                )
            } finally {
                lock.release()
            }
        }
    }
}
