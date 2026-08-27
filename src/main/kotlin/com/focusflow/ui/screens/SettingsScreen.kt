package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.ShortcutTooltip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.BlockRule
import com.focusflow.data.models.BlockSchedule
import com.focusflow.data.models.DailyAllowance
import com.focusflow.data.models.hasValidTimeRange
import com.focusflow.enforcement.*
import com.focusflow.i18n.AppLanguage
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.BlockScheduleService
import com.focusflow.services.BreakEnforcer
import com.focusflow.services.ChimeStyle
import com.focusflow.services.DailyAllowanceTracker
import com.focusflow.services.GlobalPin
import com.focusflow.services.NuclearPin
import com.focusflow.services.PinPolicy
import com.focusflow.services.SessionPin
import com.focusflow.ui.components.NuclearPinGateDialog
import com.focusflow.ui.components.NuclearPinSetupDialog
import com.focusflow.ui.components.PinGateDialog
import com.focusflow.services.SoundAversion
import com.focusflow.services.TaskAlarmService
import com.focusflow.ui.theme.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val strings = LocalizationManager.strings
    val scope = rememberCoroutineScope()

    var alwaysOn         by remember { mutableStateOf(false) }
    var startWithWin     by remember { mutableStateOf(false) }
    var soundEnabled       by remember { mutableStateOf(true) }
    var soundVolume        by remember { mutableStateOf(1.0f) }
    var aversionSound      by remember { mutableStateOf(ChimeStyle.DEFAULT) }
    var overlayMessage     by remember { mutableStateOf("Stay focused. You've got this.") }
    var overlayDismissSecs by remember { mutableStateOf(4) }
    var overlayEnabled     by remember { mutableStateOf(true) }
    var pinSet               by remember { mutableStateOf(false) }
    var showPinDialog        by remember { mutableStateOf(false) }
    var hookActive           by remember { mutableStateOf(false) }
    var nuclearActive        by remember { mutableStateOf(false) }
    var focusLockUntilTimer   by remember { mutableStateOf(false) }
    var showAlwaysOnPinDialog by remember { mutableStateOf(false) }
    var pendingAlwaysOnValue  by remember { mutableStateOf(false) }
    var crashReportsEnabled   by remember { mutableStateOf(true) }
    // Nuclear Mode PIN state
    var nuclearPinSet         by remember { mutableStateOf(false) }
    var showNuclearPinGate    by remember { mutableStateOf(false) }
    var showNuclearPinSetup   by remember { mutableStateOf(false) }
    // Global PIN state
    var globalPinSet          by remember { mutableStateOf(false) }
    var showGlobalPinDialog   by remember { mutableStateOf(false) }
    var globalPinRemovalRequested by remember { mutableStateOf(false) }
    var showOverlayPinGate    by remember { mutableStateOf(false) }
    // Session PIN change dialog
    var showChangePinDialog   by remember { mutableStateOf(false) }

    // Pomodoro
    var pomodoroWork   by remember { mutableStateOf("25") }
    var pomodoroShort  by remember { mutableStateOf("5") }
    var pomodoroLong   by remember { mutableStateOf("15") }
    var pomodoroCycles by remember { mutableStateOf("4") }
    var pomodoroSaved  by remember { mutableStateOf(false) }
    var workChime  by remember { mutableStateOf(ChimeStyle.DEFAULT) }
    var breakChime by remember { mutableStateOf(ChimeStyle.DEFAULT) }

    fun reload() {
        scope.launch {
            val ao         = withContext(Dispatchers.IO) { Database.getSetting("always_on_enforcement") == "true" }
            val sound      = withContext(Dispatchers.IO) { Database.getSetting("sound_aversion") != "false" }
            val overlay    = withContext(Dispatchers.IO) { Database.getSetting("overlay_message") ?: "Stay focused. You've got this." }
            val pinIsSet   = withContext(Dispatchers.IO) { SessionPin.isSet() }
            val sww        = withContext(Dispatchers.IO) { WindowsStartupManager.isEnabled() }
            val pw         = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_work")   ?: "25" }
            val ps         = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_short")  ?: "5" }
            val pl         = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_long")   ?: "15" }
            val pc         = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_cycles") ?: "4" }
            val wc         = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_work_chime")  ?: ChimeStyle.DEFAULT.name }
            val bc         = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_break_chime") ?: ChimeStyle.DEFAULT.name }
            val ac         = withContext(Dispatchers.IO) { Database.getSetting("aversion_sound_style") ?: ChimeStyle.DEFAULT.name }
            val lockUntil   = withContext(Dispatchers.IO) { Database.getSetting("focus_lock_until_timer") == "true" }
            val crashRep    = withContext(Dispatchers.IO) { Database.getSetting("crash_reports_enabled") != "false" }
            val vol         = withContext(Dispatchers.IO) { Database.getSetting("sound_volume")?.toFloatOrNull() ?: 1.0f }
            val ods         = withContext(Dispatchers.IO) { Database.getSetting("overlay_dismiss_seconds")?.toIntOrNull() ?: 4 }
            val showOverlay = withContext(Dispatchers.IO) { Database.getSetting("block_overlay_enabled") != "false" }
            val nPinSet     = withContext(Dispatchers.IO) { NuclearPin.isSet() }
            val gPinSet     = withContext(Dispatchers.IO) { GlobalPin.isSet() }
            alwaysOn        = ao
            startWithWin    = sww
            soundEnabled    = sound
            soundVolume     = vol.coerceIn(0f, 1f)
            overlayMessage  = overlay
            overlayDismissSecs = ods.coerceIn(2, 15)
            overlayEnabled  = showOverlay
            pinSet          = pinIsSet
            SoundAversion.volumeMultiplier      = soundVolume
            FloatingBlockOverlay.dismissSeconds = overlayDismissSecs
            FloatingBlockOverlay.overlayMessage  = overlayMessage
            AppBlocker.overlayEnabled           = overlayEnabled
            hookActive      = WinEventHook.isActive
            nuclearActive   = NuclearMode.isActive
            nuclearPinSet   = nPinSet
            globalPinSet    = gPinSet
            pomodoroWork    = pw
            pomodoroShort   = ps
            pomodoroLong    = pl
            pomodoroCycles  = pc
            workChime  = runCatching { ChimeStyle.valueOf(wc) }.getOrDefault(ChimeStyle.DEFAULT)
            breakChime = runCatching { ChimeStyle.valueOf(bc) }.getOrDefault(ChimeStyle.DEFAULT)
            aversionSound = runCatching { ChimeStyle.valueOf(ac) }.getOrDefault(ChimeStyle.DEFAULT)
            SoundAversion.workChimeStyle  = workChime
            SoundAversion.breakChimeStyle = breakChime
            SoundAversion.blockAlertStyle = aversionSound
            focusLockUntilTimer = lockUntil
            crashReportsEnabled = crashRep
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        AppBlocker.overlayEnabled = enabled
        if (!enabled) AppBlocker.hideOverlay()
        scope.launch {
            withContext(Dispatchers.IO) {
                Database.setSetting("block_overlay_enabled", enabled.toString())
            }
        }
    }

    val settingsListState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = settingsListState,
        modifier = Modifier.fillMaxSize().background(Surface).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(strings.settingsTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
        }

        // ── Language ──────────────────────────────────────────────────────────
        item {
            LanguageSettingsSection()
        }

        // ── PIN Management ───────────────────────────────────────────────────
        item {
            SectionCard(title = "PIN Management") {
                Text(
                    "Manage the PINs that protect FocusFlow. Custom PINs are ${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} characters; generated PINs are random and 20–${PinPolicy.MAX_LENGTH} characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
                Spacer(Modifier.height(12.dp))

                PinManagementRow(
                    name = "Session PIN",
                    description = if (pinSet) "Required to end an active focus session"
                                  else "No PIN — anyone can end a session",
                    active = pinSet,
                    onSet = { showPinDialog = true },
                    onChange = { showChangePinDialog = true },
                    onRemove = { showPinDialog = true }
                )
                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 8.dp))
                PinManagementRow(
                    name = "Global PIN",
                    description = if (globalPinSet) "Required to remove blocks, schedules, and settings"
                                  else "Not set — removal is unrestricted",
                    active = globalPinSet,
                    onSet = {
                        globalPinRemovalRequested = false
                        showGlobalPinDialog = true
                    },
                    onChange = {
                        globalPinRemovalRequested = false
                        showGlobalPinDialog = true
                    },
                    onRemove = {
                        globalPinRemovalRequested = true
                        showGlobalPinDialog = true
                    }
                )
                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 8.dp))
                PinManagementRow(
                    name = "Nuclear Mode PIN",
                    description = if (nuclearPinSet) "Required to turn Nuclear Mode off"
                                  else "Optional: require a PIN to turn Nuclear Mode off",
                    active = nuclearPinSet,
                    onSet = { showNuclearPinSetup = true },
                    onChange = { showNuclearPinSetup = true },
                    onRemove = { showNuclearPinSetup = true }
                )
            }
        }

        // ── Enforcement ───────────────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsEnforcement) {
                SettingRow(
                    label = strings.settingsProcessMonitor,
                    subtitle = if (isWindows) "Active — 500ms polling + instant WinEventHook"
                               else "Inactive — only enforced on Windows",
                    trailing = {
                        Icon(
                            if (isWindows) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            tint = if (isWindows) Success else Warning
                        )
                    }
                )

                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 8.dp))

                SettingRow(
                    label    = strings.settingsInstantDetection,
                    subtitle = if (hookActive)
                                   "WinEventHook active — zero-delay foreground detection"
                               else "WinEventHook inactive — polling fallback only",
                    trailing = {
                        Icon(
                            if (hookActive) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            null,
                            tint = if (hookActive) Success else OnSurface2
                        )
                    }
                )

                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 8.dp))

                SettingRow(
                    label    = strings.settingsAlwaysOn,
                    subtitle = strings.settingsAlwaysOnSub,
                    trailing = {
                        Switch(
                            checked = alwaysOn,
                            onCheckedChange = { enabled ->
                                if (!enabled && globalPinSet) {
                                    pendingAlwaysOnValue = false
                                    showAlwaysOnPinDialog = true
                                } else {
                                    alwaysOn = enabled
                                    ProcessMonitor.alwaysOnEnabled = enabled
                                    if (enabled) ProcessMonitor.start()
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Database.setSetting("always_on_enforcement", enabled.toString())
                                        }
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }

        // ── Pomodoro Settings ─────────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsPomodoroTimer) {
                Text(
                    "Configure session and break durations for Pomodoro mode in the Focus screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PomodoroField(
                        label = strings.settingsWorkMin,
                        value = pomodoroWork,
                        onValueChange = { pomodoroWork = it; pomodoroSaved = false },
                        modifier = Modifier.weight(1f)
                    )
                    PomodoroField(
                        label = strings.settingsShortBreakLabel,
                        value = pomodoroShort,
                        onValueChange = { pomodoroShort = it; pomodoroSaved = false },
                        modifier = Modifier.weight(1f)
                    )
                    PomodoroField(
                        label = strings.settingsLongBreakLabel,
                        value = pomodoroLong,
                        onValueChange = { pomodoroLong = it; pomodoroSaved = false },
                        modifier = Modifier.weight(1f)
                    )
                    PomodoroField(
                        label = strings.settingsBeforeLong,
                        value = pomodoroCycles,
                        onValueChange = { pomodoroCycles = it; pomodoroSaved = false },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val w = pomodoroWork.toIntOrNull()?.coerceIn(1, 120)   ?: 25
                            val s = pomodoroShort.toIntOrNull()?.coerceIn(1, 60)   ?: 5
                            val l = pomodoroLong.toIntOrNull()?.coerceIn(1, 60)    ?: 15
                            val c = pomodoroCycles.toIntOrNull()?.coerceIn(1, 10)  ?: 4
                            scope.launch {
                                withContext(Dispatchers.IO) { BreakEnforcer.saveSettings(w, s, l, c) }
                                pomodoroSaved = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.btnSave)
                    }
                    if (pomodoroSaved) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.settingsSaved, color = Success, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // ── Chime Presets ─────────────────────────────────────────────
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = OnSurface2.copy(alpha = 0.12f))
                Text("Chime Sounds", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose which sound plays when your work session or break begins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
                Spacer(Modifier.height(12.dp))

                // Work chime
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.PlayCircle, null, tint = Purple80, modifier = Modifier.size(16.dp))
                    Text("Work session start", style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = FontWeight.Medium, modifier = Modifier.width(148.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChimeStyle.entries.forEach { style ->
                            val sel = workChime == style
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    workChime = style
                                    SoundAversion.workChimeStyle = style
                                    scope.launch(Dispatchers.IO) { Database.setSetting("pomodoro_work_chime", style.name) }
                                },
                                label = { Text(style.label, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple80.copy(alpha = 0.18f),
                                    selectedLabelColor     = Purple80,
                                    containerColor         = Surface3,
                                    labelColor             = OnSurface2
                                )
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { SoundAversion.playSessionStart() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Preview", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Break chime
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Coffee, null, tint = Success, modifier = Modifier.size(16.dp))
                    Text("Break start", style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = FontWeight.Medium, modifier = Modifier.width(148.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChimeStyle.entries.forEach { style ->
                            val sel = breakChime == style
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    breakChime = style
                                    SoundAversion.breakChimeStyle = style
                                    scope.launch(Dispatchers.IO) { Database.setSetting("pomodoro_break_chime", style.name) }
                                },
                                label = { Text(style.label, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Success.copy(alpha = 0.15f),
                                    selectedLabelColor     = Success,
                                    containerColor         = Surface3,
                                    labelColor             = OnSurface2
                                )
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { SoundAversion.playBreakReminder() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Preview", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ── Startup ───────────────────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsStartup) {
                SettingRow(
                    label    = strings.settingsStartWithWindows,
                    subtitle = if (!isWindows) "Only available on Windows"
                               else if (startWithWin) "FocusFlow launches at login (HKCU\\Run)"
                               else "FocusFlow does not start automatically",
                    trailing = {
                        Switch(
                            checked  = startWithWin,
                            enabled  = isWindows,
                            onCheckedChange = { enabled ->
                                startWithWin = enabled
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (enabled) WindowsStartupManager.enable()
                                        else         WindowsStartupManager.disable()
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }

        // ── Sound Notifications ───────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsSoundNotifications) {
                SettingRow(
                    label    = strings.settingsAversionTones,
                    subtitle = "Harsh tone when blocked app killed; chime on session start/end/break",
                    trailing = {
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { enabled ->
                                soundEnabled = enabled
                                SoundAversion.isEnabled = enabled
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Database.setSetting("sound_aversion", enabled.toString())
                                    }
                                }
                            }
                        )
                    }
                )
                if (soundEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                        Text("Volume", style = MaterialTheme.typography.bodySmall, color = OnSurface2, modifier = Modifier.width(54.dp))
                        Slider(
                            value = soundVolume,
                            onValueChange = { soundVolume = it; SoundAversion.volumeMultiplier = it },
                            onValueChangeFinished = {
                                scope.launch { withContext(Dispatchers.IO) { Database.setSetting("sound_volume", soundVolume.toString()) } }
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Purple80, activeTrackColor = Purple80)
                        )
                        Text(
                            "${(soundVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2,
                            modifier = Modifier.width(38.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(16.dp))
                        Text(
                            "Aversion sound",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2,
                            modifier = Modifier.width(104.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChimeStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = aversionSound == style,
                                    onClick = {
                                        aversionSound = style
                                        SoundAversion.blockAlertStyle = style
                                        scope.launch(Dispatchers.IO) {
                                            Database.setSetting("aversion_sound_style", style.name)
                                        }
                                    },
                                    label = {
                                        Text(style.label, style = MaterialTheme.typography.bodySmall)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Warning.copy(alpha = 0.18f),
                                        selectedLabelColor = Warning,
                                        containerColor = Surface3,
                                        labelColor = OnSurface2
                                    )
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { SoundAversion.playBlockAlert() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Preview", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 8.dp))
                SettingRow(
                    label    = strings.settingsTaskAlarms,
                    subtitle = "Tray notification when a scheduled task is about to start (5min + 1min warnings)",
                    trailing = {
                        Button(
                            onClick = { TaskAlarmService.testAlarm("Test Task") },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.NotificationsActive, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.settingsTest, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                )
            }
        }

        // ── Block Overlay ─────────────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsBlockOverlay) {
                SettingRow(
                    label = "Show block overlay",
                    subtitle = "Show the full-screen and in-app overlay when a blocked window is detected",
                    trailing = {
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled && globalPinSet) {
                                    showOverlayPinGate = true
                                } else {
                                    setOverlayEnabled(enabled)
                                }
                            }
                        )
                    }
                )
                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 8.dp))
                Text(strings.settingsOverlayMessageDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = overlayMessage,
                    onValueChange = { msg ->
                        overlayMessage = msg
                        FloatingBlockOverlay.overlayMessage = msg
                        scope.launch {
                            withContext(Dispatchers.IO) { Database.setSetting("overlay_message", msg) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Purple80,
                        unfocusedBorderColor = OnSurface2
                    )
                )
                HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Timer, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Block Overlay Duration", style = MaterialTheme.typography.bodySmall, color = OnSurface)
                            Text(
                                "How long the overlay stays on screen after blocking an app",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = OnSurface2
                            )
                        }
                        Text(
                            "${overlayDismissSecs}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = Purple80,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = overlayDismissSecs.toFloat(),
                        onValueChange = {
                            overlayDismissSecs = it.toInt()
                            FloatingBlockOverlay.dismissSeconds = it.toInt()
                        },
                        onValueChangeFinished = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    Database.setSetting("overlay_dismiss_seconds", overlayDismissSecs.toString())
                                }
                            }
                        },
                        valueRange = 2f..15f,
                        steps = 12,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(thumbColor = Purple80, activeTrackColor = Purple80)
                    )
                }
            }
        }

        // ── Session PIN ───────────────────────────────────────────────────────
        item {
            if (false) { /*
                        // Session PIN actions are managed in the PIN Management card above.
                            if (pinSet) {
                                OutlinedButton(
                                    onClick = { showChangePinDialog = true },
                                    border  = androidx.compose.foundation.BorderStroke(1.dp, Purple80),
                                    colors  = ButtonDefaults.outlinedButtonColors()
                                ) { Text("Change", color = Purple80) }
                            }
                            Button(
                                onClick = { showPinDialog = true },
                                colors  = ButtonDefaults.buttonColors(
                                    containerColor = if (pinSet) Error.copy(alpha = 0.7f) else Purple80
                                )
                            ) {
                                Text(if (pinSet) strings.settingsRemovePin else strings.settingsSetPin)
                            }
                        }
                    }
                )
            }
        }

        // ── Global PIN ────────────────────────────────────────────────────────
        item {
            SectionCard(title = "Global PIN") {
                SettingRow(
                    label    = "Master removal lock",
                    subtitle = if (globalPinSet) "Required to remove blocks, schedules, and settings"
                               else "Not set — removal is unrestricted",
                    trailing = {
                        Button(
                            onClick = { showGlobalPinDialog = true },
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = if (globalPinSet) Error.copy(alpha = 0.15f) else Purple80
                            )
                        ) {
                            Text(
                                if (globalPinSet) "Change / Clear" else "Set PIN",
                                color = if (globalPinSet) Error else androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                )
            }
        }

            */
            }
        }

        // ── Focus Mode ────────────────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsFocusModeLabel) {
                SettingRow(
                    label    = strings.settingsLockUntilTimer,
                    subtitle = "Session cannot be ended early — the timer must fully expire",
                    trailing = {
                        Switch(
                            checked = focusLockUntilTimer,
                            onCheckedChange = { enabled ->
                                focusLockUntilTimer = enabled
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Database.setSetting("focus_lock_until_timer", enabled.toString())
                                    }
                                }
                            }
                        )
                    }
                )
                if (focusLockUntilTimer) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Warning.copy(alpha = 0.10f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lock, null, tint = Warning, modifier = Modifier.size(16.dp))
                        Text(strings.settingsLockEndDisabled, style = MaterialTheme.typography.bodySmall, color = Warning)
                    }
                }
            }
        }

        // ── System Tray ───────────────────────────────────────────────────────
        item {
            SectionCard(title = strings.settingsSystemTray) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Purple80.copy(alpha = 0.08f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Info, null, tint = Purple80, modifier = Modifier.size(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.settingsSysTrayDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            "If you don't see the icon, click the ^ (Show hidden icons) arrow in your taskbar corner. " +
                            "Right-click the FocusFlow icon to show the window or quit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }
                }
            }
        }

        // ── Privacy ───────────────────────────────────────────────────────────
        item {
            SectionCard(title = "Privacy") {
                SettingRow(
                    label    = "Send anonymous diagnostics",
                    subtitle = "Covers crash reports and stack traces so we can fix bugs. No task names, usernames, file paths, or personal data are ever included.",
                    trailing = {
                        Switch(
                            checked = crashReportsEnabled,
                            onCheckedChange = { enabled ->
                                crashReportsEnabled = enabled
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Database.setSetting("crash_reports_enabled", enabled.toString())
                                    }
                                }
                            }
                        )
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Crash reports are sent only when enabled. You can change this setting at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        item {
            SectionCard(title = strings.settingsAbout) {
                Text("FocusFlow PC v1.1.8", color = OnSurface)
                Spacer(Modifier.height(4.dp))
                Text("Kotlin 1.9.22 + Compose Multiplatform Desktop 1.6.1", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Text("Enforcement: JNA Win32 + WinEventHook + Nuclear Mode + Windows Firewall", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Text("Features: Pomodoro, Daily Notes, 7-Day Stats, Task Alarms, App Scanner", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Text("Database: SQLite at %USERPROFILE%\\.focusflow\\focusflow.db", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            }
        }

        // ── Fix 8: Nuclear Mode moved to "Advanced" at the bottom ─────────────
        item {
            SectionCard(title = "⚠ Advanced") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (nuclearActive) Error.copy(alpha = 0.1f) else Surface3)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock, null,
                        tint     = if (nuclearActive) Error else OnSurface2,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (nuclearActive) "${strings.settingsNuclearMode} ACTIVE" else strings.settingsNuclearMode,
                            color = if (nuclearActive) Error else OnSurface
                        )
                        Text(
                            "Kills Task Manager, regedit, cmd, PowerShell, Process Explorer when detected — no escape",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = nuclearActive,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                NuclearMode.enable()
                                nuclearActive = NuclearMode.isActive
                            } else {
                                // Gate the off-switch behind the Nuclear Mode PIN when one is set.
                                // FocusLauncherService calls disable(silent=true) directly and must
                                // never hit this UI gate — PIN enforcement is UI-layer only.
                                if (NuclearPin.isSet()) {
                                    showNuclearPinGate = true
                                } else {
                                    NuclearMode.disable()
                                    nuclearActive = NuclearMode.isActive
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Error,
                            checkedTrackColor = Error.copy(alpha = 0.3f)
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ Nuclear Mode kills system utilities every 300ms. Use with caution — you must toggle it off inside FocusFlow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Surface3, thickness = 1.dp)
                Spacer(Modifier.height(10.dp))
                // ── Nuclear Mode PIN ──────────────────────────────────────────
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Nuclear Mode PIN",
                            color = OnSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (nuclearPinSet) "4-char PIN active — required to turn Nuclear Mode off"
                            else              "Optional: require a PIN to turn Nuclear Mode off",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }
                    Button(
                        onClick = { showNuclearPinSetup = true },
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = if (nuclearPinSet) Error.copy(alpha = 0.15f)
                                             else              Purple80.copy(alpha = 0.15f)
                        )
                    ) {
                        Text(
                            if (nuclearPinSet) "Change / Clear" else "Set PIN",
                            color = if (nuclearPinSet) Error else Purple80
                        )
                    }
                }
            }
        }
    }
    FfVerticalScrollbar(
        listState = settingsListState,
        modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
    )
    }

    if (showAlwaysOnPinDialog) {
        AlwaysOnPinGateDialog(
            onDismiss = { showAlwaysOnPinDialog = false },
            onVerified = {
                showAlwaysOnPinDialog = false
                alwaysOn = false
                ProcessMonitor.alwaysOnEnabled = false
                scope.launch { withContext(Dispatchers.IO) { Database.setSetting("always_on_enforcement", "false") } }
            }
        )
    }

    if (showPinDialog) {
        PinDialog(
            pinAlreadySet = pinSet,
            onDismiss     = { showPinDialog = false },
            onSave        = { pin ->
                val success = withContext(Dispatchers.IO) {
                    if (pinSet) SessionPin.clear(pin) else { SessionPin.set(pin); true }
                }
                if (success) {
                    showPinDialog = false
                    reload()
                }
                success
            }
        )
    }

    // ── Nuclear Mode PIN gate — shown when toggling Nuclear Mode off while a PIN is set ──
    if (showNuclearPinGate) {
        NuclearPinGateDialog(
            onDismiss  = { showNuclearPinGate = false },
            onVerified = {
                showNuclearPinGate = false
                NuclearMode.disable()
                nuclearActive = NuclearMode.isActive
            }
        )
    }

    // ── Nuclear Mode PIN setup — set, change, or clear ────────────────────────
    if (showNuclearPinSetup) {
        NuclearPinSetupDialog(
            pinAlreadySet = nuclearPinSet,
            onDismiss     = { showNuclearPinSetup = false },
            onChanged     = { showNuclearPinSetup = false; reload() }
        )
    }

    // ── Session PIN change dialog ─────────────────────────────────────────────
    if (showChangePinDialog) {
        SessionPinChangeDialog(
            onDismiss = { showChangePinDialog = false },
            onChanged = { showChangePinDialog = false; reload() }
        )
    }

    // ── Global PIN manage dialog ──────────────────────────────────────────────
    if (showGlobalPinDialog) {
        GlobalPinManageDialog(
            pinAlreadySet       = globalPinSet,
            startWithRemoval    = globalPinRemovalRequested,
            onDismiss           = {
                showGlobalPinDialog = false
                globalPinRemovalRequested = false
            },
            onChanged           = {
                showGlobalPinDialog = false
                globalPinRemovalRequested = false
                reload()
            }
        )
    }

    if (showOverlayPinGate) {
        PinGateDialog(
            title    = "Disable block overlay",
            subtitle = "Enter your Global PIN to hide block overlays. Process blocking will remain active.",
            allowReset = false,
            onSuccess = {
                showOverlayPinGate = false
                setOverlayEnabled(false)
            },
            onDismiss = { showOverlayPinGate = false }
        )
    }

}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun PinManagementRow(
    name: String,
    description: String,
    active: Boolean,
    onSet: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (active) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = null,
            tint = if (active) Success else OnSurface2,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = OnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
            Text(
                if (active) "Active" else "Not set",
                color = if (active) Success else OnSurface2,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active) {
                OutlinedButton(
                    onClick = onChange,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Purple80)
                ) { Text("Change", color = Purple80) }
                TextButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("Remove", color = Error) }
            } else {
                Button(
                    onClick = onSet,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) { Text("Set PIN") }
            }
        }
    }
}

@Composable
private fun PomodoroField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = modifier,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Purple80,
            unfocusedBorderColor = OnSurface2
        )
    )
}

@Composable
private fun SettingRow(label: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = OnSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
        }
        trailing()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Surface2).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = OnSurface)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onSave: (BlockRule) -> Unit) {
    val strings      = LocalizationManager.strings
    var processName  by remember { mutableStateOf("") }
    var displayName  by remember { mutableStateOf("") }
    var blockNetwork by remember { mutableStateOf(false) }
    var showPicker   by remember { mutableStateOf(false) }
    var searchQuery  by remember { mutableStateOf("") }

    val scannedApps = remember {
        com.focusflow.enforcement.InstalledAppsScanner.getRunningApps()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (showPicker) "Pick App to Block" else "Block an App", color = OnSurface)
                TextButton(onClick = { showPicker = !showPicker }) {
                    Icon(
                        if (showPicker) Icons.Default.Edit else Icons.Default.Apps,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showPicker) "Manual" else "Pick App", color = Purple80)
                }
            }
        },
        text = {
            if (showPicker) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(strings.settingsSearchApps, color = OnSurface2) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = OnSurface2) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2
                        ),
                        singleLine = true
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        scannedApps
                            .filter {
                                searchQuery.isBlank() ||
                                it.displayName.contains(searchQuery, ignoreCase = true) ||
                                it.processName.contains(searchQuery, ignoreCase = true)
                            }
                            .forEach { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (processName == app.processName) Purple80.copy(alpha = 0.15f)
                                            else Surface3
                                        )
                                        .clickable {
                                            processName = app.processName
                                            displayName = app.displayName
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.displayName, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                                        Text(app.processName, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (app.isRunning) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(Success)
                                        )
                                    }
                                }
                            }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = blockNetwork, onCheckedChange = { blockNetwork = it })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(strings.settingsAlsoBlockNetwork, color = OnSurface)
                            Text(strings.settingsFirewallNote, style = MaterialTheme.typography.bodySmall, color = Warning)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value         = processName,
                        onValueChange = { processName = it },
                        label         = { Text("Process name (e.g. chrome.exe)") },
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                    )
                    OutlinedTextField(
                        value         = displayName,
                        onValueChange = { displayName = it },
                        label         = { Text("Display name (e.g. Google Chrome)") },
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = blockNetwork, onCheckedChange = { blockNetwork = it })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(strings.settingsAlsoBlockNetwork, color = OnSurface)
                            Text(strings.settingsFirewallNote, style = MaterialTheme.typography.bodySmall, color = Warning)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (processName.isBlank()) return@Button
                    val name = if (processName.endsWith(".exe")) processName else "$processName.exe"
                    onSave(BlockRule(UUID.randomUUID().toString(), name.lowercase(), displayName.ifBlank { name }, true, blockNetwork))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(LocalizationManager.strings.btnAdd) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) } }
    )
}

@Composable
private fun PinDialog(pinAlreadySet: Boolean, onDismiss: () -> Unit, onSave: suspend (String) -> Boolean) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title            = { Text(if (pinAlreadySet) LocalizationManager.strings.settingsRemovePin else LocalizationManager.strings.settingsSetPin, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = pin,
                    onValueChange = {
                        pin = if (pinAlreadySet) it else it.take(PinPolicy.MAX_LENGTH)
                        error = false
                    },
                    label         = {
                        Text(if (pinAlreadySet) "Current PIN"
                             else "New PIN (${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} chars)")
                    },
                    isError       = error || (!pinAlreadySet && pin.isNotBlank() && pin.length < PinPolicy.MIN_LENGTH),
                    supportingText = when {
                        error ->
                            { { Text(LocalizationManager.strings.settingsIncorrectPin, color = Error) } }
                        !pinAlreadySet && pin.isNotBlank() && pin.length < PinPolicy.MIN_LENGTH ->
                            { {
                                Text(
                                    "PIN must be at least ${PinPolicy.MIN_LENGTH} characters (${pin.length}/${PinPolicy.MIN_LENGTH})",
                                    color = androidx.compose.ui.graphics.Color(0xFFCF6679)
                                )
                            } }
                        else -> null
                    },
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                )
                if (!pinAlreadySet) {
                    Text(
                        "The PIN is required to end a focus session early. Use ${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} characters, or generate a secure 20–${PinPolicy.MAX_LENGTH}-character PIN below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2
                    )
                    TextButton(
                        onClick = { pin = PinPolicy.generate(); error = false },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate secure PIN", color = Purple80)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pin.isNotBlank()) {
                        scope.launch {
                            val success = onSave(pin)
                            if (!success) error = true
                        }
                    }
                },
                enabled = if (pinAlreadySet) pin.isNotBlank() else pin.length >= PinPolicy.MIN_LENGTH,
                colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(LocalizationManager.strings.settingsConfirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) } }
    )
}

// ── Session PIN change dialog — verify current → enter new → confirm ──────────
@Composable
private fun SessionPinChangeDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    var step       by remember { mutableStateOf(0) } // 0 = verify old, 1 = set new
    var currentPin by remember { mutableStateOf("") }
    var newPin     by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var showNew    by remember { mutableStateOf(false) }
    var error      by remember { mutableStateOf("") }
    val scope      = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Purple80, modifier = Modifier.size(22.dp))
                Text(if (step == 0) "Verify Current PIN" else "Set New Session PIN",
                    color = OnSurface, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step == 0) {
                    Text("Enter your current Session PIN to continue.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    OutlinedTextField(
                        value         = currentPin,
                        onValueChange = { currentPin = it; error = "" },
                        label         = { Text("Current PIN") },
                        singleLine    = true,
                        isError       = error.isNotEmpty(),
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                    )
                } else {
                        Text("Choose a new PIN (${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} characters), or generate one below.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    OutlinedTextField(
                        value         = newPin,
                        onValueChange = { newPin = it.take(PinPolicy.MAX_LENGTH); error = "" },
                        label         = { Text("New PIN (${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} chars)") },
                        singleLine    = true,
                        visualTransformation = if (showNew) androidx.compose.ui.text.input.VisualTransformation.None
                                               else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon  = {
                            IconButton(onClick = { showNew = !showNew }) {
                                Icon(if (showNew) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                            }
                        },
                        isError  = error.isNotEmpty() ||
                            (newPin.isNotBlank() && newPin.length < PinPolicy.MIN_LENGTH),
                        modifier = Modifier.fillMaxWidth(),
                        colors   = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                    )
                    OutlinedTextField(
                        value         = confirmPin,
                        onValueChange = { confirmPin = it; error = "" },
                        label         = { Text("Confirm new PIN") },
                        singleLine    = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError  = error.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors   = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                    )
                    TextButton(
                        onClick = {
                            newPin = PinPolicy.generate()
                            confirmPin = newPin
                            showNew = true
                            error = ""
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate secure PIN", color = Purple80)
                    }
                }
                if (error.isNotEmpty()) Text(error, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        if (step == 0) {
                            val ok = withContext(Dispatchers.IO) { SessionPin.verify(currentPin) }
                            if (ok) { step = 1; error = "" } else error = "Incorrect PIN."
                        } else {
                            when {
                                newPin.length < PinPolicy.MIN_LENGTH ->
                                    error = "PIN must be at least ${PinPolicy.MIN_LENGTH} characters."
                                newPin.length > PinPolicy.MAX_LENGTH ->
                                    error = "PIN must be at most ${PinPolicy.MAX_LENGTH} characters."
                                newPin != confirmPin -> error = "PINs do not match."
                                else -> {
                                    withContext(Dispatchers.IO) { SessionPin.set(newPin) }
                                    onChanged()
                                }
                            }
                        }
                    }
                },
                enabled = if (step == 0) currentPin.isNotBlank()
                          else newPin.length >= PinPolicy.MIN_LENGTH && confirmPin.isNotBlank(),
                colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(if (step == 0) "Next" else "Save PIN") }
        },
        dismissButton = {
            TextButton(
                onClick = if (step == 1) {{ step = 0; error = "" }} else onDismiss
            ) { Text(if (step == 1) "Back" else "Cancel", color = OnSurface2) }
        }
    )
}

// ── Global PIN manage dialog — set, change, or clear ─────────────────────────
@Composable
private fun GlobalPinManageDialog(
    pinAlreadySet: Boolean,
    startWithRemoval: Boolean = false,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var step       by remember { mutableStateOf(if (pinAlreadySet) 0 else 1) }
    var clearing   by remember { mutableStateOf(startWithRemoval) }
    var currentPin by remember { mutableStateOf("") }
    var newPin     by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var showNew    by remember { mutableStateOf(false) }
    var error      by remember { mutableStateOf("") }
    val scope      = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Purple80, modifier = Modifier.size(22.dp))
                Text(
                    when { step == 0 -> "Verify Current PIN"; clearing -> "Remove Global PIN"; else -> if (pinAlreadySet) "Set New Global PIN" else "Set Global PIN" },
                    color = OnSurface, fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    0 -> {
                        Text("Enter your current Global PIN to continue.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        OutlinedTextField(
                            value         = currentPin,
                            onValueChange = { currentPin = it; error = "" },
                            label         = { Text("Current PIN") },
                            singleLine    = true,
                            isError       = error.isNotEmpty(),
                            modifier      = Modifier.fillMaxWidth(),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                        )
                        if (error.isNotEmpty()) Text(error, color = Error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clearing = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("Remove PIN instead", color = Warning, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    1 -> {
                        if (clearing) {
                            Text("Remove the Global PIN? Blocks and settings can be changed freely without a PIN.",
                                style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        } else {
                             Text("Choose a Global PIN (${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} characters), or generate one below. Required to remove any block or setting.",
                                style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                            OutlinedTextField(
                                value         = newPin,
                                 onValueChange = { newPin = it.take(PinPolicy.MAX_LENGTH); error = "" },
                                 label         = { Text("New PIN (${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} chars)") },
                                singleLine    = true,
                                visualTransformation = if (showNew) androidx.compose.ui.text.input.VisualTransformation.None
                                                       else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon  = {
                                    IconButton(onClick = { showNew = !showNew }) {
                                        Icon(if (showNew) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                                    }
                                },
                                isError  = error.isNotEmpty() ||
                                    (newPin.isNotBlank() && newPin.length < PinPolicy.MIN_LENGTH),
                                modifier = Modifier.fillMaxWidth(),
                                colors   = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                            )
                            OutlinedTextField(
                                value         = confirmPin,
                                onValueChange = { confirmPin = it; error = "" },
                                label         = { Text("Confirm PIN") },
                                singleLine    = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                isError  = error.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                colors   = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                            )
                             TextButton(
                                 onClick = {
                                     newPin = PinPolicy.generate()
                                     confirmPin = newPin
                                     showNew = true
                                     error = ""
                                 },
                                 contentPadding = PaddingValues(0.dp)
                             ) {
                                 Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp))
                                 Spacer(Modifier.width(6.dp))
                                 Text("Generate secure PIN", color = Purple80)
                             }
                            if (error.isNotEmpty()) Text(error, color = Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        when (step) {
                            0 -> {
                                val ok = withContext(Dispatchers.IO) { GlobalPin.verify(currentPin) }
                                if (ok) { step = 1; error = "" } else error = "Incorrect PIN."
                            }
                            1 -> {
                                if (clearing) {
                                    withContext(Dispatchers.IO) { GlobalPin.resetWithoutPin() }
                                    onChanged()
                                } else {
                                    when {
                                         newPin.length < PinPolicy.MIN_LENGTH ->
                                             error = "PIN must be at least ${PinPolicy.MIN_LENGTH} characters."
                                         newPin.length > PinPolicy.MAX_LENGTH ->
                                             error = "PIN must be at most ${PinPolicy.MAX_LENGTH} characters."
                                        newPin != confirmPin -> error = "PINs do not match."
                                        else -> { withContext(Dispatchers.IO) { GlobalPin.set(newPin) }; onChanged() }
                                    }
                                }
                            }
                        }
                    }
                },
                enabled = when (step) {
                    0 -> currentPin.isNotBlank()
                    else -> if (clearing) true else newPin.length >= PinPolicy.MIN_LENGTH && confirmPin.isNotBlank()
                },
                colors  = ButtonDefaults.buttonColors(containerColor = if (clearing && step == 1) Error else Purple80)
            ) { Text(when { step == 0 -> "Next"; clearing -> "Remove PIN"; else -> "Save PIN" }) }
        },
        dismissButton = {
            TextButton(
                onClick = if (step == 1 && pinAlreadySet) {{ step = 0; clearing = false; error = "" }} else onDismiss
            ) { Text(if (step == 1 && pinAlreadySet) "Back" else "Cancel", color = OnSurface2) }
        }
    )
}

@Composable
private fun AlwaysOnPinGateDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Warning, modifier = Modifier.size(22.dp))
                Text(LocalizationManager.strings.settingsPinRequired, color = OnSurface)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter your Global PIN to disable Always-On Enforcement.", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it; error = false },
                    label = { Text("Global PIN") }, modifier = Modifier.fillMaxWidth(),
                    isError = error, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error)
                )
                if (error) Text(LocalizationManager.strings.settingsIncorrectPin, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (GlobalPin.verify(pin)) onVerified() else error = true },
                colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(LocalizationManager.strings.settingsConfirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) } }
    )
}

@Composable
private fun AddScheduleDialog(onDismiss: () -> Unit, onSave: (BlockSchedule) -> Unit) {
    var name         by remember { mutableStateOf("") }
    var startHour    by remember { mutableStateOf("9") }
    var startMinute  by remember { mutableStateOf("0") }
    var endHour      by remember { mutableStateOf("17") }
    var endMinute    by remember { mutableStateOf("0") }
    var selectedDays by remember { mutableStateOf(setOf(1,2,3,4,5)) }
    var processNames by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf(false) }
    val strings      = LocalizationManager.strings

    val dayLabels = listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(LocalizationManager.strings.settingsAddBlockSchedule, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(460.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it; validationError = false }, label = { Text(LocalizationManager.strings.settingsScheduleName) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                Text(LocalizationManager.strings.settingsDaysOfWeek, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayLabels.forEachIndexed { i, label ->
                        val day = i + 1
                        FilterChip(selected = day in selectedDays, onClick = { selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = startHour,   onValueChange = { startHour   = it.filter { c -> c.isDigit() }.take(2); validationError = false }, label = { Text(LocalizationManager.strings.settingsStartHr) },  modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                    OutlinedTextField(value = startMinute, onValueChange = { startMinute = it.filter { c -> c.isDigit() }.take(2); validationError = false }, label = { Text(LocalizationManager.strings.settingsStartMin) }, modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                    OutlinedTextField(value = endHour,     onValueChange = { endHour     = it.filter { c -> c.isDigit() }.take(2); validationError = false }, label = { Text(LocalizationManager.strings.settingsEndHr) },    modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                    OutlinedTextField(value = endMinute,   onValueChange = { endMinute   = it.filter { c -> c.isDigit() }.take(2); validationError = false }, label = { Text(LocalizationManager.strings.settingsEndMin) },   modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                }
                OutlinedTextField(value = processNames, onValueChange = { processNames = it }, label = { Text(LocalizationManager.strings.settingsProcessesCsv) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                Text(strings.settingsLeaveProcessesBlank, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                if (validationError) {
                    Text(
                        "Use hours 0–23 and minutes 0–59. End time may be 24:00.",
                        color = Error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val startHourValue = startHour.toIntOrNull()
                val startMinuteValue = startMinute.toIntOrNull()
                val endHourValue = endHour.toIntOrNull()
                val endMinuteValue = endMinute.toIntOrNull()
                val schedule = if (
                    startHourValue != null && startMinuteValue != null &&
                    endHourValue != null && endMinuteValue != null
                ) {
                    BlockSchedule(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(),
                        daysOfWeek = selectedDays.toList().sorted(),
                        startHour = startHourValue,
                        startMinute = startMinuteValue,
                        endHour = endHourValue,
                        endMinute = endMinuteValue,
                        processNames = processNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    )
                } else null

                if (schedule != null &&
                    schedule.name.isNotBlank() &&
                    schedule.daysOfWeek.isNotEmpty() &&
                    schedule.hasValidTimeRange()
                ) {
                    onSave(schedule)
                } else {
                    validationError = true
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Purple80)) { Text(LocalizationManager.strings.btnAdd) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) } }
    )
}

@Composable
private fun LanguageSettingsSection() {
    val scope = rememberCoroutineScope()
    val currentLanguage = LocalizationManager.currentLanguage
    val s = LocalizationManager.strings
    var expanded by remember { mutableStateOf(false) }

    SectionCard(title = s.settingsLanguageTitle) {
        SettingRow(
            label = s.settingsLanguageTitle,
            subtitle = s.settingsLanguageDesc,
            trailing = {
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "${currentLanguage.flag} ${currentLanguage.nativeName}",
                            color = OnSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            null,
                            tint = OnSurface2,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Surface2)
                    ) {
                        AppLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(lang.flag, fontSize = 18.sp)
                                        Column {
                                            Text(lang.nativeName, color = if (lang == currentLanguage) Purple80 else OnSurface, fontWeight = if (lang == currentLanguage) FontWeight.SemiBold else FontWeight.Normal)
                                            Text(lang.displayName, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                                        }
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    scope.launch { LocalizationManager.saveLanguage(lang) }
                                },
                                trailingIcon = if (lang == currentLanguage) {
                                    { Icon(Icons.Default.CheckCircle, null, tint = Purple80, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun AddAllowanceDialog(onDismiss: () -> Unit, onSave: (DailyAllowance) -> Unit) {
    var processName   by remember { mutableStateOf("") }
    var displayName   by remember { mutableStateOf("") }
    var allowanceMins by remember { mutableStateOf("30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(LocalizationManager.strings.settingsAddDailyAllowance, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(380.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(LocalizationManager.strings.settingsEditAllowanceDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                OutlinedTextField(value = processName, onValueChange = { processName = it }, label = { Text(LocalizationManager.strings.settingsProcessNameHint) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text(LocalizationManager.strings.settingsDisplayNameLabel) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LocalizationManager.strings.settingsAllowanceLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    listOf(15, 30, 60, 120).forEach { m ->
                        FilterChip(selected = allowanceMins == m.toString(), onClick = { allowanceMins = m.toString() }, label = { Text("${m}m") })
                    }
                }
                OutlinedTextField(value = allowanceMins, onValueChange = { allowanceMins = it.filter { c -> c.isDigit() }.take(3) }, label = { Text(LocalizationManager.strings.settingsCustomMinutes) }, modifier = Modifier.width(160.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (processName.isBlank()) return@Button
                onSave(DailyAllowance(processName = processName.trim(), displayName = displayName.ifBlank { processName.removeSuffix(".exe").replaceFirstChar { it.uppercase() } }, allowanceMinutes = allowanceMins.toIntOrNull()?.coerceAtLeast(1) ?: 30))
            }, colors = ButtonDefaults.buttonColors(containerColor = Purple80)) { Text(LocalizationManager.strings.btnAdd) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) } }
    )
}
