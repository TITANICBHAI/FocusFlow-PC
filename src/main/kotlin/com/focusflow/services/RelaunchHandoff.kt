package com.focusflow.services

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID

internal data class HandoffMarker(
    val oldPid: Long,
    val nonce: String,
    val createdAtMs: Long,
    val userName: String
)

internal data class HandoffHello(
    val newPid: Long,
    val nonce: String,
    val createdAtMs: Long,
    val userName: String
)

internal class RelaunchHandoffStore(
    private val directory: File,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val markerFile get() = File(directory, "relaunch.marker")
    private val helloFile get() = File(directory, "relaunch.hello")

    @Synchronized
    fun begin(oldPid: Long, userName: String, nonce: String = UUID.randomUUID().toString()): HandoffMarker? {
        if (!directory.exists() && !directory.mkdirs()) return null
        if (!directory.isDirectory) return null

        val previous = readMarker()
        if (previous != null && isFresh(previous.createdAtMs)) return null
        markerFile.delete()
        helloFile.delete()

        val marker = HandoffMarker(oldPid, nonce, nowMs(), userName)
        return if (writeAtomically(markerFile, encode(marker))) marker else null
    }

    @Synchronized
    fun acceptIncoming(
        nonce: String,
        currentPid: Long,
        userName: String,
        elevated: Boolean
    ): HandoffMarker? {
        if (!elevated || currentPid <= 0L || userName.isBlank()) return null
        val marker = readMarker() ?: return null
        if (marker.nonce != nonce ||
            marker.oldPid == currentPid ||
            marker.userName != userName ||
            !isFresh(marker.createdAtMs)
        ) {
            return null
        }

        val hello = HandoffHello(currentPid, nonce, nowMs(), userName)
        return if (writeAtomically(helloFile, encode(hello))) marker else null
    }

    fun awaitHello(
        marker: HandoffMarker,
        timeoutMs: Long,
        pollIntervalMs: Long = 100L,
        sleep: (Long) -> Unit = Thread::sleep
    ): Boolean {
        val deadline = nowMs() + timeoutMs
        while (true) {
            val hello = readHello()
            if (hello != null &&
                hello.nonce == marker.nonce &&
                hello.newPid > 0L &&
                hello.newPid != marker.oldPid &&
                hello.userName == marker.userName &&
                isFresh(hello.createdAtMs)
            ) {
                return true
            }
            val remaining = deadline - nowMs()
            if (remaining <= 0L) return false
            sleep(minOf(pollIntervalMs, remaining))
        }
    }

    @Synchronized
    fun currentMarker(): HandoffMarker? =
        readMarker()?.takeIf { isFresh(it.createdAtMs) }

    @Synchronized
    fun clear(marker: HandoffMarker) {
        if (readMarker()?.nonce == marker.nonce) markerFile.delete()
        if (readHello()?.nonce == marker.nonce) helloFile.delete()
    }

    private fun readMarker(): HandoffMarker? =
        runCatching { decode(markerFile.readText()) as? HandoffMarker }.getOrNull()

    private fun readHello(): HandoffHello? =
        runCatching { decode(helloFile.readText()) as? HandoffHello }.getOrNull()

    private fun isFresh(timestamp: Long): Boolean =
        (nowMs() - timestamp) in 0..MAX_MARKER_AGE_MS

    private fun writeAtomically(file: File, contents: String): Boolean = runCatching {
        val temporary = File(directory, "${file.name}.${UUID.randomUUID()}.tmp")
        Files.writeString(
            temporary.toPath(),
            contents,
            StandardOpenOption.CREATE_NEW,
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
        true
    }.getOrDefault(false)

    private fun encode(marker: HandoffMarker): String = listOf(
        "marker",
        marker.oldPid.toString(),
        marker.nonce,
        marker.createdAtMs.toString(),
        encodeUser(marker.userName)
    ).joinToString("|")

    private fun encode(hello: HandoffHello): String = listOf(
        "hello",
        hello.newPid.toString(),
        hello.nonce,
        hello.createdAtMs.toString(),
        encodeUser(hello.userName)
    ).joinToString("|")

    private fun decode(value: String): Any? {
        val fields = value.trim().split('|')
        if (fields.size != 5) return null
        val id = fields[1].toLongOrNull() ?: return null
        val timestamp = fields[3].toLongOrNull() ?: return null
        val userName = decodeUser(fields[4]) ?: return null
        if (fields[2].isBlank()) return null
        return when (fields[0]) {
            "marker" -> HandoffMarker(id, fields[2], timestamp, userName)
            "hello" -> HandoffHello(id, fields[2], timestamp, userName)
            else -> null
        }
    }

    private fun encodeUser(userName: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(userName.toByteArray(Charsets.UTF_8))

    private fun decodeUser(value: String): String? =
        runCatching {
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()

    companion object {
        const val MAX_MARKER_AGE_MS = 60_000L
    }
}

internal object RelaunchHandoff {
    data class Marker internal constructor(
        val oldPid: Long,
        val nonce: String,
        val createdAtMs: Long,
        val userName: String
    )

    private val store by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RelaunchHandoffStore(File(File(System.getProperty("user.home"), ".focusflow").absoluteFile))
    }

    @Volatile private var incomingMarker: HandoffMarker? = null
    @Volatile private var incomingSuppressShowUntilMs: Long = 0L

    fun begin(): Marker? {
        val created = store.begin(ProcessHandle.current().pid(), currentUserName())
            ?: return null
        return created.toPublicMarker()
    }

    fun acceptIncoming(nonce: String): Boolean {
        val accepted = store.acceptIncoming(
            nonce = nonce,
            currentPid = ProcessHandle.current().pid(),
            userName = currentUserName(),
            elevated = com.focusflow.enforcement.ElevationManager.isElevated
        ) ?: return false
        incomingMarker = accepted
        incomingSuppressShowUntilMs = System.currentTimeMillis() + INCOMING_LOCK_WAIT_MS
        return true
    }

    fun shouldSuppressShowRequest(): Boolean =
        incomingMarker != null && System.currentTimeMillis() < incomingSuppressShowUntilMs

    fun isWaitingForIncomingInstance(): Boolean = shouldSuppressShowRequest()

    fun completeIncomingHandoff() {
        incomingMarker?.let(store::clear)
        incomingMarker = null
        incomingSuppressShowUntilMs = 0L
    }

    fun awaitHello(marker: Marker, timeoutMs: Long): Boolean =
        store.awaitHello(marker.toInternalMarker(), timeoutMs)

    fun clear(marker: Marker) {
        store.clear(marker.toInternalMarker())
    }

    private fun currentUserName(): String = System.getProperty("user.name").orEmpty()

    private fun HandoffMarker.toPublicMarker() =
        Marker(oldPid, nonce, createdAtMs, userName)

    private fun Marker.toInternalMarker() =
        HandoffMarker(oldPid, nonce, createdAtMs, userName)

    private const val INCOMING_LOCK_WAIT_MS = 25_000L
}
