package com.focusflow.services

internal fun interface TelemetrySink {
    fun post(payloadJson: String)
}

internal object NoopTelemetrySink : TelemetrySink {
    override fun post(payloadJson: String) = Unit
}
