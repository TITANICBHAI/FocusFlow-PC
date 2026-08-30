package com.focusflow.enforcement

/**
 * Identifies the Windows distribution channel at runtime.
 *
 * A Store/MSIX package is owned by Windows, so FocusFlow must not try to guard
 * its removal.  The direct EXE/MSI builds are the only builds where the app's
 * own quit/uninstaller protections are appropriate.
 */
object InstallVariant {

    private val windows = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * MSIX apps normally run from WindowsApps and receive package identity
     * environment variables.  Checking both signals keeps this safe across
     * Store and sideloaded MSIX launches without requiring a native WinRT
     * dependency in the desktop app.
     */
    val isMsix: Boolean by lazy {
        if (!windows) return@lazy false

        val packageIdentityPresent = listOf(
            "PACKAGE_FAMILY_NAME",
            "APPX_PACKAGE_FAMILY_NAME",
            "APPX_PACKAGE_NAME"
        ).any { !System.getenv(it).isNullOrBlank() }

        val executablePath = runCatching {
            ProcessHandle.current().info().command().orElse("")
        }.getOrDefault("").replace('/', '\\')

        val resourcePath = System.getProperty("compose.application.resources.dir", "")
            .replace('/', '\\')
        packageIdentityPresent ||
            executablePath.contains("\\windowsapps\\", ignoreCase = true) ||
            resourcePath.contains("\\windowsapps\\", ignoreCase = true)
    }

    val isWindowsDirectInstall: Boolean
        get() = windows && !isMsix

    /** Used by the dashboard to avoid advertising the direct installer on non-Windows. */
    val isWindows: Boolean
        get() = windows
}