package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExePathResolverTest {
    private val files = mutableSetOf<String>()
    private var running: String? = null
    private val subdirs = mutableMapOf<String, List<String>>()

    private val resolver = ExePathResolver(
        systemRoot = "C:\\Windows",
        env = { name -> if (name == "ProgramFiles") "C:\\Program Files" else null },
        exists = { it in files },
        runningImage = { running },
        subdirectories = { subdirs[it].orEmpty() }
    )

    @Test
    fun theRealSystemCopyBeatsARunningCopyFromAnotherFolder() {
        files += "C:\\Windows\\System32\\cmd.exe"
        running = "C:\\Users\\me\\AppData\\Local\\Temp\\cmd.exe"

        assertEquals("C:\\Windows\\System32\\cmd.exe", resolver.resolve("cmd.exe"))
    }

    @Test
    fun systemToolsInsideSystemSubfoldersAreFoundThere() {
        files += "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        running = "D:\\portable\\powershell.exe"

        assertEquals("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", resolver.resolve("powershell.exe"))
    }

    @Test
    fun aProgramThatIsNotASystemToolUsesItsRunningImage() {
        running = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"

        assertEquals("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", resolver.resolve("chrome.exe"))
    }

    @Test
    fun aProgramThatIsNotRunningIsFoundOneLevelInsideAnInstallRoot() {
        subdirs["C:\\Program Files"] = listOf("C:\\Program Files\\Zed")
        files += "C:\\Program Files\\Zed\\zed.exe"

        assertEquals("C:\\Program Files\\Zed\\zed.exe", resolver.resolve("zed.exe"))
    }

    @Test
    fun nothingFoundIsNull() {
        assertNull(resolver.resolve("nope.exe"))
    }
}
