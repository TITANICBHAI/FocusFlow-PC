package com.focusflow.services

import com.focusflow.data.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ReviewPromptService — smart Microsoft Store rating prompt for FocusFlow.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  ELIGIBILITY CONDITIONS (all must be met)                               │
 * │  • app_open_count >= 10  (never prompts before the 10th launch)         │
 * │  • Not permanently dismissed (user clicked "Rate Now" before)           │
 * │  • Not in 30-day cooldown (set when user clicks "Maybe Later")          │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  TRIGGER POINTS (any one fires the check)                               │
 * │  • Task completed                                                       │
 * │  • Focus session completed (timer ran out or manually marked done)      │
 * │  • Standalone block expired or manually stopped                         │
 * │  • User passed a PIN gate to remove a block/restriction                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  FEEDBACK WEBHOOK                                                       │
 * │  Encoded as Base64 to avoid plain-text scraping.                        │
 * │  To set: Base64.getEncoder().encodeToString(webhookUrl.toByteArray())   │
 * │  Leave OBFUSCATED_FEEDBACK_WEBHOOK blank to disable feedback entirely.  │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
object ReviewPromptService {

    // ── Scope ─────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State ─────────────────────────────────────────────────────────────────
    private val _shouldShow = MutableStateFlow(false)

    /** Collect in App() to drive the dialog visibility. */
    val shouldShow: StateFlow<Boolean> = _shouldShow.asStateFlow()

    // ── MS Store ──────────────────────────────────────────────────────────────
    // Replace PLACEHOLDER_STORE_ID with the Product ID from Partner Center.
    // Example: "9NBLGGH4R315"
    private const val MS_STORE_PRODUCT_ID = "9njn9fprq7t1"

    // ── Feedback webhook ──────────────────────────────────────────────────────
    // Stored as Base64 so plain-text scrapers skip it — same pattern as
    // CrashReporter and ResourceMonitorService.
    // To generate: Base64.getEncoder().encodeToString("https://discord.com/...".toByteArray())
    // Leave blank to disable the "Report an Issue" button entirely.
    private const val OBFUSCATED_FEEDBACK_WEBHOOK =
        "aHR0cHM6Ly9kaXNjb3JkLmNvbS9hcGkvd2ViaG9va3MvMTUxMTA0MjQ2NzA5NTM4MDIzMC84" +
        "cVpmYXQwUllWZlhlS3NsOUhOWnRmOE1MczlOb1JYdWxzQ21jMjZld0ptRGNxTXdfdl94NGNj" +
        "MEg0bUhRWFNFdDIxVA=="

    private val FEEDBACK_WEBHOOK_URL: String by lazy {
        try {
            if (OBFUSCATED_FEEDBACK_WEBHOOK.isBlank()) ""
            else String(java.util.Base64.getDecoder().decode(OBFUSCATED_FEEDBACK_WEBHOOK), Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }

    /** True when the feedback webhook is configured — drives "Report an Issue" button visibility. */
    val feedbackEnabled: Boolean get() = FEEDBACK_WEBHOOK_URL.isNotBlank()

    // ── DB keys ───────────────────────────────────────────────────────────────
    private const val KEY_DISMISSED  = "review_permanently_dismissed"
    private const val KEY_DECLINED   = "review_declined_date"
    // Legacy key written by the old launch-count logic — treated as permanent dismiss.
    private const val KEY_LEGACY     = "review_prompt_shown"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call from any trigger point (task done, session done, standalone block ended,
     * PIN gate passed). Safe to call from any thread — fires a non-blocking IO check.
     */
    fun triggerCheck() {
        scope.launch {
            if (shouldShowPrompt()) _shouldShow.value = true
        }
    }

    /** Called when the user taps "Rate Now". Opens the Store and permanently dismisses. */
    fun onRateNow() {
        _shouldShow.value = false
        scope.launch { Database.setSetting(KEY_DISMISSED, "true") }
        try {
            java.awt.Desktop.getDesktop().browse(
                java.net.URI("ms-windows-store://review/?ProductId=$MS_STORE_PRODUCT_ID")
            )
        } catch (_: Throwable) {
            // Fallback: open the web Store page if the protocol handler fails
            try {
                java.awt.Desktop.getDesktop().browse(
                    java.net.URI("https://www.microsoft.com/store/apps/$MS_STORE_PRODUCT_ID")
                )
            } catch (_: Throwable) {}
        }
    }

    /** Called when the user taps "Maybe Later". Starts the 30-day cooldown. */
    fun onDecline() {
        _shouldShow.value = false
        scope.launch {
            Database.setSetting(KEY_DECLINED, java.time.LocalDate.now().toString())
        }
    }

    /** Dismiss without recording a decision (e.g. dialog X button). Treats as decline. */
    fun onDismiss() = onDecline()

    /**
     * Send an issue/feedback message to the Discord feedback webhook.
     *
     * Discord limits an embed description to 4096 characters. Trim before
     * building JSON so a long pasted report cannot be rejected with HTTP 400.
     * The callback runs on the UI dispatcher and reports the actual HTTP result
     * instead of optimistically claiming the message was sent.
     */
    fun sendFeedback(message: String, onResult: (Boolean) -> Unit = {}) {
        val cleanMessage = message.trim().take(4_000)
        if (cleanMessage.isBlank() || FEEDBACK_WEBHOOK_URL.isBlank()) {
            onResult(false)
            return
        }
        scope.launch {
            val delivered = try {
                val payload = buildString {
                    append("{\"embeds\":[{")
                    append("\"title\":\"💬 User Feedback\",")
                    append("\"description\":${escapeJson(cleanMessage)},")
                    append("\"color\":5814783,")
                    append("\"fields\":[{\"name\":\"Version\",\"value\":\"${CrashReporter.APP_VERSION}\",\"inline\":true}]")
                    append("}]}")
                }
                DiscordWebhookClient.post(FEEDBACK_WEBHOOK_URL, payload)
            } catch (_: Throwable) {
                false
            }
            withContext(Dispatchers.Main) { onResult(delivered) }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun shouldShowPrompt(): Boolean {
        val openCount = Database.getSetting("app_open_count")?.toIntOrNull() ?: 0
        if (openCount < 10) return false
        if (Database.getSetting(KEY_DISMISSED) == "true") return false
        if (Database.getSetting(KEY_LEGACY) == "true") return false   // legacy guard
        val declinedStr = Database.getSetting(KEY_DECLINED)
        if (declinedStr != null) {
            val daysSince = try {
                java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(declinedStr),
                    java.time.LocalDate.now()
                )
            } catch (_: Exception) { 31L }
            if (daysSince < 30) return false
        }
        return true
    }

    private fun escapeJson(s: String): String =
        "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
}
