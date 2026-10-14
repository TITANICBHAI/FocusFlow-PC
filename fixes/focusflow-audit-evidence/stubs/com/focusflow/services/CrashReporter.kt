package com.focusflow.services

// Stand-in that records what the real CrashReporter.reportCritical would have been given.
object CrashReporter {
    val reported = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun reportCritical(source: String, message: String, throwable: Throwable? = null) {
        reported += "source=$source throwable=${throwable?.javaClass?.name}: ${throwable?.message}"
    }
}
