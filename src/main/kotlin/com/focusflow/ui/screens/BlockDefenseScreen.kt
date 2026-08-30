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
import com.focusflow.data.models.BlockSchedule
import com.focusflow.data.models.hasValidTimeRange
import com.focusflow.data.models.isActiveAt
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.BlockScheduleService
import com.focusflow.services.GlobalPin
import com.focusflow.services.SessionPin
import com.focusflow.ui.components.BlockScheduleEditorDialog
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
    var globalPinSet     by remember { mutableStateOf(false) }
    var blockSchedules   by remember { mutableStateOf(listOf<BlockSchedule>()) }

    var showAddSchedule    by remember { mutableStateOf(false) }
    var scheduleBeingEdited by remember { mutableStateOf<BlockSchedule?>(null) }
    var showPinGate        by remember { mutableStateOf(false) }
    var pendingPinAction   by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showGlobalPinDialog by remember { mutableStateOf(false) }
    var showVpnInfo        by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                alwaysOn      = Database.getSetting("always_on_enforcement") == "true"
                vpnEnabled    = Database.getSetting("vpn_block_enabled") == "true"
                soundAversion = Database.getSetting("sound_aversion") == "true"
                temptationLog = Database.getSetting("temptation_log") == "true"
                globalPinSet = GlobalPin.isSet()
                blockSchedules = Database.getBlockSchedules()
            }
        }
    }

    fun withGlobalPin(action: () -> Unit) {
        if (globalPinSet) {
            pendingPinAction = action
            showPinGate = true
        } else {
            action()
        }
    }

    fun saveAlwaysOn(enabled: Boolean) {
        alwaysOn = enabled
        ProcessMonitor.alwaysOnEnabled = enabled
        scope.launch {
            withContext(Dispatchers.IO) {
                Database.setSetting("always_on_enforcement", enabled.toString())
            }
        }
        if (!enabled) {
            com.focusflow.services.ReviewPromptService.triggerCheck()
        }
    }

    fun saveSchedule(schedule: BlockSchedule) {
        scope.launch {
            withContext(Dispatchers.IO) { Database.upsertBlockSchedule(schedule) }
            BlockScheduleService.forceCheck()
            scheduleBeingEdited = null
            showAddSchedule = false
            reload()
        }
    }

    fun setScheduleEnabled(schedule: BlockSchedule, enabled: Boolean) {
        val action = { saveSchedule(schedule.copy(enabled = enabled)) }
        if (enabled) action() else withGlobalPin(action)
    }

    fun deleteSchedule(schedule: BlockSchedule) {
        withGlobalPin {
            scope.launch {
                withContext(Dispatchers.IO) { Database.deleteBlockSchedule(schedule.id) }
                BlockScheduleService.forceCheck()
                reload()
            }
        }
    }

    fun openScheduleEditor(schedule: BlockSchedule) {
        withGlobalPin { scheduleBeingEdited = schedule }
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

        // ── Always-On Enforcement ───────────────────────────────────────────────
        DefCard(title = strings.defAlwaysOnEnforcement) {
            DefToggleRow(
                label   = strings.defAlwaysOnEnforcement,
                checked = alwaysOn,
                icon    = Icons.Default.Shield,
                iconColor = if (alwaysOn) Success else OnSurface2
            ) { newVal ->
                if (!newVal && globalPinSet) {
                    withGlobalPin { saveAlwaysOn(false) }
                } else {
                    saveAlwaysOn(newVal)
                }
            }

            Spacer(Modifier.height(6.dp))

            DefToggleRow(
                label   = "Global PIN Lock",
                checked = globalPinSet,
                icon    = Icons.Default.Lock,
                iconColor = if (globalPinSet) Warning else OnSurface2,
                enabled = false
            ) {}

            Text(
                if (globalPinSet) "Required to turn Always-On Enforcement off and remove protected blocks."
                else "Set a Global PIN in Settings to protect Always-On Enforcement from being disabled.",
                color = OnSurface2,
                style = MaterialTheme.typography.bodySmall
            )

            TextButton(
                onClick = onNavigateToAppBlocker,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
            )
            {
                Icon(Icons.Default.Apps, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Manage blocked apps →", color = Purple80)
            }
        }

        // ── Global PIN ──────────────────────────────────────────────────────────
        DefCard(title = "Global PIN protection") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    null,
                    tint = if (globalPinSet) Warning else OnSurface2,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (globalPinSet) "Global PIN is active" else "Global PIN is not set",
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (globalPinSet) {
                            "Required to disable or remove protected schedules, blocks, and enforcement settings."
                        } else {
                            "Protect schedules, blocks, and enforcement settings from being disabled."
                        },
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = { showGlobalPinDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (globalPinSet) Error.copy(alpha = 0.85f) else Purple80
                    )
                ) {
                    Text(if (globalPinSet) "Change / Clear" else "Set PIN")
                }
            }
        }

        // ── Block Schedules ────────────────────────────────────────────────────
        DefCard(title = strings.defBlockSchedules) {
            Text(
                "Choose the apps to block and the recurring hours when they should be blocked. " +
                    "This uses the same process enforcement as Always-On during the schedule.",
                color = OnSurface2,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            if (blockSchedules.isEmpty()) {
                Text(strings.defNoSchedules, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val now = LocalDateTime.now()
                    blockSchedules.forEach { schedule ->
                        val validTimeRange = schedule.hasValidTimeRange()
                        val activeNow = schedule.isActiveAt(now)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (activeNow) Warning.copy(alpha = 0.1f) else Surface3)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        schedule.name,
                                        color = OnSurface,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (activeNow) {
                                        Text(
                                            strings.defScheduleActive,
                                            color = Warning,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                                val dayStr = schedule.daysOfWeek.mapNotNull { days.getOrNull(it - 1) }.joinToString(", ")
                                Text(
                                    "$dayStr  %02d:%02d–%02d:%02d · ${schedule.processNames.size} app${if (schedule.processNames.size == 1) "" else "s"}%s".format(
                                        schedule.startHour,
                                        schedule.startMinute,
                                        schedule.endHour,
                                        schedule.endMinute,
                                        if (validTimeRange) "" else " · Invalid time"
                                    ),
                                    color = if (validTimeRange) OnSurface2 else Error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(
                                onClick = { openScheduleEditor(schedule) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Edit, "Edit schedule", tint = OnSurface2, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { deleteSchedule(schedule) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Delete schedule", tint = Error, modifier = Modifier.size(16.dp))
                            }
                            Switch(
                                checked = schedule.enabled,
                                onCheckedChange = { setScheduleEnabled(schedule, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Surface,
                                    checkedTrackColor = Warning
                                )
                            )
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

        // ── VPN & Network Shield ───────────────────────────────────────────────
        DefCard(title = strings.defVpnSection) {
            DefToggleRow(
                label     = strings.vpnShieldLabel,
                checked   = vpnEnabled,
                icon      = Icons.Default.VpnKey,
                iconColor = if (vpnEnabled) Purple80 else OnSurface2,
                enabled   = true
            ) { showVpnInfo = true }
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
            title    = "Global PIN required",
            subtitle = "Enter your Global PIN to change protected enforcement settings.",
            onDismiss = {
                showPinGate = false
                pendingPinAction = null
            },
            onVerified = {
                showPinGate = false
                pendingPinAction?.invoke()
                pendingPinAction = null
            }
        )
    }

    if (showGlobalPinDialog) {
        GlobalPinManageDialog(
            pinAlreadySet = globalPinSet,
            onDismiss = { showGlobalPinDialog = false },
            onChanged = {
                showGlobalPinDialog = false
                globalPinSet = GlobalPin.isSet()
                reload()
            }
        )
    }

    if (showVpnInfo) {
        AlertDialog(
            onDismissRequest = { showVpnInfo = false },
            containerColor = Surface2,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.VpnKey, null, tint = Error, modifier = Modifier.size(22.dp))
                    Text("VPN Shield is managed separately", color = OnSurface)
                }
            },
            text = {
                Text(
                    "This switch is shown here for status only. Open VPN & Network Shield to enable it. " +
                        "VPN blocking also needs FocusFlow to run as Administrator on Windows.",
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVpnInfo = false
                        onNavigateToVpn()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) { Text("Open VPN & Network") }
            },
            dismissButton = {
                TextButton(onClick = { showVpnInfo = false }) {
                    Text(strings.btnCancel, color = OnSurface2)
                }
            }
        )
    }

    // ── Schedule editor dialogs ────────────────────────────────────────────────
    if (showAddSchedule) {
        BlockScheduleEditorDialog(
            onDismiss = { showAddSchedule = false },
            onSave = ::saveSchedule
        )
    }
    scheduleBeingEdited?.let { schedule ->
        BlockScheduleEditorDialog(
            initialSchedule = schedule,
            onDismiss = { scheduleBeingEdited = null },
            onSave = ::saveSchedule
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
