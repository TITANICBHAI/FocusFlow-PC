package com.focusflow.services

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * Persistent crash-report de-duplication and retention for the app-owned
 * report directories. Desktop is deliberately excluded from pruning.
 */
internal class CrashReportRetention(
    private val appDataDirectory: File,
    private val temporaryDirectory: File,
    private val desktopDirectory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val logLine: (String) -> Unit = { System.err.println(it) }
) {
    sealed interface WriteResult {
        data class Written(val file: File) : WriteResult
        data object Duplicate : WriteResult
        data class Failed(val reason: String) : WriteResult
    }

    fun write(signature: String, reportWriter: () -> File?): WriteResult {
        val signatureHash = sha256(signature)
        return synchronized(JVM_LOCK) {
            val result = writeWithLock(signatureHash, reportWriter)
            if (result != null) {
                result
            } else {
                logLine("[FocusFlow] Crash-report de-duplication unavailable; writing without de-duplication.")
                writeWithoutDeduplication(reportWriter)
            }
        }
    }

    /** Exposed internally so retention behavior can be tested without writing reports. */
    fun prune() = synchronized(JVM_LOCK) {
        pruneOwnedDirectories()
    }

    private fun writeWithLock(signatureHash: String, reportWriter: () -> File?): WriteResult? {
        return try {
            Files.createDirectories(appDataDirectory.toPath())
            val lockPath = File(appDataDirectory, INDEX_LOCK_NAME).toPath()
            FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use {
                    val now = nowMillis()
                    val signatures = readRecentSignatures(now)
                    val previousWrite = signatures[signatureHash]
                    if (previousWrite != null &&
                        previousWrite >= now - DEDUP_WINDOW_MILLIS &&
                        previousWrite <= now
                    ) {
                        pruneOwnedDirectories()
                        logLine(
                            "[FocusFlow] Suppressed duplicate crash report " +
                                "(${signatureHash.take(12)}) within the 60-minute window."
                        )
                        WriteResult.Duplicate
                    } else {
                        val reportResult = try {
                            reportWriter()?.let { WriteResult.Written(it) }
                                ?: WriteResult.Failed("No crash-report destination accepted the report.")
                        } catch (failure: Throwable) {
                            logLine(
                                "[FocusFlow] Crash-report write failed: " +
                                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
                            )
                            WriteResult.Failed(
                                "${failure.javaClass.simpleName}: ${failure.message ?: "no detail"}"
                            )
                        }

                        pruneOwnedDirectories()
                        if (reportResult is WriteResult.Written) {
                            signatures[signatureHash] = now
                            persistSignatures(signatures, now)
                        }
                        reportResult
                    }
                }
            }
        } catch (failure: Exception) {
            logLine(
                "[FocusFlow] Crash-report de-duplication lock failed: " +
                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
            )
            null
        }
    }

    private fun writeWithoutDeduplication(reportWriter: () -> File?): WriteResult {
        val reportResult = try {
            reportWriter()?.let { WriteResult.Written(it) }
                ?: WriteResult.Failed("No crash-report destination accepted the report.")
        } catch (failure: Throwable) {
            logLine(
                "[FocusFlow] Crash-report write failed: " +
                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
            )
            WriteResult.Failed(
                "${failure.javaClass.simpleName}: ${failure.message ?: "no detail"}"
            )
        }
        pruneOwnedDirectories()
        return reportResult
    }

    private fun readRecentSignatures(now: Long): MutableMap<String, Long> {
        val indexPath = File(appDataDirectory, INDEX_FILE_NAME).toPath()
        if (!Files.exists(indexPath)) return mutableMapOf()

        return try {
            if (Files.size(indexPath) > MAX_INDEX_BYTES) {
                logLine("[FocusFlow] Crash-report de-duplication index exceeded its size limit; resetting it.")
                return mutableMapOf()
            }

            Files.readAllLines(indexPath, StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val columns = line.split('\t', limit = 2)
                    if (columns.size != 2 || !isSha256(columns[0])) return@mapNotNull null
                    val timestamp = columns[1].toLongOrNull() ?: return@mapNotNull null
                    if (timestamp < now - DEDUP_WINDOW_MILLIS || timestamp > now) {
                        null
                    } else {
                        columns[0] to timestamp
                    }
                }
                .toMap()
                .toMutableMap()
        } catch (failure: Exception) {
            logLine(
                "[FocusFlow] Could not read crash-report de-duplication index: " +
                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
            )
            mutableMapOf()
        }
    }

    private fun persistSignatures(signatures: Map<String, Long>, now: Long) {
        val recent = signatures.entries
            .filter { (now - DEDUP_WINDOW_MILLIS..now).contains(it.value) }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(MAX_SIGNATURES)
            .sortedBy { it.key }
        val content = recent.joinToString(
            separator = "\n",
            postfix = if (recent.isEmpty()) "" else "\n"
        ) { "${it.key}\t${it.value}" }

        val temporaryFile = try {
            Files.createTempFile(appDataDirectory.toPath(), ".crash-report-index-", ".tmp")
        } catch (failure: Throwable) {
            logLine(
                "[FocusFlow] Could not create crash-report de-duplication index: " +
                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
            )
            return
        }

        try {
            Files.write(
                temporaryFile,
                content.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            try {
                Files.move(
                    temporaryFile,
                    File(appDataDirectory, INDEX_FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile,
                    File(appDataDirectory, INDEX_FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (failure: Throwable) {
            val cleanupFailure = try {
                Files.deleteIfExists(temporaryFile)
                null
            } catch (cleanup: Throwable) {
                cleanup
            }
            val cleanupMessage = cleanupFailure?.let {
                "; temporary index cleanup failed: ${it.javaClass.name}: ${it.message ?: "no detail"}"
            }.orEmpty()
            logLine(
                "[FocusFlow] Could not persist crash-report de-duplication index: " +
                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}$cleanupMessage"
            )
        }
    }

    private fun pruneOwnedDirectories() {
        val directories = listOf(appDataDirectory, temporaryDirectory)
            .distinctBy { normalizedPath(it) }
        directories.forEach { directory ->
            if (isDesktopOrAncestor(directory)) {
                logLine("[FocusFlow] Skipping crash-report pruning in Desktop or its parent directory.")
                return@forEach
            }

            val files = try {
                directory.listFiles()
            } catch (failure: Exception) {
                logLine(
                    "[FocusFlow] Could not list crash-report directory ${directory.absolutePath}: " +
                        "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
                )
                null
            }
            if (files == null) {
                if (directory.exists()) {
                    logLine("[FocusFlow] Could not list crash-report directory ${directory.absolutePath}.")
                }
                return@forEach
            }

            files.asSequence()
                .filter { it.isFile && isOwnedCrashReportName(it.name) }
                .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
                .drop(MAX_REPORTS_PER_DIRECTORY)
                .forEach { oldReport ->
                    try {
                        if (!oldReport.delete() && oldReport.exists()) {
                            logLine("[FocusFlow] Could not remove old app-owned crash report ${oldReport.absolutePath}.")
                        }
                    } catch (failure: Exception) {
                        logLine(
                            "[FocusFlow] Could not remove old app-owned crash report ${oldReport.absolutePath}: " +
                                "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
                        )
                    }
                }
        }
    }

    private fun isDesktopOrAncestor(directory: File): Boolean {
        return try {
            val directoryPath = directory.canonicalFile.toPath()
            val desktopPath = desktopDirectory.canonicalFile.toPath()
            directoryPath.startsWith(desktopPath) || desktopPath.startsWith(directoryPath)
        } catch (failure: Exception) {
            logLine(
                "[FocusFlow] Could not verify crash-report directory safety for ${directory.absolutePath}: " +
                    "${failure.javaClass.name}: ${failure.message ?: "no detail"}"
            )
            true
        }
    }

    private fun normalizedPath(directory: File): Path =
        try {
            directory.canonicalFile.toPath()
        } catch (failure: Exception) {
            directory.absoluteFile.toPath().normalize()
        }

    private fun isOwnedCrashReportName(name: String): Boolean =
        name.startsWith(CRASH_REPORT_PREFIX) && name.endsWith(".log")

    private fun isSha256(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    companion object {
        private const val CRASH_REPORT_PREFIX = "FocusFlow-crash-"
        private const val DEDUP_WINDOW_MILLIS = 60L * 60L * 1000L
        private const val MAX_REPORTS_PER_DIRECTORY = 20
        private const val MAX_SIGNATURES = 4096
        private const val MAX_INDEX_BYTES = 512L * 1024L
        private const val SHA256_HEX_LENGTH = 64
        private const val INDEX_FILE_NAME = ".crash-report-dedup.tsv"
        private const val INDEX_LOCK_NAME = ".crash-report-dedup.lock"
        private val JVM_LOCK = Any()

        fun signatureFor(throwable: Throwable): String {
            val seen = java.util.Collections.newSetFromMap(
                IdentityHashMap<Throwable, Boolean>()
            )
            var root = throwable
            seen.add(root)
            while (true) {
                val next = try {
                    root.cause
                } catch (_: Throwable) {
                    null
                } ?: break
                if (!seen.add(next)) break
                root = next
            }

            val material = buildString {
                append(root.javaClass.name)
                append('\n')
                append(root.message.orEmpty().trim())
                root.stackTrace.take(4).forEach { frame ->
                    append('\n')
                    append(frame.className)
                    append('#')
                    append(frame.methodName)
                }
            }
            return sha256(material)
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(hex[unsigned ushr 4])
                    append(hex[unsigned and 0x0f])
                }
            }
        }
    }
}
