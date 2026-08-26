package com.focusflow.enforcement

import kotlinx.coroutines.*

/**
 * AppBlocker
 *
 * Coordinates showing the block overlay when a blocked app is detected.
 *
 * Two-layer approach:
 *  1. FloatingBlockOverlay — a standalone always-on-top AWT JWindow that covers
 *     every monitor immediately, regardless of FocusFlow's own window position.
 *     This is the primary layer and works even when FocusFlow is minimised.
 *  2. BlockOverlay composable (inside the main window) — fires via callbacks so
 *     App.kt can react in Compose state. Acts as a secondary / in-app indicator.
 *
 * Both layers auto-dismiss after 4 seconds.
 */
object AppBlocker {

    private var overlayJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Controls both overlay surfaces:
     *  - FloatingBlockOverlay outside the main window
     *  - BlockOverlay inside the Compose window
     *
     * This is deliberately independent from enforcement. A disabled overlay
     * must never disable the process kill or temptation logging.
     */
    @Volatile var overlayEnabled: Boolean = true

    /** Called by App.kt — invoked with the blocked app name when in-app overlay should appear. */
    var onOverlayShow: ((String) -> Unit)? = null

    /** Called by App.kt — invoked when in-app overlay should disappear. */
    var onOverlayHide: (() -> Unit)? = null

    /**
     * Show the block overlay for [appName].
     *
     * Triggers FloatingBlockOverlay (always-on-top AWT window covering all screens)
     * as the primary layer, then also fires the in-app Compose overlay callback
     * so the main window UI stays in sync.
     *
     * Safe to call from any thread.
     */
    fun showOverlay(appName: String) {
        if (!overlayEnabled) return

        // Primary: standalone floating window — works regardless of FocusFlow window state
        FloatingBlockOverlay.show(appName)

        // Secondary: in-app Compose overlay (requires FocusFlow window to be visible)
        scope.launch {
            onOverlayShow?.invoke(appName)
            overlayJob?.cancel()
            overlayJob = launch {
                delay(4_000)
                hideOverlay()
            }
        }
    }

    fun hideOverlay() {
        FloatingBlockOverlay.hide()
        // Dispatch to Main so Compose state is touched on the correct thread
        scope.launch {
            overlayJob?.cancel()
            onOverlayHide?.invoke()
        }
    }

    fun dispose() {
        FloatingBlockOverlay.dispose()
        scope.cancel()
    }
}
