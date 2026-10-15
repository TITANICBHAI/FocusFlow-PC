package com.focusflow

import java.io.File

/**
 * Central paths within FocusFlow's per-user data directory.
 *
 * Each property is a getter so tests and profile changes are respected without
 * caching a path resolved from an earlier user.home value.
 */
object AppPaths {
    private const val DATA_DIRECTORY_NAME = ".focusflow"
    private const val DATABASE_FILE_NAME = "focusflow.db"
    private const val CONSENT_FILE_NAME = "telemetry-consent"

    val dataDir: File
        get() = dataDirForHome(System.getProperty("user.home"))

    val dbFile: File
        get() = File(dataDir, DATABASE_FILE_NAME)

    val backupDir: File
        get() = File(dataDir, "backups")

    val logDir: File
        get() = dataDir

    val consentFile: File
        get() = File(dataDir, CONSENT_FILE_NAME)

    val nativeDir: File
        get() = File(dataDir, "native")

    fun dataDirForHome(home: String?): File =
        File(home, DATA_DIRECTORY_NAME)

    fun consentFileForHome(home: String?): File =
        File(dataDirForHome(home), CONSENT_FILE_NAME)
}
