package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.FocusSession
import com.focusflow.data.models.SessionState
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.ProcessMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.util.UUID

/** Summary data emitted once at the end of every focus session. */
data class SessionSummary(
    val taskName:        String,
    val actualMinutes:   Int,
    val blockedAttempts: Int,
    val completed:       Boolean
)

object FocusSessionService {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // @Volatile: timerJob is written in startTimer() (called from @Synchronized start()
    // and resume()) and read in end() (which can be called from the "FocusFlow-Shutdown"
    // daemon thread via doShutdown). Without @Volatile the shutdown thread may read a
    // stale reference and fail to cancel the in-flight timer coroutine.
    @Volatile private var timerJob: Job? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    private val _lastSummary = MutableStateFlow<SessionSummary?>(null)
    val lastSummary: StateFlow<SessionSummary?> = _lastSummary

    private var sessionStartTime: LocalDateTime? = null
    private var sessionId: String? = null
    private var taskId: String? = null
    private var taskName: String = ""
    private var plannedMinutes: Int = 0
    /** Milestone percentages (25 / 50 / 75) already fired this session. */
    private val firedMilestones = mutableSetOf<Int>()

    var pomodoroMode: Boolean = false

    /** Optional callback — invoked on the coroutine's thread when a session ends. */
    var onSessionEnded: ((SessionSummary) -> Unit)? = null

    /** Notes entered by the user during the active session; flushed to DB on end(). */
    @Volatile private var currentNotes: String = ""
    fun setNotes(notes: String) { currentNotes = notes }

    /**
     * Start a new focus session.
     *
     * @Synchronized prevents a race between rapid UI taps where two start() calls
     * could both pass the isActive guard before either sets state. Also prevents
     * a concurrent end() from seeing partially-initialised session fields.
     */
    @Synchronized
    fun start(
        name: String,
        minutes: Int,
        tid: String? = null,
        blockedProcesses: List<String> = emptyList()
    ) {
        if (_state.value.isActive) return

        // Capture as locals immediately — these are read by DB insert on IO thread
        val newSessionId    = UUID.randomUUID().toString()
        val newStartTime    = LocalDateTime.now()

        sessionId        = newSessionId
        taskName         = name
        plannedMinutes   = minutes
        taskId           = tid
        sessionStartTime = newStartTime

        _state.value = SessionState(
            isActive         = true,
            isPaused         = false,
            taskName         = name,
            totalSeconds     = minutes * 60,
            elapsedSeconds   = 0,
            blockedProcesses = blockedProcesses
        )

        firedMilestones.clear()
        ProcessMonitor.sessionActive = true
        ProcessMonitor.sessionExtraBlockedProcesses =
            blockedProcesses.map { it.lowercase().let { n -> if (!n.endsWith(".exe")) "$n.exe" else n } }.toSet()
        ProcessMonitor.start()
        NotificationService.sessionStarted(name, minutes)

        startTimer()

        // DB write on IO — never block the UI or enforcement thread
        val capturedTid = tid
        scope.launch(Dispatchers.IO) {
            try {
                Database.insertSession(
                    FocusSession(
                        id             = newSessionId,
                        taskId         = capturedTid,
                        taskName       = name,
                        startTime      = newStartTime,
                        endTime        = null,
                        plannedMinutes = minutes,
                        actualMinutes  = 0,
                        completed      = false,
                        interrupted    = false
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun pause() {
        if (!_state.value.isActive || _state.value.isPaused) return
        timerJob?.cancel()
        _state.value = _state.value.copy(isPaused = true)
        SystemTrayManager.updateTooltip("FocusFlow — $taskName (paused)")
    }

    fun resume() {
        if (!_state.value.isActive || !_state.value.isPaused) return
        _state.value = _state.value.copy(isPaused = false)
        SystemTrayManager.updateTooltip("FocusFlow — $taskName (resumed)")
        startTimer()
    }

    /**
     * End the active session.
     *
     * @Synchronized prevents double-calls (timer auto-fire racing with user clicking
     * Stop, or shutdown racing with timer completion). All mutable session fields are
     * captured as locals before the state is cleared so the async DB write is safe.
     */
    @Synchronized
    fun end(completed: Boolean = false) {
        if (!_state.value.isActive) return

        val notesToSave  = currentNotes.also { currentNotes = "" }
        timerJob?.cancel()
        ProcessMonitor.sessionActive = false
        ProcessMonitor.sessionExtraBlockedProcesses = emptySet()

        // Capture everything needed for DB + callbacks before clearing state
        val elapsed      = _state.value.elapsedSeconds
        val endTime      = LocalDateTime.now()
        val name         = taskName
        val attempts     = TemptationLogger.getSessionAttempts()
        val sid          = sessionId
        val tid          = taskId
        val planned      = plannedMinutes
        val startT       = sessionStartTime ?: endTime

        // Clear state immediately — UI reflects session end without waiting for DB
        _state.value = SessionState()
        sessionId    = null

        // Async firewall cleanup — state is already cleared so enforcement is logically
        // ended; the slow PowerShell process runs in the background without holding any
        // lock or blocking the EDT.
        scope.launch(Dispatchers.IO) { try { NetworkBlocker.removeAllRules() } catch (_: Exception) {} }

        // DB write on IO — never block UI or enforcement thread
        scope.launch(Dispatchers.IO) {
            try {
                sid?.let { id ->
                    Database.insertSession(
                        FocusSession(
                            id             = id,
                            taskId         = tid,
                            taskName       = name,
                            startTime      = startT,
                            endTime        = endTime,
                            plannedMinutes = planned,
                            actualMinutes  = elapsed / 60,
                            completed      = completed,
                            interrupted    = !completed,
                            notes          = notesToSave
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (name.isNotBlank()) NotificationService.sessionEnded(name, completed)
        if (completed && pomodoroMode) BreakEnforcer.onSessionCompleted()
        if (completed) ReviewPromptService.triggerCheck()

        // Emit summary before clearing so listeners see it
        if (name.isNotBlank() && elapsed >= 30) {
            val summary = SessionSummary(
                taskName        = name,
                actualMinutes   = elapsed / 60,
                blockedAttempts = attempts,
                completed       = completed
            )
            _lastSummary.value = summary
            scope.launch(Dispatchers.Default) {
                try { onSessionEnded?.invoke(summary) } catch (_: Exception) {}
            }
        }

        // Clear session log AFTER capturing attempts — keeps next session's count clean
        TemptationLogger.clearSession()
    }

    /** Call from UI after showing the summary dialog. */
    fun clearSummary() { _lastSummary.value = null }

    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive && _state.value.isActive && !_state.value.isPaused) {
                delay(1000)
                val current    = _state.value
                val newElapsed = current.elapsedSeconds + 1

                val remaining = current.totalSeconds - newElapsed
                if (remaining in 1..299 && remaining % 60 == 0) {
                    val mins = remaining / 60
                    SystemTrayManager.updateTooltip("FocusFlow — $taskName (${mins}m left)")
                }

                if (newElapsed >= current.totalSeconds) {
                    withContext(Dispatchers.Main) { end(completed = true) }
                    return@launch
                }

                // Fire milestone sounds at 25 / 50 / 75% elapsed
                if (current.totalSeconds > 0) {
                    val pct = (newElapsed * 100) / current.totalSeconds
                    for (mark in listOf(25, 50, 75)) {
                        if (pct >= mark && firedMilestones.add(mark)) {
                            SoundAversion.playMilestone()
                        }
                    }
                }

                // Re-read current state after the delay — pause() or end() may have fired
                // while the coroutine was suspended. If the session is no longer active or
                // is now paused, abort the write-back so we never overwrite isPaused = true.
                val latest = _state.value
                if (!latest.isActive || latest.isPaused) return@launch
                _state.value = latest.copy(elapsedSeconds = newElapsed)
            }
        }
    }

    fun dispose() {
        // End any active session before tearing down the scope so that
        // ProcessMonitor.sessionActive is cleared and NetworkBlocker rules are removed.
        // Without this, a hard app close (e.g. window X button) leaves enforcement
        // state permanently dirty — ProcessMonitor keeps killing processes on the next launch.
        if (_state.value.isActive) end(completed = false)
        scope.cancel()
    }
}
