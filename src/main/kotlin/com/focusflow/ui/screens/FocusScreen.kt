package com.focusflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextAlign
import com.focusflow.data.Database
import com.focusflow.data.models.Task
import com.focusflow.enforcement.InstalledAppsScanner
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.ScannedApp
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.*
import com.focusflow.ui.components.AllowanceLoadBanner
import com.focusflow.ui.components.EmergencyBreakAllowanceNotice
import com.focusflow.ui.components.PinGateDialog
import com.focusflow.ui.components.ShortcutTooltip
import com.focusflow.ui.theme.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun FocusScreen(
    preloadTask: Task? = null,
    onOpenAllowances: () -> Unit = {}
) {
    val sessionState    by FocusSessionService.state.collectAsState()
    val strings         = LocalizationManager.strings
    val pomodoroState   by BreakEnforcer.state.collectAsState()
    val standaloneBlock by StandaloneBlockService.block.collectAsState()
    val lastSummary     by FocusSessionService.lastSummary.collectAsState()

    var customTaskName by remember { mutableStateOf(preloadTask?.title ?: "") }
    var customMinutes  by remember { mutableStateOf((preloadTask?.durationMinutes ?: pomodoroState.workMinutes).toString()) }
    var pomodoroMode   by remember { mutableStateOf(false) }
    var sessionNotes      by remember { mutableStateOf("") }
    var distractionCount  by remember { mutableStateOf(0) }
    var recentTasks       by remember { mutableStateOf(listOf<Task>()) }
    var alwaysOnEnabled      by remember { mutableStateOf(false) }
    var blockRulesCount      by remember { mutableStateOf(0) }
    var scheduleCount        by remember { mutableStateOf(0) }
    var allowanceLoadState by remember {
        mutableStateOf<AllowanceLoadState<AllowanceUsageSummary>>(AllowanceLoadState.Loading())
    }
    var keywordCount         by remember { mutableStateOf(0) }

    var focusModeActive    by remember { mutableStateOf(preloadTask?.focusMode == true) }
    var focusIntensity     by remember { mutableStateOf(preloadTask?.focusIntensity ?: "standard") }
    var focusModeAutoEnabledEnforcement by remember { mutableStateOf(false) }
    var focusModeAutoEnabledNuclear     by remember { mutableStateOf(false) }
    var showBreakSkipPinDialog by remember { mutableStateOf(false) }

    var showStandaloneDialog by remember { mutableStateOf(false) }
    var showEndPinDialog     by remember { mutableStateOf(false) }
    var showPinRevealDialog  by remember { mutableStateOf(false) }
    var generatedPinText     by remember { mutableStateOf("") }
    var pendingStartMins     by remember { mutableStateOf(0) }
    var pendingStartApps     by remember { mutableStateOf(listOf<String>()) }
    var pendingStartTaskId   by remember { mutableStateOf<String?>(null) }
    var selectedTaskId       by remember { mutableStateOf(preloadTask?.id) }
    var focusModeRequirePin  by remember { mutableStateOf(preloadTask?.focusRequirePin == true) }
    var focusLockUntilTimer  by remember { mutableStateOf(false) }
    var showAdvanced         by remember { mutableStateOf(false) }
    var sessionExtraApps     by remember { mutableStateOf(setOf<String>()) }
    var showSessionAppPicker by remember { mutableStateOf(false) }
    var scannedApps          by remember { mutableStateOf(listOf<ScannedApp>()) }

    val scope = rememberCoroutineScope()

    fun reloadAllowances() {
        val previous = allowanceLoadState.lastKnownValue()
        allowanceLoadState = AllowanceLoadState.Loading(previous)
        scope.launch {
            allowanceLoadState = loadAllowanceData(previous) {
                withContext(Dispatchers.IO) {
                    check(Database.isReady) { "Database unavailable" }
                    val allowances = Database.getDailyAllowances()
                    DailyAllowanceTracker.reload().getOrThrow()
                    summarizeAllowanceUsage(allowances, DailyAllowanceTracker.blockedProcesses)
                }
            }
        }
    }

    fun reload() {
        reloadAllowances()
        scope.launch {
            try {
                val rt  = withContext(Dispatchers.IO) { Database.getTasks().filter { !it.completed }.take(10) }
                val aoe = withContext(Dispatchers.IO) { Database.getSetting("always_on_enforcement") == "true" }
                val brc = withContext(Dispatchers.IO) { Database.getBlockRules().count { it.enabled } }
                val sc  = withContext(Dispatchers.IO) { Database.getBlockSchedules().count { it.enabled } }
                val kwc = withContext(Dispatchers.IO) { Database.getBlockedKeywords().size }
                recentTasks          = rt
                alwaysOnEnabled      = aoe
                blockRulesCount      = brc
                scheduleCount        = sc
                keywordCount         = kwc
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Allowance failures have their own visible state and retry action.
            }
        }
    }

    LaunchedEffect(Unit) {
        // Load all persisted prefs on IO — never block the composition thread
        val pm = withContext(Dispatchers.IO) { Database.getSetting("pomodoro_mode") == "true" }
        pomodoroMode = pm
        FocusSessionService.pomodoroMode = pm
        reload()
        withContext(Dispatchers.IO) { BreakEnforcer.loadSettings() }
        focusLockUntilTimer = withContext(Dispatchers.IO) { Database.getSetting("focus_lock_until_timer") == "true" }
    }

    LaunchedEffect(preloadTask) {
        preloadTask?.let {
            customTaskName      = it.title
            customMinutes       = it.durationMinutes.toString()
            focusModeActive     = it.focusMode
            focusIntensity      = it.focusIntensity
            focusModeRequirePin = it.focusRequirePin
            selectedTaskId      = it.id
        }
    }

    LaunchedEffect(sessionState.isActive) {
        if (!sessionState.isActive) {
            if (focusModeAutoEnabledEnforcement) {
                alwaysOnEnabled = false
                ProcessMonitor.alwaysOnEnabled = false
                withContext(Dispatchers.IO) { Database.setSetting("always_on_enforcement", "false") }
                focusModeAutoEnabledEnforcement = false
            }
            // Auto-disable Nuclear Mode if we started it for this session
            if (focusModeAutoEnabledNuclear && NuclearMode.isActive) {
                NuclearMode.disable()
                focusModeAutoEnabledNuclear = false
            }
        }
    }

    val isStandaloneActive  = standaloneBlock != null && StandaloneBlockService.isActive
    val standaloneRemaining = StandaloneBlockService.remainingMs()

    val focusScrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.P -> {
                if (!sessionState.isActive) {
                    pomodoroMode = !pomodoroMode
                    FocusSessionService.pomodoroMode = pomodoroMode
                    scope.launch(Dispatchers.IO) {
                        Database.setSetting("pomodoro_mode", pomodoroMode.toString())
                    }
                    if (!pomodoroMode) BreakEnforcer.reset()
                }
                true
            }
            Key.Enter -> {
                if (!sessionState.isActive && !focusModeRequirePin) {
                    val mins = if (pomodoroMode) pomodoroState.workMinutes else customMinutes.toIntOrNull() ?: 25
                    SessionPin.clearForced()
                    FocusSessionService.start(customTaskName.ifBlank { "Focus Session" }, mins, tid = selectedTaskId)
                    TemptationLogger.clearSession()
                }
                true
            }
            else -> false
        }
    }) {
    Column(
        modifier = Modifier.fillMaxSize().background(Surface).verticalScroll(focusScrollState).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.focusTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.focusPomodoroLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                ShortcutTooltip("Ctrl+P") {
                    Switch(
                        checked = pomodoroMode,
                        onCheckedChange = {
                            pomodoroMode = it
                            FocusSessionService.pomodoroMode = it
                            scope.launch(Dispatchers.IO) { Database.setSetting("pomodoro_mode", it.toString()) }
                            if (!it) BreakEnforcer.reset()
                        }
                    )
                }
            }
        }

        if (pomodoroMode) {
            PomodoroCycleIndicator(
                cycleNumber      = pomodoroState.cycleNumber,
                cyclesBeforeLong = pomodoroState.cyclesBeforeLongBreak
            )
        }

        // ── Break overlay ─────────────────────────────────────────────────────
        if (pomodoroMode && pomodoroState.phase != BreakPhase.IDLE) {
            val isLong     = pomodoroState.phase == BreakPhase.LONG_BREAK
            val breakColor = if (isLong) Success else Purple80
            val breakMins  = pomodoroState.breakSecondsRemaining / 60
            val breakSecs  = pomodoroState.breakSecondsRemaining % 60
            val breakPulse = rememberInfiniteTransition(label = "breakPulse")
            val breakTimerScale by breakPulse.animateFloat(
                initialValue   = 1.00f,
                targetValue    = 1.04f,
                animationSpec  = infiniteRepeatable(
                    animation = tween(1100, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breakTimerScale"
            )
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(breakColor.copy(alpha = 0.12f)).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(if (isLong) strings.focusLongBreak else strings.focusShortBreak, style = MaterialTheme.typography.headlineMedium, color = breakColor)
                Text("%02d:%02d".format(breakMins, breakSecs), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 52.sp), color = breakColor, fontWeight = FontWeight.Bold, modifier = Modifier.scale(breakTimerScale))
                Text(if (isLong) strings.focusLongBreakDesc else strings.focusShortBreakDesc, color = OnSurface2, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        if (GlobalPin.isSet()) showBreakSkipPinDialog = true
                        else BreakEnforcer.skipBreak()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = breakColor)
                ) {
                    Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.focusSkipBreak)
                }

                if (showBreakSkipPinDialog) {
                    PinGateDialog(
                        title    = "Skip Break",
                        subtitle = "Enter your PIN to skip this break early",
                        onSuccess = {
                            showBreakSkipPinDialog = false
                            BreakEnforcer.skipBreak()
                        },
                        onDismiss = { showBreakSkipPinDialog = false }
                    )
                }
            }

        } else if (!sessionState.isActive) {
            // ── Compact setup panel ────────────────────────────────────────────
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Surface2).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Quick-start row: [task name] [min] [▶ Start] ──────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customTaskName, onValueChange = { customTaskName = it; selectedTaskId = null },
                        label = { Text(strings.focusWhatWorkingOn) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                    )
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("min") },
                        placeholder = { Text("25", color = OnSurface2.copy(alpha = 0.4f)) },
                        modifier = Modifier.width(72.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                    )
                    val startBtnColor = when {
                        focusModeActive && focusIntensity == "nuclear" -> Error.copy(alpha = 0.9f)
                        focusModeActive && focusIntensity == "deep"    -> Warning.copy(alpha = 0.9f)
                        else                                           -> Purple80
                    }
                    ShortcutTooltip("Ctrl+Enter") {
                    Button(
                        onClick = {
                            val mins = if (pomodoroMode) pomodoroState.workMinutes else customMinutes.toIntOrNull() ?: 25
                            distractionCount = 0
                            FocusSessionService.setNotes(sessionNotes.trim())
                            sessionNotes = ""
                            if (focusModeActive && focusIntensity != "standard" && !alwaysOnEnabled) {
                                alwaysOnEnabled = true
                                ProcessMonitor.alwaysOnEnabled = true
                                scope.launch(Dispatchers.IO) { Database.setSetting("always_on_enforcement", "true") }
                                focusModeAutoEnabledEnforcement = true
                            }
                            if (focusModeActive && focusIntensity == "nuclear" && !NuclearMode.isActive) {
                                NuclearMode.enable()
                                focusModeAutoEnabledNuclear = true
                            }
                            val extraApps = ((preloadTask?.focusBlockedApps ?: emptyList()) + sessionExtraApps).distinct()
                            if (focusModeActive && focusModeRequirePin) {
                                pendingStartMins   = mins
                                pendingStartApps   = extraApps
                                pendingStartTaskId = selectedTaskId
                                generatedPinText   = SessionPin.autoGenerate()
                                showPinRevealDialog = true
                            } else {
                                SessionPin.clearForced()
                                FocusSessionService.start(customTaskName.ifBlank { "Focus Session" }, mins, tid = selectedTaskId, blockedProcesses = extraApps)
                                TemptationLogger.clearSession()
                            }
                        },
                        modifier = Modifier.height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = startBtnColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Start session",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Start",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 14.sp
                        )
                    }
                    } // ShortcutTooltip
                }

                // ── Duration preset chips ──────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 25, 45, 60, 90).forEach { m ->
                        FilterChip(
                            selected = customMinutes == m.toString(),
                            onClick  = { customMinutes = m.toString() },
                            label    = { Text("${m}m") },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Purple80.copy(alpha = 0.20f),
                                selectedLabelColor     = Purple80,
                                containerColor         = Surface3,
                                labelColor             = OnSurface2
                            )
                        )
                    }
                }

                // ── Quick picks (recent tasks) ─────────────────────────────────
                if (recentTasks.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.focusQuickPick, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        Spacer(Modifier.width(2.dp))
                        recentTasks.take(8).forEach { task ->
                            FilterChip(
                                selected = customTaskName == task.title,
                                onClick  = { customTaskName = task.title; customMinutes = task.durationMinutes.toString(); selectedTaskId = task.id },
                                label    = { Text(task.title, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple80.copy(alpha = 0.20f),
                                    selectedLabelColor     = Purple80,
                                    containerColor         = Surface3,
                                    labelColor             = OnSurface2
                                )
                            )
                        }
                    }
                }

                // ── Status badges ──────────────────────────────────────────────
                if (blockRulesCount > 0 || pomodoroMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (blockRulesCount > 0) {
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Purple80.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Shield, null, tint = Purple80, modifier = Modifier.size(12.dp))
                                Text("$blockRulesCount app${if (blockRulesCount == 1) "" else "s"} ${strings.focusAppsWillBeBlocked}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = Purple80)
                            }
                        }
                        if (pomodoroMode) {
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Success.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Autorenew, null, tint = Success, modifier = Modifier.size(12.dp))
                                Text("${pomodoroState.workMinutes}m + ${pomodoroState.shortBreakMinutes}m break", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = Success)
                            }
                        }
                    }
                }

                // ── Advanced toggle row ────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { showAdvanced = !showAdvanced }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (focusModeActive) Icons.Default.Shield else Icons.Default.Tune,
                        null,
                        tint     = if (focusModeActive) Purple80 else OnSurface2,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(strings.focusModeLabel, style = MaterialTheme.typography.bodySmall,
                        color = if (focusModeActive) Purple80 else OnSurface2, fontWeight = FontWeight.Medium)
                    if (focusModeActive) {
                        val intensityColor = when (focusIntensity) { "nuclear" -> Error; "deep" -> Warning; else -> Purple80 }
                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(intensityColor.copy(alpha = 0.15f)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Text(when (focusIntensity) { "nuclear" -> "Nuclear"; "deep" -> "Deep"; else -> "Standard" },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = intensityColor)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                }

                // ── Advanced section (notes + Focus Mode card) ─────────────────
                AnimatedVisibility(visible = showAdvanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = sessionNotes, onValueChange = { sessionNotes = it },
                            label = { Text(strings.focusPreSessionNotes) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null, tint = OnSurface2, modifier = Modifier.size(18.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (focusModeActive) Purple80.copy(alpha = 0.10f) else Surface3).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Shield, null, tint = if (focusModeActive) Purple80 else OnSurface2, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text(strings.focusModeLabel, style = MaterialTheme.typography.bodyMedium, color = if (focusModeActive) Purple80 else OnSurface, fontWeight = FontWeight.SemiBold)
                                        Text(when { !focusModeActive -> strings.focusModeOff; focusIntensity == "deep" -> strings.focusModeDeepDesc; focusIntensity == "nuclear" -> strings.focusModeNuclearDesc; else -> strings.focusModeStandardDesc },
                                            style = MaterialTheme.typography.bodySmall, color = when { !focusModeActive -> OnSurface2; focusIntensity == "nuclear" -> Error; focusIntensity == "deep" -> Warning; else -> Purple60 })
                                    }
                                }
                                Switch(checked = focusModeActive, onCheckedChange = { focusModeActive = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Purple80, checkedTrackColor = Purple80.copy(alpha = 0.4f)))
                            }
                            if (focusModeActive) {
                                HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                                Text(strings.focusIntensityLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(Triple("standard", strings.focusStandardLabel, strings.focusStandardSubDesc), Triple("deep", strings.focusDeepWorkLabel, strings.focusDeepSubDesc), Triple("nuclear", strings.focusNuclearLabel, strings.focusNuclearSubDesc)).forEach { (key, label, desc) ->
                                        val sel = focusIntensity == key
                                        val col = when (key) { "deep" -> Warning; "nuclear" -> Error; else -> Purple80 }
                                        FilterChip(selected = sel, onClick = { focusIntensity = key }, label = {
                                            Column {
                                                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                                                Text(desc, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = OnSurface2)
                                            }
                                        }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = col.copy(alpha = 0.15f), selectedLabelColor = col, containerColor = if (key == "nuclear") Error.copy(alpha = 0.07f) else Surface2, labelColor = if (key == "nuclear") Error.copy(alpha = 0.75f) else OnSurface2),
                                            leadingIcon = if (key == "nuclear") ({ Icon(Icons.Default.Warning, null, tint = if (sel) Error else Error.copy(alpha = 0.55f), modifier = Modifier.size(14.dp)) }) else null)
                                    }
                                }
                                HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { focusModeRequirePin = !focusModeRequirePin }.padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(checked = focusModeRequirePin, onCheckedChange = { focusModeRequirePin = it }, colors = CheckboxDefaults.colors(checkedColor = Purple80))
                                    Column {
                                        Text(strings.focusRequirePin, style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
                                        Text(strings.focusRequirePinHint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = OnSurface2)
                                    }
                                }
                            } else {
                                Text("Enable to set intensity: Standard · Deep · Nuclear", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = OnSurface2.copy(alpha = 0.7f))
                            }
                        }

                        // ── Extra blocked apps for this session ───────────────
                        HorizontalDivider(color = Surface3)
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Add, null, tint = Purple80, modifier = Modifier.size(15.dp))
                                Column {
                                    Text(
                                        "Extra blocked apps",
                                        style      = MaterialTheme.typography.bodySmall,
                                        color      = OnSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        if (sessionExtraApps.isEmpty()) "Block additional apps for this session only"
                                        else "${sessionExtraApps.size} extra app${if (sessionExtraApps.size == 1) "" else "s"} queued",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = if (sessionExtraApps.isEmpty()) OnSurface2 else Purple80
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    showSessionAppPicker = true
                                    if (scannedApps.isEmpty()) {
                                        scope.launch {
                                            val running = withContext(Dispatchers.IO) { InstalledAppsScanner.getRunningApps() }
                                            val curated = withContext(Dispatchers.IO) { InstalledAppsScanner.getCuratedApps() }
                                            val runningNames = running.map { it.processName }.toSet()
                                            scannedApps = running + curated.filter { it.processName !in runningNames }
                                        }
                                    }
                                },
                                border          = androidx.compose.foundation.BorderStroke(1.dp, Purple80.copy(alpha = 0.5f)),
                                contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Apps, null, tint = Purple80, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (sessionExtraApps.isEmpty()) "Pick apps" else "Edit (${sessionExtraApps.size})",
                                    color    = Purple80,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        if (sessionExtraApps.isNotEmpty()) {
                            Row(
                                modifier              = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                sessionExtraApps.forEach { proc ->
                                    val dName = InstalledAppsScanner.friendlyNameFor(proc)
                                    InputChip(
                                        selected     = true,
                                        onClick      = { sessionExtraApps = sessionExtraApps - proc },
                                        label        = { Text(dName, fontSize = 11.sp) },
                                        trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp)) },
                                        colors       = InputChipDefaults.inputChipColors(
                                            selectedContainerColor    = Purple80.copy(alpha = 0.15f),
                                            selectedLabelColor        = Purple80,
                                            selectedTrailingIconColor = Purple80
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Standalone block / always-on panel ────────────────────────────
            StandaloneBlockPanel(
                isActive             = isStandaloneActive,
                remainingMs          = standaloneRemaining,
                blockedCount         = standaloneBlock?.processNames?.size ?: 0,
                alwaysOnEnabled      = alwaysOnEnabled,
                blockRulesCount      = blockRulesCount,
                scheduleCount        = scheduleCount,
                allowanceLoadState   = allowanceLoadState,
                onOpenAllowances     = onOpenAllowances,
                onRetryAllowances    = ::reloadAllowances,
                keywordCount         = keywordCount,
                onStartBlock         = { showStandaloneDialog = true },
                onAddTime            = { StandaloneBlockService.addTime(it * 60_000L) },
                onToggleAlwaysOn     = {
                    alwaysOnEnabled = !alwaysOnEnabled
                    ProcessMonitor.alwaysOnEnabled = alwaysOnEnabled
                    scope.launch(Dispatchers.IO) { Database.setSetting("always_on_enforcement", alwaysOnEnabled.toString()) }
                }
            )

        } else {
            // ── Active session ring ────────────────────────────────────────────
            val progress  = if (sessionState.totalSeconds > 0) sessionState.elapsedSeconds.toFloat() / sessionState.totalSeconds else 0f
            val remaining = sessionState.totalSeconds - sessionState.elapsedSeconds
            val mins = remaining / 60; val secs = remaining % 60

            val ringColorTarget = when {
                sessionState.isPaused -> OnSurface2.copy(alpha = 0.4f)
                remaining <= 60       -> Error.copy(alpha = 0.9f)
                remaining <= 300      -> Warning
                else                  -> Purple80
            }
            val animatedProgress by animateFloatAsState(
                targetValue    = progress,
                animationSpec  = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                label          = "ringProgress"
            )
            val animatedRingColor by animateColorAsState(
                targetValue   = ringColorTarget,
                animationSpec = tween(durationMillis = 600),
                label         = "ringColor"
            )

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 16.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2
                    val center = Offset(size.width / 2, size.height / 2)
                    drawArc(Surface3, -90f, 360f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(animatedRingColor, -90f, 360f * animatedProgress, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%02d:%02d".format(mins, secs), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 52.sp), color = if (sessionState.isPaused) OnSurface2 else animatedRingColor, fontWeight = FontWeight.Bold)
                    Text(sessionState.taskName, style = MaterialTheme.typography.bodyMedium, color = OnSurface2)
                    if (pomodoroMode) { Spacer(Modifier.height(4.dp)); Text("${strings.focusCycleLabel} ${pomodoroState.cycleNumber + 1}", style = MaterialTheme.typography.bodySmall, color = Purple60) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (sessionState.isPaused) {
                    Button(onClick = { FocusSessionService.resume() }, colors = ButtonDefaults.buttonColors(containerColor = Success)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(strings.focusResume)
                    }
                } else {
                    OutlinedButton(onClick = { FocusSessionService.pause() }) {
                        Icon(Icons.Default.Pause, null); Spacer(Modifier.width(6.dp)); Text(strings.focusPause)
                    }
                }
                Button(
                    onClick = {
                        when {
                            focusLockUntilTimer -> { /* timer must expire */ }
                            SessionPin.isSet()  -> showEndPinDialog = true
                            else               -> { FocusSessionService.end(completed = false); if (pomodoroMode) BreakEnforcer.reset() }
                        }
                    },
                    enabled = !focusLockUntilTimer,
                    colors  = ButtonDefaults.buttonColors(containerColor = if (focusLockUntilTimer) OnSurface2.copy(alpha = 0.4f) else Error.copy(alpha = 0.8f))
                ) {
                    Icon(if (focusLockUntilTimer) Icons.Default.Lock else Icons.Default.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (focusLockUntilTimer) strings.focusLocked else strings.focusEndBtn)
                }
            }

            if (focusModeActive) {
                val intensityColor = when (focusIntensity) { "nuclear" -> Error; "deep" -> Warning; else -> Purple80 }
                val intensityLabel = when (focusIntensity) { "nuclear" -> strings.focusNuclearLabel; "deep" -> strings.focusDeepWorkLabel; else -> strings.focusStandardLabel }
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(intensityColor.copy(alpha = 0.15f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Shield, null, tint = intensityColor, modifier = Modifier.size(14.dp))
                    Text("${strings.focusModeLabel} · $intensityLabel", style = MaterialTheme.typography.bodySmall, color = intensityColor, fontWeight = FontWeight.SemiBold)
                }
            }

            val motivationalMessage = when {
                sessionState.isPaused         -> "Session paused — resume when you're ready."
                progress < 0.25f              -> "Just getting started — lock in and go!"
                progress < 0.5f              -> "Good momentum — you're finding your flow."
                progress < 0.75f             -> "More than halfway there — keep the energy up!"
                progress < 0.95f             -> "Almost done — don't slow down now!"
                else                         -> "Final stretch — finish strong!"
            }
            Text(
                motivationalMessage,
                style     = MaterialTheme.typography.bodySmall,
                color     = if (sessionState.isPaused) OnSurface2 else Purple60,
                fontWeight = FontWeight.Medium
            )

            val count = TemptationLogger.getSessionAttempts()
            Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Surface2).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Purple80, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (count == 0) "No blocked app attempts this session" else "$count app attempt${if (count == 1) "" else "s"} blocked", style = MaterialTheme.typography.bodySmall, color = if (count == 0) Success else Warning)
            }

            // Distraction counter
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Warning, null, tint = if (distractionCount == 0) Success else Warning, modifier = Modifier.size(16.dp))
                Text(
                    if (distractionCount == 0) "No distractions logged" else "$distractionCount distraction${if (distractionCount == 1) "" else "s"} logged",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (distractionCount == 0) Success else Warning,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { distractionCount++ },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.focusLog, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Session notes
            OutlinedTextField(
                value        = sessionNotes,
                onValueChange = {
                    sessionNotes = it
                    FocusSessionService.setNotes(it)
                },
                label      = { Text(strings.focusSessionNotes) },
                modifier   = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                maxLines   = 3,
                colors     = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Purple80,
                    unfocusedBorderColor = OnSurface2.copy(alpha = 0.5f)
                )
            )
        }
    }
    FfVerticalScrollbar(
        scrollState = focusScrollState,
        modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
    )
    }

    // ── Session summary dialog ─────────────────────────────────────────────────
    if (lastSummary != null) {
        SessionSummaryDialog(
            summary   = lastSummary!!,
            onDismiss = { FocusSessionService.clearSummary() }
        )
    }

    if (showEndPinDialog) {
        EndSessionPinDialog(
            onDismiss  = { showEndPinDialog = false },
            onVerified = {
                showEndPinDialog = false
                SessionPin.clearForced()
                FocusSessionService.end(completed = false)
                if (pomodoroMode) BreakEnforcer.reset()
            }
        )
    }

    if (showPinRevealDialog) {
        PinRevealDialog(
            pin = generatedPinText,
            onConfirm = {
                showPinRevealDialog = false
                FocusSessionService.start(customTaskName.ifBlank { "Focus Session" }, pendingStartMins, tid = pendingStartTaskId, blockedProcesses = pendingStartApps)
                TemptationLogger.clearSession()
            },
            onDismiss = {
                showPinRevealDialog = false
                SessionPin.clearForced()
            }
        )
    }

    if (showStandaloneDialog) {
        StartStandaloneBlockDialog(
            onDismiss = { showStandaloneDialog = false },
            onStart   = { apps, hours ->
                StandaloneBlockService.start(apps.split(",").map { it.trim() }.filter { it.isNotBlank() }, hours * 3600_000L)
                showStandaloneDialog = false
            }
        )
    }

    if (showSessionAppPicker) {
        SessionAppPickerDialog(
            scannedApps  = scannedApps,
            preSelected  = sessionExtraApps,
            onDismiss    = { showSessionAppPicker = false },
            onConfirm    = { picked ->
                sessionExtraApps = picked
                showSessionAppPicker = false
            }
        )
    }
}

// ── PIN Reveal Dialog ──────────────────────────────────────────────────────────

@Composable
private fun PinRevealDialog(pin: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalizationManager.strings
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Purple80, modifier = Modifier.size(24.dp))
                Text(strings.focusPinTitle, color = OnSurface)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.focusPinBody, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Purple80.copy(alpha = 0.12f))
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        pin,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Purple80,
                        letterSpacing = 4.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Warning.copy(alpha = 0.10f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(16.dp))
                    Text("This PIN is shown only once — a new one is generated each session.", style = MaterialTheme.typography.bodySmall, color = Warning)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.focusPinConfirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )
}

// ── Session Summary Dialog ─────────────────────────────────────────────────────

@Composable
private fun SessionSummaryDialog(summary: SessionSummary, onDismiss: () -> Unit) {
    val strings = LocalizationManager.strings
    val h = summary.actualMinutes / 60
    val m = summary.actualMinutes % 60
    val timeStr = when {
        h > 0  -> "${h}h ${m}m"
        m > 0  -> "${m}m"
        else   -> "< 1m"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (summary.completed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    null,
                    tint   = if (summary.completed) Success else Warning,
                    modifier = Modifier.size(28.dp)
                )
                Text(if (summary.completed) "Session Complete!" else "Session Ended", color = OnSurface)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(summary.taskName, style = MaterialTheme.typography.bodyLarge, color = OnSurface2)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Time box
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(Purple80.copy(alpha = 0.12f)).padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Timer, null, tint = Purple80, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(timeStr, style = MaterialTheme.typography.titleMedium, color = Purple80, fontWeight = FontWeight.Bold)
                        Text(strings.focusFocusedLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }

                    // Blocked attempts box
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(
                                if (summary.blockedAttempts == 0) Success.copy(alpha = 0.10f)
                                else Warning.copy(alpha = 0.10f)
                            ).padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Shield, null,
                            tint = if (summary.blockedAttempts == 0) Success else Warning,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("${summary.blockedAttempts}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (summary.blockedAttempts == 0) Success else Warning,
                            fontWeight = FontWeight.Bold)
                        Text(strings.focusBlockedLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }
                }

                if (summary.completed) {
                    Text(
                        "Keep the streak going — great session!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Success
                    )
                } else {
                    val temptSummary = TemptationLogger.getSessionSummary()
                    if (summary.blockedAttempts > 0) {
                        Text(temptSummary, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text("Done") }
        }
    )
}

// ── Enforcement sub-row ────────────────────────────────────────────────────────

@Composable
private fun EnforcementRow(
    icon:       androidx.compose.ui.graphics.vector.ImageVector,
    label:      String,
    count:      Int,
    countLabel: String,
    subtitle:   String? = null,
    onClick:    (() -> Unit)? = null,
    actionLabel: String? = null
) {
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
    } else {
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                .background(Purple80.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Purple80, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(
                subtitle ?: if (count > 0) "$count $countLabel" else "None configured",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface2
            )
        }
        if (actionLabel != null) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = Purple80)
        }
        if (count > 0) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(Purple80.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.bodySmall,
                    color = Purple80,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
    }
}

// ── Standalone Block Panel ─────────────────────────────────────────────────────

@Composable
private fun StandaloneBlockPanel(
    isActive:             Boolean,
    remainingMs:          Long,
    blockedCount:         Int,
    alwaysOnEnabled:      Boolean,
    blockRulesCount:      Int,
    scheduleCount:        Int,
    allowanceLoadState: AllowanceLoadState<AllowanceUsageSummary>,
    onOpenAllowances: () -> Unit,
    onRetryAllowances: () -> Unit,
    keywordCount:         Int,
    onStartBlock:         () -> Unit,
    onAddTime:            (Int) -> Unit,
    onToggleAlwaysOn:     () -> Unit
) {
    val strings = LocalizationManager.strings
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Header row with master toggle ──────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = if (alwaysOnEnabled || isActive) Warning else OnSurface2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Always-On Enforcement", style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    Text(
                        if (alwaysOnEnabled) "Active — blocking 24/7 outside sessions"
                        else "Paused — apps are not being blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (alwaysOnEnabled) Warning else OnSurface2
                    )
                }
            }
            Switch(
                checked = alwaysOnEnabled,
                onCheckedChange = { onToggleAlwaysOn() },
                colors = SwitchDefaults.colors(checkedThumbColor = Warning, checkedTrackColor = Warning.copy(alpha = 0.4f))
            )
        }

        HorizontalDivider(color = Surface3)

        // ── Android-style 4 enforcement sub-rows ──────────────────────────────
        EnforcementRow(Icons.Default.Block,    "Always-On App List", blockRulesCount,      "app${if (blockRulesCount == 1) "" else "s"}")
        val allowanceSummary = if (allowanceLoadState is AllowanceLoadState.Failed) {
            null
        } else {
            allowanceLoadState.lastKnownValue()
        }
        val allowanceSubtitle = allowanceSummaryLabel(
            state = allowanceLoadState,
            format = strings.blockerAllowanceSummaryFormat,
            loadingText = strings.blockerLoading,
            unavailableText = strings.blockerLoadFailed
        )
        EnforcementRow(
            icon = Icons.Default.Timer,
            label = strings.blockerTabDailyAllowance,
            count = allowanceSummary?.appCount ?: 0,
            countLabel = "",
            subtitle = allowanceSubtitle,
            onClick = onOpenAllowances,
            actionLabel = strings.blockerManage
        )
        if (allowanceLoadState !is AllowanceLoadState.Loaded) {
            AllowanceLoadBanner(
                loading = allowanceLoadState is AllowanceLoadState.Loading,
                failed = allowanceLoadState is AllowanceLoadState.Failed,
                onRetry = onRetryAllowances
            )
        }
        EmergencyBreakAllowanceNotice()
        EnforcementRow(Icons.Default.Schedule, "Block Schedules",    scheduleCount,        "schedule${if (scheduleCount == 1) "" else "s"}")
        EnforcementRow(Icons.Default.Search,   "Keyword Blocker",    keywordCount,         "keyword${if (keywordCount == 1) "" else "s"}")

        HorizontalDivider(color = Surface3)

        if (isActive) {
            val remSec = (remainingMs / 1000).toInt()
            val h = remSec / 3600; val m = (remSec % 3600) / 60; val s = remSec % 60
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Error.copy(alpha = 0.08f)).padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Block, null, tint = Error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("$blockedCount app${if (blockedCount == 1) "" else "s"} ${strings.focusAppsBlockedLabel}", color = Error, fontWeight = FontWeight.Bold)
                        Text(if (h > 0) "${h}h ${m}m remaining" else "${m}m ${s}s remaining", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }
                }
            }
            Text("Add time to extend the block:", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "+30m", 60 to "+1h", 120 to "+2h", 240 to "+4h").forEach { (mins, label) ->
                    OutlinedButton(
                        onClick = { onAddTime(mins) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                    ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } else {
            Text(strings.focusQuickBlockDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartBlock, colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.85f))) {
                    Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.focusStartBlock, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (scheduleCount > 0) {
            HorizontalDivider(color = Surface3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = Purple60, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("$scheduleCount ${strings.focusActiveSchedules}", style = MaterialTheme.typography.bodySmall, color = Purple60)
            }
        }
    }
}

@Composable
private fun StartStandaloneBlockDialog(onDismiss: () -> Unit, onStart: (String, Int) -> Unit) {
    val strings = LocalizationManager.strings
    var apps  by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(strings.focusQuickBlockTitle, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.focusEnterProcessNamesDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                OutlinedTextField(
                    value = apps, onValueChange = { apps = it },
                    label = { Text(strings.focusProcessLabel) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Error, unfocusedBorderColor = OnSurface2)
                )
                Text("Duration", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4, 8).forEach { h ->
                        FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("${h}h") })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (apps.isNotBlank()) onStart(apps, hours) }, colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.85f))) { Text(strings.focusStartBlock) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )
}

// ── End Session PIN Dialog ─────────────────────────────────────────────────────

@Composable
private fun EndSessionPinDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
    val strings = LocalizationManager.strings
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Warning, modifier = Modifier.size(22.dp))
                Text(strings.defPinRequired, color = OnSurface)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.dashEnterPinEarly, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                OutlinedTextField(
                    value         = pin,
                    onValueChange = { pin = it; error = false },
                    label         = { Text(strings.defPinLabel) },
                    modifier      = Modifier.fillMaxWidth(),
                    isError       = error,
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Purple80,
                        unfocusedBorderColor = OnSurface2,
                        errorBorderColor     = Error
                    )
                )
                if (error) {
                    Text(strings.dashIncorrectPinRetry, color = Error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (SessionPin.verify(pin)) onVerified()
                    else error = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.85f))
            ) { Text(strings.focusEndBtn) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )
}

// ── Session App Picker Dialog ──────────────────────────────────────────────────
@Composable
private fun SessionAppPickerDialog(
    scannedApps: List<ScannedApp>,
    preSelected: Set<String>,
    onDismiss:   () -> Unit,
    onConfirm:   (Set<String>) -> Unit
) {
    var selected by remember { mutableStateOf(preSelected) }
    var search   by remember { mutableStateOf("") }
    var showAll  by remember { mutableStateOf(false) }

    val runningApps = remember(scannedApps) { scannedApps.filter { it.isRunning } }
    val sourceList  = if (showAll) scannedApps else runningApps
    val filtered    = remember(search, sourceList) {
        if (search.isBlank()) sourceList
        else sourceList.filter {
            it.displayName.contains(search, ignoreCase = true) ||
            it.processName.contains(search, ignoreCase = true)
        }
    }
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        modifier         = Modifier.width(500.dp),
        shape            = RoundedCornerShape(20.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Apps, null, tint = Purple80, modifier = Modifier.size(20.dp))
                    Text("Extra apps to block this session", color = OnSurface, fontWeight = FontWeight.Bold)
                }
                Text(
                    "These apps will only be blocked for the duration of this session — not added to your permanent block list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
                OutlinedTextField(
                    value         = search,
                    onValueChange = { search = it },
                    placeholder   = { Text("Search by name or .exe…", color = OnSurface2, fontSize = 12.sp) },
                    leadingIcon   = { Icon(Icons.Default.Search, null, tint = OnSurface2, modifier = Modifier.size(16.dp)) },
                    trailingIcon  = if (search.isNotBlank()) {
                        { IconButton(onClick = { search = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = OnSurface2, modifier = Modifier.size(14.dp))
                        } }
                    } else null,
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Purple80,
                        unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f)
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !showAll,
                        onClick  = { showAll = false },
                        label    = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Success))
                                Text("Running (${runningApps.size})", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Success.copy(alpha = 0.15f),
                            selectedLabelColor     = Success
                        )
                    )
                    FilterChip(
                        selected = showAll,
                        onClick  = { showAll = true },
                        label    = { Text("All apps (${scannedApps.size})", style = MaterialTheme.typography.labelSmall) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple80.copy(alpha = 0.15f),
                            selectedLabelColor     = Purple80
                        )
                    )
                    if (selected.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall,
                            color = Purple80, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        text = {
            Box(modifier = Modifier.height(340.dp)) {
                if (scannedApps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Purple80)
                            Text("Scanning apps…", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        }
                    }
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (!showAll) "No running apps detected — switch to All apps"
                            else "No apps match \"$search\"",
                            style     = MaterialTheme.typography.bodySmall,
                            color     = OnSurface2,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(filtered, key = { i, it -> "${it.processName}_$i" }) { _, app ->
                            val isSel = app.processName in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) Purple80.copy(alpha = 0.12f) else Surface3)
                                    .clickable {
                                        selected = if (isSel) selected - app.processName else selected + app.processName
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked         = isSel,
                                    onCheckedChange = {
                                        selected = if (isSel) selected - app.processName else selected + app.processName
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Purple80),
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(app.displayName, color = if (isSel) Purple80 else OnSurface,
                                            fontSize = 13.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal)
                                        if (app.isRunning) {
                                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Success))
                                        }
                                    }
                                    Text(app.processName, style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface2, fontSize = 10.sp)
                                }
                                if (isSel) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Purple80, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = { onConfirm(selected) },
                colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) {
                Text(if (selected.isEmpty()) "No extra apps" else "Block ${selected.size} app${if (selected.size == 1) "" else "s"} this session")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurface2) }
        }
    )
}

@Composable
private fun PomodoroCycleIndicator(cycleNumber: Int, cyclesBeforeLong: Int) {
    val position = cycleNumber % cyclesBeforeLong
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Autorenew, null, tint = Purple80, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        (0 until cyclesBeforeLong).forEach { i ->
            val dotColorTarget = when {
                i < position  -> Purple80
                i == position -> Purple80.copy(alpha = 0.55f)
                else          -> Surface3
            }
            val dotColor by animateColorAsState(
                targetValue   = dotColorTarget,
                animationSpec = tween(durationMillis = 400),
                label         = "dot$i"
            )
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        }
        Spacer(Modifier.width(4.dp))
        Text(if (position == 0 && cycleNumber > 0) "Cycle ${cycleNumber / cyclesBeforeLong + 1}" else "Session ${position + 1}/${cyclesBeforeLong}", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
    }
}
