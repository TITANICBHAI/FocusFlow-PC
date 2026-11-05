package com.focusflow.services

import com.focusflow.AppPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Tells the running FocusFlow that the uninstall wizard has already
 * authorized a removal.
 *
 * The wizard runs in its own JVM. After the user passes every PIN and block
 * check there, it starts Windows Installer; the running app's uninstall guard
 * would otherwise see that installer, not know about the wizard's decision, and
 * either kill a legitimate uninstall or ask for the same PIN a second time.
 *
 * The grant is a tiny file holding an expiry time. It is friction, not a
 * security boundary: anyone who can write to their own profile can write the
 * file, exactly as they can edit the app's other per-user settings. The expiry
 * is capped so a stale or hand-made file cannot grant a long-lived bypass.
 */
internal object UninstallAuthorizationHandoff {

    /** How long a grant stays valid after the wizard writes it. */
    const val WINDOW_MS = 60_000L

    private const val FILE_NAME = "uninstall-authorization"
    private const val KEY = "expiresAtMs"

    @Volatile internal var fileForTest: File? = null
    @Volatile internal var nowForTest: (() -> Long)? = null

    private val file: File
        get() = fileForTest ?: File(AppPaths.dataDir, FILE_NAME)

    private fun now(): Long = nowForTest?.invoke() ?: System.currentTimeMillis()

    /** Writes a grant that expires [durationMs] from now. Returns false if it could not be written. */
    fun grant(durationMs: Long = WINDOW_MS): Boolean = runCatching {
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText("$KEY=${now() + durationMs.coerceIn(0L, WINDOW_MS)}\n")
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
        true
    }.getOrDefault(false)

    /** True while an unexpired grant exists. Any read problem means "no grant". */
    fun isGranted(): Boolean = runCatching {
        val target = file
        if (!target.isFile) return@runCatching false
        val expiresAt = target.readLines()
            .firstOrNull { it.startsWith("$KEY=") }
            ?.substringAfter('=')
            ?.trim()
            ?.toLongOrNull()
            ?: return@runCatching false
        val current = now()
        expiresAt > current && expiresAt - current <= WINDOW_MS
    }.getOrDefault(false)

    fun clear() {
        runCatching { file.delete() }
    }

    internal fun resetForTest() {
        fileForTest = null
        nowForTest = null
    }
}
