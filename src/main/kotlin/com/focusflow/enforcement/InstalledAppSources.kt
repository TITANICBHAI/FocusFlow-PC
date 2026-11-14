package com.focusflow.enforcement

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Pure helpers for the installed-app scan: extra places apps are registered, and rules for
 * what the person never wants to see in an app list. The Windows registry reads themselves
 * stay in [InstalledAppsScanner]; everything here works on plain values or files so it can
 * be tested anywhere.
 */
internal object InstalledAppSources {

    // ── What is never a "program the person opens" ───────────────────────────

    private val runtimeNamePattern = Regex(
        "(?i)(redistributable|\\bruntime\\b|webview2?|vcredist|visual c\\+\\+ \\d|" +
            "\\.net (core|framework|sdk|desktop|runtime)|windows (software development kit|driver package|sdk)|" +
            "update health tools|\\bservice pack\\b|\\bhotfix\\b|language pack|debug symbols)"
    )

    /**
     * Installer-registry entries that are plumbing, not applications: runtimes and
     * redistributables, updates and patches, and anything the installer marked as a system
     * component.
     */
    fun isRuntimeOrComponentEntry(
        displayName: String,
        systemComponent: Boolean,
        hasParent: Boolean,
        releaseType: String?
    ): Boolean {
        if (systemComponent || hasParent) return true
        val release = releaseType?.trim()?.lowercase(Locale.ROOT)
        if (release != null && release in setOf("update", "hotfix", "security update", "service pack")) return true
        return runtimeNamePattern.containsMatchIn(displayName)
    }

    private val helperProcessPattern = Regex(
        "(?i)(webview2?|crashpad|crashhandler|crashreporter|elevation_service|notification_helper|" +
            "identity_helper|cefsubprocess|browsersubprocess|edgeupdate|vcredist|vc_redist|dxsetup)"
    )

    /** Background helper or runtime executables: never shown as something to launch or block. */
    fun isBackgroundHelperProcess(processName: String): Boolean =
        helperProcessPattern.containsMatchIn(
            processName.replace('\\', '/').substringAfterLast('/')
        )

    // ── Windows folder programs that are real apps ───────────────────────────

    private val windowsFolderAllowed = setOf("mspaint.exe", "wordpad.exe")

    /**
     * Programs inside the Windows folder are system tools, except a few ordinary apps that
     * Windows keeps there.
     */
    fun isWindowsFolderSystemTool(exePath: String, systemRoot: String?): Boolean {
        val path = exePath.replace('/', '\\').lowercase(Locale.ROOT)
        val name = path.substringAfterLast('\\')
        if (name in windowsFolderAllowed) return false
        val root = systemRoot?.replace('/', '\\')?.trimEnd('\\')?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank) ?: "c:\\windows"
        return path.startsWith("$root\\")
    }

    // ── App Paths registry values ─────────────────────────────────────────────

    /** The program path out of an App Paths default value (quoted or not, maybe with arguments). */
    fun appPathsTarget(defaultValue: String?): String? {
        val raw = defaultValue?.trim()?.takeIf(String::isNotBlank) ?: return null
        return executablePathFromDisplayIcon(raw)
    }

    // ── Start Menu shortcuts ──────────────────────────────────────────────────

    private val nonProgramShortcut = Regex(
        "(?i)(uninstall|readme|read me|release notes|documentation|user guide|\\bmanual\\b|\\bhelp\\b|" +
            "licen[sc]e|\\beula\\b|web ?site|on the web|changelog|what'?s new|\\brepair\\b|check for updates)"
    )

    /** Shortcuts that open documentation, a website or an uninstaller instead of the program. */
    fun isProgramShortcutName(shortcutName: String): Boolean =
        shortcutName.isNotBlank() && !nonProgramShortcut.containsMatchIn(shortcutName)

    /**
     * Reads the program a Windows shortcut (.lnk) points to, using the documented
     * MS-SHLLINK layout: header, optional item-id list, then the LinkInfo block that holds
     * the local target path. Returns null for anything unexpected — shortcuts that only
     * hold a shell item (Store apps, folders) have no such path.
     */
    fun parseShellLinkTarget(bytes: ByteArray): String? {
        try {
            if (bytes.size < 0x4C + 4 || bytes.size > MAX_LINK_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt(0) != 0x4C) return null
            val flags = buf.getInt(0x14)
            val hasIdList = flags and 0x01 != 0
            val hasLinkInfo = flags and 0x02 != 0
            if (!hasLinkInfo) return null

            var pos = 0x4C
            if (hasIdList) {
                val idListSize = buf.getShort(pos).toInt() and 0xFFFF
                pos += 2 + idListSize
            }
            if (pos + 0x1C > bytes.size) return null

            val infoStart = pos
            val infoSize = buf.getInt(infoStart)
            if (infoSize < 0x1C || infoStart + infoSize > bytes.size) return null
            val headerSize = buf.getInt(infoStart + 4)
            val infoFlags = buf.getInt(infoStart + 8)
            if (infoFlags and 0x01 == 0) return null // no local base path

            val localBaseOffset = buf.getInt(infoStart + 16)
            val suffixOffset = buf.getInt(infoStart + 24)
            val infoEnd = infoStart + infoSize

            var base: String? = null
            var suffix: String? = null
            if (headerSize >= 0x24 && infoSize >= 0x24) {
                val baseUnicode = buf.getInt(infoStart + 28)
                val suffixUnicode = buf.getInt(infoStart + 32)
                if (baseUnicode > 0) base = readUtf16(bytes, infoStart + baseUnicode, infoEnd)
                if (suffixUnicode > 0) suffix = readUtf16(bytes, infoStart + suffixUnicode, infoEnd)
            }
            if (base == null && localBaseOffset > 0) {
                base = readAnsi(bytes, infoStart + localBaseOffset, infoEnd)
            }
            if (suffix == null && suffixOffset > 0) {
                suffix = readAnsi(bytes, infoStart + suffixOffset, infoEnd)
            }
            val basePath = base?.takeIf(String::isNotBlank) ?: return null
            val tail = suffix.orEmpty()
            return when {
                tail.isEmpty() -> basePath
                basePath.endsWith('\\') || tail.startsWith('\\') -> basePath + tail
                else -> "$basePath\\$tail"
            }
        } catch (_: RuntimeException) {
            return null
        }
    }

    private const val MAX_LINK_BYTES = 1 shl 20

    private fun readAnsi(bytes: ByteArray, start: Int, limit: Int): String? {
        if (start < 0 || start >= limit) return null
        var end = start
        while (end < limit && bytes[end] != 0.toByte()) end++
        if (end == limit) return null
        val charset = runCatching { charset("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        return String(bytes, start, end - start, charset)
    }

    private fun readUtf16(bytes: ByteArray, start: Int, limit: Int): String? {
        if (start < 0 || start + 1 >= limit) return null
        var end = start
        while (end + 1 < limit && !(bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte())) end += 2
        if (end + 1 >= limit) return null
        return String(bytes, start, end - start, Charsets.UTF_16LE)
    }

    /**
     * Programs reachable from Start Menu shortcuts under [roots]. Bounded in depth and count so a
     * huge or odd Start Menu can never stall the scan.
     */
    fun scanShortcuts(
        roots: List<File>,
        systemRoot: String? = System.getenv("SystemRoot"),
        maxDepth: Int = 4,
        maxShortcuts: Int = 3_000,
        readTarget: (File) -> String? = { link ->
            if (link.length() in 1..MAX_LINK_BYTES) parseShellLinkTarget(link.readBytes()) else null
        },
        targetExists: (String) -> Boolean = { File(it).isFile }
    ): List<ScannedApp> {
        val found = ArrayList<ScannedApp>()
        var visited = 0

        fun walk(directory: File, depth: Int) {
            if (depth > maxDepth || visited >= maxShortcuts) return
            val children = directory.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }
            for (child in children) {
                if (visited >= maxShortcuts) return
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.extension.equals("lnk", ignoreCase = true)) {
                    visited++
                    val name = child.nameWithoutExtension.trim()
                    if (!isProgramShortcutName(name)) continue
                    val target = runCatching { readTarget(child) }.getOrNull()
                        ?.trim()?.removeSurrounding("\"") ?: continue
                    if (!target.endsWith(".exe", ignoreCase = true) || !targetExists(target)) continue
                    val processName = target.replace('/', '\\').substringAfterLast('\\').lowercase(Locale.ROOT)
                    if (isBackgroundHelperProcess(processName)) continue
                    if (isWindowsFolderSystemTool(target, systemRoot)) continue
                    if (InstalledAppsScanner.classifyLauncherCandidate(processName, target) != null) continue
                    found += ScannedApp(
                        processName = processName,
                        displayName = name,
                        isRunning = false,
                        exePath = target
                    )
                }
            }
        }

        roots.filter(File::isDirectory).forEach { walk(it, 0) }
        return found
    }

    // ── Microsoft Store (packaged) apps ───────────────────────────────────────

    class StoreApp(val packageName: String, val displayName: String, val processName: String)

    /**
     * Well-known user-facing Store apps and the program each one runs. Only these are listed:
     * a Store package list is mostly frameworks and system components, and the names are
     * matched against the installed package names, never guessed.
     */
    private val knownStoreApps: List<StoreApp> = listOf(
        StoreApp("Microsoft.WindowsCalculator", "Calculator", "calculatorapp.exe"),
        StoreApp("Microsoft.WindowsAlarms", "Clock", "time.exe"),
        StoreApp("microsoft.windowscommunicationsapps", "Calendar", "hxcalendarappimm.exe"),
        StoreApp("microsoft.windowscommunicationsapps", "Mail", "hxoutlook.exe"),
        StoreApp("Microsoft.Windows.Photos", "Photos", "microsoft.photos.exe"),
        StoreApp("Microsoft.WindowsCamera", "Camera", "windowscamera.exe"),
        StoreApp("Microsoft.ScreenSketch", "Snipping Tool", "snippingtool.exe"),
        StoreApp("Microsoft.Paint", "Paint", "mspaint.exe"),
        StoreApp("Microsoft.MicrosoftStickyNotes", "Sticky Notes", "microsoft.notes.exe"),
        StoreApp("Microsoft.ZuneVideo", "Movies & TV", "video.ui.exe"),
        StoreApp("Microsoft.ZuneMusic", "Media Player", "microsoft.media.player.exe"),
        StoreApp("Microsoft.Todos", "Microsoft To Do", "todos.exe"),
        StoreApp("Clipchamp.Clipchamp", "Clipchamp", "clipchamp.exe"),
        StoreApp("Microsoft.YourPhone", "Phone Link", "phoneexperiencehost.exe"),
        StoreApp("Microsoft.GamingApp", "Xbox", "xboxpcapp.exe"),
        StoreApp("SpotifyAB.SpotifyMusic", "Spotify", "spotify.exe")
    )

    /** `Microsoft.WindowsCalculator_11.2.0_x64__8wekyb3d8bbwe` → `Microsoft.WindowsCalculator`. */
    fun storePackageName(packageFullName: String): String? =
        packageFullName.trim().substringBefore('_').takeIf(String::isNotBlank)

    /** Installed well-known Store apps, listed as unsupported entries (they can't be launched yet). */
    fun storeAppsFor(installedPackageFullNames: Collection<String>): List<ScannedApp> {
        val installed = installedPackageFullNames.mapNotNull(::storePackageName)
            .mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return knownStoreApps
            .filter { it.packageName.lowercase(Locale.ROOT) in installed }
            .map {
                ScannedApp(
                    processName = it.processName,
                    displayName = it.displayName,
                    isRunning = false,
                    exePath = null,
                    issue = LauncherAppIssue.StoreApp
                )
            }
    }

    // ── Combining the sources ─────────────────────────────────────────────────

    /**
     * One entry per program name. A usable entry beats an unusable one for the same program;
     * otherwise the earlier source wins.
     */
    fun merge(vararg sources: List<ScannedApp>): List<ScannedApp> =
        sources.asSequence().flatten()
            .groupBy { it.processName.lowercase(Locale.ROOT) }
            .values
            .map { candidates -> candidates.firstOrNull { it.issue == null } ?: candidates.first() }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
}
