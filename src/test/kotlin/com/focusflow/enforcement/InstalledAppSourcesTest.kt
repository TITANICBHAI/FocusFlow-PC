package com.focusflow.enforcement

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledAppSourcesTest {

    // ── Runtimes and helpers are plumbing, not apps ──────────────────────────

    @Test
    fun runtimesRedistributablesAndUpdatesAreHidden() {
        listOf(
            "Microsoft Edge WebView2 Runtime",
            "Microsoft Visual C++ 2015-2022 Redistributable (x64) - 14.38.33135",
            "Microsoft .NET Runtime - 8.0.1 (x64)",
            "Microsoft .NET SDK 8.0.100",
            "Windows Software Development Kit - Windows 10.0.22621",
            "Microsoft Update Health Tools",
            "Java(TM) SE Runtime Environment 8"
        ).forEach {
            assertTrue(
                InstalledAppSources.isRuntimeOrComponentEntry(it, false, false, null),
                "$it should be hidden"
            )
        }
    }

    @Test
    fun ordinaryProgramsAreKept() {
        listOf("Adobe Acrobat (64-bit)", "Adobe Photoshop 2024", "Google Chrome", "Notepad++", "Discord")
            .forEach {
                assertFalse(
                    InstalledAppSources.isRuntimeOrComponentEntry(it, false, false, null),
                    "$it should stay"
                )
            }
    }

    @Test
    fun systemComponentsPatchesAndUpdatesAreHiddenWhateverTheirName() {
        assertTrue(InstalledAppSources.isRuntimeOrComponentEntry("Some App", true, false, null))
        assertTrue(InstalledAppSources.isRuntimeOrComponentEntry("Some App", false, true, null))
        assertTrue(InstalledAppSources.isRuntimeOrComponentEntry("Some App", false, false, "Security Update"))
        assertTrue(InstalledAppSources.isRuntimeOrComponentEntry("Some App", false, false, "Hotfix"))
        assertFalse(InstalledAppSources.isRuntimeOrComponentEntry("Some App", false, false, "Application"))
    }

    @Test
    fun webViewAndCrashHelpersAreBackgroundProcesses() {
        listOf(
            "msedgewebview2.exe", "C:\\Program Files (x86)\\Microsoft\\EdgeWebView\\Application\\msedgewebview2.exe",
            "crashpad_handler.exe", "elevation_service.exe", "notification_helper.exe", "MicrosoftEdgeUpdate.exe"
        ).forEach { assertTrue(InstalledAppSources.isBackgroundHelperProcess(it), "$it") }
        listOf("msedge.exe", "chrome.exe", "Acrobat.exe", "Photoshop.exe").forEach {
            assertFalse(InstalledAppSources.isBackgroundHelperProcess(it), it)
        }
    }

    // ── Windows folder ───────────────────────────────────────────────────────

    @Test
    fun windowsFolderToolsAreSystemToolsExceptOrdinaryApps() {
        assertTrue(InstalledAppSources.isWindowsFolderSystemTool("C:\\Windows\\System32\\msinfo32.exe", "C:\\Windows"))
        assertFalse(InstalledAppSources.isWindowsFolderSystemTool("C:\\Windows\\System32\\mspaint.exe", "C:\\Windows"))
        assertFalse(InstalledAppSources.isWindowsFolderSystemTool("C:\\Program Files\\Adobe\\Acrobat.exe", "C:\\Windows"))
        assertTrue(InstalledAppSources.isWindowsFolderSystemTool("c:/windows/regedit.exe", null))
    }

    // ── App Paths ────────────────────────────────────────────────────────────

    @Test
    fun appPathsValuesAreUnquotedAndStrippedOfArguments() {
        assertEquals(
            "C:\\Program Files\\Adobe\\Photoshop.exe",
            InstalledAppSources.appPathsTarget("\"C:\\Program Files\\Adobe\\Photoshop.exe\"")
        )
        assertEquals(
            "C:\\Apps\\tool.exe",
            InstalledAppSources.appPathsTarget("C:\\Apps\\tool.exe --silent")
        )
        assertNull(InstalledAppSources.appPathsTarget(null))
        assertNull(InstalledAppSources.appPathsTarget("  "))
        assertNull(InstalledAppSources.appPathsTarget("C:\\Apps\\readme.txt"))
    }

    // ── Shortcut names ───────────────────────────────────────────────────────

    @Test
    fun documentationAndUninstallShortcutsAreNotPrograms() {
        listOf("Uninstall Foo", "Foo Help", "Foo Readme", "Foo on the Web", "Foo Release Notes", "Foo License Agreement")
            .forEach { assertFalse(InstalledAppSources.isProgramShortcutName(it), it) }
        listOf("Adobe Acrobat", "Adobe Photoshop 2024", "Visual Studio Code", "Calculator Plus")
            .forEach { assertTrue(InstalledAppSources.isProgramShortcutName(it), it) }
    }

    // ── Shortcut file parsing ────────────────────────────────────────────────

    @Test
    fun readsTheLocalTargetFromAShortcutWithAnItemIdList() {
        val bytes = buildLink(target = "C:\\Program Files\\Adobe\\Acrobat DC\\Acrobat\\Acrobat.exe", withIdList = true)
        assertEquals(
            "C:\\Program Files\\Adobe\\Acrobat DC\\Acrobat\\Acrobat.exe",
            InstalledAppSources.parseShellLinkTarget(bytes)
        )
    }

    @Test
    fun readsTheLocalTargetFromAShortcutWithoutAnItemIdList() {
        val bytes = buildLink(target = "D:\\Tools\\tool.exe", withIdList = false)
        assertEquals("D:\\Tools\\tool.exe", InstalledAppSources.parseShellLinkTarget(bytes))
    }

    @Test
    fun readsAnAnsiOnlyShortcutAndJoinsThePathSuffix() {
        val bytes = buildLink(target = "C:\\Apps\\Foo", suffix = "foo.exe", unicode = false)
        assertEquals("C:\\Apps\\Foo\\foo.exe", InstalledAppSources.parseShellLinkTarget(bytes))
    }

    @Test
    fun readsANonAsciiTargetFromTheUnicodeFields() {
        val bytes = buildLink(target = "C:\\Programme\\Büro\\büro.exe")
        assertEquals("C:\\Programme\\Büro\\büro.exe", InstalledAppSources.parseShellLinkTarget(bytes))
    }

    @Test
    fun shortcutsWithoutALocalPathAreIgnored() {
        assertNull(InstalledAppSources.parseShellLinkTarget(buildLink(target = "C:\\x.exe", hasLinkInfo = false)))
        assertNull(InstalledAppSources.parseShellLinkTarget(buildLink(target = "C:\\x.exe", localPathFlag = false)))
    }

    @Test
    fun damagedShortcutsNeverThrow() {
        val good = buildLink(target = "C:\\Apps\\a.exe")
        assertNull(InstalledAppSources.parseShellLinkTarget(ByteArray(0)))
        assertNull(InstalledAppSources.parseShellLinkTarget(ByteArray(200)))
        assertNull(InstalledAppSources.parseShellLinkTarget(good.copyOf(good.size / 2)))
        for (length in 0..good.size step 7) {
            InstalledAppSources.parseShellLinkTarget(good.copyOf(length)) // must not throw
        }
        val corrupted = good.copyOf()
        for (index in 0x4C until corrupted.size step 3) corrupted[index] = 0x7F
        InstalledAppSources.parseShellLinkTarget(corrupted) // must not throw
    }

    // ── Start Menu walk ──────────────────────────────────────────────────────

    @Test
    fun startMenuShortcutsBecomeAppsNamedAfterTheShortcut() {
        val dir = Files.createTempDirectory("startmenu").toFile()
        val acrobat = File(dir, "Acrobat/Acrobat.exe").also { it.parentFile.mkdirs(); it.writeText("x") }
        val help = File(dir, "Acrobat/Acrobat.exe").path
        File(dir, "Adobe/Adobe Acrobat.lnk").also { it.parentFile.mkdirs(); it.writeBytes(buildLink(acrobat.path)) }
        File(dir, "Adobe/Adobe Acrobat Help.lnk").writeBytes(buildLink(help))
        File(dir, "Uninstall Adobe.lnk").writeBytes(buildLink(help))

        val apps = InstalledAppSources.scanShortcuts(listOf(dir), systemRoot = "C:\\Windows", readTarget = ::readLink)

        assertEquals(listOf("Adobe Acrobat"), apps.map { it.displayName })
        assertEquals("acrobat.exe", apps.single().processName)
        assertEquals(acrobat.path, apps.single().exePath)
        assertNull(apps.single().issue)
    }

    @Test
    fun startMenuSkipsMissingTargetsInstallersHelpersAndSystemTools() {
        val dir = Files.createTempDirectory("startmenu2").toFile()
        fun exe(path: String) = File(dir, "bin/$path").also { it.parentFile.mkdirs(); it.writeText("x") }.path
        fun link(name: String, target: String) { File(dir, "$name.lnk").writeBytes(buildLink(target)) }
        link("Gone", File(dir, "bin/missing.exe").path)
        link("Updater", exe("Update.exe"))
        link("Edge Runtime", exe("msedgewebview2.exe"))
        link("Notes", exe("notes.exe"))
        link("Regedit", "C:\\Windows\\regedit.exe")

        val apps = InstalledAppSources.scanShortcuts(
            listOf(dir),
            systemRoot = "C:\\Windows",
            readTarget = ::readLink,
            targetExists = { File(it).isFile || it.startsWith("C:\\Windows") }
        )

        assertEquals(listOf("Notes"), apps.map { it.displayName })
    }

    @Test
    fun startMenuWalkIsBounded() {
        val dir = Files.createTempDirectory("startmenu3").toFile()
        repeat(12) { File(dir, "app$it.lnk").writeBytes(ByteArray(1)) }
        var reads = 0
        InstalledAppSources.scanShortcuts(listOf(dir), maxShortcuts = 5, readTarget = { reads++; null })
        assertEquals(5, reads)

        val deepRoot = Files.createTempDirectory("startmenu4").toFile()
        File(deepRoot, "a/b/c/d").also { it.mkdirs() }
        File(deepRoot, "a/b/c/d/deep.lnk").writeBytes(ByteArray(1))
        var deepReads = 0
        InstalledAppSources.scanShortcuts(listOf(deepRoot), maxDepth = 2, readTarget = { deepReads++; null })
        assertEquals(0, deepReads)
        InstalledAppSources.scanShortcuts(listOf(deepRoot), maxDepth = 4, readTarget = { deepReads++; null })
        assertEquals(1, deepReads)
    }

    // ── Store apps ───────────────────────────────────────────────────────────

    @Test
    fun installedStorePackagesAreListedAsUnsupportedEntries() {
        val apps = InstalledAppSources.storeAppsFor(
            listOf(
                "Microsoft.WindowsCalculator_11.2410.1.0_x64__8wekyb3d8bbwe",
                "Microsoft.WindowsAlarms_11.2410.2.0_x64__8wekyb3d8bbwe",
                "microsoft.windowscommunicationsapps_16005.14326.21548.0_x64__8wekyb3d8bbwe",
                "Microsoft.UI.Xaml.2.8_8.2310.30001.0_x64__8wekyb3d8bbwe",
                "Microsoft.VCLibs.140.00_14.0.33519.0_x64__8wekyb3d8bbwe"
            )
        )

        assertEquals(listOf("Calculator", "Clock", "Calendar", "Mail").sorted(), apps.map { it.displayName }.sorted())
        assertTrue(apps.all { it.issue == LauncherAppIssue.StoreApp })
        assertTrue(apps.all { it.exePath == null })
        assertEquals("calculatorapp.exe", apps.first { it.displayName == "Calculator" }.processName)
    }

    @Test
    fun storeAppsThatAreNotInstalledAreNotListed() {
        assertEquals(emptyList(), InstalledAppSources.storeAppsFor(emptyList()))
        assertEquals(emptyList(), InstalledAppSources.storeAppsFor(listOf("Some.Framework_1.0_x64__abc")))
    }

    @Test
    fun packageNamesAreReadFromFullNames() {
        assertEquals(
            "Microsoft.WindowsCalculator",
            InstalledAppSources.storePackageName("Microsoft.WindowsCalculator_11.2410.1.0_x64__8wekyb3d8bbwe")
        )
        assertNull(InstalledAppSources.storePackageName("  "))
    }

    // ── Merging ──────────────────────────────────────────────────────────────

    @Test
    fun aUsableEntryBeatsAnUnusableOneForTheSameProgram() {
        val broken = ScannedApp("a.exe", "A (registry)", false, "C:\\a.exe", LauncherAppIssue.MissingExecutable)
        val good = ScannedApp("a.exe", "A (start menu)", false, "C:\\a.exe")
        assertEquals("A (start menu)", InstalledAppSources.merge(listOf(broken), listOf(good)).single().displayName)
    }

    @Test
    fun theEarlierSourceWinsWhenBothAreUsable() {
        val first = ScannedApp("a.exe", "From registry", false, "C:\\a.exe")
        val second = ScannedApp("A.EXE", "From start menu", false, "C:\\a.exe")
        assertEquals("From registry", InstalledAppSources.merge(listOf(first), listOf(second)).single().displayName)
    }

    @Test
    fun aStoreEntryDoesNotReplaceTheDesktopVersionOfTheSameProgram() {
        val desktop = ScannedApp("spotify.exe", "Spotify", false, "C:\\Spotify\\Spotify.exe")
        val store = ScannedApp("spotify.exe", "Spotify", false, null, LauncherAppIssue.StoreApp)
        val merged = InstalledAppSources.merge(listOf(store), listOf(desktop)).single()
        assertNull(merged.issue)
        assertEquals("C:\\Spotify\\Spotify.exe", merged.exePath)
    }

    // ── helpers: a minimal MS-SHLLINK writer used only to build test input ───

    private fun readLink(file: File): String? = InstalledAppSources.parseShellLinkTarget(file.readBytes())

    private fun buildLink(
        target: String,
        suffix: String = "",
        withIdList: Boolean = true,
        hasLinkInfo: Boolean = true,
        localPathFlag: Boolean = true,
        unicode: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(0x4C).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, 0x4C)
        var flags = 0
        if (withIdList) flags = flags or 0x01
        if (hasLinkInfo) flags = flags or 0x02
        header.putInt(0x14, flags)
        out.write(header.array())
        if (withIdList) {
            out.write(byteArrayOf(4, 0, 0x11, 0x22, 0x33, 0x44)) // size 4, then 4 filler bytes
        }
        if (hasLinkInfo) {
            val ansiBase = target.toByteArray(charset("windows-1252")) + 0
            val ansiSuffix = suffix.toByteArray(charset("windows-1252")) + 0
            val uniBase = (target.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0))
            val uniSuffix = (suffix.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0))
            val headerSize = if (unicode) 0x24 else 0x1C
            val volume = ByteArray(16) // placeholder volume id
            val volumeOffset = headerSize
            val baseOffset = volumeOffset + volume.size
            val suffixOffset = baseOffset + ansiBase.size
            val uniBaseOffset = suffixOffset + ansiSuffix.size
            val uniSuffixOffset = uniBaseOffset + uniBase.size
            val total = (if (unicode) uniSuffixOffset + uniSuffix.size else suffixOffset + ansiSuffix.size)
            val info = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            info.putInt(0, total)
            info.putInt(4, headerSize)
            info.putInt(8, if (localPathFlag) 0x01 else 0x00)
            info.putInt(12, volumeOffset)
            info.putInt(16, baseOffset)
            info.putInt(20, 0)
            info.putInt(24, suffixOffset)
            if (unicode) {
                info.putInt(28, uniBaseOffset)
                info.putInt(32, uniSuffixOffset)
            }
            info.position(volumeOffset); info.put(volume)
            info.position(baseOffset); info.put(ansiBase)
            info.position(suffixOffset); info.put(ansiSuffix)
            if (unicode) {
                info.position(uniBaseOffset); info.put(uniBase)
                info.position(uniSuffixOffset); info.put(uniSuffix)
            }
            out.write(info.array())
        }
        return out.toByteArray()
    }
}
