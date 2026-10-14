package com.focusflow.services

import com.focusflow.enforcement.ElevationManager
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.RelaunchResult
import com.focusflow.enforcement.isWindows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ElevationRelaunchStatus {
    data object Idle : ElevationRelaunchStatus
    data object Pending : ElevationRelaunchStatus
    data object HandedOff : ElevationRelaunchStatus
    data object UserDeclined : ElevationRelaunchStatus
    data class Blocked(val reason: String) : ElevationRelaunchStatus
    data class Failed(val reason: String) : ElevationRelaunchStatus
    data class NotSupported(val reason: String) : ElevationRelaunchStatus
}

object AppLifecycle {
    @Volatile
    var shutdown: ((skipQuitAuthorization: Boolean) -> Unit)? = null
}

object ElevationRelaunchService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestInProgress = AtomicBoolean(false)
    private val _status = MutableStateFlow<ElevationRelaunchStatus>(ElevationRelaunchStatus.Idle)
    val status: StateFlow<ElevationRelaunchStatus> = _status.asStateFlow()

    fun canRelaunch(): Boolean =
        isWindows &&
            !ElevationManager.isElevated &&
            !isSessionActive()

    fun isSessionActive(): Boolean =
        FocusSessionService.state.value.isActive ||
            FocusLauncherService.isActive.value ||
            NuclearMode.isActive

    fun request(): Boolean {
        if (!isWindows) {
            _status.value = ElevationRelaunchStatus.NotSupported(
                "Administrator relaunch is available only on Windows."
            )
            return false
        }
        if (ElevationManager.isElevated) {
            _status.value = ElevationRelaunchStatus.Failed(
                "FocusFlow is already running as Administrator."
            )
            return false
        }
        if (isSessionActive()) {
            _status.value = ElevationRelaunchStatus.Blocked(
                "End your current focus, launcher, or Nuclear Mode session first."
            )
            return false
        }
        if (!requestInProgress.compareAndSet(false, true)) return false

        _status.value = ElevationRelaunchStatus.Pending
        scope.launch {
            try {
                when (val result = ElevationManager.relaunchElevated()) {
                    RelaunchResult.HandedOff -> {
                        val shutdown = AppLifecycle.shutdown
                        if (shutdown == null) {
                            ElevationManager.cancelPendingHandoff()
                            _status.value = ElevationRelaunchStatus.Failed(
                                "The elevated instance started, but FocusFlow could not safely hand off shutdown."
                            )
                        } else {
                            _status.value = ElevationRelaunchStatus.HandedOff
                            shutdown(true)
                        }
                    }
                    RelaunchResult.UserDeclined -> _status.value = ElevationRelaunchStatus.UserDeclined
                    is RelaunchResult.Failed ->
                        _status.value = ElevationRelaunchStatus.Failed(result.reason)
                    is RelaunchResult.NotSupported ->
                        _status.value = ElevationRelaunchStatus.NotSupported(result.reason)
                }
            } catch (failure: Exception) {
                ElevationManager.cancelPendingHandoff()
                _status.value = ElevationRelaunchStatus.Failed(
                    failure.message?.takeIf(String::isNotBlank)
                        ?: "Windows could not restart FocusFlow as Administrator."
                )
            } finally {
                requestInProgress.set(false)
            }
        }
        return true
    }

    fun dismissStatus() {
        if (_status.value != ElevationRelaunchStatus.Pending) {
            _status.value = ElevationRelaunchStatus.Idle
        }
    }
}
