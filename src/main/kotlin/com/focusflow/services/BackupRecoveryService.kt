package com.focusflow.services

import com.focusflow.AppPaths
import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID

enum class BackupLocation {
    APP_DATA,
    SECONDARY
}

data class BackupEntry(
    val file: File,
    val location: BackupLocation,
    val verification: AutoBackupService.BackupVerification,
    val createdAtMillis: Long = file.lastModified()
)

data class RestoreArchiveEntry(
    val directory: File,
    val sizeBytes: Long,
    val modifiedAtMillis: Long
)

sealed interface SecondaryLocationUpdate {
    data class Saved(val directory: File) : SecondaryLocationUpdate
    data object Removed : SecondaryLocationUpdate
    data class Failed(val reason: String) : SecondaryLocationUpdate
}

sealed interface SecondaryCopyStatus {
    data object NotConfigured : SecondaryCopyStatus
    data class Copied(val file: File) : SecondaryCopyStatus
    data class AlreadyPresent(val file: File) : SecondaryCopyStatus
    data class Failed(val reason: String) : SecondaryCopyStatus
}

sealed interface SecondaryBackupDeleteResult {
    data object Deleted : SecondaryBackupDeleteResult
    data object NotInSecondaryFolder : SecondaryBackupDeleteResult
    data class Failed(val reason: String) : SecondaryBackupDeleteResult
}

sealed interface SafetyBackupDeleteResult {
    data object Deleted : SafetyBackupDeleteResult
    data object NotSafetyBackup : SafetyBackupDeleteResult
    data class Failed(val reason: String) : SafetyBackupDeleteResult
}

sealed interface RestoreArchiveDeleteResult {
    data object Deleted : RestoreArchiveDeleteResult
    data object NotManagedArchive : RestoreArchiveDeleteResult
    data class Failed(val reason: String) : RestoreArchiveDeleteResult
}

sealed interface RestoreScheduleResult {
    data class Scheduled(val sourceName: String) : RestoreScheduleResult
    data object AlreadyPending : RestoreScheduleResult
    data class Rejected(val reason: String) : RestoreScheduleResult
    data class Failed(val reason: String) : RestoreScheduleResult
}

/**
 * Secondary backup locations and restart-only database restoration.
 *
 * Restore requests are staged while FocusFlow is running. On the next process
 * start, FocusFlow opens the current database, creates and verifies a fresh
 * safety backup, closes SQLite, preserves the live DB/WAL/SHM set, and then
 * installs the selected verified snapshot. The journal remains until the new
 * database passes normal initialization.
 */
object BackupRecoveryService {
    private const val JOURNAL_VERSION = "1"
    private const val PREIMAGE_MANIFEST = "preimage.properties"
    private val archiveTimestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
    private val backupFileTimestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val backupFilenameTimestamp =
        Regex("""^focusflow_(?:safety_)?(\d{8}_\d{6})(?:_\d+)?\.db$""")

    private data class RestoreJournal(
        val state: String,
        val candidateHash: String,
        val sourceName: String? = null,
        val sourcePath: String? = null,
        val preRestoreDirectory: String? = null
    )

    fun secondaryBackupDirectory(): File? {
        val locationFile = AppPaths.secondaryBackupLocationFile
        if (!locationFile.isFile) return null
        val storedPath = locationFile.readText(Charsets.UTF_8).trim()
        if (storedPath.isBlank()) return null
        return File(storedPath).canonicalFile
    }

    fun configureSecondaryBackupDirectory(directory: File?): SecondaryLocationUpdate {
        val locationFile = AppPaths.secondaryBackupLocationFile
        return try {
            if (directory == null) {
                Files.deleteIfExists(locationFile.toPath())
                SecondaryLocationUpdate.Removed
            } else {
                if (!directory.isDirectory) {
                    return SecondaryLocationUpdate.Failed("Choose an existing folder.")
                }
                val canonical = directory.canonicalFile
                if (!isSafeSecondaryDirectory(canonical)) {
                    return SecondaryLocationUpdate.Failed(
                        "Choose a folder outside FocusFlow's app-data and backup folders."
                    )
                }
                locationFile.parentFile?.mkdirs()
                writeTextAtomically(locationFile, canonical.absolutePath)
                SecondaryLocationUpdate.Saved(canonical)
            }
        } catch (failure: Exception) {
            SecondaryLocationUpdate.Failed(failure.message ?: "Could not save the backup folder.")
        }
    }

    fun listBackupEntries(): List<BackupEntry> {
        val entries = mutableListOf<BackupEntry>()
        AutoBackupService.listBackups().forEach { file ->
            entries += BackupEntry(
                file = file,
                location = BackupLocation.APP_DATA,
                verification = AutoBackupService.verifyBackup(file),
                createdAtMillis = backupCreatedAtMillis(file)
            )
        }
        val secondary = secondaryBackupDirectory()
        if (secondary != null && secondary.isDirectory && isSafeSecondaryDirectory(secondary)) {
            secondary.listFiles { file ->
                file.isFile && file.name.startsWith("focusflow_") && file.name.endsWith(".db")
            }?.forEach { file ->
                entries += BackupEntry(
                    file = file,
                    location = BackupLocation.SECONDARY,
                    verification = AutoBackupService.verifyBackup(file),
                    createdAtMillis = backupCreatedAtMillis(file)
                )
            }
        }
        return entries.distinctBy { runCatching { it.file.canonicalPath }.getOrDefault(it.file.absolutePath) }
            .sortedByDescending { it.createdAtMillis }
    }

    internal fun backupCreatedAtMillis(file: File): Long {
        val encodedTimestamp = backupFilenameTimestamp.matchEntire(file.name)?.groupValues?.get(1)
        return encodedTimestamp?.let { timestamp ->
            runCatching {
                LocalDateTime.parse(timestamp, backupFileTimestamp)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        } ?: file.lastModified()
    }

    fun listRestoreArchives(): List<RestoreArchiveEntry> =
        AppPaths.restoreArchiveDir.listFiles { directory ->
            directory.isDirectory &&
                (directory.name.startsWith("pre-restore-") || directory.name.startsWith("failed-restore-")) &&
                File(directory, PREIMAGE_MANIFEST).isFile
        }?.map { directory ->
            val size = directory.walkTopDown()
                .filter(File::isFile)
                .sumOf(File::length)
            RestoreArchiveEntry(directory, size, directory.lastModified())
        }?.sortedByDescending { it.modifiedAtMillis }.orEmpty()

    @Synchronized
    fun mirrorVerifiedBackup(primaryFile: File): SecondaryCopyStatus {
        val destination = try {
            secondaryBackupDirectory()
        } catch (failure: Exception) {
            return SecondaryCopyStatus.Failed(
                "Could not read the selected backup folder: ${failure.message ?: failure.javaClass.simpleName}"
            )
        } ?: return SecondaryCopyStatus.NotConfigured

        if (!destination.isDirectory || !isSafeSecondaryDirectory(destination)) {
            return SecondaryCopyStatus.Failed("The selected secondary backup folder is unavailable or no longer safe.")
        }

        val primaryVerification = AutoBackupService.verifyBackup(primaryFile)
        val primaryHash = (primaryVerification as? AutoBackupService.BackupVerification.Verified)?.hash
            ?: return SecondaryCopyStatus.Failed("The app-data backup is not verified.")

        val canonicalDestination = runCatching { destination.canonicalFile }.getOrNull()
            ?: return SecondaryCopyStatus.Failed("Could not resolve the selected backup folder.")
        val target = uniqueSecondaryTarget(primaryFile, canonicalDestination, primaryHash)
        if (target.second) return SecondaryCopyStatus.AlreadyPresent(target.first)
        val finalDb = target.first
        val finalHash = sidecar(finalDb, "sha256")
        val finalMeta = sidecar(finalDb, "meta.json")
        val tempDb = File(canonicalDestination, ".focusflow-${UUID.randomUUID()}.db")
        val tempHash = sidecar(tempDb, "sha256")
        val tempMeta = sidecar(tempDb, "meta.json")
        var promotedHash = false
        var promotedMeta = false

        return try {
            Files.copy(primaryFile.toPath(), tempDb.toPath())
            val copiedHash = sha256(tempDb)
            if (copiedHash != primaryHash) {
                return SecondaryCopyStatus.Failed("The secondary copy did not match the verified source.")
            }
            val quickCheckFailure = AutoBackupService.sqliteQuickCheckFailureForRecovery(tempDb)
            if (quickCheckFailure != null) {
                return SecondaryCopyStatus.Failed(quickCheckFailure)
            }
            tempHash.writeText(copiedHash)
            tempMeta.writeText(
                AutoBackupService.readMeta(primaryFile)
                    ?: """{"timestamp":"${LocalDateTime.now()}","sizeBytes":${tempDb.length()},"sha256":"$copiedHash"}"""
            )
            if (AutoBackupService.verifyBackup(tempDb) !is AutoBackupService.BackupVerification.Verified) {
                return SecondaryCopyStatus.Failed("The staged secondary copy failed verification.")
            }

            moveWithoutReplacing(tempHash, finalHash)
            promotedHash = true
            moveWithoutReplacing(tempMeta, finalMeta)
            promotedMeta = true
            moveWithoutReplacing(tempDb, finalDb)
            SecondaryCopyStatus.Copied(finalDb)
        } catch (failure: Exception) {
            if (promotedHash) Files.deleteIfExists(finalHash.toPath())
            if (promotedMeta) Files.deleteIfExists(finalMeta.toPath())
            SecondaryCopyStatus.Failed(
                "Could not copy the backup to the selected folder: ${failure.message ?: failure.javaClass.simpleName}"
            )
        } finally {
            Files.deleteIfExists(tempDb.toPath())
            Files.deleteIfExists(tempHash.toPath())
            Files.deleteIfExists(tempMeta.toPath())
        }
    }

    @Synchronized
    fun deleteSecondaryBackup(file: File): SecondaryBackupDeleteResult {
        val secondary = try {
            secondaryBackupDirectory()
        } catch (failure: Exception) {
            return SecondaryBackupDeleteResult.Failed(
                "Could not read the selected backup folder: ${failure.message ?: failure.javaClass.simpleName}"
            )
        } ?: return SecondaryBackupDeleteResult.NotInSecondaryFolder

        val target = try {
            file.canonicalFile
        } catch (failure: Exception) {
            return SecondaryBackupDeleteResult.Failed("Could not resolve this backup file.")
        }
        if (!isSafeSecondaryDirectory(secondary) ||
            target.parentFile != secondary.canonicalFile ||
            !target.name.startsWith("focusflow_") ||
            !target.name.endsWith(".db") ||
            !target.isFile
        ) {
            return SecondaryBackupDeleteResult.NotInSecondaryFolder
        }

        return try {
            Files.deleteIfExists(target.toPath())
            Files.deleteIfExists(sidecar(target, "sha256").toPath())
            Files.deleteIfExists(sidecar(target, "meta.json").toPath())
            SecondaryBackupDeleteResult.Deleted
        } catch (failure: Exception) {
            SecondaryBackupDeleteResult.Failed(
                "Could not remove the selected FocusFlow backup: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    @Synchronized
    fun deleteSafetyBackup(file: File): SafetyBackupDeleteResult {
        val target = try {
            file.canonicalFile
        } catch (failure: Exception) {
            return SafetyBackupDeleteResult.Failed("Could not resolve this safety backup.")
        }
        if (target.parentFile != AppPaths.backupDir.canonicalFile ||
            !target.name.startsWith("focusflow_safety_") ||
            !target.name.endsWith(".db") ||
            !target.isFile
        ) {
            return SafetyBackupDeleteResult.NotSafetyBackup
        }
        return try {
            Files.deleteIfExists(target.toPath())
            Files.deleteIfExists(sidecar(target, "sha256").toPath())
            Files.deleteIfExists(sidecar(target, "meta.json").toPath())
            SafetyBackupDeleteResult.Deleted
        } catch (failure: Exception) {
            SafetyBackupDeleteResult.Failed(
                "Could not remove the selected safety backup: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    @Synchronized
    fun deleteRestoreArchive(directory: File): RestoreArchiveDeleteResult {
        val target = try {
            directory.canonicalFile
        } catch (_: Exception) {
            return RestoreArchiveDeleteResult.NotManagedArchive
        }
        val archiveRoot = AppPaths.restoreArchiveDir.canonicalFile
        if (target.parentFile != archiveRoot ||
            !target.isDirectory ||
            !(target.name.startsWith("pre-restore-") || target.name.startsWith("failed-restore-")) ||
            !File(target, PREIMAGE_MANIFEST).isFile
        ) {
            return RestoreArchiveDeleteResult.NotManagedArchive
        }
        if (AppPaths.pendingRestoreFile.isFile) {
            val pending = try {
                readJournal()
            } catch (failure: Exception) {
                return RestoreArchiveDeleteResult.Failed(
                    "FocusFlow could not verify whether this archive is in use: ${failure.message ?: failure.javaClass.simpleName}"
                )
            }
            if (pending?.preRestoreDirectory == target.name) {
                return RestoreArchiveDeleteResult.Failed("This archive is protecting a pending restore and cannot be removed yet.")
            }
        }
        return try {
            if (!target.deleteRecursively()) {
                RestoreArchiveDeleteResult.Failed("Could not remove the selected recovery archive.")
            } else {
                RestoreArchiveDeleteResult.Deleted
            }
        } catch (failure: Exception) {
            RestoreArchiveDeleteResult.Failed(
                "Could not remove the selected recovery archive: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    fun hasPendingRestore(): Boolean = AppPaths.pendingRestoreFile.isFile

    @Synchronized
    fun scheduleRestore(sourceFile: File): RestoreScheduleResult {
        if (AppPaths.pendingRestoreFile.exists()) return RestoreScheduleResult.AlreadyPending
        val source = runCatching { sourceFile.canonicalFile }.getOrNull()
            ?: return RestoreScheduleResult.Rejected("Could not resolve the selected backup.")
        if (!isKnownBackup(source)) {
            return RestoreScheduleResult.Rejected("Choose a backup listed by FocusFlow.")
        }
        val verification = AutoBackupService.verifyBackup(source)
        if (verification !is AutoBackupService.BackupVerification.Verified) {
            return RestoreScheduleResult.Rejected(
                when (verification) {
                    AutoBackupService.BackupVerification.Unverified ->
                        "This backup has no trusted hash and cannot be restored."
                    is AutoBackupService.BackupVerification.Invalid ->
                        "This backup failed verification: ${verification.reason}"
                    is AutoBackupService.BackupVerification.Verified -> error("Already handled")
                }
            )
        }

        val staging = AppPaths.restoreStagingDir
        val candidate = candidateFile()
        val candidateHash = sidecar(candidate, "sha256")
        val candidateMeta = sidecar(candidate, "meta.json")
        val temporary = File(staging, ".candidate-${UUID.randomUUID()}.db")
        val temporaryHash = sidecar(temporary, "sha256")
        val temporaryMeta = sidecar(temporary, "meta.json")
        return try {
            if (!staging.exists() && !staging.mkdirs()) {
                return RestoreScheduleResult.Failed("Could not create the restore staging folder.")
            }
            Files.copy(source.toPath(), temporary.toPath())
            val stagedHash = sha256(temporary)
            if (stagedHash != verification.hash) {
                return RestoreScheduleResult.Failed("The staged backup did not match the verified source.")
            }
            temporaryHash.writeText(stagedHash)
            temporaryMeta.writeText(
                AutoBackupService.readMeta(source)
                    ?: """{"timestamp":"${LocalDateTime.now()}","sizeBytes":${temporary.length()},"sha256":"$stagedHash"}"""
            )
            if (AutoBackupService.verifyBackup(temporary) !is AutoBackupService.BackupVerification.Verified) {
                return RestoreScheduleResult.Failed("The staged backup failed verification.")
            }
            Files.deleteIfExists(candidate.toPath())
            Files.deleteIfExists(candidateHash.toPath())
            Files.deleteIfExists(candidateMeta.toPath())
            moveReplacing(temporary, candidate)
            moveReplacing(temporaryHash, candidateHash)
            moveReplacing(temporaryMeta, candidateMeta)
            writeJournal(
                RestoreJournal(
                    state = "prepared",
                    candidateHash = stagedHash,
                    sourceName = source.name,
                    sourcePath = source.absolutePath
                )
            )
            RestoreScheduleResult.Scheduled(source.name)
        } catch (failure: Exception) {
            RestoreScheduleResult.Failed(
                "Could not stage the selected backup: ${failure.message ?: failure.javaClass.simpleName}"
            )
        } finally {
            Files.deleteIfExists(temporary.toPath())
            Files.deleteIfExists(temporaryHash.toPath())
            Files.deleteIfExists(temporaryMeta.toPath())
        }
    }

    /**
     * Called after the single-instance lock is held and before normal startup
     * services start. It returns the database state that Main should use.
     */
    @Synchronized
    fun initializeDatabaseForStartup(): DbInitResult {
        if (!AppPaths.pendingRestoreFile.exists()) return Database.init()

        val journal = try {
            readJournal()
        } catch (failure: Exception) {
            return DbInitResult.Failed(
                IOException("The pending restore journal is unreadable; the database was not opened.", failure),
                AppPaths.dbFile
            )
        } ?: return Database.init()

        if (journal.state == "applying") {
            Database.close()
            if (!rollbackFromArchive(journal.preRestoreDirectory)) {
                return DbInitResult.Failed(
                    IOException("An interrupted restore could not be rolled back. Preserved files remain in ${AppPaths.restoreArchiveDir}."),
                    AppPaths.dbFile
                )
            }
            clearPendingRestoreFiles()
            RecoveryNoticeService.writeRestoreNotice(
                "Interrupted restore was rolled back",
                "FocusFlow detected an interrupted restore and restored the database files saved before it. The selected backup was not applied. You can review backups under Settings → Backup & Recovery."
            )
            return Database.init()
        }

        if (journal.state != "prepared") {
            return DbInitResult.Failed(
                IOException("The pending restore has an unsupported state; the database was not changed."),
                AppPaths.dbFile
            )
        }

        val currentDatabaseResult = Database.init()
        if (currentDatabaseResult != DbInitResult.Ready) return currentDatabaseResult

        val candidate = candidateFile()
        val candidateVerification = AutoBackupService.verifyBackup(candidate)
        if (candidateVerification !is AutoBackupService.BackupVerification.Verified ||
            candidateVerification.hash != journal.candidateHash
        ) {
            cancelPreparedRestore()
            RecoveryNoticeService.writeRestoreNotice(
                "Restore not applied",
                "The staged backup no longer passes verification. FocusFlow left the current database unchanged and cancelled the pending restore. Select a verified backup to try again."
            )
            return DbInitResult.Ready
        }

        val sourceToPreserve = journal.sourcePath
            ?.let(::File)
            ?.takeIf { runCatching { isKnownBackup(it.canonicalFile) }.getOrDefault(false) }
        val safetyBackup = AutoBackupService.runBackupNowPreserving(
            preserveBackup = sourceToPreserve,
            safetyBackup = true
        )
        if (safetyBackup !is AutoBackupService.BackupResult.Success ||
            AutoBackupService.verifyBackup(safetyBackup.file) !is AutoBackupService.BackupVerification.Verified
        ) {
            cancelPreparedRestore()
            RecoveryNoticeService.writeRestoreNotice(
                "Restore not applied",
                "FocusFlow could not create and verify a safety backup of the current database. The current database was left unchanged and the pending restore was cancelled."
            )
            return DbInitResult.Ready
        }

        Database.close()
        val preRestoreDirectory = try {
            archiveCurrentDatabaseSet()
        } catch (failure: Exception) {
            cancelPreparedRestore()
            RecoveryNoticeService.writeRestoreNotice(
                "Restore not applied",
                "FocusFlow could not preserve the current database files before restore: ${failure.message ?: failure.javaClass.simpleName}. The selected backup was not applied."
            )
            return Database.init()
        }

        val applyingJournal = journal.copy(
            state = "applying",
            preRestoreDirectory = preRestoreDirectory.name
        )
        try {
            writeJournal(applyingJournal)
        } catch (failure: Exception) {
            val reopened = Database.init()
            cancelPreparedRestore()
            RecoveryNoticeService.writeRestoreNotice(
                "Restore not applied",
                "FocusFlow could not save the restore journal. The current database was not replaced."
            )
            return reopened
        }

        val installFailure = installCandidate(candidate, journal.candidateHash)
        if (installFailure != null) {
            return rollbackAndReopen(
                applyingJournal,
                "FocusFlow could not install the selected backup: $installFailure"
            )
        }

        val brokenBeforeOpen = RecoveryNoticeService.brokenArchiveNames().toSet()
        val restoredResult = Database.init()
        val newlyBrokenNames = RecoveryNoticeService.brokenArchiveNames()
            .filterNot { it in brokenBeforeOpen }
        val newlyBroken = newlyBrokenNames.isNotEmpty()
        if (restoredResult == DbInitResult.Ready && !newlyBroken) {
            clearPendingRestoreFiles()
            RecoveryNoticeService.writeRestoreNotice(
                "Backup restored",
                "FocusFlow restored ${journalSourceName(journal)}. A verified safety backup and the previous database files were preserved in the backup folder."
            )
            return DbInitResult.Ready
        }

        Database.close()
        return rollbackAndReopen(
            applyingJournal,
            "The selected backup did not initialize successfully. FocusFlow restored the database files that were present before the restore.",
            newlyBrokenNames
        )
    }

    private fun rollbackAndReopen(
        journal: RestoreJournal,
        notice: String,
        archivesToAcknowledge: List<String> = emptyList()
    ): DbInitResult {
        if (!rollbackFromArchive(journal.preRestoreDirectory)) {
            return DbInitResult.Failed(
                IOException("Restore failed and the pre-restore database set could not be reinstated. Preserved files remain in ${AppPaths.restoreArchiveDir}."),
                AppPaths.dbFile
            )
        }
        clearPendingRestoreFiles()
        RecoveryNoticeService.acknowledgeBrokenArchiveNames(archivesToAcknowledge)
        val reopened = Database.init()
        RecoveryNoticeService.writeRestoreNotice("Restore rolled back", notice)
        return reopened
    }

    private fun journalSourceName(journal: RestoreJournal): String =
        journal.sourceName?.takeIf(String::isNotBlank) ?: "the selected verified backup"

    private fun uniqueSecondaryTarget(primaryFile: File, directory: File, hash: String): Pair<File, Boolean> {
        val original = File(directory, primaryFile.name)
        if (!backupArtifactsExist(original)) return original to false
        if (AutoBackupService.verifyBackup(original) is AutoBackupService.BackupVerification.Verified &&
            (AutoBackupService.verifyBackup(original) as AutoBackupService.BackupVerification.Verified).hash == hash
        ) {
            return original to true
        }
        var copyIndex = 1
        while (true) {
            val candidate = File(
                directory,
                "${primaryFile.nameWithoutExtension}_secondary_${copyIndex.toString().padStart(2, '0')}.db"
            )
            if (!backupArtifactsExist(candidate)) return candidate to false
            val verification = AutoBackupService.verifyBackup(candidate)
            if (verification is AutoBackupService.BackupVerification.Verified && verification.hash == hash) {
                return candidate to true
            }
            copyIndex++
        }
    }

    private fun backupArtifactsExist(file: File): Boolean =
        file.exists() || sidecar(file, "sha256").exists() || sidecar(file, "meta.json").exists()

    private fun isKnownBackup(file: File): Boolean {
        if (!file.name.startsWith("focusflow_") || !file.name.endsWith(".db")) return false
        val parent = file.canonicalFile.parentFile
        val primary = AppPaths.backupDir.canonicalFile
        if (parent == primary) return true
        val secondary = secondaryBackupDirectory()?.canonicalFile
        return secondary != null && isSafeSecondaryDirectory(secondary) && parent == secondary
    }

    private fun isSafeSecondaryDirectory(directory: File): Boolean {
        val selected = directory.canonicalFile.toPath().normalize()
        val appData = AppPaths.dataDir.canonicalFile.toPath().normalize()
        return selected != appData &&
            !selected.startsWith(appData) &&
            !appData.startsWith(selected)
    }

    private fun candidateFile(): File = File(AppPaths.restoreStagingDir, "candidate.db")

    private fun candidateMetaFile(): File = sidecar(candidateFile(), "meta.json")

    private fun writeJournal(journal: RestoreJournal) {
        val properties = Properties().apply {
            setProperty("version", JOURNAL_VERSION)
            setProperty("state", journal.state)
            setProperty("candidateHash", journal.candidateHash)
            journal.sourceName?.let { setProperty("sourceName", it) }
            journal.sourcePath?.let { setProperty("sourcePath", it) }
            journal.preRestoreDirectory?.let { setProperty("preRestoreDirectory", it) }
        }
        val destination = AppPaths.pendingRestoreFile
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { properties.store(it, "FocusFlow pending database restore") }
            moveReplacing(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun readJournal(): RestoreJournal? {
        val file = AppPaths.pendingRestoreFile
        if (!file.isFile) return null
        val properties = Properties().also { file.inputStream().use(it::load) }
        if (properties.getProperty("version") != JOURNAL_VERSION) {
            throw IOException("Unsupported pending restore journal version.")
        }
        val state = properties.getProperty("state") ?: throw IOException("Restore state is missing.")
        val hash = properties.getProperty("candidateHash")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw IOException("Restore candidate hash is invalid.")
        val sourceName = properties.getProperty("sourceName")
            ?.takeIf { it.isNotBlank() && File(it).name == it }
        val sourcePath = properties.getProperty("sourcePath")
        val preRestoreDirectory = properties.getProperty("preRestoreDirectory")
        if (state == "applying" && (preRestoreDirectory.isNullOrBlank() ||
                preRestoreDirectory.contains('/') || preRestoreDirectory.contains('\\') ||
                preRestoreDirectory == "." || preRestoreDirectory == "..")
        ) {
            throw IOException("Pre-restore archive path is invalid.")
        }
        return RestoreJournal(state, hash, sourceName, sourcePath, preRestoreDirectory)
    }

    private fun archiveCurrentDatabaseSet(): File {
        val liveDatabase = AppPaths.dbFile
        if (!liveDatabase.isFile) throw IOException("The current database file is missing.")
        val archiveRoot = AppPaths.restoreArchiveDir
        if (!archiveRoot.exists() && !archiveRoot.mkdirs()) {
            throw IOException("Could not create the pre-restore archive folder.")
        }
        val name = "pre-restore-${LocalDateTime.now().format(archiveTimestamp)}-${UUID.randomUUID().toString().take(8)}"
        val archive = File(archiveRoot, name)
        if (!archive.mkdir()) throw IOException("Could not create the pre-restore archive.")

        val present = mutableSetOf<String>()
        val sourceFiles = listOf(
            liveDatabase to "focusflow.db",
            File("${liveDatabase.absolutePath}-wal") to "focusflow.db-wal",
            File("${liveDatabase.absolutePath}-shm") to "focusflow.db-shm"
        )
        sourceFiles.forEach { (source, archiveName) ->
            if (source.isFile) {
                val destination = File(archive, archiveName)
                Files.copy(source.toPath(), destination.toPath())
                if (!sameContents(source, destination)) {
                    throw IOException("Pre-restore copy verification failed for ${source.name}.")
                }
                present += archiveName
            }
        }
        if ("focusflow.db" !in present) throw IOException("The current database could not be archived.")
        val manifest = Properties().apply {
            setProperty("version", JOURNAL_VERSION)
            present.forEach {
                setProperty("present.$it", "true")
                setProperty("sha256.$it", sha256(File(archive, it)))
            }
        }
        val manifestFile = File(archive, PREIMAGE_MANIFEST)
        manifestFile.outputStream().use { manifest.store(it, "FocusFlow pre-restore database set") }
        return archive
    }

    private fun installCandidate(candidate: File, expectedHash: String): String? {
        val liveDatabase = AppPaths.dbFile
        val temporaryDb = File(liveDatabase.parentFile, ".${liveDatabase.name}.${UUID.randomUUID()}.restore")
        val temporaryHash = sidecar(temporaryDb, "sha256")
        return try {
            Files.copy(candidate.toPath(), temporaryDb.toPath())
            temporaryHash.writeText(expectedHash)
            val verification = AutoBackupService.verifyBackup(temporaryDb)
            if (verification !is AutoBackupService.BackupVerification.Verified ||
                verification.hash != expectedHash
            ) {
                return "The candidate copy failed its integrity check."
            }
            Files.deleteIfExists(File("${liveDatabase.absolutePath}-wal").toPath())
            Files.deleteIfExists(File("${liveDatabase.absolutePath}-shm").toPath())
            moveReplacing(temporaryDb, liveDatabase)
            null
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.simpleName
        } finally {
            Files.deleteIfExists(temporaryDb.toPath())
            Files.deleteIfExists(temporaryHash.toPath())
        }
    }

    private fun rollbackFromArchive(preRestoreDirectory: String?): Boolean {
        if (preRestoreDirectory.isNullOrBlank() ||
            preRestoreDirectory.contains('/') ||
            preRestoreDirectory.contains('\\') ||
            preRestoreDirectory == "." ||
            preRestoreDirectory == ".."
        ) return false

        val archive = File(AppPaths.restoreArchiveDir, preRestoreDirectory).canonicalFile
        if (archive.parentFile != AppPaths.restoreArchiveDir.canonicalFile) return false
        val manifestFile = File(archive, PREIMAGE_MANIFEST)
        if (!manifestFile.isFile) return false
        val manifest = runCatching {
            Properties().also { manifestFile.inputStream().use(it::load) }
        }.getOrNull() ?: return false
        if (manifest.getProperty("version") != JOURNAL_VERSION) return false
        val expectedFiles = listOf("focusflow.db", "focusflow.db-wal", "focusflow.db-shm")
            .filter { manifest.getProperty("present.$it") == "true" }
        if ("focusflow.db" !in expectedFiles ||
            expectedFiles.any { name ->
                val archived = File(archive, name)
                !archived.isFile ||
                    manifest.getProperty("sha256.$name")?.let { it == sha256(archived) } != true
            }
        ) return false

        val live = AppPaths.dbFile
        val failedArchive = File(
            AppPaths.restoreArchiveDir,
            "failed-restore-${LocalDateTime.now().format(archiveTimestamp)}-${UUID.randomUUID().toString().take(8)}"
        )
        if (!failedArchive.mkdir()) return false
        val activeFiles = listOf(
            live to "focusflow.db",
            File("${live.absolutePath}-wal") to "focusflow.db-wal",
            File("${live.absolutePath}-shm") to "focusflow.db-shm"
        )
        try {
            activeFiles.forEach { (current, name) ->
                if (current.isFile) {
                    val copy = File(failedArchive, name)
                    Files.copy(current.toPath(), copy.toPath())
                    if (!sameContents(current, copy)) throw IOException("Could not preserve failed restore file ${current.name}.")
                }
            }

            val temporaryDirectory = File(AppPaths.dataDir, ".restore-rollback-${UUID.randomUUID()}")
            if (!temporaryDirectory.mkdir()) throw IOException("Could not create rollback staging folder.")
            try {
                expectedFiles.forEach { name ->
                    val source = File(archive, name)
                    val target = File(temporaryDirectory, name)
                    Files.copy(source.toPath(), target.toPath())
                    if (!sameContents(source, target)) {
                        throw IOException("Could not verify rollback copy of $name.")
                    }
                }
                activeFiles.forEach { (current, _) -> Files.deleteIfExists(current.toPath()) }
                expectedFiles.forEach { name ->
                    val staged = File(temporaryDirectory, name)
                    val destination = when (name) {
                        "focusflow.db" -> live
                        "focusflow.db-wal" -> File("${live.absolutePath}-wal")
                        else -> File("${live.absolutePath}-shm")
                    }
                    moveReplacing(staged, destination)
                }
                expectedFiles.all { name ->
                    val source = File(archive, name)
                    val restored = when (name) {
                        "focusflow.db" -> live
                        "focusflow.db-wal" -> File("${live.absolutePath}-wal")
                        else -> File("${live.absolutePath}-shm")
                    }
                    restored.isFile && sameContents(source, restored)
                }
            } finally {
                temporaryDirectory.deleteRecursively()
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    private fun cancelPreparedRestore() {
        clearPendingRestoreFiles()
    }

    private fun clearPendingRestoreFiles() {
        Files.deleteIfExists(AppPaths.pendingRestoreFile.toPath())
        val candidate = candidateFile()
        Files.deleteIfExists(candidate.toPath())
        Files.deleteIfExists(sidecar(candidate, "sha256").toPath())
        Files.deleteIfExists(sidecar(candidate, "meta.json").toPath())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sameContents(first: File, second: File): Boolean =
        first.length() == second.length() && sha256(first) == sha256(second)

    private fun sidecar(file: File, extension: String): File =
        File(file.parentFile, "${file.nameWithoutExtension}.$extension")

    private fun writeTextAtomically(destination: File, text: String) {
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary.toPath(), text)
            moveReplacing(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun moveWithoutReplacing(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
