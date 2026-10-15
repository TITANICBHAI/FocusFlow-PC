package com.focusflow.services

import com.focusflow.AppPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class RecoveryNotice(
    val id: String,
    val title: String,
    val message: String,
    val archiveFile: File? = null
)

/**
 * Persistent, database-independent notices for recovery actions.
 *
 * The notice state lives outside SQLite so it remains available when the
 * database is corrupt or has just been recreated.
 */
object RecoveryNoticeService {
    private const val NOTICE_VERSION = "1"

    fun brokenArchiveNames(): List<String> {
        val prefix = "${AppPaths.dbFile.name}.broken_"
        return AppPaths.dataDir.listFiles { file ->
            file.isFile && file.name.startsWith(prefix)
        }?.map(File::getName).orEmpty()
    }

    fun pendingNotice(): RecoveryNotice? {
        val restoreNotice = AppPaths.restoreNoticeFile
        if (restoreNotice.isFile) {
            val properties = runCatching {
                Properties().also { restoreNotice.inputStream().use(it::load) }
            }.getOrNull()
            if (properties == null) {
                return RecoveryNotice(
                    id = "restore-corrupt-notice",
                    title = "Restore status",
                    message = "A restore status notice could not be read. Check the backup folder and the current database before attempting another restore."
                )
            }
            if (properties.getProperty("version") == NOTICE_VERSION) {
                return RecoveryNotice(
                    id = properties.getProperty("id") ?: "restore-status",
                    title = properties.getProperty("title") ?: "Backup and recovery status",
                    message = properties.getProperty("message")
                        ?: "FocusFlow completed a database recovery action. Review your backups before continuing."
                )
            }
        }

        val dataDirectory = AppPaths.dataDir
        val databaseName = AppPaths.dbFile.name
        val acknowledged = runCatching {
            if (AppPaths.recoveryNoticeSeenFile.isFile) {
                AppPaths.recoveryNoticeSeenFile.readLines().toSet()
            } else {
                emptySet()
            }
        }.getOrDefault(emptySet())
        val archive = dataDirectory.listFiles { file ->
            file.isFile && file.name.startsWith("$databaseName.broken_")
        }?.sortedByDescending(File::lastModified)
            ?.firstOrNull { it.name !in acknowledged }
            ?: return null

        return RecoveryNotice(
            id = "corrupt-db:${archive.name}",
            title = "Database recovery",
            message = "FocusFlow preserved an unreadable database file and its SQLite sidecars, then opened a fresh database. The previous data was not restored. You can review verified backups under Settings → Backup & Recovery. The preserved files have not been deleted.",
            archiveFile = archive
        )
    }

    fun acknowledge(notice: RecoveryNotice) {
        if (notice.archiveFile == null) {
            val file = AppPaths.restoreNoticeFile
            if (!file.isFile) return
            val currentId = runCatching {
                Properties().also { file.inputStream().use(it::load) }.getProperty("id")
            }.getOrNull()
            if (currentId == notice.id || notice.id == "restore-corrupt-notice") {
                Files.deleteIfExists(file.toPath())
            }
            return
        }

        val archive = notice.archiveFile.canonicalFile
        val dataDirectory = AppPaths.dataDir.canonicalFile
        require(archive.parentFile == dataDirectory)
        require(archive.name.startsWith("${AppPaths.dbFile.name}.broken_"))
        acknowledgeBrokenArchiveNames(listOf(archive.name))
    }

    fun acknowledgeBrokenArchiveNames(names: Iterable<String>) {
        val prefix = "${AppPaths.dbFile.name}.broken_"
        val validNames = names.filter {
            it.startsWith(prefix) && File(it).name == it && !it.contains('/') && !it.contains('\\')
        }
        if (validNames.isEmpty()) return
        val seenFile = AppPaths.recoveryNoticeSeenFile
        seenFile.parentFile?.mkdirs()
        val acknowledged = runCatching {
            if (seenFile.isFile) seenFile.readLines().toMutableSet() else mutableSetOf()
        }.getOrDefault(mutableSetOf())
        acknowledged += validNames
        writeAtomically(seenFile, acknowledged.sorted().joinToString("\n", postfix = "\n"))
    }

    fun writeRestoreNotice(title: String, message: String) {
        val properties = Properties().apply {
            setProperty("version", NOTICE_VERSION)
            setProperty("id", "restore-${UUID.randomUUID()}")
            setProperty("title", title)
            setProperty("message", message)
        }
        val destination = AppPaths.restoreNoticeFile
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { properties.store(it, "FocusFlow recovery notice") }
            moveReplacing(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun writeAtomically(destination: File, text: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary.toPath(), text)
            moveReplacing(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary.toPath())
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
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
