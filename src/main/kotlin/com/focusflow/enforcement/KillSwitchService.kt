package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.services.FocusLauncherService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import com.focusflow.data.Database.SettingRead
import com.focusflow.data.Database.SettingWrite

/**
 * KillSwitchService — emergency enforcement pause with a 5-minute daily budget.
 *
 * The user gets exactly 300 seconds of relief per calendar day.
 * Activating the kill switch pauses ALL enforcement layers (ProcessMonitor,
 * NuclearMode) for as long as the budget lasts or until the user manually
 * deactivates it. When the budget runs out, enforcement resumes automatically.
 *
 * Budget resets at midnight (first access after the calendar date changes).
 * Remaining time is persisted to the database so restarts don't refill the
 * budget mid-day.
 */
object KillSwitchService {

    const val DAILY_BUDGET_SECONDS = 300

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var countdownJob: Job? = null
    @Volatile private var settingsRetryJob: Job? = null

    private val _remainingSecondsToday = MutableStateFlow(DAILY_BUDGET_SECONDS)
    val remainingSecondsToday: StateFlow<Int> = _remainingSecondsToday
    private val _settingsAvailable = MutableStateFlow(false)
    val settingsAvailable: StateFlow<Boolean> = _settingsAvailable.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    val isExhausted: Boolean get() = _remainingSecondsToday.value <= 0

    fun loadFromDb(): Boolean {
        if (!loadPersistedSettings()) {
            EnforcementLog.warn(
                "KillSwitchService",
                "Daily emergency-break budget is unreadable; keeping its current value and retrying"
            )
            scheduleSettingsRetry()
            return false
        }
        settingsRetryJob?.cancel()
        settingsRetryJob = null
        return true
    }

    /**
     * Activate the kill switch.
     * Returns true if enforcement was paused, false if the daily budget is already
     * exhausted (the caller should notify the user).
     */
    fun activate(): Boolean {
        if (!_settingsAvailable.value) return false
        if (isExhausted) return false
        // compareAndSet prevents two simultaneous callers (e.g. rapid tray double-click)
        // from both passing the guard and launching two countdownJobs.
        if (!_isActive.compareAndSet(false, true)) return true  // already active

        ProcessMonitor.killSwitchActive = true
        // If the Focus Launcher kiosk is active, temporarily lift OS restrictions
        // so the user can access their desktop during the emergency break window.
        FocusLauncherService.onKillSwitchActivated()

        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (_isActive.value && _remainingSecondsToday.value > 0) {
                delay(1_000L)
                val newVal = (_remainingSecondsToday.value - 1).coerceAtLeast(0)
                _remainingSecondsToday.value = newVal
                if (newVal % 30 == 0 || newVal <= 10) saveToDb()
                if (newVal == 0) deactivate()
            }
        }
        return true
    }

    fun deactivate() {
        // compareAndSet prevents the countdown auto-fire (line 72) and a simultaneous
        // user click from both passing the guard and double-invoking onKillSwitchDeactivated.
        if (!_isActive.compareAndSet(true, false)) return

        countdownJob?.cancel()
        ProcessMonitor.killSwitchActive = false
        // Re-engage kiosk enforcement if a Focus Launcher session is still running
        FocusLauncherService.onKillSwitchDeactivated()
        saveToDb()
    }

    /**
     * Toggle: deactivate if active, activate otherwise.
     * Returns false only when budget is exhausted and activation is impossible.
     */
    fun toggle(): Boolean =
        if (_isActive.value) { deactivate(); true } else activate()

    private fun saveToDb() {
        when (val result = Database.writeSettings(
            linkedMapOf(
                "killswitch_remaining_today" to _remainingSecondsToday.value.toString(),
                "killswitch_reset_date" to LocalDate.now().toString()
            )
        )) {
            SettingWrite.Ok -> Unit
            is SettingWrite.Failed -> {
                _settingsAvailable.value = false
                EnforcementLog.warn(
                    "KillSwitchService",
                    "Could not persist daily emergency-break budget",
                    result.cause
                )
                scheduleSettingsRetry()
            }
        }
    }

    private fun loadPersistedSettings(): Boolean {
        // Until this complete read succeeds, activation must remain unavailable.
        _settingsAvailable.value = false
        val today = LocalDate.now()
        val date = when (val read = Database.readSetting("killswitch_reset_date")) {
            is SettingRead.Present -> runCatching { LocalDate.parse(read.value) }.getOrNull()
                ?: return false
            SettingRead.Absent -> null
            is SettingRead.Unreadable -> return false
        }

        if (date == today) {
            val remaining = when (val read = Database.readSetting("killswitch_remaining_today")) {
                is SettingRead.Present -> read.value.toIntOrNull()?.takeIf {
                    it in 0..DAILY_BUDGET_SECONDS
                } ?: return false
                SettingRead.Absent, is SettingRead.Unreadable -> return false
            }
            _remainingSecondsToday.value = remaining
            _settingsAvailable.value = true
            return true
        }

        when (val write = Database.writeSettings(
            linkedMapOf(
                "killswitch_remaining_today" to DAILY_BUDGET_SECONDS.toString(),
                "killswitch_reset_date" to today.toString()
            )
        )) {
            SettingWrite.Ok -> {
                _remainingSecondsToday.value = DAILY_BUDGET_SECONDS
                _settingsAvailable.value = true
                return true
            }
            is SettingWrite.Failed -> {
                _settingsAvailable.value = false
                EnforcementLog.warn(
                    "KillSwitchService",
                    "Could not initialize the daily emergency-break budget",
                    write.cause
                )
                return false
            }
        }
    }

    private fun scheduleSettingsRetry() {
        if (settingsRetryJob?.isActive == true) return
        settingsRetryJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                if (loadPersistedSettings()) {
                    settingsRetryJob = null
                    return@launch
                }
            }
        }
    }

    internal fun resetForTest() {
        settingsRetryJob?.cancel()
        settingsRetryJob = null
        countdownJob?.cancel()
        countdownJob = null
        _remainingSecondsToday.value = DAILY_BUDGET_SECONDS
        _settingsAvailable.value = false
        _isActive.value = false
        ProcessMonitor.killSwitchActive = false
    }
}
