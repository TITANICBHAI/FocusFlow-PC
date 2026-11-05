package com.focusflow.enforcement

/**
 * Decides whether a `msiexec` command line is an attempt to REMOVE FocusFlow.
 *
 * Every Windows route to an MSI-based uninstall ends in a `msiexec.exe` client
 * process: Settings > Installed apps, Control Panel, `winget uninstall`,
 * PowerShell `Uninstall-Package`, and a plain `msiexec /x` from a terminal.
 * Matching the client's command line therefore covers all of them, whether or
 * not the registry UninstallString redirect to FocusFlow's own wizard is in
 * place.
 *
 * This is pure string logic with no Windows calls so it can be unit-tested.
 */
internal object UninstallCommandMatcher {

    private const val GUID_BODY = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    private val GUID = Regex("\\{$GUID_BODY\\}")

    /** `/x`, `-x` or `/uninstall`, with or without a space before the product code or path. */
    private val REMOVE_SWITCH = Regex("""(?:^|[\s"'])[/-](?:x|uninstall)(?=[\s{"']|$)""")

    /** `REMOVE=ALL`, which also removes a product when passed with `/i`. */
    private val REMOVE_PROPERTY = Regex("""(?:^|\s)remove\s*=\s*"?all\b""")

    /**
     * `/I{product-code}` opens maintenance mode for an already-installed
     * product, which is also how older registrations expose "Remove".
     */
    private val MAINTENANCE_BY_CODE =
        Regex("""(?:^|[\s"'])[/-]i\s*"?(\{$GUID_BODY\})""", RegexOption.IGNORE_CASE)

    /** Upper-cased Windows Installer product codes found in any of the given strings. */
    fun productCodesIn(vararg texts: String?): Set<String> =
        texts.asSequence()
            .filterNotNull()
            .flatMap { GUID.findAll(it).map { match -> match.value.uppercase() } }
            .toSet()

    /**
     * True when [commandLine] is a `msiexec` invocation that removes FocusFlow.
     *
     * [productCodes] are FocusFlow's installed product codes (see
     * [productCodesIn]). When they are unknown the match falls back to the word
     * "focusflow" appearing in the command line, which catches
     * `msiexec /x C:\...\FocusFlow-1.0.msi`.
     *
     * A plain `/i package.msi` (install or major upgrade) is deliberately not a
     * removal request, so installing a newer FocusFlow over the old one is not
     * blocked.
     */
    fun isFocusFlowRemoval(commandLine: String?, productCodes: Set<String>): Boolean {
        val line = commandLine?.trim().orEmpty()
        if (line.isEmpty()) return false
        val lower = line.lowercase()
        if (!lower.contains("msiexec")) return false

        val knownCodes = productCodes.mapTo(HashSet()) { it.uppercase() }

        val maintenanceOnFocusFlow = MAINTENANCE_BY_CODE.findAll(line)
            .any { it.groupValues[1].uppercase() in knownCodes }
        if (maintenanceOnFocusFlow) return true

        val removal = REMOVE_SWITCH.containsMatchIn(lower) || REMOVE_PROPERTY.containsMatchIn(lower)
        if (!removal) return false

        val namesFocusFlow = knownCodes.any { lower.contains(it.lowercase()) } ||
            lower.contains("focusflow")
        return namesFocusFlow
    }
}
