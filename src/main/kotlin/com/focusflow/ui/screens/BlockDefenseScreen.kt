package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusflow.data.Database
import com.focusflow.data.models.BlockRule
import com.focusflow.data.models.BlockSchedule
import com.focusflow.data.models.hasValidTimeRange
import com.focusflow.data.models.isActiveAt
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.BlockScheduleService
import com.focusflow.services.GlobalPin
import com.focusflow.services.SessionPin
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Composable
fun BlockDefenseScreen(onNavigateToVpn: () -> Unit = {}, onNavigateToAppBlocker: () -> Unit = {}) {
    val strings = LocalizationManager.strings
    val scope = rememberCoroutineScope()

    var alwaysOn         by remember { mutableStateOf(false) }
    var vpnEnabled       by remember { mutableStateOf(false) }
    var soundAversion    by remember { mutableStateOf(false) }
    var temptationLog    by remember { mutableStateOf(false) }
    var alwaysOnRules    by remember { mutableStateOf(listOf<BlockRule>()) }
    var blockSchedules   by remember { mutableStateOf(listOf<BlockSchedule>()) }
    var overlayMsg       by remember { mutableStateOf("") }

    var showAddSchedule  by remember { mutableStateOf(false) }
    var showPinGate      by remember { mutableStateOf(false) }
    var pendingAlwaysOn  by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                alwaysOn      = Database.getSetting("always_on_enforcement") == "true"
                vpnEnabled    = Database.getSetting("vpn_enabled") == "true"
                soundAversion = Database.getSetting("sound_aversion") == "true"
                temptationLog = Database.getSetting("temptation_log") == "true"
                alwaysOnRules = Database.getBlockRules().filter { it.enabled }
                blockSchedules = Database.getBlockSchedules()
                overlayMsg    = Database.getSetting("overlay_message") ?: ""
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(Surface)
            .verticalScroll(scrollState).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(strings.defTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)

        // ── System Protection ──────────────────────────────────────────────────
        DefCard(title = strings.defSystemProtection) {
            DefToggleRow(
                label   = strings.defAlwaysOnEnforcement,
                checked = alwaysOn,
                icon    = Icons.Default.Shield,
                iconColor = if (alwaysOn) Success else OnSurface2
            ) { newVal ->
                if (!newVal && GlobalPin.isSet()) {
                    pendingAlwaysOn = false
                    showPinGate = true
                } else {
                    alwaysOn = newVal
                    ProcessMonitor.alwaysOnEnabled = newVal
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Database.setSetting("always_on_enforcement", newVal.toString())
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            DefToggleRow(
                label   = strings.defSessionPinLock,
                checked = SessionPin.isSet(),
                icon    = Icons.Default.Lock,
                iconColor = if (SessionPin.isSet()) Warning else OnSurface2,
                enabled = false
            ) {}

            Spacer(Modifier.height(10.dp))

            // Block overlay
            Text(strings.defOverlayMessage, color = OnSurface2, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = overlayMsg,
                onValueChange = { overlayMsg = it
                    scope.launch { withContext(Dispatchers.IO) { Database.setSetting("overlay_message", it) } }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2
                )
            )
        }

        // ── VPN & Network Shield ───────────────────────────────────────────────
        DefCard(title = strings.defVpnSection) {
            DefToggleRow(
                label     = strings.vpnShieldLabel,
                checked   = vpnEnabled,
                icon      = Icons.Default.VpnKey,
                iconColor = if (vpnEnabled) Purple80 else OnSurface2,
                enabled   = false
            ) {}
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNavigateToVpn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.defOpenVpn)
            }
        }

        // ── Aversion Deterrents ────────────────────────────────────────────────
        DefCard(title = strings.defAversionSection) {
            DefToggleRow(
                label     = strings.defSoundAversion,
                checked   = soundAversion,
                icon      = Icons.AutoMirrored.Filled.VolumeUp,
                iconColor = if (soundAversion) Warning else OnSurface2
            ) { newVal ->
                soundAversion = newVal
                scope.launch { withContext(Dispatchers.IO) { Database.setSetting("sound_aversion", newVal.toString()) } }
            }
            Spacer(Modifier.height(6.dp))
            DefToggleRow(
                label     = strings.defTemptationLog,
                checked   = temptationLog,
                icon      = Icons.Default.History,
                iconColor = if (temptationLog) Purple80 else OnSurface2
            ) { newVal ->
                temptationLog = newVal
                scope.launch { withContext(Dispatchers.IO) { Database.setSetting("temptation_log", newVal.toString()) } }
            }
        }

        // ── Always-On Block List ───────────────────────────────────────────────
        DefCard(title = strings.defAlwaysOnList) {
            if (alwaysOnRules.isEmpty()) {
                Text(strings.defNoAppsBlocked, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    alwaysOnRules.take(8).forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Surface3).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(rule.displayName, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                                Text(rule.processName, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                            }
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(Success.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(strings.defAlwaysOnTag, style = MaterialTheme.typography.bodySmall, color = Success, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (alwaysOnRules.size > 8) {
                        Text("+ ${alwaysOnRules.size - 8} more…", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = onNavigateToAppBlocker,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Edit in App Blocker →")
            }
        }

        // ── Block Schedules ────────────────────────────────────────────────────
        DefCard(title = strings.defBlockSchedules) {
            if (blockSchedules.isEmpty()) {
                Text(strings.defNoSchedules, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val now = LocalDateTime.now()
                    blockSchedules.forEach { sched ->
                        val validTimeRange = sched.hasValidTimeRange()
                        val activeNow = sched.isActiveAt(now)
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (activeNow) Warning.copy(alpha = 0.1f) else Surface3)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(sched.name, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                                val days = listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
                                val dayStr = sched.daysOfWeek.mapNotNull { days.getOrNull(it-1) }.joinToString(", ")
                                Text(
                                    "$dayStr  %02d:%02d–%02d:%02d%s".format(
                                        sched.startHour, sched.startMinute,
                                        sched.endHour, sched.endMinute,
                                        if (validTimeRange) "" else " · Invalid time"
                                    ),
                                    color = if (validTimeRange) OnSurface2 else Error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (activeNow) {
                                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(Warning.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(strings.defScheduleActive, color = Warning, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showAddSchedule = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.defAddSchedule, color = Purple80)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
    FfVerticalScrollbar(
        scrollState = scrollState,
        modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
    )
    }

    // ── PIN gate to turn off Always-On ─────────────────────────────────────────
    if (showPinGate) {
        PinGateDialog(
            title    = strings.defPinRequired,
            subtitle = strings.defEnterPin,
            onDismiss = { showPinGate = false },
            onVerified = {
                showPinGate = false
                alwaysOn = false
                ProcessMonitor.alwaysOnEnabled = false
                scope.launch { withContext(Dispatchers.IO) { Database.setSetting("always_on_enforcement", "false") } }
                com.focusflow.services.ReviewPromptService.triggerCheck()
            }
        )
    }

    // ── Add schedule dialog ─────────────────────────────────────────────────────
    if (showAddSchedule) {
        AddScheduleDialogBD(
            onDismiss = { showAddSchedule = false },
            onSave    = { sched ->
                scope.launch {
                    withContext(Dispatchers.IO) { Database.upsertBlockSchedule(sched) }
                    BlockScheduleService.forceCheck()
                    reload()
                    showAddSchedule = false
                }
            }
        )
    }
}

@Composable
private fun DefCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Surface2).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DefToggleRow(
    label: String,
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            Text(label, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else { _ -> },
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Surface, checkedTrackColor = Purple80)
        )
    }
}

@Composable
private fun PinGateDialog(title: String, subtitle: String, onDismiss: () -> Unit, onVerified: () -> Unit) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lock, null, tint = Warning, modifier = Modifier.size(22.dp))
                Text(title, color = OnSurface)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(subtitle, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it; error = false },
                    label = { Text(LocalizationManager.strings.defPinLabel) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = error,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2, errorBorderColor = Error
                    )
                )
                if (error) Text(LocalizationManager.strings.defIncorrectPin, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { GlobalPin.verify(pin) }
                        if (ok) onVerified() else error = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(LocalizationManager.strings.btnSave) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) }
        }
    )
}

@Composable
private fun AddScheduleDialogBD(onDismiss: () -> Unit, onSave: (BlockSchedule) -> Unit) {
    val strings  = LocalizationManager.strings
    val days     = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    var name     by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf("Mon", "Tue", "Wed", "Thu", "Fri")) }
    var startH   by remember { mutableStateOf("9") }
    var startM   by remember { mutableStateOf("0") }
    var endH     by remember { mutableStateOf("17") }
    var endM     by remember { mutableStateOf("0") }
    var validationError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title = { Text(strings.defAddBlockSchedule, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; validationError = false },
                    label = { Text(strings.defScheduleName) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                )
                Text(strings.defDaysLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { d ->
                        FilterChip(
                            selected = d in selected,
                            onClick  = {
                                selected = if (d in selected) selected - d else selected + d
                            },
                            label    = { Text(d, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startH, onValueChange = { startH = it.filter(Char::isDigit).take(2); validationError = false },
                        label = { Text(strings.defStartH) }, modifier = Modifier.weight(1f), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2))
                    OutlinedTextField(value = startM, onValueChange = { startM = it.filter(Char::isDigit).take(2); validationError = false },
                        label = { Text(strings.defStartM) }, modifier = Modifier.weight(1f), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2))
                    OutlinedTextField(value = endH, onValueChange = { endH = it.filter(Char::isDigit).take(2); validationError = false },
                        label = { Text(strings.defEndH) }, modifier = Modifier.weight(1f), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2))
                    OutlinedTextField(value = endM, onValueChange = { endM = it.filter(Char::isDigit).take(2); validationError = false },
                        label = { Text(strings.defEndM) }, modifier = Modifier.weight(1f), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2))
                }
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
            Button(
                onClick = {
                    val startHourValue = startH.toIntOrNull()
                    val startMinuteValue = startM.toIntOrNull()
                    val endHourValue = endH.toIntOrNull()
                    val endMinuteValue = endM.toIntOrNull()
                    val valid = name.isNotBlank() &&
                        selected.isNotEmpty() &&
                        startHourValue != null && startMinuteValue != null &&
                        endHourValue != null && endMinuteValue != null &&
                        startHourValue in 0..23 &&
                        startMinuteValue in 0..59 &&
                        endHourValue in 0..24 &&
                        endMinuteValue in 0..59 &&
                        (endHourValue != 24 || endMinuteValue == 0)

                    if (valid) {
                        onSave(BlockSchedule(
                            id          = java.util.UUID.randomUUID().toString(),
                            name        = name,
                            daysOfWeek  = selected.map { days.indexOf(it) + 1 }.sorted(),
                            startHour   = startHourValue!!,
                            startMinute = startMinuteValue!!,
                            endHour     = endHourValue!!,
                            endMinute   = endMinuteValue!!,
                            enabled     = true
                        ))
                    } else {
                        validationError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(strings.btnSave) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )
}
