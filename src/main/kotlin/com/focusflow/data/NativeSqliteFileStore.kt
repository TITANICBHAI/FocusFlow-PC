package com.focusflow.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class NativeSqliteExtractedFile(
    val file: File,
    val sha256: String,
    val reused: Boolean
)

internal object NativeSqliteFileStore {
    fun install(directory: File, resourceName: String, bytes: ByteArray): NativeSqliteExtractedFile {
        val digest = sha256(bytes)
        val extension = resourceName.substringAfterLast('.', missingDelimiterValue = "")
        val stem = "sqlitejdbc-${digest.take(12)}"
        val baseName = if (extension.isBlank()) stem else "$stem.$extension"
        var suffix = 0

        while (suffix < 1_000) {
            val name = if (suffix == 0) baseName else {
                if (extension.isBlank()) "$stem-$suffix" else "$stem-$suffix.$extension"
            }
            val target = directory.toPath().resolve(name)
            if (Files.exists(target)) {
                if (matches(target, digest)) {
                    return NativeSqliteExtractedFile(target.toFile(), digest, reused = true)
                }
                suffix++
                continue
            }

            val temporary = Files.createTempFile(directory.toPath(), ".focusflow-native-", ".tmp")
            var operationFailure: Throwable? = null
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING)
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                    return NativeSqliteExtractedFile(target.toFile(), digest, reused = false)
                } catch (_: FileAlreadyExistsException) {
                    if (matches(target, digest)) {
                        return NativeSqliteExtractedFile(target.toFile(), digest, reused = true)
                    }
                    suffix++
                } catch (unsupported: AtomicMoveNotSupportedException) {
                    throw unsupported
                }
            } catch (failure: Throwable) {
                operationFailure = failure
                throw failure
            } finally {
                deleteTemporaryFile(temporary, operationFailure)
            }
        }

        throw IOException("Could not allocate a unique content-addressed SQLite library filename in $directory")
    }

    private fun matches(path: Path, expectedSha256: String): Boolean {
        return try {
            Files.isRegularFile(path) && sha256(Files.readAllBytes(path)) == expectedSha256
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            false
        }
    }

    private fun deleteTemporaryFile(path: Path, primaryFailure: Throwable?) {
        try {
            Files.deleteIfExists(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
