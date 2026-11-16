package com.focusflow.services

import com.focusflow.enforcement.SystemTools

/**
 * Opens a Windows Settings page (an `ms-settings:` link).
 *
 * `Desktop.browse` is meant for web addresses and, on some machines, hands the link to the default
 * browser instead of Windows, so the Grant Permissions buttons opened a browser window. Windows'
 * own URL handler (`rundll32 url.dll,FileProtocolHandler`) opens the Settings app directly.
 *
 * Only `ms-settings:` links are accepted here: the handler would also run a file path or any other
 * registered scheme, and nothing else in the app needs this route.
 *
 * Everything that touches the OS is injected so the command shape and the fallback can be tested
 * without Windows.
 */
internal object WindowsSettingsLauncher {

    const val SECURITY = "ms-settings:windowsdefender"
    const val NOTIFICATIONS = "ms-settings:notifications"
    const val FOCUS_ASSIST = "ms-settings:quiethours"

    private const val SCHEME = "ms-settings:"
    private const val MAX_LENGTH = 200

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /** True for a plain `ms-settings:<page>` link: no spaces, control characters, quotes or shell characters. */
    fun isSettingsLink(link: String): Boolean {
        if (link.length > MAX_LENGTH || !link.startsWith(SCHEME, ignoreCase = true)) return false
        val page = link.substring(SCHEME.length)
        return page.isNotEmpty() && page.all { it.isLetterOrDigit() || it in "-_.:/=?&%" }
    }

    /** The exact command that opens [link], or null when [link] is not a Settings link. */
    fun command(link: String, systemRoot: String? = SystemTools.systemRoot()): List<String>? {
        if (!isSettingsLink(link)) return null
        return listOf(SystemTools.path("rundll32.exe", systemRoot), "url.dll,FileProtocolHandler", link)
    }

    /**
     * Opens [link]. Returns true when the Windows handler was started, and otherwise falls back to
     * [fallback] (the previous behaviour) and returns whether that worked.
     */
    fun open(
        link: String,
        windows: Boolean = isWindows,
        systemRoot: String? = SystemTools.systemRoot(),
        start: (List<String>) -> Unit = { ProcessBuilder(it).start() },
        fallback: (String) -> Unit = { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) },
    ): Boolean {
        val command = if (windows) command(link, systemRoot) else null
        if (command != null) {
            try {
                start(command)
                return true
            } catch (_: Throwable) {
                // Fall through to the generic route below.
            }
        }
        return try {
            fallback(link)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
