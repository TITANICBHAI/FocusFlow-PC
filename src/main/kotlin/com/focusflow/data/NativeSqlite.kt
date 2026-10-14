package com.focusflow.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.sqlite.SQLiteJDBCLoader
import org.sqlite.util.LibraryLoaderUtil

internal sealed interface NativeSqliteStatus {
    data class Loaded(
        val path: String,
        val how: String,
        val attempts: List<NativeSqliteLoadAttempt>
    ) : NativeSqliteStatus

    data class Unavailable(
        val failure: NativeSqliteUnavailableException
    ) : NativeSqliteStatus
}

internal data class NativeSqliteCandidate(
    val label: String,
    val directory: File
)

internal data class NativeSqliteResource(
    val directoryPath: String,
    val fileName: String,
    val bytes: ByteArray
)

internal data class NativeSqliteLoadAttempt(
    val candidate: String,
    val directory: String,
    val directoryExisted: Boolean,
    val directoryCreated: Boolean,
    val writable: Boolean,
    val extraction: String,
    val failureClass: String? = null,
    val failureMessage: String? = null
)

internal class NativeSqliteUnavailableException(
    message: String,
    val attempts: List<NativeSqliteLoadAttempt>,
    val requiresRestart: Boolean,
    val diagnostics: String
) : Exception(message) {
    init {
        attempts.forEach { attempt ->
            val failureClass = attempt.failureClass ?: return@forEach
            val failureMessage = attempt.failureMessage.orEmpty()
            addSuppressed(SanitizedNativeLoadFailure(failureClass, failureMessage))
        }
    }
}

private class SanitizedNativeLoadFailure(
    originalClass: String,
    message: String
) : Exception("$originalClass: $message")

/**
 * Loads sqlite-jdbc before Database touches the user's database or its sidecars.
 * Only successful loads are cached; a failure can be retried in the same JVM
 * unless the sqlite-jdbc default-loader fallback failed.
 */
internal object NativeSqlite {
    private const val SQLITE_JDBC_VERSION = "3.47.1.0"
    private const val RESOURCE_PREFIX = "org/sqlite/native/"

    @Volatile
    private var loadedStatus: NativeSqliteStatus.Loaded? = null

    @Volatile
    internal var testHooksForTest: NativeSqliteTestHooks? = null

    @Synchronized
    fun ensureLoaded(): NativeSqliteStatus {
        val hooks = testHooksForTest
        val canReuseLoadedStatus = hooks == null || hooks.cacheSuccessfulLoad
        if (canReuseLoadedStatus) loadedStatus?.let { return it }

        val attempts = mutableListOf<NativeSqliteLoadAttempt>()
        var resource: NativeSqliteResource? = null
        var resourceFailure: Throwable? = null
        try {
            resource = hooks?.resource ?: readNativeResource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            resourceFailure = failure
        }

        val candidates = hooks?.candidates ?: defaultCandidates(resource?.directoryPath)
        candidates.forEach { candidate ->
            val (attempt, loaded) = attemptCandidate(candidate, resource, resourceFailure, hooks)
            attempts += attempt
            if (loaded != null) {
                val result = loaded.copy(attempts = attempts.toList())
                if (canReuseLoadedStatus) loadedStatus = result
                return result
            }
        }

        val fallback = attemptDefaultLoader(attempts, hooks)
        if (fallback != null) {
            if (canReuseLoadedStatus) loadedStatus = fallback
            return fallback
        }

        return NativeSqliteStatus.Unavailable(
            unavailableException(
                attempts = attempts,
                resourcePath = resource?.let { "${it.directoryPath}/${it.fileName}" },
                requiresRestart = true
            )
        )
    }

    @Synchronized
    internal fun resetForTest() {
        // JNI libraries cannot be unloaded from a JVM. Keep a successful
        // process-level load cached across database test/profile resets.
        testHooksForTest = null
    }

    private fun readNativeResource(): NativeSqliteResource {
        val directoryPath = normalizeResourcePath(LibraryLoaderUtil.getNativeLibResourcePath())
        val fileName = LibraryLoaderUtil.getNativeLibName()
        val resourcePath = "$directoryPath/$fileName"
        val stream = LibraryLoaderUtil::class.java.getResourceAsStream("/$resourcePath")
            ?: throw IOException("SQLite native resource is missing: $resourcePath")
        val bytes = stream.use { it.readBytes() }
        if (bytes.isEmpty()) throw IOException("SQLite native resource is empty: $resourcePath")
        return NativeSqliteResource(directoryPath, fileName, bytes)
    }

    private fun defaultCandidates(resourceDirectoryPath: String?): List<NativeSqliteCandidate> {
        val platformDirectory = resourceDirectoryPath
            ?.substringAfter(RESOURCE_PREFIX, missingDelimiterValue = "")
            ?.trim('/')
            ?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("os.name", "unknown")}/${System.getProperty("os.arch", "unknown")}"

        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?: File(System.getProperty("user.home").orEmpty(), "AppData/Local").absolutePath
        val localCandidate = NativeSqliteCandidate(
            label = if (System.getenv("LOCALAPPDATA").isNullOrBlank()) {
                "user.home/AppData/Local"
            } else {
                "LOCALAPPDATA"
            },
            directory = File(
                File(File(File(localAppData, "FocusFlow"), "native"), "sqlite-$SQLITE_JDBC_VERSION"),
                platformDirectory
            )
        )
        val profileCandidate = NativeSqliteCandidate(
            label = "user.home/.focusflow",
            directory = File(
                File(File(System.getProperty("user.home").orEmpty(), ".focusflow"), "native"),
                "sqlite-$SQLITE_JDBC_VERSION/$platformDirectory"
            )
        )

        return listOf(localCandidate, profileCandidate)
            .distinctBy { it.directory.toPath().toAbsolutePath().normalize().toString() }
    }

    private fun attemptCandidate(
        candidate: NativeSqliteCandidate,
        resource: NativeSqliteResource?,
        resourceFailure: Throwable?,
        hooks: NativeSqliteTestHooks?
    ): Pair<NativeSqliteLoadAttempt, NativeSqliteStatus.Loaded?> {
        val directory = candidate.directory.toPath().toAbsolutePath().normalize()
        var directoryExisted = false
        var directoryCreated = false
        var writable = false
        var extraction = "not attempted"

        try {
            directoryExisted = Files.isDirectory(directory)
            if (!Files.exists(directory)) {
                Files.createDirectories(directory)
                directoryCreated = true
            }
            val directoryAvailable = Files.isDirectory(directory)
            writable = directoryAvailable && Files.isWritable(directory)
            if (!writable) throw IOException("Candidate directory is not writable")

            val availableResource = resource ?: throw IOException(
                "Could not read the SQLite native resource",
                resourceFailure
            )
            val extracted = NativeSqliteFileStore.install(
                directory.toFile(),
                availableResource.fileName,
                availableResource.bytes
            )
            extraction = if (extracted.reused) {
                "reused ${extracted.file.name} (SHA-256 ${extracted.sha256})"
            } else {
                "installed ${extracted.file.name} (SHA-256 ${extracted.sha256})"
            }

            val previousLibraryProperties = if (hooks?.systemLoad == null) {
                System.getProperty("org.sqlite.lib.path") to
                    System.getProperty("org.sqlite.lib.name")
            } else {
                null
            }
            try {
                if (hooks?.systemLoad != null) {
                    hooks.systemLoad.invoke(extracted.file.absolutePath)
                } else {
                    System.load(extracted.file.absolutePath)
                    System.setProperty("org.sqlite.lib.path", directory.toString())
                    System.setProperty("org.sqlite.lib.name", extracted.file.name)
                }

                val initialized = hooks?.candidateInitialize?.invoke()
                    ?: SQLiteJDBCLoader.initialize()
                if (!initialized) {
                    throw IOException("sqlite-jdbc did not confirm the loaded native library")
                }
            } catch (failure: Throwable) {
                if (previousLibraryProperties != null) {
                    try {
                        restoreProperty("org.sqlite.lib.path", previousLibraryProperties.first)
                        restoreProperty("org.sqlite.lib.name", previousLibraryProperties.second)
                    } catch (restoreFailure: Throwable) {
                        failure.addSuppressed(restoreFailure)
                    }
                }
                throw failure
            }

            val attempt = NativeSqliteLoadAttempt(
                candidate = candidate.label,
                directory = redactSensitive(directory.toString()),
                directoryExisted = directoryExisted,
                directoryCreated = directoryCreated,
                writable = writable,
                extraction = redactSensitive(extraction)
            )
            return attempt to NativeSqliteStatus.Loaded(
                path = extracted.file.absolutePath,
                how = candidate.label,
                attempts = emptyList()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            val safeMessage = safeFailureMessage(failure)
            val attempt = NativeSqliteLoadAttempt(
                candidate = candidate.label,
                directory = redactSensitive(directory.toString()),
                directoryExisted = directoryExisted,
                directoryCreated = directoryCreated,
                writable = writable,
                extraction = redactSensitive(extraction),
                failureClass = failure.javaClass.name,
                failureMessage = safeMessage
            )
            return attempt to null
        }
    }

    private fun attemptDefaultLoader(
        attempts: MutableList<NativeSqliteLoadAttempt>,
        hooks: NativeSqliteTestHooks?
    ): NativeSqliteStatus.Loaded? {
        val defaultDirectory = System.getProperty("org.sqlite.tmpdir")
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.io.tmpdir").orEmpty()
        val directory = File(defaultDirectory).toPath().toAbsolutePath().normalize()
        var directoryExisted = false
        var writable = false
        val extraction = "delegated to sqlite-jdbc's default loader"

        try {
            directoryExisted = Files.isDirectory(directory)
            writable = directoryExisted && Files.isWritable(directory)
            val initialized = hooks?.defaultInitialize?.invoke()
                ?: SQLiteJDBCLoader.initialize()
            if (!initialized) throw IOException("sqlite-jdbc default loader returned false")

            val attempt = NativeSqliteLoadAttempt(
                candidate = "sqlite-jdbc default loader",
                directory = redactSensitive(directory.toString()),
                directoryExisted = directoryExisted,
                directoryCreated = false,
                writable = writable,
                extraction = extraction
            )
            attempts += attempt
            val loadedPath = listOfNotNull(
                System.getProperty("org.sqlite.lib.path"),
                System.getProperty("org.sqlite.lib.name")
            ).joinToString(File.separator)
                .ifBlank { directory.toString() }
            return NativeSqliteStatus.Loaded(
                path = loadedPath,
                how = "sqlite-jdbc default loader",
                attempts = attempts.toList()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            attempts += NativeSqliteLoadAttempt(
                candidate = "sqlite-jdbc default loader",
                directory = redactSensitive(directory.toString()),
                directoryExisted = directoryExisted,
                directoryCreated = false,
                writable = writable,
                extraction = extraction,
                failureClass = failure.javaClass.name,
                failureMessage = safeFailureMessage(failure)
            )
            return null
        }
    }

    private fun unavailableException(
        attempts: List<NativeSqliteLoadAttempt>,
        resourcePath: String?,
        requiresRestart: Boolean
    ): NativeSqliteUnavailableException {
        val safeAttempts = attempts.map { attempt ->
            attempt.copy(
                directory = redactSensitive(attempt.directory),
                extraction = redactSensitive(attempt.extraction),
                failureMessage = attempt.failureMessage?.let(::redactSensitive)
            )
        }
        val environment = "os=${System.getProperty("os.name", "unknown")} " +
            "${System.getProperty("os.arch", "unknown")}, java=${System.getProperty("java.version", "unknown")}"
        val summary = safeAttempts.mapIndexed { index, attempt ->
            "[${index + 1}] ${attempt.directory}: " +
                "${attempt.failureClass ?: "loaded"}: ${attempt.failureMessage ?: attempt.extraction}"
        }.joinToString("; ")
        val message = singleLine(
            redactSensitive("SQLite native library could not be loaded ($environment): $summary")
        )
        val diagnostics = redactSensitive(buildString {
            appendLine("SQLite native loader diagnostics")
            appendLine(environment)
            appendLine("java.vendor=${System.getProperty("java.vendor", "unknown")}")
            appendLine("java.io.tmpdir=${System.getProperty("java.io.tmpdir", "unknown")}")
            appendLine("user.home=~")
            appendLine("native resource=${resourcePath ?: "unavailable"}")
            safeAttempts.forEachIndexed { index, attempt ->
                appendLine(
                    "[${index + 1}] ${attempt.candidate}: directory=${attempt.directory}; " +
                        "existed=${attempt.directoryExisted}; created=${attempt.directoryCreated}; " +
                        "writable=${attempt.writable}; extraction=${attempt.extraction}; " +
                        "failure=${attempt.failureClass ?: "none"}: ${attempt.failureMessage.orEmpty()}"
                )
            }
        })
        return NativeSqliteUnavailableException(
            message = message,
            attempts = safeAttempts,
            requiresRestart = requiresRestart,
            diagnostics = diagnostics
        )
    }

    private fun safeFailureMessage(failure: Throwable): String =
        singleLine(redactSensitive(failure.message ?: failure.toString()))

    private fun redactSensitive(value: String): String {
        var redacted = value
        val replacements = linkedMapOf(
            System.getProperty("user.home") to "~",
            System.getenv("USERPROFILE") to "~",
            System.getenv("LOCALAPPDATA") to "%LOCALAPPDATA%",
            System.getProperty("user.name") to "<user>",
            System.getenv("USERNAME") to "<user>",
            System.getenv("COMPUTERNAME") to "<host>",
            System.getenv("HOSTNAME") to "<host>"
        )
        replacements.entries
            .filter { (sensitive, _) -> !sensitive.isNullOrBlank() }
            .sortedByDescending { (sensitive, _) -> sensitive!!.length }
            .forEach { (sensitive, replacement) ->
                val variants = setOf(
                    sensitive!!,
                    sensitive.replace('\\', '/'),
                    sensitive.replace('/', '\\')
                )
                variants.sortedByDescending(String::length).forEach { variant ->
                    redacted = redacted.replace(variant, replacement, ignoreCase = true)
                }
            }
        return redacted
    }

    private fun normalizeResourcePath(path: String): String =
        path.replace('\\', '/').trim('/')

    private fun singleLine(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }
}

internal data class NativeSqliteTestHooks(
    val candidates: List<NativeSqliteCandidate>? = null,
    val resource: NativeSqliteResource? = null,
    val systemLoad: ((String) -> Unit)? = null,
    val candidateInitialize: (() -> Boolean)? = null,
    val defaultInitialize: (() -> Boolean)? = null,
    val cacheSuccessfulLoad: Boolean = false
)
