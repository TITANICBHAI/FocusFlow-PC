package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.StandaloneBlock
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.NetworkBlocker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.awt.TrayIcon
import java.util.logging.Level
import java.util.logging.Logger

object StandaloneBlockService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = Logger.getLogger(StandaloneBlockService::class.java.name)
    private const val PROCESSES_KEY = "standalone_block_processes"
    private const val UNTIL_KEY = "standalone_block_until"
    private const val START_KEY = "standalone_block_start"
    private val CLEAR_BLOCK_SETTINGS = linkedMapOf(
        PROCESSES_KEY to "",
        UNTIL_KEY to "0",
        START_KEY to "0"
    )
    // @Volatile: watchJob is assigned inside a scope.launch coroutine (IO thread)
    // and read/cancelled from stop() which may be called from the UI thread.
    // Without @Volatile the UI thread may see a stale null reference and fail
    // to cancel the watcher, leaving it running past the intended stop point.
    @Volatile private var watchJob: Job? = null
    @Volatile private var loadRetryJob: Job? = null

    private val _block = MutableStateFlow<StandaloneBlock?>(null)
    val block: StateFlow<StandaloneBlock?> = _block

    /** True when there is an active block whose window has started and not yet ended. */
    val isActive: Boolean get() {
        val b = _block.value ?: return false
        val now = System.currentTimeMillis()
        val started = b.startMs == null || now >= b.startMs
        return started && b.untilMs > now && b.processNames.isNotEmpty()
    }

    /** True when a block is configured (may be scheduled but not yet started). */
    val isScheduled: Boolean get() {
        val b = _block.value ?: return false
        val now = System.currentTimeMillis()
        return b.untilMs > now && b.processNames.isNotEmpty()
    }

    /** Starts or schedules a standalone block.
     *  @param startMs epoch-ms when enforcement should begin; null = immediately. */
    fun start(processNames: List<String>, durationMs: Long, startMs: Long? = null): Boolean {
        val resolvedStart = startMs?.coerceAtLeast(System.currentTimeMillis()) ?: System.currentTimeMillis()
        val untilMs = resolvedStart + durationMs
        val newBlock = StandaloneBlock(
            processNames = processNames,
            untilMs      = untilMs,
            startMs      = if (startMs != null && startMs > System.currentTimeMillis()) startMs else null
        )
        val persisted = writeSettings(
            linkedMapOf(
                PROCESSES_KEY to processNames.joinToString(","),
                UNTIL_KEY to untilMs.toString(),
                START_KEY to (newBlock.startMs ?: 0L).toString()
            ),
            "start standalone block"
        )
        _block.update { newBlock }

        if (newBlock.startMs == null) {
            // Immediate enforcement
            ProcessMonitor.standaloneBlockedProcesses = processNames.map { it.lowercase() }.toSet()
            SystemTrayManager.showNotification(
                "Block Started",
                "${processNames.size} app(s) blocked for ${durationMs / 60_000}m",
                TrayIcon.MessageType.WARNING
            )
            SystemTrayManager.updateTooltip("FocusFlow — Blocking ${processNames.size} apps")
        } else {
            // Scheduled — enforcement will kick in when startMs is reached
            SystemTrayManager.showNotification(
                "Block Scheduled",
                "${processNames.size} app(s) will be blocked from the scheduled time.",
                TrayIcon.MessageType.INFO
            )
        }

        startWatcher()
        return persisted
    }

    fun addTime(extraMs: Long): Boolean {
        // update{} is atomic — prevents a read-modify-write race if two UI events
        // (e.g., two rapid "Add 5 min" taps) call addTime() simultaneously.
        // Without it, both callers read the same current value, compute the same
        // newUntil, and the second write silently discards the first's addition.
        var newUntil: Long? = null
        _block.update { current ->
            if (current == null) return@update null
            val nu = maxOf(current.untilMs, System.currentTimeMillis()) + extraMs
            newUntil = nu
            current.copy(untilMs = nu)
        }
        return newUntil?.let {
            writeSetting(UNTIL_KEY, it.toString(), "extend standalone block")
        } ?: true
    }

    fun addApps(moreProcesses: List<String>): Boolean {
        // Same atomicity rationale as addTime() above.
        var finalMerged: List<String>? = null
        _block.update { current ->
            if (current == null) return@update null
            val merged = (current.processNames + moreProcesses).distinct()
            finalMerged = merged
            current.copy(processNames = merged)
        }
        val merged = finalMerged ?: return true
        val persisted = writeSetting(
            PROCESSES_KEY,
            merged.joinToString(","),
            "add apps to standalone block"
        )
        if (isActive) {
            ProcessMonitor.standaloneBlockedProcesses = merged.map { it.lowercase() }.toSet()
        }
        return persisted
    }

    fun stop(): Boolean {
        if (!writeSettings(CLEAR_BLOCK_SETTINGS, "stop standalone block")) return false
        _block.value = null
        watchJob?.cancel()
        watchJob = null
        ProcessMonitor.standaloneBlockedProcesses = emptySet()
        SystemTrayManager.updateTooltip("FocusFlow — Ready")
        ReviewPromptService.triggerCheck()
        return true
    }

    fun loadFromDb() {
        if (loadFromDbOnce()) {
            loadRetryJob?.cancel()
            loadRetryJob = null
        } else {
            logger.log(
                Level.WARNING,
                "Standalone block settings are unreadable; retaining current state and retrying"
            )
            scheduleLoadRetry()
        }
    }

    private fun loadFromDbOnce(): Boolean {
        val processRead = Database.readSetting(PROCESSES_KEY)
        val untilRead = Database.readSetting(UNTIL_KEY)
        val startRead = Database.readSetting(START_KEY)
        val unreadable = listOf(processRead, untilRead, startRead)
            .filterIsInstance<Database.SettingRead.Unreadable>()
            .firstOrNull()
        if (unreadable != null) return false

        val processes = when (processRead) {
            is Database.SettingRead.Present -> processRead.value
            Database.SettingRead.Absent -> ""
            is Database.SettingRead.Unreadable -> return false
        }
        val until = when (untilRead) {
            is Database.SettingRead.Present -> untilRead.value.toLongOrNull() ?: 0L
            Database.SettingRead.Absent -> 0L
            is Database.SettingRead.Unreadable -> return false
        }
        val startEpoch = when (startRead) {
            is Database.SettingRead.Present -> startRead.value.toLongOrNull() ?: 0L
            Database.SettingRead.Absent -> 0L
            is Database.SettingRead.Unreadable -> return false
        }
        val startMs    = if (startEpoch > 0L) startEpoch else null
        val now        = System.currentTimeMillis()
        val pList = processes.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val restored = if (pList.isNotEmpty() && until > now) {
            StandaloneBlock(processNames = pList, untilMs = until, startMs = startMs)
        } else {
            null
        }
        _block.update { restored }
        ProcessMonitor.standaloneBlockedProcesses =
            if (restored != null && (startMs == null || now >= startMs)) {
                pList.map { it.lowercase() }.toSet()
            } else {
                emptySet()
            }
        if (restored != null) startWatcher()
        else {
            watchJob?.cancel()
            watchJob = null
        }
        return true
    }

    private fun scheduleLoadRetry() {
        if (loadRetryJob?.isActive == true) return
        loadRetryJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (loadFromDbOnce()) {
                    loadRetryJob = null
                    return@launch
                }
            }
        }
    }

    private fun startWatcher() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                delay(5_000)
                val b   = _block.value ?: return@launch
                val now = System.currentTimeMillis()

                // Activate enforcement when scheduled start is reached.
                // Compare against the expected set (not just isEmpty) so a new scheduled
                // block activates correctly even if a prior block left the set non-empty.
                if (b.startMs != null && now >= b.startMs) {
                    val targetSet = b.processNames.map { it.lowercase() }.toSet()
                    if (ProcessMonitor.standaloneBlockedProcesses != targetSet) {
                        ProcessMonitor.standaloneBlockedProcesses = targetSet
                        SystemTrayManager.showNotification(
                            "Block Started",
                            "${b.processNames.size} app(s) are now blocked.",
                            TrayIcon.MessageType.WARNING
                        )
                        SystemTrayManager.updateTooltip("FocusFlow — Blocking ${b.processNames.size} apps")
                    }
                }

                // Expire when end time is reached
                if (now >= b.untilMs) {
                    ProcessMonitor.standaloneBlockedProcesses = emptySet()
                    if (!writeSettings(CLEAR_BLOCK_SETTINGS, "expire standalone block")) {
                        continue
                    }
                    _block.update { current -> if (current == b) null else current }
                    SystemTrayManager.showNotification(
                        "Block Ended",
                        "Standalone block expired.",
                        TrayIcon.MessageType.INFO
                    )
                    SystemTrayManager.updateTooltip("FocusFlow — Ready")
                    ReviewPromptService.triggerCheck()
                    return@launch
                }
            }
        }
    }

    fun remainingMs(): Long {
        val b = _block.value ?: return 0L
        return maxOf(0L, b.untilMs - System.currentTimeMillis())
    }

    /** ms until the scheduled block starts, or 0 if already started / no block. */
    fun startsInMs(): Long {
        val b = _block.value ?: return 0L
        val startMs = b.startMs ?: return 0L
        return maxOf(0L, startMs - System.currentTimeMillis())
    }

    private fun writeSetting(key: String, value: String, operation: String): Boolean =
        when (val result = Database.writeSetting(key, value)) {
            Database.SettingWrite.Ok -> true
            is Database.SettingWrite.Failed -> {
                logger.log(Level.WARNING, "Could not persist $operation", result.cause)
                false
            }
        }

    private fun writeSettings(pairs: Map<String, String>, operation: String): Boolean =
        when (val result = Database.writeSettings(pairs)) {
            Database.SettingWrite.Ok -> true
            is Database.SettingWrite.Failed -> {
                logger.log(Level.WARNING, "Could not persist $operation", result.cause)
                false
            }
        }

    internal fun resetForTest() {
        watchJob?.cancel()
        watchJob = null
        loadRetryJob?.cancel()
        loadRetryJob = null
        _block.update { null }
        ProcessMonitor.standaloneBlockedProcesses = emptySet()
    }
}
