package com.focusflow.enforcement

import kotlinx.coroutines.*

/**
 * FloatingBlockOverlay
 *
 * A standalone always-on-top AWT/Swing window that covers every monitor
 * the moment a blocked app is detected — regardless of where FocusFlow's
 * own window is, whether it is minimised, or whether Compose is alive.
 *
 * This replaces the old approach of rendering a Compose layer inside the
 * main window (which required FocusFlow to be in the foreground to be visible).
 *
 * Design:
 *  - One JWindow spanning the union of all screen bounds (multi-monitor safe)
 *  - setAlwaysOnTop(true) — sits above every other window
 *  - Painted with a solid dark background + centered text (pure AWT, no Compose)
 *  - Auto-dismisses after DISMISS_MS milliseconds
 *  - Safe to call from any thread; all AWT work is dispatched to the EDT
 *  - No-ops silently on non-Windows platforms
 */
object FloatingBlockOverlay {

    @Volatile var dismissSeconds: Int = 4
        set(v) { field = v.coerceIn(2, 15) }

    @Volatile private var appNameText: String = ""
    @Volatile var overlayMessage: String = "Stay focused. You've got this."
        set(v) { field = v.take(120) }

    private var window: javax.swing.JWindow? = null
    private val scope            = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: show() is called from the enforcement thread; the auto-dismiss
    // coroutine (scope.launch on Dispatchers.IO) also reads/writes dismissJob
    // via hide(). Without @Volatile the enforcement-thread write of a new Job
    // reference may not be visible to the IO thread that calls hide(), leaving
    // it cancelling a stale reference and the new dismiss timer running past
    // the intended window.
    @Volatile private var dismissJob: Job? = null

    // ── Android promo tracking ───────────────────────────────────────────────
    // After the 3rd blocked attempt within a 30-day window, paint a small
    // "Also on Android" nudge at the bottom of the overlay.
    private var blockAttemptsSinceReset: Int = 0
    @Volatile private var showAndroidPromo: Boolean    = false
    private val promoLock = Any()
    private const val PROMO_THRESHOLD = 3

    // ── Public API ──────────────────────────────────────────────────────────

    fun show(appName: String, message: String = overlayMessage) {
        if (!isWindows) return
        appNameText = appName
        overlayMessage = message

        // Increment block attempt counter and update promo flag (DB-backed 30-day reset).
        scope.launch { updateBlockPromo() }

        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(dismissSeconds * 1000L)
            hide()
        }

        javax.swing.SwingUtilities.invokeLater {
            val w = ensureWindow()
            w.repaint()
            w.isVisible = true
            // Bring above everything, including always-on-top task manager etc.
            w.toFront()
            w.requestFocus()
        }
    }

    private fun updateBlockPromo() {
        synchronized(promoLock) {
            val today = java.time.LocalDate.now()
            val resetDateStr = com.focusflow.data.Database.getSetting("block_promo_reset_date")
            val daysSinceReset: Long = if (resetDateStr != null) {
                try {
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(resetDateStr),
                        today
                    )
                } catch (_: Exception) { 31L }
            } else 31L

            if (daysSinceReset >= 30) {
                blockAttemptsSinceReset = 0
                com.focusflow.data.Database.setSetting("block_promo_reset_date", today.toString())
            }
            blockAttemptsSinceReset++
            showAndroidPromo = blockAttemptsSinceReset >= PROMO_THRESHOLD
        }
    }

    fun hide() {
        dismissJob?.cancel()
        dismissJob = null
        javax.swing.SwingUtilities.invokeLater {
            window?.isVisible = false
        }
    }

    /** Call from App.kt shutdown to release OS resources. */
    fun dispose() {
        scope.cancel()
        javax.swing.SwingUtilities.invokeLater {
            window?.dispose()
            window = null
        }
    }

    // ── Window management ────────────────────────────────────────────────────

    private fun ensureWindow(): javax.swing.JWindow =
        window ?: buildWindow().also { window = it }

    private fun buildWindow(): javax.swing.JWindow {
        // Compute the union of ALL screen bounds so we cover every monitor.
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (gd in ge.screenDevices) {
            val b = gd.defaultConfiguration.bounds
            if (b.x            < minX) minX = b.x
            if (b.y            < minY) minY = b.y
            if (b.x + b.width  > maxX) maxX = b.x + b.width
            if (b.y + b.height > maxY) maxY = b.y + b.height
        }
        // Fallback if no screen devices found
        if (minX == Int.MAX_VALUE) { minX = 0; minY = 0; maxX = 1920; maxY = 1080 }

        val jw = javax.swing.JWindow()
        jw.setBounds(minX, minY, maxX - minX, maxY - minY)
        jw.isAlwaysOnTop = true
        jw.focusableWindowState = false   // don't steal keyboard focus from our app
        jw.type = java.awt.Window.Type.POPUP

        val overlay = this
        val panel = object : javax.swing.JPanel() {
            private val colBg      = java.awt.Color(13, 12, 24)
            private val colTitle   = java.awt.Color(232, 228, 240)
            private val colSub     = java.awt.Color(155, 150, 176)
            private val colHint    = java.awt.Color(90, 85, 115)
            private val colAccent  = java.awt.Color(124, 92, 191)

            override fun isOpaque() = true

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                    )

                    // ── Background ─────────────────────────────────────────
                    g2.color = colBg
                    g2.fillRect(0, 0, width, height)

                    val cx = width / 2
                    val cy = height / 2

                    // ── Block icon (filled circle with X) ──────────────────
                    val iconR = 42
                    g2.color = colAccent.darker()
                    g2.fillOval(cx - iconR, cy - 140 - iconR, iconR * 2, iconR * 2)
                    g2.color = colAccent
                    g2.stroke = java.awt.BasicStroke(4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                    val pad = 16
                    val bx = cx - iconR + pad; val by = cy - 140 - iconR + pad
                    val ex = cx + iconR - pad; val ey = cy - 140 + iconR - pad
                    g2.drawLine(bx, by, ex, ey)
                    g2.drawLine(ex, by, bx, ey)

                    // ── App name + "is blocked" title ──────────────────────
                    g2.color = colTitle
                    val titleFont = bestFont(
                        "Segoe UI", java.awt.Font.BOLD, 38,
                        java.awt.Font.BOLD, 38
                    )
                    g2.font = titleFont
                    val appPart = overlay.appNameText
                    val titlePart = " is blocked"

                    // Draw app name in accent colour, rest in white
                    val fmT = g2.fontMetrics
                    val fullTitle = "$appPart$titlePart"
                    val fullW     = fmT.stringWidth(fullTitle)
                    val startX    = cx - fullW / 2
                    val baseY     = cy - 36

                    g2.color = colAccent
                    g2.drawString(appPart, startX, baseY)
                    val appW = fmT.stringWidth(appPart)
                    g2.color = colTitle
                    g2.drawString(titlePart, startX + appW, baseY)

                    // ── Motivational message ───────────────────────────────
                    g2.color = colSub
                    g2.font = bestFont("Segoe UI", java.awt.Font.PLAIN, 20, java.awt.Font.PLAIN, 20)
                    val msg = overlay.overlayMessage
                    val fmS = g2.fontMetrics
                    g2.drawString(msg, cx - fmS.stringWidth(msg) / 2, cy + 20)

                    // ── Thin accent divider ────────────────────────────────
                    g2.color = colAccent.darker().darker()
                    g2.stroke = java.awt.BasicStroke(1f)
                    val divW = 200
                    g2.drawLine(cx - divW / 2, cy + 46, cx + divW / 2, cy + 46)

                    // ── Hint ───────────────────────────────────────────────
                    g2.color = colHint
                    g2.font = bestFont("Segoe UI", java.awt.Font.PLAIN, 14, java.awt.Font.PLAIN, 14)
                    val hint = "This overlay will close automatically."
                    val fmH = g2.fontMetrics
                    g2.drawString(hint, cx - fmH.stringWidth(hint) / 2, cy + 76)

                    // ── Android promo (shown after 3rd blocked attempt) ─────
                    if (overlay.showAndroidPromo) {
                        g2.color = colAccent.darker()
                        g2.font = bestFont("Segoe UI", java.awt.Font.PLAIN, 13, java.awt.Font.PLAIN, 13)
                        val promo = "FocusFlow is also available on Android  -  Get it on AppGallery"
                        val fmP = g2.fontMetrics
                        g2.drawString(promo, cx - fmP.stringWidth(promo) / 2, cy + 108)
                    }

                } finally {
                    g2.dispose()
                }
            }
        }
        panel.background = java.awt.Color(13, 12, 24)
        jw.contentPane = panel

        return jw
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the preferred font if available, otherwise falls back to SansSerif. */
    private fun bestFont(
        preferredName: String,
        preferredStyle: Int,
        preferredSize: Int,
        fallbackStyle: Int,
        fallbackSize: Int
    ): java.awt.Font {
        val preferred = java.awt.Font(preferredName, preferredStyle, preferredSize)
        return if (preferred.family.equals(preferredName, ignoreCase = true)) preferred
        else java.awt.Font(java.awt.Font.SANS_SERIF, fallbackStyle, fallbackSize)
    }
}
