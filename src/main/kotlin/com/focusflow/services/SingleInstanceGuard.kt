package com.focusflow.services

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

sealed interface InstanceAcquireResult {
    data class Acquired(val handle: SingleInstanceGuard.Handle) : InstanceAcquireResult
    data object AlreadyRunning : InstanceAcquireResult
    data object HolderUnresponsive : InstanceAcquireResult
}

/**
 * Holds a per-user file lock for the process lifetime and offers a local-only
 * SHOW handoff to launches that find an existing holder.
 */
object SingleInstanceGuard {
    class Handle internal constructor(
        private val channel: FileChannel,
        private val lock: FileLock,
        private val server: ServerSocket,
        private val portFile: File,
        private val portFileContents: String,
        private val onShow: () -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        internal fun serve() {
            Thread({
                while (!closed.get()) {
                    try {
                        server.accept().use { client ->
                            client.soTimeout = HANDOFF_TIMEOUT_MS
                            val command = BufferedReader(InputStreamReader(client.getInputStream())).readLine()
                            if (command == SHOW_COMMAND) {
                                runCatching(onShow)
                                BufferedWriter(OutputStreamWriter(client.getOutputStream())).use { writer ->
                                    writer.write(SHOW_ACK)
                                    writer.newLine()
                                    writer.flush()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (!closed.get()) Thread.sleep(SERVER_RETRY_MS)
                    }
                }
            }, "FocusFlow-Instance-Handoff").apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            runCatching { lock.release() }
            runCatching { channel.close() }
            runCatching {
                if (portFile.exists() && portFile.readText() == portFileContents) {
                    Files.deleteIfExists(portFile.toPath())
                }
            }
        }
    }

    fun acquire(
        lockFile: File = defaultLockFile(),
        onShow: () -> Unit = {}
    ): InstanceAcquireResult {
        val parent = lockFile.absoluteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return InstanceAcquireResult.HolderUnresponsive
        }

        var channel: FileChannel? = null
        var lock: FileLock? = null
        var server: ServerSocket? = null
        try {
            channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                channel.close()
                return if (requestShow(portFileFor(lockFile))) {
                    InstanceAcquireResult.AlreadyRunning
                } else {
                    InstanceAcquireResult.HolderUnresponsive
                }
            }

            server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val portFile = portFileFor(lockFile)
            val portFileContents = server.localPort.toString()
            writePortFile(portFile, portFileContents)

            val handle = Handle(channel, lock, server, portFile, portFileContents, onShow)
            handle.serve()
            return InstanceAcquireResult.Acquired(handle)
        } catch (_: Exception) {
            runCatching { server?.close() }
            runCatching { lock?.release() }
            runCatching { channel?.close() }
            return InstanceAcquireResult.HolderUnresponsive
        }
    }

    private fun requestShow(portFile: File): Boolean {
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), HANDOFF_TIMEOUT_MS)
                socket.soTimeout = HANDOFF_TIMEOUT_MS
                BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { writer ->
                    writer.write(SHOW_COMMAND)
                    writer.newLine()
                    writer.flush()
                    val response = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                    response == SHOW_ACK
                }
            }
        }.getOrDefault(false)
    }

    private fun writePortFile(file: File, contents: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        Files.writeString(
            temporary.toPath(),
            contents,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun defaultLockFile(): File =
        File(File(System.getProperty("user.home"), ".focusflow"), "instance.lock")

    private fun portFileFor(lockFile: File): File =
        File(lockFile.absoluteFile.parentFile ?: File("."), "instance.port")

    private const val SHOW_COMMAND = "SHOW"
    private const val SHOW_ACK = "OK"
    private const val HANDOFF_TIMEOUT_MS = 1_000
    private const val SERVER_RETRY_MS = 50L
}
