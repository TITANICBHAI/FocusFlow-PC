package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.Task
import com.focusflow.enforcement.InstalledAppsScanner
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.EndChip
import com.focusflow.services.EndDateResult
import com.focusflow.services.SeriesEdit
import com.focusflow.services.SeriesEditScope
import com.focusflow.services.SeriesInfo
import com.focusflow.services.SeriesRole
import com.focusflow.services.TaskSeriesService
import com.focusflow.ui.components.DeleteTaskDialog
import com.focusflow.ui.components.SeriesText
import com.focusflow.ui.components.ShortcutTooltip
import com.focusflow.ui.components.TaskCard
import com.focusflow.ui.theme.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun TasksScreen(onStartFocus: (Task) -> Unit) {
    val strings              = LocalizationManager.strings
    var tasks                by remember { mutableStateOf(listOf<Task>()) }
    var showAdd              by remember { mutableStateOf(false) }
    var editTask             by remember { mutableStateOf<Task?>(null) }
    var searchQuery          by remember { mutableStateOf("") }
    var sortMode             by remember { mutableStateOf("date") }
    var priorityFilter       by remember { mutableStateOf("all") }
    var showCompletedSection by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch { tasks = withContext(Dispatchers.IO) { Database.getTasks() } }
    }
    LaunchedEffect(Unit) { reload() }

    var searchExpanded   by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    fun deleteWithUndo(task: Task, stopSeries: Boolean = false) {
        tasks = tasks.filter { it.id != task.id }
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message      = "\"${task.title}\" deleted",
                actionLabel  = "Undo",
                duration     = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                reload()
            } else {
                withContext(Dispatchers.IO) {
                    if (stopSeries) TaskSeriesService.deleteAndStop(task) else TaskSeriesService.deleteOccurrence(task)
                }
                reload()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize().background(Surface).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
            when (event.key) {
                Key.N -> { showAdd = true; true }
                Key.F -> { try { searchFocusRequester.requestFocus() } catch (_: Throwable) {}; true }
                else  -> false
            }
        } else false
    }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(strings.tasksTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
                    val done  = tasks.count { it.completed }
                    val total = tasks.size
                    if (total > 0) Text("$done/$total ${strings.tasksDoneOf}", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Search icon toggle
                    ShortcutTooltip("Ctrl+F") {
                        IconButton(onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) searchQuery = ""
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search tasks",
                                tint     = if (searchExpanded || searchQuery.isNotEmpty()) Purple80 else OnSurface2,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // Completed-tasks toggle
                    val completedCount = tasks.count { it.completed || it.skipped }
                    if (completedCount > 0) {
                        IconButton(onClick = { showCompletedSection = !showCompletedSection }) {
                            Icon(
                                if (showCompletedSection) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showCompletedSection) "Hide completed" else "Show completed",
                                tint     = if (showCompletedSection) Purple80 else OnSurface2,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    ShortcutTooltip("Ctrl+N") {
                        Button(onClick = { showAdd = true }, colors = ButtonDefaults.buttonColors(containerColor = Purple80)) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.tasksNewTask)
                        }
                    }
                }
            }

            // Search bar — shown only when expanded or query is active
            AnimatedVisibility(visible = searchExpanded || searchQuery.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(strings.tasksSearchHint) },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = OnSurface2, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        IconButton(onClick = { searchQuery = ""; searchExpanded = false }) {
                            Icon(Icons.Default.Close, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)
                )
            }

            // Fix 4: Sort + priority chips collapsed behind a "Filters" toggle
            var showFilters by remember { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected    = showFilters,
                    onClick     = { showFilters = !showFilters },
                    label       = { Text("Filters", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Default.FilterList, null, modifier = Modifier.size(14.dp)) }
                )
                if (!showFilters) {
                    if (priorityFilter != "all") {
                        val pColor = when (priorityFilter) { "high" -> Error; "medium" -> Warning; else -> Success }
                        Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(pColor))
                        Text(priorityFilter, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }
                    if (sortMode != "date") {
                        Text("↑ $sortMode", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }
                }
            }
            AnimatedVisibility(visible = showFilters) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.tasksSortLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        listOf("date" to strings.tasksByDate, "priority" to strings.tasksByPriority, "title" to strings.tasksByTitle).forEach { (mode, label) ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick  = { sortMode = mode },
                                label    = { Text(label, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.tasksPriorityLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        listOf(
                            "all"    to strings.tasksAll,
                            "high"   to strings.tasksHigh,
                            "medium" to strings.tasksMedium,
                            "low"    to strings.tasksLow
                        ).forEach { (p, label) ->
                            FilterChip(
                                selected = priorityFilter == p,
                                onClick  = { priorityFilter = p },
                                label    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = if (p != "all") ({
                                    Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(when (p) { "high" -> Error; "medium" -> Warning; else -> Success }))
                                }) else null
                            )
                        }
                    }
                }
            }

            val today = LocalDate.now()
            val priorityOrder = mapOf("high" to 0, "medium" to 1, "low" to 2)
            val base = run {
                val active = tasks.filter { !it.completed && !it.skipped }
                if (priorityFilter == "all") active else active.filter { it.priority == priorityFilter }
            }
            val filtered = if (searchQuery.isBlank()) base
                           else base.filter { it.title.contains(searchQuery, ignoreCase = true) || it.description.contains(searchQuery, ignoreCase = true) }

            val overdueTasks = filtered.filter { it.scheduledDate != null && it.scheduledDate.isBefore(today) }
            val currentTasks = filtered.filter { it.scheduledDate == null || !it.scheduledDate.isBefore(today) }

            val sortedCurrent = when (sortMode) {
                "priority" -> currentTasks.sortedBy { priorityOrder[it.priority] ?: 1 }
                "title"    -> currentTasks.sortedBy { it.title.lowercase() }
                else       -> currentTasks.sortedWith(Comparator { a, b ->
                    when {
                        a.scheduledDate == null && b.scheduledDate == null -> 0
                        a.scheduledDate == null -> 1
                        b.scheduledDate == null -> -1
                        else -> a.scheduledDate.compareTo(b.scheduledDate)
                    }
                })
            }
            val displayed = overdueTasks + sortedCurrent

            if (displayed.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📋", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(strings.tasksNoTasksYet, color = OnSurface2)
                        TextButton(onClick = { showAdd = true }) { Text(strings.tasksAddFirstTask, color = Purple80) }
                    }
                }
            } else {
                val completedTasks = tasks.filter { it.completed || it.skipped }
                val tasksListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = tasksListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (overdueTasks.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 2.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Error, modifier = Modifier.size(16.dp))
                                Text(
                                    "${strings.tasksOverdue} (${overdueTasks.size})",
                                    style      = MaterialTheme.typography.titleSmall,
                                    color      = Error
                                )
                            }
                        }
                        items(overdueTasks, key = { "od_${it.id}" }) { task ->
                            TaskCard(
                                task         = task,
                                onComplete   = { scope.launch { withContext(Dispatchers.IO) { Database.completeTask(task.id) }; com.focusflow.services.ReviewPromptService.triggerCheck(); reload() } },
                                onDelete     = { deleteWithUndo(task) },
                                onDeleteAndStop = { deleteWithUndo(task, stopSeries = true) },
                                onStartFocus = { onStartFocus(task) },
                                onEdit       = { editTask = task },
                                onSkip       = { scope.launch { withContext(Dispatchers.IO) { Database.skipTask(task.id) }; reload() } }
                            )
                        }
                        if (sortedCurrent.isNotEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Purple80, modifier = Modifier.size(16.dp))
                                    Text(strings.tasksUpcoming, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                                }
                            }
                        }
                    }
                    items(sortedCurrent, key = { it.id }) { task ->
                        TaskCard(
                            task        = task,
                            onComplete  = { scope.launch { withContext(Dispatchers.IO) { Database.completeTask(task.id) }; com.focusflow.services.ReviewPromptService.triggerCheck(); reload() } },
                            onDelete    = { deleteWithUndo(task) },
                            onDeleteAndStop = { deleteWithUndo(task, stopSeries = true) },
                            onStartFocus = { onStartFocus(task) },
                            onEdit      = { editTask = task },
                            onSkip      = { scope.launch { withContext(Dispatchers.IO) { Database.skipTask(task.id) }; reload() } }
                        )
                    }
                    if (completedTasks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Surface2)
                                    .clickable { showCompletedSection = !showCompletedSection }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                                    Text("${strings.tasksCompletedSection} (${completedTasks.size})", style = MaterialTheme.typography.titleSmall, color = OnSurface)
                                }
                                Icon(
                                    if (showCompletedSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null, tint = OnSurface2, modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (showCompletedSection) {
                            items(completedTasks.sortedByDescending { it.scheduledDate }, key = { "done_${it.id}" }) { task ->
                                TaskCard(
                                    task        = task,
                                    onComplete  = { scope.launch { withContext(Dispatchers.IO) { Database.completeTask(task.id) }; com.focusflow.services.ReviewPromptService.triggerCheck(); reload() } },
                                    onDelete    = { deleteWithUndo(task) },
                                    onDeleteAndStop = { deleteWithUndo(task, stopSeries = true) },
                                    onStartFocus = { onStartFocus(task) },
                                    onEdit      = { editTask = task },
                                    onSkip      = { scope.launch { withContext(Dispatchers.IO) { Database.skipTask(task.id) }; reload() } }
                                )
                            }
                        }
                    }
                }
                FfVerticalScrollbar(
                    listState = tasksListState,
                    modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
                }
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier  = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
    )
    } // end Box

    if (showAdd) {
        AddTaskDialog(onDismiss = { showAdd = false }, onSave = { task ->
            scope.launch { withContext(Dispatchers.IO) { Database.upsertTask(task) }; reload() }
            showAdd = false
        })
    }

    if (editTask != null) {
        val opened = editTask!!
        EditTaskDialog(
            task      = opened,
            onDismiss = { editTask = null },
            onSave    = { edited, editScope, series ->
                editTask = null
                scope.launch { withContext(Dispatchers.IO) { TaskSeriesService.saveEdit(opened, edited, editScope, series) }; reload() }
            },
            onStopRepeating = {
                editTask = null
                scope.launch { withContext(Dispatchers.IO) { TaskSeriesService.stopRepeating(opened) }; reload() }
            },
            onDeleteOnly = {
                editTask = null
                scope.launch { withContext(Dispatchers.IO) { TaskSeriesService.deleteOccurrence(opened) }; reload() }
            },
            onDeleteAndStop = {
                editTask = null
                scope.launch { withContext(Dispatchers.IO) { TaskSeriesService.deleteAndStop(opened) }; reload() }
            }
        )
    }
}

@Composable
fun AddTaskDialog(onDismiss: () -> Unit, onSave: (Task) -> Unit) {
    var title         by remember { mutableStateOf("") }
    var description   by remember { mutableStateOf("") }
    var duration      by remember { mutableStateOf("25") }
    var date          by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var time          by remember { mutableStateOf("") }
    var priority      by remember { mutableStateOf("medium") }
    var tags          by remember { mutableStateOf("") }
    var focusMode           by remember { mutableStateOf(false) }
    var focusIntensity      by remember { mutableStateOf("standard") }
    var recurring           by remember { mutableStateOf(false) }
    var recurringType       by remember { mutableStateOf("daily") }
    var endText             by remember { mutableStateOf("") }
    var selectedBlockedApps by remember { mutableStateOf(setOf<String>()) }
    var requirePin          by remember { mutableStateOf(false) }
    val curatedApps         = remember { InstalledAppsScanner.getCuratedApps() }
    val strings             = LocalizationManager.strings
    val startDate = runCatching { LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull() ?: LocalDate.now()
    val endResult = if (recurring) TaskSeriesService.resolveEndDate(endText, startDate) else EndDateResult.Valid(null)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(strings.tasksNewTask, color = OnSurface) },
        text = {
            val dialogScrollState = rememberScrollState()
            Box(modifier = Modifier.width(420.dp).heightIn(max = 520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().verticalScroll(dialogScrollState).padding(end = 10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(strings.tasksFieldTitle) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(strings.tasksFieldDescOpt) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text(strings.tasksFieldDate) }, modifier = Modifier.weight(1f), colors = fieldColors(), singleLine = true)
                    OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text(strings.tasksFieldTime) }, modifier = Modifier.weight(1f), colors = fieldColors(), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.tasksFieldDurationLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    listOf(15, 25, 30, 45, 60, 90).forEach { m ->
                        FilterChip(selected = duration == m.toString(), onClick = { duration = m.toString() }, label = { Text("${m}m") })
                    }
                }
                OutlinedTextField(value = duration, onValueChange = { duration = it.filter { c -> c.isDigit() }.take(3) }, label = { Text(strings.tasksFieldCustomDuration) }, modifier = Modifier.width(160.dp), colors = fieldColors(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.tasksPriorityLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    listOf("low", "medium", "high").forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it },
                    label = { Text(strings.tasksFieldTags) },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(), singleLine = true,
                    placeholder = { Text(strings.tasksFieldTagsHint, color = OnSurface2.copy(alpha = 0.5f)) }
                )
                // ── Focus Mode card ────────────────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focusMode) Purple80.copy(alpha = 0.10f) else Surface3)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Shield, null, tint = if (focusMode) Purple80 else OnSurface2, modifier = Modifier.size(16.dp))
                            Column {
                                Text(strings.settingsFocusModeLabel, style = MaterialTheme.typography.bodyMedium, color = if (focusMode) Purple80 else OnSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(strings.tasksFocusModeDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                            }
                        }
                        Switch(
                            checked = focusMode,
                            onCheckedChange = { focusMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Purple80, checkedTrackColor = Purple80.copy(alpha = 0.4f))
                        )
                    }
                    if (focusMode) {
                        HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                        Text(strings.focusIntensityLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Triple("standard", strings.focusStandardLabel, strings.focusStandardSubDesc),
                                Triple("deep",     strings.focusDeepWorkLabel, strings.focusDeepSubDesc),
                                Triple("nuclear",  strings.focusNuclearLabel,  strings.focusNuclearSubDesc)
                            ).forEach { (key, label, desc) ->
                                val sel = focusIntensity == key
                                val col = when (key) { "deep" -> Warning; "nuclear" -> Error; else -> Purple80 }
                                FilterChip(
                                    selected = sel,
                                    onClick  = { focusIntensity = key },
                                    label = {
                                        Column {
                                            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
                                            Text(desc, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = OnSurface2)
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = col.copy(alpha = 0.15f),
                                        selectedLabelColor     = col,
                                        containerColor         = Surface2,
                                        labelColor             = OnSurface2
                                    )
                                )
                            }
                        }
                        // ── App picker ─────────────────────────────────────────
                        HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Block, null, tint = Error.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                            Text(strings.tasksExtraAppsDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            curatedApps.chunked(2).forEach { rowApps ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowApps.forEach { app ->
                                        Row(
                                            modifier = Modifier.weight(1f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    selectedBlockedApps = if (app.processName in selectedBlockedApps)
                                                        selectedBlockedApps - app.processName
                                                    else selectedBlockedApps + app.processName
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Checkbox(
                                                checked = app.processName in selectedBlockedApps,
                                                onCheckedChange = { checked ->
                                                    selectedBlockedApps = if (checked) selectedBlockedApps + app.processName
                                                    else selectedBlockedApps - app.processName
                                                },
                                                modifier = Modifier.size(16.dp),
                                                colors = CheckboxDefaults.colors(checkedColor = Error)
                                            )
                                            Text(app.displayName, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = OnSurface, maxLines = 1)
                                        }
                                    }
                                    if (rowApps.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        if (selectedBlockedApps.isNotEmpty()) {
                            Text("${selectedBlockedApps.size} ${strings.tasksExtraAppsCount}", style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.8f))
                        }
                        // ── PIN toggle ─────────────────────────────────────────
                        HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { requirePin = !requirePin }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(checked = requirePin, onCheckedChange = { requirePin = it }, colors = CheckboxDefaults.colors(checkedColor = Purple80))
                            Column {
                                Text(strings.focusRequirePin, style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(strings.focusRequirePinHint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = OnSurface2)
                            }
                        }
                    }
                }
                RecurrenceSection(
                    showCheckbox = true,
                    recurring = recurring, onRecurringChange = { recurring = it },
                    recurringType = recurringType, onRecurringTypeChange = { recurringType = it },
                    endText = endText, onEndTextChange = { endText = it },
                    startDate = startDate,
                    endResult = endResult
                )
            }
            FfVerticalScrollbar(
                scrollState = dialogScrollState,
                modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
            } // end Box
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) return@Button
                    val parsedDate = try { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) { LocalDate.now() }
                    val tagList = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(Task(id = UUID.randomUUID().toString(), title = title.trim(), description = description.trim(), durationMinutes = duration.toIntOrNull() ?: 25, scheduledDate = parsedDate, scheduledTime = time.ifBlank { null }, priority = priority, tags = tagList, focusMode = focusMode, focusIntensity = if (focusMode) focusIntensity else "standard", focusBlockedApps = if (focusMode) selectedBlockedApps.toList() else emptyList(), focusRequirePin = focusMode && requirePin, recurring = recurring, recurringType = if (recurring) recurringType else null, recurringUntil = if (recurring) (endResult as? EndDateResult.Valid)?.date else null, createdAt = LocalDateTime.now()))
                },
                enabled = endResult !is EndDateResult.Invalid,
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(strings.tasksAddTaskBtn) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )
}

@Composable
fun EditTaskDialog(
    task: Task,
    onDismiss: () -> Unit,
    onSave: (Task, SeriesEditScope, SeriesEdit?) -> Unit,
    onStopRepeating: () -> Unit,
    onDeleteOnly: () -> Unit,
    onDeleteAndStop: () -> Unit
) {
    var title         by remember { mutableStateOf(task.title) }
    var description   by remember { mutableStateOf(task.description) }
    var duration      by remember { mutableStateOf(task.durationMinutes.toString()) }
    var date          by remember { mutableStateOf(task.scheduledDate?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "") }
    var time          by remember { mutableStateOf(task.scheduledTime ?: "") }
    var priority      by remember { mutableStateOf(task.priority) }
    var tags          by remember { mutableStateOf(task.tags.joinToString(", ")) }
    var focusMode      by remember { mutableStateOf(task.focusMode) }
    var focusIntensity by remember { mutableStateOf(task.focusIntensity) }
    var recurring      by remember { mutableStateOf(task.recurring) }
    var recurringType  by remember { mutableStateOf(task.recurringType ?: "daily") }
    var showConfirmDelete   by remember { mutableStateOf(false) }
    var showConfirmStop     by remember { mutableStateOf(false) }
    var endText             by remember { mutableStateOf(task.recurringUntil?.toString() ?: "") }
    var editScope           by remember { mutableStateOf(SeriesEditScope.ONLY_THIS) }
    var seriesInfoState     by remember { mutableStateOf<SeriesInfo?>(null) }
    val role                = remember(task) { TaskSeriesService.roleOf(task) }
    var selectedBlockedApps by remember { mutableStateOf(task.focusBlockedApps.toSet()) }
    var requirePin          by remember { mutableStateOf(task.focusRequirePin) }
    val curatedApps         = remember { InstalledAppsScanner.getCuratedApps() }
    val strings             = LocalizationManager.strings

    // Read the series (original + count) off the UI thread; copies adopt the series' repeat settings once.
    LaunchedEffect(task.id) {
        if (role != SeriesRole.NONE) {
            val loaded = withContext(Dispatchers.IO) { TaskSeriesService.seriesInfo(task) }
            seriesInfoState = loaded
            if (role == SeriesRole.COPY && loaded != null) {
                recurringType = loaded.template.recurringType ?: recurringType
                endText = loaded.template.recurringUntil?.toString() ?: ""
            }
        }
    }
    val info = seriesInfoState
    val seriesActive = info != null && info.template.recurring
    val showScope = role == SeriesRole.COPY && seriesActive
    val futureScope = showScope && editScope == SeriesEditScope.THIS_AND_FUTURE
    val showRecurrence = role != SeriesRole.COPY || futureScope
    val startDate = runCatching { LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        ?: task.scheduledDate ?: LocalDate.now()
    val endResult = if (showRecurrence && (futureScope || recurring)) TaskSeriesService.resolveEndDate(endText, startDate)
                    else EndDateResult.Valid(null)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(strings.tasksEditTaskTitle, color = OnSurface)
                ShortcutTooltip("Delete task") {
                    IconButton(onClick = { showConfirmDelete = true }) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Error)
                    }
                }
            }
        },
        text = {
            val dialogScrollState = rememberScrollState()
            Box(modifier = Modifier.width(420.dp).heightIn(max = 520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().verticalScroll(dialogScrollState).padding(end = 10.dp)) {
                if (info != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(SeriesText.describeSeries(info), color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        if (role == SeriesRole.TEMPLATE) {
                            Text(SeriesText.originalNote, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        }
                        if (seriesActive) {
                            OutlinedButton(
                                onClick = { showConfirmStop = true },
                                border = androidx.compose.foundation.BorderStroke(1.dp, Warning)
                            ) { Text(SeriesText.stopButton, color = Warning) }
                        }
                    }
                }
                if (showScope) {
                    Text(SeriesText.scopeLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    listOf(
                        SeriesEditScope.ONLY_THIS to SeriesText.scopeOnlyThis,
                        SeriesEditScope.THIS_AND_FUTURE to SeriesText.scopeFuture
                    ).forEach { (option, optionLabel) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { editScope = option },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = editScope == option,
                                onClick = { editScope = option },
                                colors = RadioButtonDefaults.colors(selectedColor = Purple80)
                            )
                            Text(optionLabel, color = OnSurface, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(strings.tasksFieldTitle) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(strings.tasksFieldDesc) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text(strings.tasksFieldDateShort) }, modifier = Modifier.weight(1f), colors = fieldColors(), singleLine = true)
                    OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text(strings.tasksFieldTime) }, modifier = Modifier.weight(1f), colors = fieldColors(), singleLine = true)
                }
                if (futureScope) {
                    Text(SeriesText.dateHint, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.tasksFieldDurationLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    listOf(15, 25, 30, 45, 60, 90).forEach { m ->
                        FilterChip(selected = duration == m.toString(), onClick = { duration = m.toString() }, label = { Text("${m}m") })
                    }
                }
                OutlinedTextField(value = duration, onValueChange = { duration = it.filter { c -> c.isDigit() }.take(3) }, label = { Text(strings.tasksFieldDurationMin) }, modifier = Modifier.width(160.dp), colors = fieldColors(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.tasksPriorityLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    listOf("low", "medium", "high").forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(
                    value = tags, onValueChange = { tags = it },
                    label = { Text(strings.tasksFieldTags) },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(), singleLine = true,
                    placeholder = { Text(strings.tasksFieldTagsHint, color = OnSurface2.copy(alpha = 0.5f)) }
                )
                // ── Focus Mode card ────────────────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focusMode) Purple80.copy(alpha = 0.10f) else Surface3)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Shield, null, tint = if (focusMode) Purple80 else OnSurface2, modifier = Modifier.size(16.dp))
                            Column {
                                Text(strings.settingsFocusModeLabel, style = MaterialTheme.typography.bodyMedium, color = if (focusMode) Purple80 else OnSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(strings.tasksFocusModeDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                            }
                        }
                        Switch(
                            checked = focusMode,
                            onCheckedChange = { focusMode = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Purple80, checkedTrackColor = Purple80.copy(alpha = 0.4f))
                        )
                    }
                    if (focusMode) {
                        HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                        Text(strings.focusIntensityLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Triple("standard", strings.focusStandardLabel, strings.focusStandardSubDesc),
                                Triple("deep",     strings.focusDeepWorkLabel, strings.focusDeepSubDesc),
                                Triple("nuclear",  strings.focusNuclearLabel,  strings.focusNuclearSubDesc)
                            ).forEach { (key, label, desc) ->
                                val sel = focusIntensity == key
                                val col = when (key) { "deep" -> Warning; "nuclear" -> Error; else -> Purple80 }
                                FilterChip(
                                    selected = sel,
                                    onClick  = { focusIntensity = key },
                                    label = {
                                        Column {
                                            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
                                            Text(desc, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = OnSurface2)
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = col.copy(alpha = 0.15f),
                                        selectedLabelColor     = col,
                                        containerColor         = Surface2,
                                        labelColor             = OnSurface2
                                    )
                                )
                            }
                        }
                        // ── App picker ─────────────────────────────────────────
                        HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Block, null, tint = Error.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                            Text(strings.tasksExtraAppsDesc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            curatedApps.chunked(2).forEach { rowApps ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowApps.forEach { app ->
                                        Row(
                                            modifier = Modifier.weight(1f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    selectedBlockedApps = if (app.processName in selectedBlockedApps)
                                                        selectedBlockedApps - app.processName
                                                    else selectedBlockedApps + app.processName
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Checkbox(
                                                checked = app.processName in selectedBlockedApps,
                                                onCheckedChange = { checked ->
                                                    selectedBlockedApps = if (checked) selectedBlockedApps + app.processName
                                                    else selectedBlockedApps - app.processName
                                                },
                                                modifier = Modifier.size(16.dp),
                                                colors = CheckboxDefaults.colors(checkedColor = Error)
                                            )
                                            Text(app.displayName, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = OnSurface, maxLines = 1)
                                        }
                                    }
                                    if (rowApps.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        if (selectedBlockedApps.isNotEmpty()) {
                            Text("${selectedBlockedApps.size} ${strings.tasksExtraAppsCount}", style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.8f))
                        }
                        // ── PIN toggle ─────────────────────────────────────────
                        HorizontalDivider(color = Purple80.copy(alpha = 0.15f))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { requirePin = !requirePin }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(checked = requirePin, onCheckedChange = { requirePin = it }, colors = CheckboxDefaults.colors(checkedColor = Purple80))
                            Column {
                                Text(strings.focusRequirePin, style = MaterialTheme.typography.bodySmall, color = OnSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(strings.focusRequirePinHint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = OnSurface2)
                            }
                        }
                    }
                }
                if (showRecurrence) {
                    RecurrenceSection(
                        showCheckbox = role != SeriesRole.COPY,
                        recurring = if (role == SeriesRole.COPY) true else recurring,
                        onRecurringChange = { recurring = it },
                        recurringType = recurringType, onRecurringTypeChange = { recurringType = it },
                        endText = endText, onEndTextChange = { endText = it },
                        startDate = startDate,
                        endResult = endResult
                    )
                }
            }
            FfVerticalScrollbar(
                scrollState = dialogScrollState,
                modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
            } // end Box
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedDate = try { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) { task.scheduledDate }
                    val tagList = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val endDate = (endResult as? EndDateResult.Valid)?.date
                    val isCopy = role == SeriesRole.COPY
                    val edited = task.copy(title = title.trim(), description = description.trim(), durationMinutes = duration.toIntOrNull() ?: task.durationMinutes, scheduledDate = parsedDate, scheduledTime = time.ifBlank { null }, priority = priority, tags = tagList, focusMode = focusMode, focusIntensity = if (focusMode) focusIntensity else "standard", focusBlockedApps = if (focusMode) selectedBlockedApps.toList() else emptyList(), focusRequirePin = focusMode && requirePin, recurring = if (isCopy) task.recurring else recurring, recurringType = if (isCopy) task.recurringType else if (recurring) recurringType else null, recurringUntil = if (!isCopy && recurring) endDate else null)
                    onSave(edited, editScope, if (futureScope) SeriesEdit(recurringType, endDate) else null)
                },
                enabled = endResult !is EndDateResult.Invalid,
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text(strings.btnSave) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )

    if (showConfirmStop) {
        AlertDialog(
            onDismissRequest = { showConfirmStop = false },
            containerColor = Surface2,
            title = { Text(SeriesText.stopConfirmTitle, color = Warning) },
            text = { Text(SeriesText.stopConfirmBody(task.title), color = OnSurface2) },
            confirmButton = {
                Button(
                    onClick = { showConfirmStop = false; onStopRepeating() },
                    colors = ButtonDefaults.buttonColors(containerColor = Warning)
                ) { Text(SeriesText.stopButton) }
            },
            dismissButton = { TextButton(onClick = { showConfirmStop = false }) { Text(strings.btnCancel, color = OnSurface2) } }
        )
    }

    if (showConfirmDelete) {
        DeleteTaskDialog(
            task = task,
            onDismiss = { showConfirmDelete = false },
            onDeleteOnly = { showConfirmDelete = false; onDeleteOnly() },
            onDeleteAndStop = if (role == SeriesRole.NONE) null else ({ showConfirmDelete = false; onDeleteAndStop() })
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple80, unfocusedBorderColor = OnSurface2)

/**
 * Repeat controls shared by the Add and Edit dialogs: the optional "Recurring" checkbox, the
 * repeat-type chips, and the "Ends" chips + date field with its error line.
 */
@Composable
private fun RecurrenceSection(
    showCheckbox: Boolean,
    recurring: Boolean, onRecurringChange: (Boolean) -> Unit,
    recurringType: String, onRecurringTypeChange: (String) -> Unit,
    endText: String, onEndTextChange: (String) -> Unit,
    startDate: LocalDate,
    endResult: EndDateResult
) {
    val strings = LocalizationManager.strings
    if (showCheckbox) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = recurring, onCheckedChange = onRecurringChange, colors = CheckboxDefaults.colors(checkedColor = Purple80))
            Text(strings.tasksRecurringLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (recurring) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.tasksRepeatsLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
            listOf("daily", "weekdays", "weekly").forEach { t ->
                FilterChip(selected = recurringType == t, onClick = { onRecurringTypeChange(t) }, label = { Text(t.replaceFirstChar { it.uppercase() }) })
            }
        }
        val endChips = listOf(
            EndChip.NEVER to SeriesText.chipNever,
            EndChip.ONE_WEEK to SeriesText.chipOneWeek,
            EndChip.ONE_MONTH to SeriesText.chipOneMonth,
            EndChip.THREE_MONTHS to SeriesText.chipThreeMonths
        )
        Text(SeriesText.endsLabel, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            endChips.forEach { (chip, chipLabel) ->
                val chipText = TaskSeriesService.endDateForChip(startDate, chip)?.toString() ?: ""
                FilterChip(selected = endText.trim() == chipText, onClick = { onEndTextChange(chipText) }, label = { Text(chipLabel) })
            }
        }
        OutlinedTextField(
            value = endText, onValueChange = onEndTextChange,
            label = { Text(SeriesText.endsOnLabel) },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(), singleLine = true,
            isError = endResult is EndDateResult.Invalid
        )
        if (endResult is EndDateResult.Invalid) {
            Text(SeriesText.endDateProblem(endResult.problem), color = Error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
