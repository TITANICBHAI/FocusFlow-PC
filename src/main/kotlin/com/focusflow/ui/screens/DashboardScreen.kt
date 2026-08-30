package com.focusflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.InfoTooltip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.DailyAllowance
import com.focusflow.data.models.Task
import com.focusflow.services.DailyAllowanceTracker
import com.focusflow.services.FocusInsightsService
import com.focusflow.services.FocusSessionService
import com.focusflow.services.SessionPin
import com.focusflow.enforcement.InstallVariant
import com.focusflow.i18n.LocalizationManager
import com.focusflow.ui.components.ShortcutTooltip
import com.focusflow.ui.components.TaskCard
import com.focusflow.ui.components.openUrl
import com.focusflow.ui.theme.*
import androidx.compose.ui.input.key.*
import com.focusflow.ui.LocalNavigate
import com.focusflow.data.models.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(refreshKey: Int = 0, onStartFocus: (Task) -> Unit, onNavigateTasks: () -> Unit) {
    val today   = LocalDate.now()
    val session by FocusSessionService.state.collectAsState()
    val scope   = rememberCoroutineScope()

    var tasks            by remember { mutableStateOf(listOf<Task>()) }
    var streak           by remember { mutableStateOf(0) }
    var focusToday       by remember { mutableStateOf(0) }
    var completedToday   by remember { mutableStateOf(0) }
    var dailyGoal        by remember { mutableStateOf(120) }
    var showQuickAdd     by remember { mutableStateOf(false) }
    var showEndPinDialog by remember { mutableStateOf(false) }
    var userName         by remember { mutableStateOf("") }
    var allowances       by remember { mutableStateOf(listOf<DailyAllowance>()) }
    var blockedAttempts  by remember { mutableStateOf(0) }
    var insights         by remember { mutableStateOf(FocusInsightsService.Insights()) }
    var showWhatsNew     by remember { mutableStateOf(false) }
    var showExeProtectionNotice by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    val strings = LocalizationManager.strings

    fun reload() {
        val latestChangelogVersion = LATEST_CHANGELOG_VERSION
        scope.launch {
            val t   = withContext(Dispatchers.IO) { Database.getTasksForDate(today) }
            val s   = withContext(Dispatchers.IO) { Database.getCurrentStreak() }
            val ft  = withContext(Dispatchers.IO) { Database.getTotalFocusMinutesToday() }
            val dg  = withContext(Dispatchers.IO) { Database.getSetting("daily_focus_goal")?.toIntOrNull() ?: 120 }
            val un  = withContext(Dispatchers.IO) { Database.getSetting("user_name") ?: "" }
            val al  = withContext(Dispatchers.IO) { Database.getDailyAllowances() }
            val ba  = withContext(Dispatchers.IO) { Database.getTemptationsInRange(today.toString(), today.toString()) }
            val lsv = withContext(Dispatchers.IO) { Database.getSetting("last_seen_version") }
            val exeNoticeDismissed = withContext(Dispatchers.IO) {
                Database.getSetting(EXE_PROTECTION_NOTICE_DISMISSED) == "true"
            }
            tasks           = t
            streak          = s
            focusToday      = ft
            completedToday  = t.count { it.completed }
            dailyGoal       = dg
            userName        = un
            allowances      = al
            blockedAttempts = ba
            val ins = withContext(Dispatchers.IO) { FocusInsightsService.compute() }
            insights = ins
            // Show "What's New" banner once for each release in the changelog.
            if (latestChangelogVersion != null && lsv != latestChangelogVersion) {
                withContext(Dispatchers.IO) {
                    Database.setSetting("last_seen_version", latestChangelogVersion)
                }
                showWhatsNew = true
            }
            // Store/MSIX users are the audience for this one-time direct-installer
            // notice. EXE/MSI users do not need to be told to switch channels.
            showExeProtectionNotice =
                InstallVariant.isWindows && InstallVariant.isMsix && !exeNoticeDismissed
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    // Reload dashboard stats whenever a focus session ends (completed or stopped early)
    DisposableEffect(Unit) {
        FocusSessionService.onSessionEnded = { _ -> reload() }
        onDispose { FocusSessionService.onSessionEnded = null }
    }

    // Auto-dismiss the What's New banner after 10 seconds
    LaunchedEffect(showWhatsNew) {
        if (showWhatsNew) {
            delay(10_000)
            showWhatsNew = false
        }
    }

    val goalPct    = (focusToday.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val taskPct    = if (tasks.isNotEmpty()) completedToday.toFloat() / tasks.size else 0f
    val focusScore = ((goalPct * 60f) + (taskPct * 30f) + (if (streak > 0) 10f else 0f)).toInt().coerceIn(0, 100)
    val scoreColor = when {
        focusScore >= 80 -> Success
        focusScore >= 50 -> Warning
        else             -> Error.copy(alpha = 0.8f)
    }
    val animatedGoalPct by animateFloatAsState(
        targetValue   = goalPct,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label         = "goalRing"
    )

    val dashScrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.N) {
            showQuickAdd = true; true
        } else false
    }) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Fix 3: Slim session strip — pinned above scroll, never pushes content ──
            AnimatedVisibility(
                visible = session.isActive,
                enter   = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
                exit    = shrinkVertically(tween(250)) + fadeOut(tween(200))
            ) {
                val remaining      = session.totalSeconds - session.elapsedSeconds
                val bannerDotPulse = rememberInfiniteTransition(label = "bannerDot")
                val bannerDotScale by bannerDotPulse.animateFloat(
                    initialValue  = 0.80f,
                    targetValue   = 1.25f,
                    animationSpec = infiniteRepeatable(
                        animation  = tween(700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bannerDotScale"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Purple80.copy(alpha = 0.14f))
                        .padding(horizontal = 32.dp, vertical = 9.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.scale(bannerDotScale).size(7.dp).clip(CircleShape).background(Purple80))
                    Text(strings.dashNow, color = Purple80, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(
                        session.taskName,
                        style      = MaterialTheme.typography.bodySmall,
                        color      = OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.weight(1f),
                        maxLines   = 1
                    )
                    Text(
                        "${remaining / 60}m ${remaining % 60}s ${strings.dashRemaining}",
                        style = MaterialTheme.typography.bodySmall, color = OnSurface2
                    )
                    TextButton(
                        onClick        = {
                            if (SessionPin.isSet()) showEndPinDialog = true
                            else FocusSessionService.end(completed = false)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(strings.dashEnd, color = Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ── Release and installer notices ─────────────────────────────────
            val navigate = LocalNavigate.current
            if (showWhatsNew || showExeProtectionNotice) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(
                        visible = showWhatsNew,
                        modifier = Modifier.weight(1f),
                        enter   = expandVertically(tween(350, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
                        exit    = shrinkVertically(tween(280)) + fadeOut(tween(220))
                    ) {
                        WhatsNewBanner(
                            onViewChangelog = { showWhatsNew = false; navigate(Screen.CHANGELOG) },
                            onDismiss       = { showWhatsNew = false }
                        )
                    }
                    AnimatedVisibility(
                        visible = showExeProtectionNotice,
                        modifier = Modifier.weight(1f),
                        enter   = expandVertically(tween(350, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
                        exit    = shrinkVertically(tween(280)) + fadeOut(tween(220))
                    ) {
                        ExeProtectionNotice(
                            onDismiss = {
                                showExeProtectionNotice = false
                                scope.launch(Dispatchers.IO) {
                                    Database.setSetting(EXE_PROTECTION_NOTICE_DISMISSED, "true")
                                }
                            }
                        )
                    }
                }
            }

            // ── Scrollable content ────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Surface)
                        .verticalScroll(dashScrollState)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // ── Header — greeting + passive donate heart ──────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                                style = MaterialTheme.typography.bodyMedium, color = OnSurface2
                            )
                            val hour = LocalTime.now().hour
                            val timeGreeting = when {
                                hour < 12 -> strings.dashGreetingMorning
                                hour < 17 -> strings.dashGreetingAfternoon
                                else      -> strings.dashGreetingEvening
                            }
                            Text(
                                if (userName.isNotBlank()) "$timeGreeting, $userName" else timeGreeting,
                                style = MaterialTheme.typography.headlineLarge, color = OnSurface
                            )
                            if (tasks.isNotEmpty()) {
                                Text(
                                    "$completedToday / ${tasks.size} tasks done",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (completedToday == tasks.size) Success else OnSurface2
                                )
                            }
                        }
                        ShortcutTooltip("Support FocusFlow") {
                            IconButton(onClick = { showDonateDialog = true }) {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = "Support FocusFlow",
                                    tint   = Error.copy(alpha = 0.75f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // ── Hero ring — 160dp, centered ──────────────────────────
                    InfoTooltip(
                        "Counts all focus sessions today, including ones stopped early.\nSessions under 1 minute are excluded."
                    ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface2)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(strings.dashDailyFocusGoal, style = MaterialTheme.typography.titleSmall, color = OnSurface2)
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val stroke = 14.dp.toPx()
                                val radius = (size.minDimension - stroke) / 2
                                val center = Offset(size.width / 2, size.height / 2)
                                drawArc(
                                    color      = Surface3,
                                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                                    topLeft    = Offset(center.x - radius, center.y - radius),
                                    size       = Size(radius * 2, radius * 2),
                                    style      = Stroke(stroke, cap = StrokeCap.Round)
                                )
                                if (animatedGoalPct > 0f) {
                                    drawArc(
                                        color      = if (goalPct >= 1f) Success else Purple80,
                                        startAngle = -90f, sweepAngle = 360f * animatedGoalPct, useCenter = false,
                                        topLeft    = Offset(center.x - radius, center.y - radius),
                                        size       = Size(radius * 2, radius * 2),
                                        style      = Stroke(stroke, cap = StrokeCap.Round)
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (focusToday >= 60) "${focusToday / 60}h ${focusToday % 60}m" else "${focusToday}m",
                                    style      = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                                    color      = if (goalPct >= 1f) Success else Purple80,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("/ ${dailyGoal}m", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                "${(goalPct * 100).toInt()}% complete",
                                style      = MaterialTheme.typography.bodyMedium,
                                color      = if (goalPct >= 1f) Success else Purple60,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (goalPct >= 1f) {
                                Text(strings.dashGoalReached, color = Success, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    } // end InfoTooltip

                    // ── Stat cards ────────────────────────────────────────────
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(strings.dashStreakLabel,  "$streak d",       Purple80,                                                         Icons.AutoMirrored.Filled.TrendingUp, Modifier.weight(1f))
                        StatCard(strings.dashDoneLabel,   "$completedToday", Success,                                                          Icons.Default.CheckCircle,            Modifier.weight(1f))
                        StatCard(strings.dashBlockedHits, "$blockedAttempts", if (blockedAttempts > 0) Error.copy(alpha = 0.8f) else OnSurface2, Icons.Default.Block,                Modifier.weight(1f))
                        StatCard(
                            label   = strings.dashFocusScore,
                            value   = "$focusScore",
                            color   = scoreColor,
                            icon    = Icons.Default.Star,
                            modifier = Modifier.weight(1f),
                            tooltip = "Daily score 0–100\n• Up to 60 pts — progress toward your focus time goal\n• Up to 30 pts — tasks completed today\n• 10 pts — active streak bonus"
                        )
                    }

                    // ── Focus Insights (above tasks) ──────────────────────────
                    if (insights.avgSessionMinutes > 0 || insights.totalHoursAllTime > 0f) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(Surface2).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(strings.dashFocusPatterns, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val bestHour = insights.mostProductiveHour
                                val hourLabel = if (bestHour != null) {
                                    val ampm = if (bestHour < 12) "AM" else "PM"
                                    val h12  = when (bestHour) { 0 -> 12; in 1..12 -> bestHour; else -> bestHour - 12 }
                                    "${h12}${ampm}"
                                } else "—"
                                InsightChip(strings.dashPeakHour,  hourLabel,
                                    if (bestHour != null) Purple80 else OnSurface2, Modifier.weight(1f))
                                val dayLabel = insights.bestDayOfWeek
                                    ?.name?.lowercase()?.replaceFirstChar { it.uppercase() }?.take(3) ?: "—"
                                InsightChip(strings.dashBestDay,   dayLabel,
                                    if (insights.bestDayOfWeek != null) Success else OnSurface2, Modifier.weight(1f))
                                InsightChip(strings.dashAvgSession, "${insights.avgSessionMinutes}m", Warning, Modifier.weight(1f))
                                val pct = (insights.completionRate * 100).toInt()
                                InsightChip(strings.dashCompletion,  "$pct%",
                                    if (insights.completionRate >= 0.7f) Success else Warning, Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Purple80.copy(alpha = 0.08f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val h = insights.totalHoursAllTime.toInt()
                                val m = ((insights.totalHoursAllTime - h) * 60).toInt()
                                val timeStr = if (h > 0) "${h}h ${m}m total" else "${m}m total"
                                Text(timeStr, style = MaterialTheme.typography.bodySmall, color = Purple60)
                                Text("Best streak: ${insights.longestStreak}d", style = MaterialTheme.typography.bodySmall, color = Purple60)
                                Text("This week: ${insights.sessionsThisWeek} sessions", style = MaterialTheme.typography.bodySmall, color = Purple60)
                            }
                        }
                    }

                    // ── Daily allowances usage (near stat cards, before tasks) ──
                    if (allowances.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(Surface2).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(strings.dashTodayAllowances, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                            // Composite key guards against duplicate processName entries
                            // (legacy DBs without the UNIQUE constraint can yield dupes which
                            // crash with: Key "discord.exe" was already used).
                            allowances.forEachIndexed { i, a ->
                                key("${a.processName}_$i") {
                                    AllowanceBarRow(allowance = a, strings = strings)
                                }
                            }
                        }
                    }

                    // ── Today's tasks ─────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(strings.dashTodayTasks, style = MaterialTheme.typography.headlineSmall, color = OnSurface)
                        TextButton(onClick = onNavigateTasks) { Text(strings.dashViewAll, color = Purple80) }
                    }

                    if (tasks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(Surface2).padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CalendarToday, null, tint = OnSurface2, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(strings.dashNoTasksToday, color = OnSurface2)
                                TextButton(onClick = { showQuickAdd = true }) { Text(strings.dashAddATask, color = Purple80) }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tasks.take(6).forEach { task ->
                                TaskCard(
                                    task         = task,
                                    onComplete   = { scope.launch { withContext(Dispatchers.IO) { Database.completeTask(task.id) }; reload() } },
                                    onDelete     = { scope.launch { withContext(Dispatchers.IO) { Database.deleteTask(task.id) }; reload() } },
                                    onStartFocus = { onStartFocus(task) }
                                )
                            }
                            if (tasks.size > 6) {
                                TextButton(onClick = onNavigateTasks, modifier = Modifier.align(Alignment.End)) {
                                    Text("${tasks.size - 6} ${strings.dashMoreTasks}", color = Purple80)
                                }
                            }
                        }
                    }
                }

                FfVerticalScrollbar(
                    scrollState = dashScrollState,
                    modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        // ── FAB (single add-task CTA, Fix 2) ─────────────────────────────────
        FloatingActionButton(
            onClick        = { showQuickAdd = true },
            modifier       = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = Purple80,
            contentColor   = androidx.compose.ui.graphics.Color.White,
            shape          = CircleShape
        ) { Icon(Icons.Default.Add, "Add Task", modifier = Modifier.size(24.dp)) }
    }

    if (showEndPinDialog) {
        DashboardEndSessionPinDialog(
            onDismiss  = { showEndPinDialog = false },
            onVerified = { showEndPinDialog = false; FocusSessionService.end(completed = false) }
        )
    }

    if (showQuickAdd) {
        AddTaskDialog(
            onDismiss = { showQuickAdd = false },
            onSave    = { task ->
                scope.launch { withContext(Dispatchers.IO) { Database.upsertTask(task) }; reload() }
                showQuickAdd = false
            }
        )
    }

    if (showDonateDialog) {
        DonateDialog(onDismiss = { showDonateDialog = false })
    }
}

@Composable
private fun DashboardEndSessionPinDialog(onDismiss: () -> Unit, onVerified: () -> Unit) {
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
                if (error) Text(strings.dashIncorrectPinRetry, color = Error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (SessionPin.verify(pin)) onVerified() else error = true },
                colors  = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.85f))
            ) { Text(strings.focusEndBtn) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.btnCancel, color = OnSurface2) } }
    )
}

@Composable
private fun AllowanceBarRow(
    allowance: DailyAllowance,
    strings: com.focusflow.i18n.AppStrings
) {
    val usedMins  = DailyAllowanceTracker.getUsageMinutes(allowance.processName).toInt()
    val rawPct    = (usedMins.toFloat() / allowance.allowanceMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val isBlocked = allowance.processName.lowercase() in DailyAllowanceTracker.blockedProcesses

    val targetBarColor = when {
        isBlocked      -> Error.copy(alpha = 0.8f)
        rawPct > 0.75f -> Warning
        else           -> Purple80
    }
    val barColor by animateColorAsState(
        targetValue   = targetBarColor,
        animationSpec = tween(400),
        label         = "allowanceBarColor"
    )
    val animPct by animateFloatAsState(
        targetValue   = rawPct,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label         = "allowanceBarPct"
    )

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(allowance.displayName, style = MaterialTheme.typography.bodySmall, color = OnSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isBlocked) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Error.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(strings.dashBlockedTag, style = MaterialTheme.typography.bodySmall, color = Error, fontSize = 9.sp)
                    }
                }
                Text("${usedMins}m / ${allowance.allowanceMinutes}m",
                    style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Surface3)) {
            Box(modifier = Modifier.fillMaxWidth(animPct).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(barColor))
        }
    }
}

@Composable
private fun InsightChip(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label,  style = MaterialTheme.typography.bodySmall,   color = OnSurface2)
    }
}

@Composable
private fun StatCard(
    label:    String,
    value:    String,
    color:    androidx.compose.ui.graphics.Color,
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tooltip:  String?  = null
) {
    // modifier (which carries weight(1f)) must always sit on the outermost element so the
    // parent Row can measure it correctly. The card visuals use a plain Modifier internally.
    val cardContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(color.copy(alpha = 0.7f)))
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.75f), modifier = Modifier.size(20.dp))
                Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.bodySmall,     color = OnSurface2)
            }
        }
    }
    if (tooltip != null) {
        InfoTooltip(tooltip, modifier = modifier) { cardContent() }
    } else {
        Box(modifier = modifier) { cardContent() }
    }
}

// ── What's New banner ─────────────────────────────────────────────────────────

@Composable
private fun WhatsNewBanner(
    onViewChangelog: () -> Unit,
    onDismiss:       () -> Unit
) {
    // Dynamically built from the latest changelog entry — no separate version
    // or highlight list needs updating when a release is added.
    val entry = LATEST_CHANGELOG_ENTRY ?: return
    val tagIcon = mapOf(
        "NEW"  to Icons.Default.AutoAwesome,
        "FIX"  to Icons.Default.BugReport,
        "IMP"  to Icons.AutoMirrored.Filled.TrendingUp,
        "SEC"  to Icons.Default.Security,
        "UPD"  to Icons.Default.Update,
        "PERF" to Icons.Default.Speed,
        "REMOVED" to Icons.Default.DeleteOutline
    )
    val highlights = entry.changes
        .take(3)
        .map { (tag, desc) -> (tagIcon[tag] ?: Icons.Default.AutoAwesome) to desc }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Purple80.copy(alpha = 0.10f))
            .padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Sparkle icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Purple80.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Purple80, modifier = Modifier.size(20.dp))
        }

        // Text block
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "What's New in v${entry.version}",
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color      = OnSurface
            )
            highlights.forEach { (icon, text) ->
                Row(
                    verticalAlignment  = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = Purple80.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = OnSurface2, maxLines = 1)
                }
            }
        }

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick        = onViewChangelog,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("See Changelog", color = Purple80, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick        = onDismiss,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Dismiss", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val EXE_PROTECTION_NOTICE_DISMISSED = "exe_protection_notice_dismissed"
private const val FOCUSFLOW_PC_GITHUB_RELEASES =
    "https://github.com/TITANICBHAI/FocusFlow-PC/releases/latest"

@Composable
private fun ExeProtectionNotice(onDismiss: () -> Unit) {
    val noticeText = buildAnnotatedString {
        append("For stronger anti-uninstall protection, download the latest EXE from ")
        pushStringAnnotation(tag = "URL", annotation = FOCUSFLOW_PC_GITHUB_RELEASES)
        withStyle(
            SpanStyle(
                color = Purple80,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append("TITANICBHAI/FocusFlow-PC on GitHub")
        }
        pop()
        append(" and enable Nuclear Mode after installing it.")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Warning.copy(alpha = 0.10f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Warning.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Want anti-uninstall protection?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            ClickableText(
                text = noticeText,
                style = MaterialTheme.typography.bodySmall.copy(color = OnSurface2),
                onClick = { offset ->
                    noticeText
                        .getStringAnnotations("URL", offset, offset)
                        .firstOrNull()
                        ?.let { openUrl(it.item) }
                }
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = OnSurface2)
        }
    }
}

// ── Donate dialog ─────────────────────────────────────────────────────────────

@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress    = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape    = RoundedCornerShape(24.dp),
            color    = Surface,
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier            = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Favorite, null, tint = Error.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
                }

                Text(
                    "Support FocusFlow",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = OnSurface
                )
                Text(
                    "FocusFlow is free and built by one developer.\nYour support keeps it going ♥",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = OnSurface2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 20.sp
                )

                // UPI card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface2)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CreditCard, null, tint = Purple80, modifier = Modifier.size(18.dp))
                        Text("UPI  (India)", style = MaterialTheme.typography.labelMedium, color = Purple80, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "himanshu00@upi",
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Open any UPI app · Pay · Enter any amount", style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                }

                // International card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface2)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Language, null, tint = Purple80, modifier = Modifier.size(18.dp))
                        Text("International", style = MaterialTheme.typography.labelMedium, color = Purple80, fontWeight = FontWeight.SemiBold)
                    }
                    Text("GitHub Sponsors or PayPal — reach out via the Contact page for the link.", style = MaterialTheme.typography.bodySmall, color = OnSurface2, lineHeight = 18.sp)
                }

                TextButton(onClick = onDismiss) {
                    Text("Maybe later", color = OnSurface2.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        }
    }
}
