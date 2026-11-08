package com.focusflow.ui.screens

/**
 * The quick session lengths on the Focus Launcher screen, in the order the chips appear. The
 * Custom chip is drawn after these, so "No limit" sits last of the presets, right before Custom:
 * the short, safe choices come first and an open-ended session is a deliberate pick. The screen
 * opens on 30 minutes.
 */
internal object FocusLauncherDurations {
    /** Chip label to length in minutes; null minutes means no time limit. */
    val presets: List<Pair<String, Int?>> = listOf(
        "30 min" to 30,
        "1 hour" to 60,
        "2 hours" to 120,
        "4 hours" to 240,
        "No limit" to null
    )

    /** The chip that is selected when the screen opens. */
    val defaultIndex: Int = presets.indexOfFirst { it.second == 30 }
        .also { check(it >= 0) { "The 30 minute preset is missing" } }
}
