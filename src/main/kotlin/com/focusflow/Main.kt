package com.focusflow

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.DbStartupState
import com.focusflow.enforcement.AppBlocker
import com.focusflow.enforcement.KillSwitchService
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.RegistryLockdown
import com.focusflow.services.CrashReporter
import com.focusflow.services.FocusSessionService
import com.focusflow.services.InstanceAcquireResult
import com.focusflow.services.SingleInstanceGuard
import com.focusflow.services.StartOnceResult
import com.focusflow.services.StartupBootstrap
import com.focusflow.services.SystemTrayManager
import com.focusflow.services.UninstallProtectionService
import com.focusflow.services.UninstallWizard
import com.focusflow.services.UninstallWizardFlag
import com.focusflow.services.WindowsUninstallRegistration
import com.focusflow.ui.launcher.LauncherWindowHost
import com.focusflow.ui.startup.StartupGateWindow
import java.awt.Desktop
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

private sealed interface StartupView {
    data object WaitingForInstance : StartupView
    data object Starting : StartupView
    data class DatabaseUnavailable(val result: DbInitResult) : StartupView
    data class ServiceFailure(val cause: Throwable) : StartupView
    data object Ready : StartupView
}

private class DesktopLaunch {
    private val handle = AtomicReference<SingleInstanceGuard.Handle?>(null)
    private val preDatabaseStarted = AtomicBoolean(false)
    val showPending = AtomicBoolean(false)
    val showRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val hasInstance: Boolean get() = handle.get() != null

    fun acquire(): InstanceAcquireResult {
        handle.get()?.let { return InstanceAcquireResult.Acquired(it) }

        val result = SingleInstanceGuard.acquire {
            showPending.set(true)
            showRequests.tryEmit(Unit)
        }
        if (result !is InstanceAcquireResult.Acquired) return result

        if (handle.compareAndSet(null, result.handle)) {
            runPreDatabaseStartup()
        } else {
            result.handle.close()
        }

        return handle.get()?.let(InstanceAcquireResult::Acquired)
            ?: InstanceAcquireResult.HolderUnresponsive
    }

    private fun runPreDatabaseStartup() {
        if (!preDatabaseStarted.compareAndSet(false, true)) return

        // These process-level actions run only after this process owns the
        // single-instance lock and never from a composable body.
        CrashReporter.install()
        runCatching { WindowsUninstallRegistration.ensureRegistered() }
            .onFailure { CrashReporter.report(Thread.currentThread(), it, source = "Windows uninstall registration") }
        runCatching { RegistryLockdown.disable() }
            .onFailure { CrashReporter.report(Thread.currentThread(), it, source = "RegistryLockdown.disable()") }
    }

    fun release() {
        handle.getAndSet(null)?.close()
    }
}

fun main(args: Array<String>) {
    // The standalone uninstaller is intentionally outside the normal
    // single-instance/database bootstrap path and is never allowed recovery.
    if (UninstallWizardFlag.isRequested(args)) {
        UninstallWizard.run()
        return
    }

    val launch = DesktopLaunch()
    if (launch.acquire() is InstanceAcquireResult.AlreadyRunning) return

    application {
        FocusFlowDesktop(launch) { exitApplication() }
    }
}

@Composable
private fun FocusFlowDesktop(launch: DesktopLaunch, exitApplication: () -> Unit) {
    var startup by remember {
        mutableStateOf(if (launch.hasInstance) StartupView.Starting else StartupView.WaitingForInstance)
    }
    var retryToken by remember { mutableIntStateOf(0) }
    var windowVisible by remember { mutableStateOf(true) }

    val dbStartupState by Database.startupState.collectAsState()
    val launcherActive by com.focusflow.services.FocusLauncherService.isActive.collectAsState()
    val sessionState by FocusSessionService.state.collectAsState()
    val killSwitchRemaining by KillSwitchService.remainingSecondsToday.collectAsState()
    val killSwitchActive by KillSwitchService.isActive.collectAsState()
    val ready = startup is StartupView.Ready

    val databaseFile = remember {
        File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db").absoluteFile
    }
    val databaseLog = remember { File(databaseFile.parentFile, "crash.log").absolutePath }

    val windowState = rememberWindowState(
        width = 1100.dp,
        height = 720.dp,
        placement = WindowPlacement.Floating
    )

    val quitGate: () -> Unit = {
        Thread({
            Database.close()
            launch.release()
            exitApplication()
        }, "FocusFlow-Gate-Quit").also { it.isDaemon = true }.start()
    }

    // Shared shutdown action keeps service teardown off the AWT event thread.
    val doShutdown: () -> Unit = {
        Thread({
            if (!UninstallProtectionService.authorizeQuit()) return@Thread
            com.focusflow.services.FocusLauncherService.exit()
            KillSwitchService.deactivate()
            FocusSessionService.end(completed = false)
            FocusSessionService.dispose()
            com.focusflow.services.WeeklyReportService.stopScheduler()
            com.focusflow.services.TaskAlarmService.stop()
            com.focusflow.services.RecurringTaskService.stop()
            com.focusflow.services.BlockScheduleService.stop()
            com.focusflow.services.DailyAllowanceTracker.stop()
            com.focusflow.services.AutoBackupService.stop()
            com.focusflow.enforcement.NuclearMode.disable()
            com.focusflow.enforcement.NuclearMode.awaitCleanup()
            ProcessMonitor.dispose()
            AppBlocker.dispose()
            SystemTrayManager.remove()
            Database.close()
            launch.release()
            exitApplication()
        }, "FocusFlow-Shutdown").also { it.isDaemon = true }.start()
    }

    LaunchedEffect(Unit) {
        if (launch.showPending.getAndSet(false)) windowVisible = true
        launch.showRequests.collect { windowVisible = true }
    }

    LaunchedEffect(retryToken) {
        while (true) {
            when (withContext(Dispatchers.IO) { launch.acquire() }) {
                is InstanceAcquireResult.Acquired -> Unit
                InstanceAcquireResult.AlreadyRunning -> {
                    launch.release()
                    exitApplication()
                    return@LaunchedEffect
                }
                InstanceAcquireResult.HolderUnresponsive -> {
                    startup = StartupView.WaitingForInstance
                    delay(10_000L)
                    continue
                }
            }

            startup = StartupView.Starting
            val result = withContext(Dispatchers.IO) { Database.init() }
            when (result) {
                DbInitResult.Ready -> {
                    when (val bootstrap = withContext(Dispatchers.IO) {
                        StartupBootstrap.startServices(
                            onRestore = { windowVisible = true },
                            onQuit = doShutdown
                        )
                    }) {
                        StartOnceResult.Started, StartOnceResult.AlreadyStarted ->
                            startup = StartupView.Ready
                        is StartOnceResult.Failed ->
                            startup = StartupView.ServiceFailure(bootstrap.cause)
                    }
                    return@LaunchedEffect
                }
                is DbInitResult.Busy -> {
                    startup = StartupView.DatabaseUnavailable(result)
                    delay(10_000L)
                }
                is DbInitResult.Failed -> {
                    startup = StartupView.DatabaseUnavailable(result)
                    return@LaunchedEffect
                }
            }
        }
    }

    LaunchedEffect(launcherActive, ready) {
        windowVisible = if (ready) !launcherActive else true
    }

    LaunchedEffect(killSwitchRemaining, killSwitchActive, ready) {
        if (!ready) return@LaunchedEffect
        val minutes = killSwitchRemaining / 60
        val seconds = (killSwitchRemaining % 60).toString().padStart(2, '0')
        val label = when {
            killSwitchRemaining <= 0 -> "Emergency Break (exhausted for today)"
            killSwitchActive -> "Stop Break — ${minutes}m ${seconds}s remaining today"
            else -> "Emergency Break (${minutes}m ${seconds}s left today)"
        }
        SystemTrayManager.updateKillSwitchItem(label)
    }

    val windowTitle = when {
        sessionState.isActive && sessionState.isPaused ->
            "FocusFlow — ${sessionState.taskName} (paused)"
        sessionState.isActive -> {
            val remainingSeconds =
                (sessionState.totalSeconds - sessionState.elapsedSeconds).coerceAtLeast(0)
            val remainingMinutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            "FocusFlow — ${sessionState.taskName} (${remainingMinutes}m ${seconds.toString().padStart(2, '0')}s left)"
        }
        else -> "FocusFlow"
    }

    val iconAvailable = remember {
        Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("focusflow_256.png")
            ?.also { it.close() } != null
    }
    val appIcon = if (iconAvailable) painterResource("focusflow_256.png") else null

    if (ready) LauncherWindowHost()

    if (windowVisible) {
        Window(
            onCloseRequest = {
                if (!ready) {
                    quitGate()
                } else if (SystemTrayManager.isSupported) {
                    windowVisible = false
                    SystemTrayManager.showNotification(
                        "FocusFlow is still running",
                        "Blocking stays active. Right-click the tray icon to quit."
                    )
                } else {
                    doShutdown()
                }
            },
            state = windowState,
            title = if (ready) windowTitle else "FocusFlow — Startup",
            icon = appIcon,
            alwaysOnTop = false
        ) {
            if (ready) {
                App()
            } else {
                val busy = (startup as? StartupView.DatabaseUnavailable)?.result as? DbInitResult.Busy
                val failed = (startup as? StartupView.DatabaseUnavailable)?.result as? DbInitResult.Failed
                val isStarting = startup is StartupView.Starting
                val title = when {
                    startup is StartupView.WaitingForInstance -> "Waiting for another instance"
                    busy != null -> "Waiting for the database"
                    failed != null -> "Database unavailable"
                    startup is StartupView.ServiceFailure -> "Background services could not start"
                    else -> "Starting safely"
                }
                val message = when {
                    startup is StartupView.WaitingForInstance ->
                        "Another FocusFlow process holds the startup lock but did not respond to the local show request. This window will retry automatically."
                    busy != null ->
                        "The database is locked. No recovery was attempted and FocusFlow has not started its background services. It will retry automatically."
                    failed != null ->
                        "FocusFlow could not safely open the database. It did not automatically reset or replace the database file. Fix the reported issue and retry."
                    startup is StartupView.ServiceFailure ->
                        "The database is ready, but startup stopped while a background service was being initialized. No automatic service restart will be attempted."
                    else -> when (val state = dbStartupState) {
                        is DbStartupState.Opening ->
                            "Opening the database (attempt ${state.attempt}, ${state.elapsedMs / 1_000}s elapsed). Background services are waiting."
                        else -> "FocusFlow is checking the database before starting background protection."
                    }
                }
                val details = when {
                    startup is StartupView.WaitingForInstance ->
                        "The instance lock is held, but the current holder did not acknowledge SHOW. Close the other FocusFlow process or retry after it exits."
                    busy != null ->
                        "SQLite busy after ${busy.attempts} attempt(s), ${busy.waitedMs}ms elapsed. ${busy.cause.message.orEmpty()}"
                    failed != null ->
                        "${failed.cause::class.java.name}: ${failed.cause.message.orEmpty()}\nFile retained at: ${failed.untouchedPath.absolutePath}"
                    startup is StartupView.ServiceFailure -> {
                        val cause = (startup as StartupView.ServiceFailure).cause
                        "${cause::class.java.name}: ${cause.message.orEmpty()}\n${cause.stackTraceToString()}"
                    }
                    else -> dbStartupState.toString()
                }

                com.focusflow.ui.theme.FocusFlowTheme {
                    StartupGateWindow(
                        title = title,
                        message = message,
                        details = details,
                        databasePath = databaseFile.absolutePath,
                        logPath = databaseLog,
                        isStarting = isStarting,
                        isBusy = busy != null,
                        retryEnabled = startup !is StartupView.ServiceFailure,
                        onRetry = { retryToken++ },
                        onOpenFolder = {
                            runCatching {
                                if (Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().open(databaseFile.parentFile)
                                }
                            }
                        },
                        onQuit = quitGate
                    )
                }
            }
        }
    }
}
