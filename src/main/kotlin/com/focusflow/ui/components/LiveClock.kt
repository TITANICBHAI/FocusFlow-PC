package com.focusflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A wall-clock "now" (epoch ms) that is a Compose [State], refreshed every [intervalMs] while
 * [active] is true.
 *
 * Why this exists: reading `System.currentTimeMillis()` (or a service's `remainingMs()`) directly
 * inside a composable gives a value that is correct only at the moment of composition. Nothing
 * tells Compose to compose again, so a countdown looks frozen until something unrelated (a tab
 * switch, a hover) forces a recompose. Incrementing a counter in a LaunchedEffect does not help
 * either unless that counter is *read* during composition.
 *
 * Read the returned state's value (e.g. `val nowMs by rememberLiveNowMs(...)`) in the composable
 * that renders the countdown, and derive the remaining time from it. Pass `active = false` when
 * there is nothing to count down so no timer runs and nothing recomposes.
 */
@Composable
fun rememberLiveNowMs(active: Boolean = true, intervalMs: Long = 1_000L): State<Long> {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, intervalMs) {
        now.value = System.currentTimeMillis()   // resync immediately on (re)start
        while (active && isActive) {
            delay(intervalMs)
            now.value = System.currentTimeMillis()
        }
    }
    return now
}
