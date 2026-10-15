package com.focusflow.ui.screens

import com.focusflow.AppPaths
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import com.focusflow.ui.components.EmptyStateCard
import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.ShortcutTooltip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.*
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.BackupService
import com.focusflow.services.CsvExportResult
import com.focusflow.ui.theme.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StatsTab { DAILY, WEEK, ALL_TIME }

@Composable
fun StatsScreen() {
    val strings      = LocalizationManager.strings
    var tab          by remember { mutableStateOf(StatsTab.DAILY) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    Column(modifier = Modifier.fillMaxSize().background(Surface)) {
        // Header
        Column(modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 0.dp)) {
            Text(strings.statsTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            Text(strings.statsSubtitle, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            Spacer(Modifier.height(16.dp))
            // Tab bar
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    StatsTab.DAILY    to "Daily",
                    StatsTab.WEEK     to strings.statsWeek,
                    StatsTab.ALL_TIME to strings.statsAllTime
                ).forEach { (t, label) ->
                    StatsTabPill(
                        label    = label,
                        selected = tab == t,
                        onClick  = { tab = t },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Date navigator (Daily tab only) ──────────────────────────────────
        if (tab == StatsTab.DAILY) {
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                ShortcutTooltip("Previous day") {
                    IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                        Icon(Icons.Default.ChevronLeft, "Previous day", tint = Purple80)
                    }
                }
                Text(
                    when (selectedDate) {
                        LocalDate.now()              -> "Today"
                        LocalDate.now().minusDays(1) -> "Yesterday"
                        else -> selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                    },
                    modifier   = Modifier.widthIn(min = 150.dp),
                    style      = MaterialTheme.typography.titleMedium,
                    color      = OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center
                )
                ShortcutTooltip("Next day") {
                    IconButton(
                        onClick = { selectedDate = selectedDate.plusDays(1) },
                        enabled = selectedDate < LocalDate.now()
                    ) {
                        Icon(
                            Icons.Default.ChevronRight, "Next day",
                            tint = if (selectedDate < LocalDate.now()) Purple80 else OnSurface2
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) },
            label = "statsTabContent"
        ) { currentTab ->
            when (currentTab) {
                StatsTab.DAILY    -> DailyTab(selectedDate)
                StatsTab.WEEK     -> WeekTab()
                StatsTab.ALL_TIME -> AllTimeTab()
            }
        }
    }
}

@Composable
private fun StatsTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue   = if (selected) Purple80 else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(220),
        label         = "tabBg"
    )
    val textColor by animateColorAsState(
        targetValue   = if (selected) androidx.compose.ui.graphics.Color.White else OnSurface2,
        animationSpec = tween(220),
        label         = "tabText"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.bodySmall,
            color      = textColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ── Week ───────────────────────────────────────────────────────────────────────

@Composable
private fun WeekTab() {
    var weekStats     by remember { mutableStateOf(listOf<DayFocusStats>()) }
    var weekTasks     by remember { mutableStateOf(listOf<Task>()) }
    var tempts        by remember { mutableStateOf(listOf<TemptationEntry>()) }
    var weekSessions  by remember { mutableStateOf(listOf<FocusSession>()) }
    var isLoading     by remember { mutableStateOf(true) }
    var sessionFilter by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        isLoading    = true
        weekStats    = withContext(Dispatchers.IO) { Database.getFocusMinutesByDay(7) }
        weekTasks    = withContext(Dispatchers.IO) { Database.getTasksInRange(LocalDate.now().minusDays(6), LocalDate.now()) }
        tempts       = withContext(Dispatchers.IO) { Database.getTemptationLog(7) }
        weekSessions = withContext(Dispatchers.IO) { Database.getSessionsInDateRange(LocalDate.now().minusDays(6), LocalDate.now()) }
        isLoading    = false
    }

    val totalFocusMins = weekStats.sumOf { it.totalMinutes }
    val totalSessions  = weekStats.sumOf { it.sessionsCount }
    val completed      = weekTasks.count { it.completed }
    val total          = weekTasks.size
    val topTempts      = tempts.groupBy { it.displayName }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.take(5)

    val filteredWeekSessions = when (sessionFilter) {
        "completed"   -> weekSessions.filter { it.completed }
        "interrupted" -> weekSessions.filter { it.interrupted }
        "notes"       -> weekSessions.filter { it.notes.isNotBlank() }
        else          -> weekSessions
    }

    val weekListState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
    LazyColumn(
        state = weekListState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp)
    ) {
        // Session filter chips — top of list, co-located with the "Last 7 days" context
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Sessions:", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                listOf("all" to "All", "completed" to "Completed", "interrupted" to "Interrupted", "notes" to "Has notes").forEach { (f, label) ->
                    FilterChip(
                        selected = sessionFilter == f,
                        onClick  = { sessionFilter = f },
                        label    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple80.copy(alpha = 0.20f),
                            selectedLabelColor     = Purple80,
                            containerColor         = Surface2
                        )
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStatBox("⏱", if (totalFocusMins >= 60) "${totalFocusMins / 60}h ${totalFocusMins % 60}m" else "${totalFocusMins}m", LocalizationManager.strings.reportsFocusLabel, Purple80, Modifier.weight(1f))
                MiniStatBox("🎯", "$totalSessions", LocalizationManager.strings.statsSessions, Purple60, Modifier.weight(1f))
                MiniStatBox("✅", "$completed/$total", LocalizationManager.strings.tasksTitle, Success, Modifier.weight(1f))
                MiniStatBox("🚫", "${tempts.size}", LocalizationManager.strings.statsBlocked, Error.copy(alpha = 0.8f), Modifier.weight(1f))
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Purple80) }
            }
        } else if (weekStats.isEmpty() && weekSessions.isEmpty()) {
            item {
                EmptyStateCard(
                    icon    = Icons.Default.BarChart,
                    title   = "No activity this week",
                    message = "Focus sessions, tasks, and blocked attempts will appear here as you work.",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {

        if (weekStats.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(LocalizationManager.strings.statsFocusLastWeek, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    FocusBarChart(stats = weekStats)
                }
            }
        }

        // Focus breakdown by task
        if (filteredWeekSessions.isNotEmpty()) {
            item {
                val byTask = filteredWeekSessions
                    .filter { it.completed }
                    .groupBy { it.taskName }
                    .mapValues { e -> e.value.sumOf { it.actualMinutes } }
                    .entries.sortedByDescending { it.value }
                    .take(8)
                val maxMins = byTask.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1

                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(LocalizationManager.strings.statsFocusByTask, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    byTask.forEach { (taskName, mins) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                taskName,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface,
                                modifier = Modifier.width(130.dp),
                                maxLines = 1
                            )
                            Box(
                                modifier = Modifier.weight(1f).height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)).background(Surface3)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(mins.toFloat() / maxMins)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Purple80.copy(alpha = 0.7f))
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            val h = mins / 60; val m = mins % 60
                            Text(
                                if (h > 0) "${h}h${m}m" else "${m}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = Purple60,
                                modifier = Modifier.width(44.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }

        // Task completion per day
        item {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(LocalizationManager.strings.statsTaskCompletion, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                val today = LocalDate.now()
                (6 downTo 0).forEach { daysAgo ->
                    val date = today.minusDays(daysAgo.toLong())
                    val dayTasks = weekTasks.filter { it.scheduledDate == date }
                    val done = dayTasks.count { it.completed }
                    val dayTotal = dayTasks.size
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            date.format(DateTimeFormatter.ofPattern("EEE")),
                            style = MaterialTheme.typography.bodySmall, color = if (date == today) Purple80 else OnSurface2,
                            modifier = Modifier.width(40.dp)
                        )
                        Box(modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Surface3)) {
                            if (dayTotal > 0) Box(modifier = Modifier.fillMaxWidth(done.toFloat() / dayTotal).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (date == today) Purple80 else Purple80.copy(alpha = 0.6f)))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("$done/$dayTotal", style = MaterialTheme.typography.bodySmall, color = OnSurface2, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }

        if (topTempts.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(LocalizationManager.strings.statsAppDiscipline, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    val maxCount = topTempts.maxOfOrNull { it.value } ?: 1
                    topTempts.forEach { (app, count) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(app, style = MaterialTheme.typography.bodySmall, color = OnSurface, modifier = Modifier.width(120.dp))
                            Box(modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Surface3)) {
                                Box(modifier = Modifier.fillMaxWidth(count.toFloat() / maxCount).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Purple80.copy(alpha = 0.7f)))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("$count×", style = MaterialTheme.typography.bodySmall, color = Purple60, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
        } // end else (data available)
    }
    FfVerticalScrollbar(
        listState = weekListState,
        modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
    )
    }
}

// ── All Time ───────────────────────────────────────────────────────────────────

@Composable
private fun AllTimeTab() {
    var allTimeMins  by remember { mutableStateOf(0) }
    var allTimeSess  by remember { mutableStateOf(0) }
    var bestStreak   by remember { mutableStateOf(0) }
    var curStreak    by remember { mutableStateOf(0) }
    var heatData     by remember { mutableStateOf(listOf<DayCompletionStats>()) }
    var allTasks     by remember { mutableStateOf(listOf<Task>()) }
    var exportMsg    by remember { mutableStateOf("") }
    val scope        = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        allTimeMins = withContext(Dispatchers.IO) { Database.getAllTimeFocusMinutes() }
        allTimeSess = withContext(Dispatchers.IO) { Database.getAllTimeFocusSessions() }
        bestStreak  = withContext(Dispatchers.IO) { Database.getBestStreak() }
        curStreak   = withContext(Dispatchers.IO) { Database.getCurrentStreak() }
        heatData    = withContext(Dispatchers.IO) { Database.getRecentDayCompletions(84) }
        allTasks    = withContext(Dispatchers.IO) { Database.getTasks() }
    }

    val totalTasksDone = allTasks.count { it.completed }

    val allTimeListState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
    LazyColumn(
        state = allTimeListState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val h = allTimeMins / 60; val m = allTimeMins % 60
                MiniStatBox("⏱", if (h > 0) "${h}h ${m}m" else "${m}m", "Focus Total", Purple80, Modifier.weight(1f))
                MiniStatBox("🎯", "$allTimeSess", "Sessions",   Purple60,   Modifier.weight(1f))
                MiniStatBox("✅", "$totalTasksDone", "Tasks Done", Success, Modifier.weight(1f))
                MiniStatBox("🔥", "$bestStreak days", "Best Streak", Warning, Modifier.weight(1f))
            }
        }

        // Export CSV
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (exportMsg.isNotEmpty()) {
                    Text(
                        exportMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exportMsg.startsWith("✓")) Success else Error,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            exportMsg = "Exporting…"
                            val dateSuffix = LocalDate.now().toString()
                            val file = File(AppPaths.dataDir, "sessions_$dateSuffix.csv")
                            val result = withContext(Dispatchers.IO) {
                                BackupService.exportSessionsTo(file)
                            }
                            exportMsg = when (result) {
                                is CsvExportResult.Saved ->
                                    "✓ ${result.rows} sessions → ${result.file.name}"
                                CsvExportResult.Cancelled -> "Export cancelled"
                                is CsvExportResult.Failed ->
                                    "Export failed: ${result.message.take(60)}"
                                }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Purple80)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(LocalizationManager.strings.statsExportCsv)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStatBox("📅", "$curStreak days", "Current Streak", Purple80, Modifier.weight(1f))
                val avgPerDay = if (heatData.count { it.focusMinutes > 0 } > 0)
                    allTimeMins / heatData.count { it.focusMinutes > 0 } else 0
                MiniStatBox("📈", "${avgPerDay}m", "Avg/Active Day", Purple60, Modifier.weight(1f))
                MiniStatBox("🗓", "${heatData.count { it.completedCount > 0 }}", "Active Days", Success, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }

        // 12-week heatmap
        if (heatData.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(LocalizationManager.strings.statsActivityHeatmap, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    ActivityHeatmap(heatData)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(LocalizationManager.strings.statsLessLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { intensity ->
                            Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(
                                if (intensity == 0f) Surface3 else Purple80.copy(alpha = 0.2f + intensity * 0.8f)
                            ))
                        }
                        Text(LocalizationManager.strings.statsMoreLabel, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                    }
                }
            }
        }

        // Milestones
        item {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(LocalizationManager.strings.statsMilestones, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                val milestones = listOf(
                    Triple("🔥", "7-day streak",     bestStreak >= 7),
                    Triple("🔥", "30-day streak",    bestStreak >= 30),
                    Triple("⏱", "10 focus hours",   allTimeMins >= 600),
                    Triple("⏱", "100 focus hours",  allTimeMins >= 6000),
                    Triple("🎯", "50 sessions",      allTimeSess >= 50),
                    Triple("🎯", "500 sessions",     allTimeSess >= 500),
                    Triple("✅", "100 tasks done",   totalTasksDone >= 100),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    milestones.forEach { (emoji, label, achieved) ->
                        Column(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (achieved) Purple80.copy(alpha = 0.15f) else Surface3)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (achieved) emoji else "🔒", fontSize = 18.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall, color = if (achieved) Purple80 else OnSurface2, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
    FfVerticalScrollbar(
        listState = allTimeListState,
        modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
    )
    }
}

// ── Shared components ──────────────────────────────────────────────────────────

@Composable
private fun ActivityHeatmap(data: List<DayCompletionStats>) {
    val cols = 12
    val rows = 7
    val cellSize = 14.dp
    val gap = 3.dp

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        val dayLabels = listOf("Mon", "", "Wed", "", "Fri", "", "Sun")
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Spacer(modifier = Modifier.width(24.dp))
            (0 until cols).forEach { week ->
                val weekStart = (cols - 1 - week) * 7
                val colData = data.subList(
                    maxOf(0, data.size - weekStart - 7).coerceAtMost(data.size),
                    (data.size - weekStart).coerceAtLeast(0).coerceAtMost(data.size)
                )
                Text(
                    if (week % 3 == 0) colData.firstOrNull()?.date?.format(DateTimeFormatter.ofPattern("MMM")) ?: "" else "",
                    style = MaterialTheme.typography.bodySmall, color = OnSurface2,
                    modifier = Modifier.width(cellSize),
                    textAlign = TextAlign.Center,
                    fontSize = 8.sp
                )
            }
        }
        (0 until rows).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                Text(dayLabels[row], style = MaterialTheme.typography.bodySmall, color = OnSurface2, modifier = Modifier.width(24.dp), fontSize = 8.sp)
                (0 until cols).forEach { col ->
                    val idx = data.size - (cols - col) * 7 + row
                    val cell = if (idx in data.indices) data[idx] else null
                    val intensity = if (cell == null || cell.completedCount == 0) 0f
                    else (cell.completedCount.toFloat() / maxOf(cell.totalCount, 1)).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier.size(cellSize).clip(RoundedCornerShape(2.dp))
                            .background(if (intensity == 0f) Surface3 else Purple80.copy(alpha = 0.2f + intensity * 0.8f))
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusBarChart(stats: List<DayFocusStats>) {
    val maxMins  = stats.maxOfOrNull { it.totalMinutes }?.coerceAtLeast(30) ?: 30
    val today    = LocalDate.now()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            val n = stats.size
            val barW = (size.width - 12.dp.toPx() * (n - 1)) / n
            val maxH = size.height - 20.dp.toPx()
            stats.forEachIndexed { i, day ->
                val x   = i * (barW + 12.dp.toPx())
                val barH = if (maxMins > 0) (day.totalMinutes.toFloat() / maxMins) * maxH else 0f
                drawRoundRect(Surface3, Offset(x, 0f), Size(barW, maxH), CornerRadius(4.dp.toPx()))
                if (barH > 0) drawRoundRect(
                    if (day.date == today) Purple80 else Purple80.copy(alpha = 0.6f),
                    Offset(x, maxH - barH), Size(barW, barH + 20.dp.toPx()), CornerRadius(4.dp.toPx())
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            stats.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(if (day.totalMinutes > 0) "${day.totalMinutes}m" else "—", style = MaterialTheme.typography.bodySmall, color = if (day.totalMinutes > 0) Purple80 else OnSurface2, fontSize = 9.sp)
                    Text(day.date.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.bodySmall, color = if (day.date == today) OnSurface else OnSurface2, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun MiniStatBox(emoji: String, value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurface2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TaskSummaryRow(task: Task) {
    val statusColor = when { task.completed -> Success; task.skipped -> Warning; else -> OnSurface2 }
    val statusIcon  = when { task.completed -> Icons.Default.CheckCircle; task.skipped -> Icons.Default.SkipNext; else -> Icons.Default.RadioButtonUnchecked }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(task.title, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                if (task.scheduledTime != null) Text(task.scheduledTime, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            }
        }
        Text("${task.durationMinutes}m", color = Purple60, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SessionRow(session: FocusSession) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(session.taskName, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(session.startTime.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.bodySmall, color = OnSurface2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${session.actualMinutes}m", color = Purple60)
            Icon(if (session.completed) Icons.Default.CheckCircle else Icons.Default.Cancel, null, tint = if (session.completed) Success else Error, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Daily tab — navigable by date, merges Yesterday + Today ────────────────────

@Composable
private fun DailyTab(date: LocalDate) {
    val isToday   = date == LocalDate.now()
    var tasks     by remember(date) { mutableStateOf(listOf<Task>()) }
    var sessions  by remember(date) { mutableStateOf(listOf<FocusSession>()) }
    var focusMins by remember(date) { mutableStateOf(0) }
    var tempts    by remember(date) { mutableStateOf(listOf<TemptationEntry>()) }
    var dailyGoal by remember { mutableStateOf(120) }
    var streak    by remember { mutableStateOf(0) }
    var isLoading     by remember(date) { mutableStateOf(true) }
    var sessionFilter by remember { mutableStateOf("all") }

    LaunchedEffect(date) {
        isLoading = true
        tasks     = withContext(Dispatchers.IO) { Database.getTasksForDate(date) }
        sessions  = withContext(Dispatchers.IO) { Database.getSessionsInDateRange(date, date) }
        focusMins = if (isToday)
            withContext(Dispatchers.IO) { Database.getTotalFocusMinutesToday() }
        else
            sessions.filter { it.completed }.sumOf { it.actualMinutes }
        tempts    = if (isToday) withContext(Dispatchers.IO) { Database.getTemptationLog(1) } else listOf()
        dailyGoal = withContext(Dispatchers.IO) { Database.getSetting("daily_focus_goal")?.toIntOrNull() ?: 120 }
        streak    = withContext(Dispatchers.IO) { Database.getCurrentStreak() }
        isLoading = false
    }

    val completed = tasks.count { it.completed }
    val total     = tasks.size
    val rate      = if (total > 0) (completed * 100) / total else 0
    val goalPct   = (focusMins.toFloat() / dailyGoal).coerceIn(0f, 1f)
    val rateColor = if (rate >= 80) Success else if (rate >= 50) Warning else if (isToday) Purple80 else Error

    val bitterTruth = when {
        total == 0 -> if (isToday) "No tasks scheduled yet — add tasks to track your day." else "No tasks scheduled — a day without a plan."
        rate >= 90 -> if (isToday) "Crushing it! $rate% done today — keep this momentum." else "Outstanding — $rate% done. Build on this streak."
        rate >= 70 -> if (isToday) "Solid progress. $completed/$total done. Push through the rest." else "Solid day. $completed/$total done. Keep building."
        rate >= 50 -> if (isToday) "Halfway there. $completed/$total tasks done — close the gap." else "Halfway there. $completed/$total done. Close the gap today."
        rate > 0   -> if (isToday) "Only $completed/$total tasks done so far. Refocus and push." else "Rough day. Only $completed/$total completed. Today is a fresh start."
        else       -> if (isToday) "No tasks completed yet. Pick one and start." else "No tasks completed. Time to change that."
    }

    val filteredSessions = when (sessionFilter) {
        "completed"   -> sessions.filter { it.completed }
        "interrupted" -> sessions.filter { it.interrupted }
        "notes"       -> sessions.filter { it.notes.isNotBlank() }
        else          -> sessions
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp)
        ) {
            if (streak > 0 && isToday) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Warning.copy(alpha = 0.1f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥", fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("$streak-${LocalizationManager.strings.statsStreakDays}", color = Warning, fontWeight = FontWeight.Bold)
                            Text(LocalizationManager.strings.statsKeepStreakMaintain, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        }
                    }
                }
            }

            item {
                if (isToday) {
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val stroke = 8.dp.toPx(); val radius = (size.minDimension - stroke) / 2; val center = Offset(size.width / 2, size.height / 2)
                                drawArc(Surface3, -90f, 360f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                                if (goalPct > 0f) drawArc(Purple80, -90f, 360f * goalPct, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (focusMins >= 60) "${focusMins / 60}h" else "${focusMins}m", style = MaterialTheme.typography.bodyMedium, color = Purple80, fontWeight = FontWeight.Bold)
                                Text("/ ${dailyGoal}m", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                            }
                        }
                        Column {
                            Text(LocalizationManager.strings.statsDailyFocusGoal, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                            Text("${(goalPct * 100).toInt()}${LocalizationManager.strings.statsPercentComplete}", color = Purple80, style = MaterialTheme.typography.bodySmall)
                            if (goalPct >= 1f) Text(LocalizationManager.strings.statsGoalReached, color = Success, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(LocalizationManager.strings.statsFocusedYesterday, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                        Spacer(Modifier.height(8.dp))
                        if (focusMins > 0) Text(if (focusMins >= 60) "${focusMins / 60}h ${focusMins % 60}m" else "${focusMins}m",
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 48.sp), color = Purple80, fontWeight = FontWeight.Bold)
                        else Text(LocalizationManager.strings.statsNoFocusSessions, color = OnSurface2)
                    }
                }
            }

            if (isLoading) {
                item { Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple80) } }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiniStatBox("✅", "$completed", LocalizationManager.strings.statsDone,    rateColor,               Modifier.weight(1f))
                        MiniStatBox("📋", "$total",    LocalizationManager.strings.statsTotal,   OnSurface2,              Modifier.weight(1f))
                        MiniStatBox("⏱",  "${sessions.size}", LocalizationManager.strings.statsSessions, Purple60,        Modifier.weight(1f))
                        MiniStatBox("🚫", "${tempts.size}", LocalizationManager.strings.statsBlocked, Error.copy(alpha = 0.8f), Modifier.weight(1f))
                    }
                }
                if (sessions.isNotEmpty()) {
                    // Session filter chips — co-located with session list header
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (isToday) LocalizationManager.strings.statsTodaysSessions else "Sessions", style = MaterialTheme.typography.titleMedium, color = OnSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf("all" to "All", "completed" to "Completed", "interrupted" to "Interrupted", "notes" to "Has notes").forEach { (f, label) ->
                                    FilterChip(
                                        selected = sessionFilter == f,
                                        onClick  = { sessionFilter = f },
                                        label    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                        colors   = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Purple80.copy(alpha = 0.20f),
                                            selectedLabelColor     = Purple80,
                                            containerColor         = Surface2
                                        )
                                    )
                                }
                            }
                        }
                    }
                    items(filteredSessions, key = { it.id }) { session -> SessionRow(session) }
                }
                if (tasks.isNotEmpty()) {
                    item { Text(if (isToday) LocalizationManager.strings.statsTodaysTasks else "Tasks", style = MaterialTheme.typography.titleMedium, color = OnSurface) }
                    items(tasks, key = { it.id }) { task -> TaskSummaryRow(task) }
                }
                if (total == 0 && sessions.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon    = Icons.AutoMirrored.Filled.EventNote,
                            title   = if (isToday) "No activity today" else "Nothing recorded",
                            message = if (isToday) "Add tasks and start a focus session." else LocalizationManager.strings.statsNoTasksYesterday,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            item {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(rateColor.copy(alpha = 0.1f)).padding(16.dp)) {
                    Text(bitterTruth, color = rateColor, fontWeight = FontWeight.Medium)
                }
            }
        }
        FfVerticalScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
