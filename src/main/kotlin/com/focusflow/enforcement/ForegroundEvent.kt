package com.focusflow.enforcement

data class ForegroundEvent(
    val exe: String?,
    val pid: Long,
    val monoNs: Long
)
