package com.focusflow.data

/** Canonical process-name key used for allowance and daily-usage state. */
fun normalizeProcessKey(processName: String): String = processName.trim().lowercase()
