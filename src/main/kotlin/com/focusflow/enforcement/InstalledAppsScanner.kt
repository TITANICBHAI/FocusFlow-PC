package com.focusflow.enforcement

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class LauncherAppIssue(val label: String) {
    StoreApp("Store app — not supported"),
    InstallerOrUpdater("Installer or updater — not supported"),
    MissingExecutable("Executable path unavailable"),
    SystemUtility("System utility — not available in Focus Launcher")
}

data class ScannedApp(
    val processName: String,
    val displayName: String,
    val isRunning: Boolean,
    val exePath: String? = null,
    val issue: LauncherAppIssue? = null
)

object InstalledAppsScanner {

    private val curated = mapOf(
        "chrome.exe"            to "Google Chrome",
        "firefox.exe"           to "Mozilla Firefox",
        "msedge.exe"            to "Microsoft Edge",
        "opera.exe"             to "Opera",
        "brave.exe"             to "Brave Browser",
        "discord.exe"           to "Discord",
        "slack.exe"             to "Slack",
        "teams.exe"             to "Microsoft Teams",
        "zoom.exe"              to "Zoom",
        "telegram.exe"          to "Telegram",
        "whatsapp.exe"          to "WhatsApp",
        "signal.exe"            to "Signal",
        "spotify.exe"           to "Spotify",
        "steam.exe"             to "Steam",
        "epicgameslauncher.exe" to "Epic Games Launcher",
        "origin.exe"            to "EA Origin",
        "battle.net.exe"        to "Battle.net",
        "leagueclient.exe"      to "League of Legends",
        "twitch.exe"            to "Twitch",
        "obs64.exe"             to "OBS Studio",
        "tiktok.exe"            to "TikTok",
        "netflix.exe"           to "Netflix",
        "vlc.exe"               to "VLC Media Player",
        "wmplayer.exe"          to "Windows Media Player",
        "itunes.exe"            to "iTunes",
        "outlook.exe"           to "Microsoft Outlook",
        "winword.exe"           to "Microsoft Word",
        "excel.exe"             to "Microsoft Excel",
        "powerpnt.exe"          to "Microsoft PowerPoint",
        "notepad.exe"           to "Notepad",
        "notepad++.exe"         to "Notepad++",
        "code.exe"              to "Visual Studio Code",
        "devenv.exe"            to "Visual Studio",
        "idea64.exe"            to "IntelliJ IDEA",
        "pycharm64.exe"         to "PyCharm",
        "webstorm64.exe"        to "WebStorm",
        "clion64.exe"           to "CLion",
        "studio64.exe"          to "Android Studio"
    )

    private val systemIgnore = setOf(
        "system", "system idle process", "registry", "smss.exe", "csrss.exe",
        "wininit.exe", "winlogon.exe", "lsass.exe", "svchost.exe", "services.exe",
        "spoolsv.exe", "searchindexer.exe", "audiodg.exe", "dwm.exe", "conhost.exe",
        "dllhost.exe", "rundll32.exe", "wermgr.exe", "wmiprvse.exe", "msiexec.exe",
        "fontdrvhost.exe", "sihost.exe", "taskhostw.exe", "explorer.exe",
        "securityhealthsystray.exe", "runtimebroker.exe", "applicationframehost.exe",
        "shellexperiencehost.exe", "startmenuexperiencehost.exe", "searchhost.exe",
        "ctfmon.exe", "textinputhost.exe", "lockapp.exe", "logonui.exe",
        "userinit.exe", "wlanext.exe", "dashost.exe", "igfxem.exe", "igfxhk.exe",
        "nvdisplay.container.exe", "amdow.exe", "focusflow.exe"
    )

    private val launcherSystemUtilities = systemIgnore + setOf(
        "cmd.exe", "powershell.exe", "pwsh.exe", "taskmgr.exe", "regedit.exe",
        "wscript.exe", "cscript.exe", "mshta.exe", "tasklist.exe", "taskkill.exe",
        "schtasks.exe", "mmc.exe", "control.exe", "windowsterminal.exe", "wt.exe",
        "wsl.exe", "wslhost.exe", "perfmon.exe", "resmon.exe"
    )

    /**
     * Exe-path lookup cache.
     * Populated by getRunningApps() and getInstalledApps().
     * Used by AppIcon to resolve real icons without re-scanning.
     */
    private val exePathCache = ConcurrentHashMap<String, String>()

    /** Installed-apps registry scan — lazily populated, cached for the session. */
    private val installedCache = mutableListOf<ScannedApp>()
    private var installedScanned = false
    private val installLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    fun getRunningApps(): List<ScannedApp> {
        val running: List<ScannedApp> = try {
            ProcessHandle.allProcesses().toList()
                .mapNotNull { ph ->
                    // Use orElse(null) on a single call to avoid the TOCTOU race where
                    // isPresent() returns true but the process exits before get() is called,
                    // causing NoSuchElementException on the second command() invocation.
                    val cmd = ph.info().command().orElse(null) ?: return@mapNotNull null
                    val exe = java.io.File(cmd).name.lowercase()
                    val display = curated[exe] ?: friendlyName(exe)
                    ScannedApp(
                        processName = exe,
                        displayName = display,
                        isRunning = true,
                        exePath = cmd,
                        issue = classifyLauncherCandidate(exe, cmd)
                    )
                }
                .filter { app ->
                    app.processName.isNotBlank() &&
                    !isHiddenSystemUtility(app.processName) &&
                    app.processName.endsWith(".exe")
                }
                .distinctBy { it.processName }
        } catch (_: Exception) { emptyList() }

        // Populate path cache from running processes (most accurate paths)
        running.forEach { app ->
            if (app.exePath != null) exePathCache[app.processName] = app.exePath
        }

        return running.sortedBy { it.displayName }
    }

    /**
     * Scans the Windows Registry for installed applications and returns them
     * with real exe paths. Results are cached for the life of the process;
     * the first call may take 200–400 ms on a typical machine.
     */
    fun getInstalledApps(): List<ScannedApp> {
        synchronized(installLock) {
            if (!installedScanned) {
                installedCache.clear()
                installedCache.addAll(scanRegistry())
                installedScanned = true
            }
            return installedCache.toList()
        }
    }

    /**
     * Returns apps that are verifiably on this machine — registry-installed apps
     * (with a real, existing .exe) plus any currently-running user processes not
     * already in the registry results.  The old hardcoded fallback list is NOT
     * included because it contained entries (TikTok, iTunes, Netflix, etc.) that
     * are rarely installed on a given PC and confused users.
     */
    fun getCuratedApps(): List<ScannedApp> {
        val installed   = getInstalledApps()
        val running     = getRunningApps()
        return (installed + running)
            .groupBy { it.processName.lowercase() }
            .values
            .map { candidates -> candidates.firstOrNull { it.issue == null } ?: candidates.first() }
            .sortedBy { it.displayName }
    }

    /** Look up the exe path for a process name using the cache built by any prior scan. */
    fun getExePathFor(processName: String): String? =
        exePathCache[processName.lowercase()]

    fun friendlyNameFor(processName: String): String =
        curated[processName.lowercase()] ?: friendlyName(processName.lowercase())

    internal fun findMainExecutableCandidate(displayName: String, candidates: Sequence<File>): File? {
        val normalizedDisplay = normalizeAppLabel(displayName)
        val knownNames = curated.entries
            .filter { (_, label) -> label.equals(displayName.trim(), ignoreCase = true) }
            .map { (name, _) -> normalizeAppLabel(name.substringBeforeLast('.')) }
            .toSet() + normalizedDisplay
        if (knownNames.all(String::isBlank)) return null

        return candidates
            .filter { it.isFile && it.canRead() && it.extension.equals("exe", ignoreCase = true) }
            .filter { !isHiddenSystemUtility(it.name) && !isInstallerOrUpdaterName(it.name) }
            .filter { normalizeAppLabel(it.name.substringBeforeLast('.')) in knownNames }
            .distinctBy { it.absolutePath.lowercase() }
            .maxByOrNull(File::lastModified)
    }

    internal fun classifyLauncherCandidate(
        processName: String,
        executablePath: String?,
        isReadableFile: (String) -> Boolean = { path ->
            val file = File(path)
            file.isFile && file.canRead()
        }
    ): LauncherAppIssue? {
        val candidate = executablePath?.takeIf(String::isNotBlank) ?: processName
        val basename = candidate.replace('\\', '/').substringAfterLast('/')
        val normalizedPath = candidate.replace('/', '\\').lowercase()
        return when {
            normalizedPath.contains("\\windowsapps\\") -> LauncherAppIssue.StoreApp
            isHiddenSystemUtility(basename) -> LauncherAppIssue.SystemUtility
            isInstallerOrUpdaterName(basename) -> LauncherAppIssue.InstallerOrUpdater
            executablePath.isNullOrBlank() || !isReadableFile(executablePath) ->
                LauncherAppIssue.MissingExecutable
            else -> null
        }
    }

    internal fun isHiddenSystemUtility(processName: String): Boolean =
        processName.replace('\\', '/').substringAfterLast('/')
            .lowercase() in launcherSystemUtilities

    // ── Registry scan ─────────────────────────────────────────────────────────

    private fun scanRegistry(): List<ScannedApp> {
        if (!isWindows) return emptyList()

        val result  = mutableMapOf<String, ScannedApp>() // key = processName
        val regKeys = listOf(
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            WinReg.HKEY_CURRENT_USER  to "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        )

        for ((hive, key) in regKeys) {
            val subkeys = try {
                Advapi32Util.registryGetKeys(hive, key)
            } catch (_: Exception) { continue }

            for (sub in subkeys) {
                try {
                    val vals = Advapi32Util.registryGetValues(hive, "$key\\$sub")

                    val displayName = (vals["DisplayName"] as? String)?.trim()
                        ?.takeIf { it.isNotBlank() } ?: continue

                    val displayIcon = (vals["DisplayIcon"] as? String)?.trim()
                        ?.takeIf(String::isNotBlank) ?: continue
                    val rawPath = executablePathFromDisplayIcon(displayIcon) ?: continue
                    val iconFile = File(rawPath)
                    val processName = iconFile.name.lowercase()
                    if (isHiddenSystemUtility(processName) || !processName.endsWith(".exe")) continue

                    // Earlier results (HKLM 64-bit) take priority; don't overwrite
                    if (processName in result) continue

                    val issue = classifyLauncherCandidate(processName, rawPath)
                    val installLocation = (vals["InstallLocation"] as? String)
                        ?.trim()?.removeSurrounding("\"")
                    val resolvedMain = when (issue) {
                        null, LauncherAppIssue.StoreApp, LauncherAppIssue.SystemUtility -> null
                        else -> findMainExecutableCandidate(
                            displayName,
                            executableCandidates(iconFile.parentFile, installLocation)
                        )
                    }
                    val resolvedPath = when {
                        issue == null -> iconFile.absolutePath
                        resolvedMain != null -> resolvedMain.absolutePath
                        else -> rawPath
                    }
                    val finalProcessName = File(resolvedPath).name.lowercase()
                    val finalIssue = if (resolvedMain != null || issue == null) {
                        null
                    } else {
                        issue ?: LauncherAppIssue.MissingExecutable
                    }
                    if (isHiddenSystemUtility(finalProcessName)) continue

                    val friendlyDisplay = curated[finalProcessName] ?: displayName

                    val app = ScannedApp(
                        processName = finalProcessName,
                        displayName = friendlyDisplay,
                        isRunning   = false,
                        exePath     = resolvedPath,
                        issue       = finalIssue
                    )
                    result[finalProcessName] = app
                    if (finalIssue == null) {
                        exePathCache.putIfAbsent(finalProcessName, resolvedPath)
                    }

                } catch (_: Exception) { /* malformed key — skip */ }
            }
        }

        return result.values
            .filter { it.processName !in systemIgnore }
            .sortedBy { it.displayName }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun friendlyName(exe: String): String =
        exe.substringBeforeLast(".")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("\\d+$"), "")
            .trim()
            .replaceFirstChar { c -> c.uppercaseChar() }

    private fun executableCandidates(iconDirectory: File?, installLocation: String?): Sequence<File> =
        sequence {
            val roots = sequenceOf(iconDirectory, installLocation?.takeIf(String::isNotBlank)?.let(::File))
                .filterNotNull()
                .filter(File::isDirectory)
                .distinctBy { it.absolutePath.lowercase() }
                .toList()
            roots.forEach { root ->
                val children = root.listFiles().orEmpty()
                children.filter(File::isFile).forEach { yield(it) }
                children.filter { it.isDirectory && it.name.startsWith("app-", ignoreCase = true) }
                    .forEach { versionDirectory ->
                        versionDirectory.listFiles().orEmpty()
                            .filter(File::isFile)
                            .forEach { yield(it) }
                    }
            }
        }

    private fun isInstallerOrUpdaterName(name: String): Boolean =
        Regex("(?i)(^|[._ -])(uninstall(?:er)?|unins\\w*|updater?|installer|setup)([._ -]|$)")
            .containsMatchIn(name.substringBeforeLast('.', name))

    private fun normalizeAppLabel(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}

internal fun executablePathFromDisplayIcon(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null

    val path = if (trimmed.startsWith('"')) {
        val closingQuote = trimmed.indexOf('"', startIndex = 1)
        if (closingQuote <= 1) return null
        trimmed.substring(1, closingQuote)
    } else {
        val exeEnd = Regex("(?i)\\.exe(?=\\s*,\\s*\\d+|\\s|$)").find(trimmed)
            ?.range?.last ?: return null
        trimmed.substring(0, exeEnd + 1).trim()
    }
    return path.takeIf { it.endsWith(".exe", ignoreCase = true) }
}
