package com.focusflow.services

import java.net.HttpURLConnection
import java.net.URL

/**
 * Small, dependency-free Discord webhook client shared by crash and feedback
 * reporting.
 *
 * Discord returns 204 for a successful execute-webhook request.  Treat the
 * whole 2xx range as success so this also works with ?wait=true and future
 * Discord response variants.
 */
internal object DiscordWebhookClient {

    private const val DEFAULT_TIMEOUT_MS = 8_000

    /**
     * Sends a payload without hiding whether Discord accepted it.
     *
     * This method is intentionally synchronous. Callers that must not block
     * should invoke it from their own IO/daemon thread. Crash handling calls it
     * directly because a daemon worker can be killed as soon as the JVM exits.
     */
    fun post(url: String, payload: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean {
        if (url.isBlank()) return false

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "FocusFlow-Telemetry/${CrashReporter.APP_VERSION}")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                useCaches = false
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            code in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}