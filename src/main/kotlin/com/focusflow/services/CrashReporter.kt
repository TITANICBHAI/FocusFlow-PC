package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.RegistryLockdown
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CrashReporter — production-grade crash & diagnostic logging for FocusFlow.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  THREAD COVERAGE                                                        │
 * │  1. All Java/Kotlin threads    → Thread.setDefaultUncaughtExceptionHandler │
 * │  2. AWT Event Dispatch Thread  → sun.awt.exception.handler property     │
 * │  3. Kotlin coroutines          → fall-through to (1) via SupervisorJob  │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  EXCEPTION TYPES HANDLED                                                │
 * │  • NullPointerException, ClassCastException, IllegalStateException, …   │
 * │  • OutOfMemoryError   → pre-allocated 1 MB reserve freed before logging │
 * │  • StackOverflowError → handler avoids recursion; uses shallow path     │
 * │  • Full cause chain + suppressed exceptions + cycle detection           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  REPORT DESTINATIONS (tried in order, first success wins)               │
 * │  1. ~/Desktop/FocusFlow-crash-<timestamp>.log                           │
 * │  2. ~/.focusflow/crash-<timestamp>.log                                  │
 * │  3. %TEMP% / java.io.tmpdir / FocusFlow-crash-<timestamp>.log           │
 * │  4. stderr (last resort — always attempted regardless)                  │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  SAFETY GUARANTEES                                                      │
 * │  • Anti-re-entrancy: AtomicBoolean prevents duplicate reports           │
 * │  • Double-fault: entire handler body wrapped in try/catch               │
 * │  • OOM-safe: 1 MB heap reserve freed as the FIRST action                │
 * │  • SO-safe: appendExceptionChain depth-capped at MAX_CAUSE_DEPTH        │
 * │  • Pre-crash cleanup: restores Windows state so PC is never left locked │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
object CrashReporter {

    // ── Public configuration ───────────────────────────────────────────────────
    /** Developer support email — pre-filled when the user clicks "Send Report". */
    const val SUPPORT_EMAIL = "support@tbtechs.dev"

    /** Single source of truth for the app version string. */
    const val APP_VERSION = "1.1.8"

    // ── Constants ─────────────────────────────────────────────────────────────

    // Webhook stored as Base64 so plain-text scrapers crawling GitHub skip it.
    // Decoded lazily in memory at runtime — never sits as a plain URL in source.
    private const val OBFUSCATED_WEBHOOK =
        "aHR0cHM6Ly9kaXNjb3JkLmNvbS9hcGkvd2ViaG9va3MvMTUxMDY4MTk2OTYxNTE3NTgy" +
        "MS9zdDduM1VoRU4wOHNGZ3hhUjVQUUNsZDZkUjQyRFY3X0Y4Tm9tSFVVYW85cENnaElo" +
        "ZUhuVTNvazVqdnZ2UHpuWGlNcQ=="

    private val DISCORD_WEBHOOK_URL: String by lazy {
        try {
            String(java.util.Base64.getDecoder().decode(OBFUSCATED_WEBHOOK), Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }

    private const val MAX_CAUSE_DEPTH   = 20
    private const val MAX_THREAD_FRAMES = 40
    private const val MAX_THREADS       = 200

    private val TIMESTAMP_FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val TIMESTAMP_HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    // ── State ─────────────────────────────────────────────────────────────────

    /** App version injected at startup — defaults to APP_VERSION, overridable via install(). */
    @Volatile private var appVersion = APP_VERSION

    /**
     * Pre-allocated 1 MB emergency heap reserve.
     * Nulled out at the very start of any crash handler so the logger has
     * memory available even when the crash was an OutOfMemoryError.
     */
    @Volatile private var oomReserve: ByteArray? = ByteArray(1024 * 1024)

    /**
     * Atomic re-entrancy guard.
     * Ensures only one crash report is ever generated, even if two threads
     * crash simultaneously (e.g. cascade failure where multiple coroutines
     * die from the same root cause).
     */
    private val crashHandled = AtomicBoolean(false)

    // ── AWT EDT exception handler ─────────────────────────────────────────────

    /**
     * Loaded by AWT via the `sun.awt.exception.handler` system property.
     * AWT instantiates this class by name and calls handle(Throwable) whenever
     * an uncaught exception propagates through the Event Dispatch Thread loop.
     *
     * Must be a non-inner class with a public no-arg constructor and a
     * public `handle(Throwable)` method — this is AWT's internal contract.
     */
    class AwtExceptionHandler {
        @Suppress("unused")
        fun handle(e: Throwable) {
            report(Thread.currentThread(), e, source = "AWT-EDT")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Install all crash handlers. Call this as the very FIRST statement in main()
     * so every subsequent failure — including Database.init() — is captured.
     *
     * @param version  The app version string written into every crash report.
     */
    fun install(version: String = APP_VERSION) {
        appVersion = version

        // 1. All Java/Kotlin threads (and coroutines via thread fallthrough)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(thread, throwable, source = "Thread")
        }

        // 2. AWT Event Dispatch Thread
        //    "sun.awt.exception.handler" is an undocumented but stable internal
        //    hook present in all Oracle/OpenJDK/GraalVM JVMs since Java 5.
        //    AWT resets this property after each EDT exception, so we must
        //    re-install it from the handler itself to survive repeated EDT crashes.
        System.setProperty(
            "sun.awt.exception.handler",
            "com.focusflow.services.CrashReporter\$AwtExceptionHandler"
        )
    }

    /**
     * Manually report a caught-but-fatal throwable.
     * Use when you catch an exception but cannot meaningfully recover.
     */
    fun report(thread: Thread, throwable: Throwable, source: String = "Manual") {
        // Atomic check — only the first caller wins; all others return immediately.
        if (!crashHandled.compareAndSet(false, true)) return

        // Free OOM reserve FIRST — before any allocation — so we have heap.
        oomReserve = null

        // Send before the handler returns. A daemon worker is not reliable here:
        // once the uncaught-exception handler returns, the JVM may exit before
        // that worker has opened the connection.
        sendToDiscord(throwable, source)

        // Re-install AWT handler (AWT clears it after each use)
        try {
            System.setProperty(
                "sun.awt.exception.handler",
                "com.focusflow.services.CrashReporter\$AwtExceptionHandler"
            )
        } catch (_: Throwable) {}

        try {
            val ts      = LocalDateTime.now()
            val tsFile  = ts.format(TIMESTAMP_FILE)
            val tsHuman = ts.format(TIMESTAMP_HUMAN)

            val reportText = buildReport(thread, throwable, source, tsHuman)
            val logFile    = writeLog(reportText, tsFile)

            // Always emit at least the exception type + message to stderr
            System.err.println("[FocusFlow] CRASH [$source] ${throwable.javaClass.name}: ${throwable.message}")
            logFile?.let { System.err.println("[FocusFlow] Log: ${it.absolutePath}") }

            // Safety cleanup: restore Windows state
            safetyCleanup()

        } catch (handlerEx: Throwable) {
            // ── Double-fault: crash handler itself crashed ────────────────────
            // This path is extremely unlikely but must be bulletproof.
            try {
                System.err.println("[FocusFlow] CRASH HANDLER FAULT: ${handlerEx.javaClass.name}: ${handlerEx.message}")
                System.err.println("[FocusFlow] Original: ${throwable.javaClass.name}: ${throwable.message}")
                throwable.printStackTrace(System.err)
                safetyCleanup()
            } catch (_: Throwable) {
                // Absolute last resort — cannot do anything more
            }
        }
    }

    // ── Report assembly ────────────────────────────────────────────────────────

    private fun buildReport(
        thread: Thread,
        throwable: Throwable,
        source: String,
        tsHuman: String
    ): String {
        val sb  = StringBuilder(8192)
        val SEP = "═".repeat(72)
        val DIV = "─".repeat(72)

        // ── Header ────────────────────────────────────────────────────────────
        sb.ln(SEP)
        sb.ln("  FOCUSFLOW CRASH REPORT")
        sb.ln(SEP)
        sb.ln("Timestamp    : $tsHuman")
        sb.ln("App Version  : $appVersion")
        sb.ln("Crash Source : $source")
        sb.ln()

        // ── System ────────────────────────────────────────────────────────────
        sb.ln("── SYSTEM $DIV".take(72))
        sb.ln("OS Name      : ${prop("os.name")}")
        sb.ln("OS Version   : ${prop("os.version")}")
        sb.ln("OS Arch      : ${prop("os.arch")}")
        sb.ln("JVM Name     : ${prop("java.vm.name")}")
        sb.ln("JVM Version  : ${prop("java.vm.version")}")
        sb.ln("JVM Vendor   : ${prop("java.vendor")}")
        sb.ln("Java Version : ${prop("java.version")}")
        sb.ln("User Home    : ${prop("user.home")}")
        sb.ln("Working Dir  : ${prop("user.dir")}")
        sb.ln("File Enc     : ${prop("file.encoding")}")
        sb.ln("Render API   : ${prop("skiko.renderApi")}")
        sb.ln()

        // ── Memory ────────────────────────────────────────────────────────────
        sb.ln("── MEMORY $DIV".take(72))
        val rt   = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val total = rt.totalMemory() / 1_048_576L
        val max   = rt.maxMemory()   / 1_048_576L
        sb.ln("Heap Used    : ${used} MB  (of ${total} MB allocated, ${max} MB max)")
        try {
            val mx = ManagementFactory.getMemoryMXBean()
            val nh = mx.nonHeapMemoryUsage
            val nhUsed = nh.used / 1_048_576L
            val nhMax  = if (nh.max < 0) "?" else "${nh.max / 1_048_576L} MB"
            sb.ln("Non-Heap     : ${nhUsed} MB used / $nhMax max")
        } catch (_: Throwable) {}
        try {
            val osMx = ManagementFactory.getOperatingSystemMXBean()
            // Use reflection to avoid importing the internal com.sun.management package
            val getTotalPhys = osMx.javaClass.getMethod("getTotalPhysicalMemorySize")
            val getFreePhys  = osMx.javaClass.getMethod("getFreePhysicalMemorySize")
            val totalPhys = (getTotalPhys.invoke(osMx) as Number).toLong() / 1_048_576L
            val freePhys  = (getFreePhys.invoke(osMx)  as Number).toLong() / 1_048_576L
            sb.ln("Physical RAM : ${freePhys} MB free / ${totalPhys} MB total")
        } catch (_: Throwable) {}
        sb.ln("CPU Cores    : ${rt.availableProcessors()}")
        try {
            val threadMx = ManagementFactory.getThreadMXBean()
            sb.ln("Thread Count : ${threadMx.threadCount} live, ${threadMx.daemonThreadCount} daemon")
            sb.ln("Peak Threads : ${threadMx.peakThreadCount}")
            sb.ln("Total Starts : ${threadMx.totalStartedThreadCount}")
        } catch (_: Throwable) {}
        sb.ln()

        // ── Crash thread ──────────────────────────────────────────────────────
        sb.ln("── CRASH THREAD $DIV".take(72))
        @Suppress("DEPRECATION") val crashThreadId = thread.id
        sb.ln("Thread Name  : ${thread.name}")
        sb.ln("Thread ID    : $crashThreadId")
        sb.ln("Daemon       : ${thread.isDaemon}")
        sb.ln("Priority     : ${thread.priority}")
        sb.ln("State        : ${thread.state}")
        sb.ln("Thread Group : ${thread.threadGroup?.name ?: "?"}")
        sb.ln()

        // ── Exception chain ───────────────────────────────────────────────────
        sb.ln("── EXCEPTION CHAIN $DIV".take(72))
        val isOom = throwable is OutOfMemoryError
        val isSo  = throwable is StackOverflowError
        when {
            isOom -> sb.ln("! OutOfMemoryError — heap was exhausted when this crash occurred.")
            isSo  -> sb.ln("! StackOverflowError — stack trace may be truncated to protect the handler.")
        }
        appendExceptionChain(sb, throwable, depth = 0, seen = mutableSetOf())
        sb.ln()

        // ── FocusFlow enforcement state ───────────────────────────────────────
        sb.ln("── FOCUSFLOW STATE $DIV".take(72))
        appendFocusFlowState(sb)
        sb.ln()

        // ── Database file info ────────────────────────────────────────────────
        sb.ln("── DATABASE $DIV".take(72))
        appendDatabaseInfo(sb)
        sb.ln()

        // ── Thread dump ───────────────────────────────────────────────────────
        sb.ln("── ALL LIVE THREADS $DIV".take(72))
        appendThreadDump(sb, isSo)
        sb.ln()

        // ── GC info ───────────────────────────────────────────────────────────
        sb.ln("── GC ACTIVITY $DIV".take(72))
        appendGcInfo(sb)
        sb.ln()

        // ── Footer ────────────────────────────────────────────────────────────
        sb.ln(SEP)
        sb.ln("  END OF REPORT — FocusFlow $appVersion")
        sb.ln(SEP)

        return sb.toString()
    }

    // ── Section builders ───────────────────────────────────────────────────────

    /**
     * Recursively appends the full exception chain.
     * Handles:
     *   - Cause chains (getCause) — depth-capped at MAX_CAUSE_DEPTH
     *   - Suppressed exceptions (addSuppressed / try-with-resources)
     *   - Circular cause references (via identity set)
     *   - StackOverflowError (stack capped at MAX_THREAD_FRAMES to avoid re-overflow)
     */
    private fun appendExceptionChain(
        sb: StringBuilder,
        t: Throwable,
        depth: Int,
        seen: MutableSet<Int>   // IdentityHashCode, not equals() — avoids allocating IdentityHashMap
    ) {
        if (depth > MAX_CAUSE_DEPTH) {
            sb.ln("  [cause chain truncated at depth $MAX_CAUSE_DEPTH]")
            return
        }
        val id = System.identityHashCode(t)
        if (!seen.add(id)) {
            sb.ln("  [CIRCULAR CAUSE REFERENCE — stopping]")
            return
        }

        val indent  = "  ".repeat(depth)
        val label   = when (depth) { 0 -> "" else -> "${indent}Caused by: " }
        val isOOM   = t is OutOfMemoryError
        val isSO    = t is StackOverflowError

        sb.ln("$label${t.javaClass.name}: ${t.message ?: "(no message)"}")

        // Stack frames — capped for StackOverflowError to avoid re-overflowing
        val stack = t.stackTrace
        val cap   = if (isSO || isOOM) minOf(MAX_THREAD_FRAMES, stack.size) else stack.size
        for (i in 0 until cap) {
            sb.ln("$indent    at ${stack[i]}")
        }
        if (cap < stack.size) {
            sb.ln("$indent    ... ${stack.size - cap} more frames (truncated)")
        }

        // Suppressed exceptions (from try-with-resources / coroutine cancellation)
        for (sup in t.suppressed) {
            sb.ln("${indent}  Suppressed: ${sup.javaClass.name}: ${sup.message ?: "(no message)"}")
            val supStack = sup.stackTrace.take(10)
            for (f in supStack) sb.ln("${indent}      at $f")
            if (sup.stackTrace.size > 10) sb.ln("${indent}      ... ${sup.stackTrace.size - 10} more")
        }

        // Cause chain
        t.cause?.let { cause ->
            appendExceptionChain(sb, cause, depth + 1, seen)
        }
    }

    private fun appendFocusFlowState(sb: StringBuilder) {
        try {
            val sess = FocusSessionService.state.value
            sb.ln("Session Active     : ${sess.isActive}")
            sb.ln("Session Paused     : ${sess.isPaused}")
            sb.ln("Session Task       : ${sess.taskName.ifBlank { "(none)" }}")
            sb.ln("Session Elapsed    : ${sess.elapsedSeconds}s / ${sess.totalSeconds}s")
            sb.ln("Blocked Procs (s)  : ${sess.blockedProcesses.size}")
        } catch (_: Throwable) { sb.ln("  (session state unavailable)") }

        try {
            sb.ln("Always-On Block    : ${ProcessMonitor.alwaysOnEnabled}")
            sb.ln("Session Blocking   : ${ProcessMonitor.sessionActive}")
            sb.ln("Kill Switch        : ${ProcessMonitor.killSwitchActive}")
            sb.ln("Launcher Active    : ${FocusLauncherService.isActive.value}")
            sb.ln("Launcher Break     : ${FocusLauncherService.breakActive.value}")
            sb.ln("Schedule Blocked   : ${ProcessMonitor.scheduleBlockedProcesses.size} processes")
            sb.ln("Standalone Blocked : ${ProcessMonitor.standaloneBlockedProcesses.size} processes")
            sb.ln("Daily Cap Blocked  : ${ProcessMonitor.dailyAllowanceBlockedProcesses.size} processes")
            sb.ln("Launcher Allowed   : ${ProcessMonitor.launcherAllowedProcesses.size} processes")
            sb.ln("Nuclear Mode       : ${NuclearMode.isActive}")
        } catch (_: Throwable) { sb.ln("  (enforcement state unavailable)") }

        try {
            sb.ln("Sound Aversion     : ${SoundAversion.isEnabled}")
        } catch (_: Throwable) {}
    }

    private fun appendDatabaseInfo(sb: StringBuilder) {
        try {
            val dbDir  = File(System.getProperty("user.home") + "/.focusflow")
            val dbFile = File(dbDir, "focusflow.db")
            sb.ln("DB Path      : ${dbFile.absolutePath}")
            sb.ln("DB Exists    : ${dbFile.exists()}")
            if (dbFile.exists()) {
                sb.ln("DB Size      : ${dbFile.length() / 1024L} KB")
                sb.ln("DB Modified  : ${java.util.Date(dbFile.lastModified())}")
            }
            // Check for WAL / SHM files (indicates a transaction was open at crash time)
            val wal = File(dbDir, "focusflow.db-wal")
            val shm = File(dbDir, "focusflow.db-shm")
            if (wal.exists()) sb.ln("WAL File     : EXISTS (${wal.length() / 1024L} KB) — open transaction at crash?")
            if (shm.exists()) sb.ln("SHM File     : EXISTS")
            // List other focusflow dir contents
            val others = dbDir.listFiles()?.filter {
                it.name != "focusflow.db" && it.name != "focusflow.db-wal" && it.name != "focusflow.db-shm"
            }?.map { "${it.name} (${it.length() / 1024L} KB)" } ?: emptyList()
            if (others.isNotEmpty()) sb.ln("Other files  : ${others.joinToString(", ")}")
        } catch (_: Throwable) { sb.ln("  (database info unavailable)") }
    }

    /**
     * Full thread dump — all live threads with stack traces.
     * Capped at MAX_THREADS to avoid gigantic logs from thread pool explosions.
     * For StackOverflowError, each thread's frames are also capped.
     */
    private fun appendThreadDump(sb: StringBuilder, isSo: Boolean) {
        try {
            val all     = Thread.getAllStackTraces()
            val sorted  = all.entries
                .sortedWith(compareBy({ it.key.isDaemon }, { it.key.name }))
                .take(MAX_THREADS)

            sb.ln("Total threads captured: ${all.size}${if (all.size > MAX_THREADS) " (showing first $MAX_THREADS)" else ""}")
            sb.ln()

            for ((t, stack) in sorted) {
                @Suppress("DEPRECATION") val tId = t.id
                sb.ln("Thread \"${t.name}\" [id=$tId state=${t.state} daemon=${t.isDaemon} priority=${t.priority}]")
                if (stack.isEmpty()) {
                    sb.ln("  (no Java frames — thread in native code)")
                } else {
                    val cap = if (isSo) minOf(MAX_THREAD_FRAMES, stack.size) else stack.size
                    for (i in 0 until cap) sb.ln("  at ${stack[i]}")
                    if (cap < stack.size) sb.ln("  ... ${stack.size - cap} more frames")
                }
                sb.ln()
            }
        } catch (_: Throwable) { sb.ln("  (thread dump unavailable)") }
    }

    private fun appendGcInfo(sb: StringBuilder) {
        try {
            var totalGcCount = 0L
            var totalGcTimeMs = 0L
            for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
                val count = gc.collectionCount
                val time  = gc.collectionTime
                sb.ln("${gc.name.padEnd(30)} : ${if (count < 0) "?" else "$count collections"}, ${if (time < 0) "?" else "${time} ms"}")
                if (count > 0) totalGcCount += count
                if (time  > 0) totalGcTimeMs += time
            }
            if (totalGcCount > 0) sb.ln("Total GC     : $totalGcCount collections, ${totalGcTimeMs} ms cumulative pause")
        } catch (_: Throwable) { sb.ln("  (GC info unavailable)") }
    }

    // ── Log file writing ───────────────────────────────────────────────────────

    /**
     * Tries three log destinations in order; returns the first that succeeds.
     * Also always writes the first ~4 KB of the report to stderr so there's
     * always a record even if all file paths fail.
     */
    private fun writeLog(content: String, tsFile: String): File? {
        val filename = "FocusFlow-crash-$tsFile.log"

        // Always write header to stderr regardless of file success
        content.lines().take(30).forEach { System.err.println(it) }

        val candidates = buildList<File> {
            // 1. Desktop
            try {
                val desktop = File(System.getProperty("user.home"), "Desktop")
                if (desktop.isDirectory) add(File(desktop, filename))
            } catch (_: Throwable) {}

            // 2. ~/.focusflow/
            try {
                add(File(System.getProperty("user.home") + "/.focusflow/$filename"))
            } catch (_: Throwable) {}

            // 3. System temp
            try {
                add(File(System.getProperty("java.io.tmpdir", System.getProperty("user.home")), filename))
            } catch (_: Throwable) {}
        }

        for (candidate in candidates) {
            try {
                candidate.parentFile?.mkdirs()
                candidate.writeText(content, Charsets.UTF_8)
                return candidate
            } catch (_: Throwable) {
                // Try next destination
            }
        }

        // All destinations failed — full content to stderr
        System.err.println("[FocusFlow] All log destinations failed. Full crash report:")
        System.err.println(content)
        return null
    }

    // ── Safety cleanup ─────────────────────────────────────────────────────────

    /**
     * Best-effort pre-crash cleanup.
     * Every call is individually guarded — one failure must not block the others.
     * Goal: leave Windows in a usable state if the app dies mid-session.
     */
    private fun safetyCleanup() {
        // Order matters: restore taskbar/windows first, then disable enforcement.
        //
        // RegistryLockdown.disable() is called DIRECTLY here — NOT only through
        // FocusLauncherService or NuclearMode — so that the DisableTaskMgr /
        // NoLogOff / HideFastUserSwitching keys are always cleaned up even if
        // the higher-level services throw. This is belt-and-suspenders: the
        // JVM shutdown hook and the Main.kt startup janitor cover the cases
        // where safetyCleanup() itself never runs (SIGKILL, power loss, OOM kill).
        try { FocusLauncherService.emergencyRestoreWindows() } catch (_: Throwable) {}
        try { NuclearMode.disable()                          } catch (_: Throwable) {}
        try { RegistryLockdown.disable()                     } catch (_: Throwable) {}
        try { ProcessMonitor.sessionActive   = false         } catch (_: Throwable) {}
        try { ProcessMonitor.alwaysOnEnabled = false         } catch (_: Throwable) {}
        try { ProcessMonitor.scheduleBlockedProcesses        = emptySet() } catch (_: Throwable) {}
        try { ProcessMonitor.standaloneBlockedProcesses      = emptySet() } catch (_: Throwable) {}
        try { ProcessMonitor.dailyAllowanceBlockedProcesses  = emptySet() } catch (_: Throwable) {}
        try { ProcessMonitor.launcherAllowedProcesses        = emptySet() } catch (_: Throwable) {}
        try { FocusSessionService.end(completed = false)     } catch (_: Throwable) {}
        // Mark in DB that a crash occurred — useful for next-launch recovery dialogs
        try { Database.setSetting("last_crash_version", appVersion) } catch (_: Throwable) {}
        try { Database.setSetting("last_crash_ts", LocalDateTime.now().toString()) } catch (_: Throwable) {}
    }

    // ── Public utilities ───────────────────────────────────────────────────────

    /**
     * Reports a non-crash **critical** silent error to Discord.
     *
     * Use this for exceptions that are swallowed (i.e. won't reach the uncaught-exception
     * handler) but that could allow enforcement bypass or leave the app in a broken state —
     * e.g. a database open failure or a kiosk sweep loop crashing silently.
     *
     * Distinct from a full crash report:
     *   • Orange embed (not red) so it's visually separate in the channel.
     *   • Title prefix "🟠 Critical Silent Error" rather than "⚠️ Production Crash".
     *   • No JVM exit — the app keeps running.
     *
     * Respects the user's crash_reports_enabled privacy setting.
     * Fires on a daemon background thread; never throws.
     */
    fun reportCritical(source: String, message: String, throwable: Throwable? = null) {
        val webhookUrl = DISCORD_WEBHOOK_URL.takeIf { it.isNotBlank() } ?: return
        val optedIn = try { Database.getSetting("crash_reports_enabled") != "false" } catch (_: Throwable) { true }
        if (!optedIn) return

        Thread {
            try {
                fun String.esc() = replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "")
                    .replace("\t", "\\t")

                val safeMsg    = message.esc()
                val safeSource = source.esc()
                val traceBlock = if (throwable != null) {
                    val raw = throwable.stackTraceToString()
                    val t   = if (raw.length > 1200) raw.substring(0, 1200) + "\n... [truncated]" else raw
                    "**Stack Trace:**\\n```kotlin\\n${t.esc()}\\n```"
                } else ""
                val fingerprint = if (throwable != null) crashFingerprint(throwable).esc()
                                  else source.take(40).replace("#", "_").esc()
                val osName  = prop("os.name").esc()
                val javaVer = prop("java.version").esc()

                val payload = """
                    {
                      "username": "FocusFlow Telemetry",
                      "embeds": [{
                        "title": "🟠 Critical Silent Error — v$appVersion",
                        "color": 16744272,
                        "fields": [
                          { "name": "Source",      "value": "$safeSource",    "inline": true  },
                          { "name": "OS",          "value": "$osName",        "inline": true  },
                          { "name": "Java",        "value": "$javaVer",       "inline": true  },
                          { "name": "Fingerprint", "value": "`$fingerprint`", "inline": false }
                        ],
                        "description": "$safeMsg\n$traceBlock"
                      }]
                    }
                """.trimIndent()

                DiscordWebhookClient.post(webhookUrl, payload)
            } catch (_: Throwable) {
                // Intentionally silent — telemetry must never cause secondary failures.
            }
        }.also { it.isDaemon = true; it.name = "focusflow-critical-telemetry" }.start()
    }

    /**
     * Returns all FocusFlow crash log files found on this machine, newest first.
     * Searches: ~/Desktop, ~/.focusflow/, and %TEMP%.
     */
    fun findCrashLogs(): List<File> {
        val home = System.getProperty("user.home", "")
        val searchDirs = listOf(
            File(home, "Desktop"),
            File("$home/.focusflow"),
            File(System.getProperty("java.io.tmpdir", home))
        )
        return searchDirs
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.startsWith("FocusFlow-crash-") && f.name.endsWith(".log") }
                    ?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    // ── Discord telemetry ──────────────────────────────────────────────────────

    /**
     * Known-benign exception patterns that should NEVER be sent to Discord.
     *
     * These are Compose / coroutine internals that fire harmlessly and recover
     * automatically.  Sending them would flood the webhook whenever many users
     * hit the same Compose Desktop quirk simultaneously.
     *
     * Matching rules (checked in order, OR logic):
     *   • exceptionClass  — startsWith match on the fully-qualified class name
     *   • stackFrameToken — substring match anywhere in the first 10 stack frames
     *
     * Add new entries here whenever Discord shows the same benign pattern 5+ times.
     */
    private data class BenignPattern(val exceptionClass: String, val stackFrameToken: String = "")

    private val BENIGN_PATTERNS = listOf(
        // Compose gesture-cancellation noise — fires on rapid clicks / focus loss.
        // PressGestureScopeImpl.reset throws inside a coroutine cancellation handler;
        // Compose recovers silently.  Not a real crash.
        BenignPattern(
            exceptionClass  = "kotlinx.coroutines.CompletionHandlerException",
            stackFrameToken = "PressGestureScopeImpl"
        ),
        // Generic coroutine cancellation wrappers from Compose's render loop.
        BenignPattern(
            exceptionClass  = "kotlinx.coroutines.CompletionHandlerException",
            stackFrameToken = "FlushCoroutineDispatcher"
        ),
        // Skiko/Skia render-thread interruptions on window minimise / close.
        BenignPattern(
            exceptionClass  = "kotlinx.coroutines.CompletionHandlerException",
            stackFrameToken = "ComposeSceneRecomposer"
        )
    )

    /**
     * Returns true when [t] matches a known-benign pattern and should be silently
     * dropped from Discord (the local crash log is still written as normal).
     */
    private fun isKnownBenign(t: Throwable): Boolean {
        val className = t.javaClass.name
        val topFrames = t.stackTrace.take(10).joinToString("\n") { it.toString() }

        for (pattern in BENIGN_PATTERNS) {
            if (!className.startsWith(pattern.exceptionClass)) continue
            if (pattern.stackFrameToken.isEmpty()) return true
            if (pattern.stackFrameToken in topFrames) return true
            // Also search cause chain for the token (coroutine wrappers bury the root)
            var cause = t.cause
            var depth = 0
            while (cause != null && depth++ < 5) {
                val causeFrames = cause.stackTrace.take(10).joinToString("\n") { it.toString() }
                if (pattern.stackFrameToken in causeFrames) return true
                cause = cause.cause
            }
        }
        return false
    }

    /**
     * Produces a short stable fingerprint for [t] so identical crashes from
     * different users can be grouped visually inside Discord.
     *
     * Format:  <short-class-name>#<first-app-frame-method> e.g. "NPE#loadSettings"
     * If no app frame is found the top-most frame method name is used instead.
     */
    private fun crashFingerprint(t: Throwable): String {
        val shortClass = t.javaClass.simpleName.take(24)
        val appFrame = (t.cause?.stackTrace ?: t.stackTrace)
            .firstOrNull { it.className.startsWith("com.focusflow") }
            ?: t.stackTrace.firstOrNull()
        val method = appFrame?.methodName?.take(28) ?: "unknown"
        return "$shortClass#$method"
    }

    /**
     * Sends a compact crash embed to a Discord webhook before the fatal handler
     * returns. This is intentionally synchronous because the JVM may terminate
     * before a daemon worker gets a chance to deliver the report.
     *
     * • Known-benign Compose/coroutine exceptions are silently dropped (no Discord ping).
     * • Every real report includes a fingerprint field — identical bugs from many
     *   users produce embeds with the same fingerprint, making deduplication easy.
     * • The endpoint is stored as Base64 (OBFUSCATED_WEBHOOK) so plain-text scrapers
     *   skimming GitHub do not pick it up.
     * • All exceptions are swallowed — telemetry must never interfere with local
     *   crash handling or JVM exit.
     */
    private fun sendToDiscord(throwable: Throwable, source: String) {
        val webhookUrl = DISCORD_WEBHOOK_URL.takeIf { it.isNotBlank() } ?: return

        // ── Benign filter — drop well-known harmless Compose/coroutine noise ──
        if (isKnownBenign(throwable)) return

        // ── User opt-out — respect the Privacy setting in Settings screen ──
        // Default is true (opted in). Only skip if the user explicitly set it to "false".
        val optedIn = try { Database.getSetting("crash_reports_enabled") != "false" } catch (_: Throwable) { true }
        if (!optedIn) return

        try {
                val rawTrace = throwable.stackTraceToString()
                val trace = if (rawTrace.length > 1500)
                    rawTrace.substring(0, 1500) + "\n... [truncated]"
                else rawTrace

                fun String.esc() = replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "")
                    .replace("\t", "\\t")

                val safeMsg         = (throwable.message ?: "Unknown exception").esc()
                val safeTrace       = trace.esc()
                val safeClass       = throwable.javaClass.name.esc()
                val osName          = prop("os.name").esc()
                val osVer           = prop("os.version").esc()
                val javaVer         = prop("java.version").esc()
                val fingerprint     = crashFingerprint(throwable).esc()

                val payload = """
                    {
                      "username": "FocusFlow Telemetry",
                      "embeds": [{
                        "title": "⚠️ Production Crash — v$appVersion",
                        "color": 16711680,
                        "fields": [
                          { "name": "Exception",    "value": "$safeClass",        "inline": false },
                          { "name": "Message",      "value": "$safeMsg",          "inline": false },
                          { "name": "Source",       "value": "$source",            "inline": true  },
                          { "name": "OS",           "value": "$osName $osVer",    "inline": true  },
                          { "name": "Java",         "value": "$javaVer",          "inline": true  },
                          { "name": "Fingerprint",  "value": "`$fingerprint`",    "inline": false }
                        ],
                        "description": "**Stack Trace:**\n```kotlin\n$safeTrace\n```\n> Same fingerprint = same root cause. Search Discord for `$fingerprint` to count duplicates."
                      }]
                    }
                """.trimIndent()

                DiscordWebhookClient.post(webhookUrl, payload)
            } catch (_: Throwable) {
                // Intentionally silent — telemetry must never cause secondary failures.
            }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun prop(key: String): String =
        try { System.getProperty(key) ?: "(not set)" } catch (_: Throwable) { "(error reading property)" }

    private fun StringBuilder.ln(s: String = "") {
        append(s)
        append('\n')
    }
}
