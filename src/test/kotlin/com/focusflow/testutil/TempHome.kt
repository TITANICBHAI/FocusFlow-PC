package com.focusflow.testutil

import com.focusflow.data.Database
import java.nio.file.Files
import java.nio.file.Path

object TempHome {
    private val properties = listOf(
        "user.home",
        "focusflow.app.version",
        "focusflow.telemetry"
    )

    @Synchronized
    fun <T> withHome(block: (Path) -> T): T {
        val home = Files.createTempDirectory("focusflow-test-home-").toAbsolutePath()
        val previousValues = properties.associateWith { System.getProperty(it) }

        try {
            Database.resetForTest()
            System.setProperty("user.home", home.toString())
            System.setProperty("focusflow.app.version", "test")
            System.setProperty("focusflow.telemetry", "off")
            return block(home)
        } finally {
            try {
                Database.resetForTest()
            } finally {
                previousValues.forEach { (name, value) ->
                    if (value == null) {
                        System.clearProperty(name)
                    } else {
                        System.setProperty(name, value)
                    }
                }
                deleteScratchTree(home)
            }
        }
    }
}

internal fun deleteScratchTree(root: Path) {
    if (!Files.exists(root)) return

    val paths = mutableListOf<Path>()
    Files.walk(root).use { walk ->
        walk.forEach { paths.add(it) }
    }
    paths.sortedByDescending { it.nameCount }.forEach { Files.deleteIfExists(it) }
}
