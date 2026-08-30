package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusflow.data.models.BlockSchedule
import com.focusflow.data.models.hasValidTimeRange
import com.focusflow.enforcement.InstalledAppsScanner
import com.focusflow.enforcement.ScannedApp
import com.focusflow.i18n.LocalizationManager
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Creates a recurring process-block schedule with the same app-selection
 * experience used by Always-On and Daily Allowances.
 *
 * A schedule is intentionally a process block, not a browser URL rule:
 * selected applications are killed while the schedule is active.
 */
@Composable
fun BlockScheduleEditorDialog(
    initialSchedule: BlockSchedule? = null,
    onDismiss: () -> Unit,
    onSave: (BlockSchedule) -> Unit
) {
    val strings = LocalizationManager.strings
    var name by remember { mutableStateOf(initialSchedule?.name ?: "") }
    var startHour by remember { mutableStateOf((initialSchedule?.startHour ?: 9).toString()) }
    var startMinute by remember { mutableStateOf((initialSchedule?.startMinute ?: 0).toString()) }
    var endHour by remember { mutableStateOf((initialSchedule?.endHour ?: 17).toString()) }
    var endMinute by remember { mutableStateOf((initialSchedule?.endMinute ?: 0).toString()) }
    var selectedDays by remember {
        mutableStateOf(initialSchedule?.daysOfWeek?.toSet() ?: setOf(1, 2, 3, 4, 5))
    }
    var selectedProcesses by remember {
        mutableStateOf(initialSchedule?.processNames?.map { it.lowercase() }?.toSet() ?: emptySet())
    }
    var customProcesses by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(listOf<ScannedApp>()) }
    var validationError by remember { mutableStateOf("") }
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            InstalledAppsScanner.getCuratedApps()
        }
    }

    val visibleApps = apps.filter {
        searchQuery.isBlank() ||
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.processName.contains(searchQuery, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Schedule, null, tint = Purple80, modifier = Modifier.size(22.dp))
                Text(
                    if (initialSchedule == null) strings.settingsAddBlockSchedule else "Edit block schedule",
                    color = OnSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; validationError = "" },
                    label = { Text(strings.settingsScheduleName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple80,
                        unfocusedBorderColor = OnSurface2
                    )
                )

                Text(strings.settingsDaysOfWeek, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    days.forEachIndexed { index, label ->
                        val day = index + 1
                        FilterChip(
                            selected = day in selectedDays,
                            onClick = {
                                selectedDays = if (day in selectedDays) {
                                    selectedDays - day
                                } else {
                                    selectedDays + day
                                }
                                validationError = ""
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Text(
                    "Block selected apps during this recurring window.",
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Apps, null, tint = Purple80, modifier = Modifier.size(17.dp))
                    Text(
                        "${selectedProcesses.size} app${if (selectedProcesses.size == 1) "" else "s"} selected",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search installed apps") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple80,
                        unfocusedBorderColor = OnSurface2
                    )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 230.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface3)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (visibleApps.isEmpty()) {
                        Text(
                            "No installed apps found. Add a process name below.",
                            color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        visibleApps.forEach { app ->
                            val selected = app.processName.lowercase() in selectedProcesses
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedProcesses = if (selected) {
                                            selectedProcesses - app.processName.lowercase()
                                        } else {
                                            selectedProcesses + app.processName.lowercase()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        selectedProcesses = if (selected) {
                                            selectedProcesses - app.processName.lowercase()
                                        } else {
                                            selectedProcesses + app.processName.lowercase()
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Purple80)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.displayName, color = OnSurface, style = MaterialTheme.typography.bodySmall)
                                    Text(app.processName, color = OnSurface2, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = customProcesses,
                    onValueChange = { customProcesses = it; validationError = "" },
                    label = { Text("Additional process names (optional)") },
                    placeholder = { Text("example.exe, another.exe") },
                    supportingText = { Text("Use this for an app not found in the installed-app list.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple80,
                        unfocusedBorderColor = OnSurface2
                    )
                )

                Text("Start and end time", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startHour,
                        onValueChange = { startHour = it.filter(Char::isDigit).take(2); validationError = "" },
                        label = { Text(strings.settingsStartHr) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = startMinute,
                        onValueChange = { startMinute = it.filter(Char::isDigit).take(2); validationError = "" },
                        label = { Text(strings.settingsStartMin) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endHour,
                        onValueChange = { endHour = it.filter(Char::isDigit).take(2); validationError = "" },
                        label = { Text(strings.settingsEndHr) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endMinute,
                        onValueChange = { endMinute = it.filter(Char::isDigit).take(2); validationError = "" },
                        label = { Text(strings.settingsEndMin) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                if (validationError.isNotBlank()) {
                    Text(validationError, color = Error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sh = startHour.toIntOrNull()
                    val sm = startMinute.toIntOrNull()
                    val eh = endHour.toIntOrNull()
                    val em = endMinute.toIntOrNull()
                    val custom = customProcesses
                        .split(",", "\n")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }
                        .map { if (it.endsWith(".exe")) it else "$it.exe" }
                    val processes = (selectedProcesses + custom).toList().distinct()
                    val schedule = if (sh != null && sm != null && eh != null && em != null) {
                        BlockSchedule(
                            id = initialSchedule?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            daysOfWeek = selectedDays.toList().sorted(),
                            startHour = sh,
                            startMinute = sm,
                            endHour = eh,
                            endMinute = em,
                            enabled = initialSchedule?.enabled ?: true,
                            processNames = processes
                        )
                    } else null

                    when {
                        schedule == null -> validationError = "Enter valid start and end times."
                        schedule.name.isBlank() -> validationError = "Enter a schedule name."
                        schedule.daysOfWeek.isEmpty() -> validationError = "Select at least one day."
                        schedule.processNames.isEmpty() -> validationError = "Select at least one app to block."
                        !schedule.hasValidTimeRange() ->
                            validationError = "Use hours 0–23 and minutes 0–59. End time may be 24:00."
                        else -> onSave(schedule)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) {
                Text(if (initialSchedule == null) strings.btnAdd else strings.btnSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.btnCancel, color = OnSurface2)
            }
        }
    )
}