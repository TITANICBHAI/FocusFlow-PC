package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.*
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.*
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun ActiveScreen(onNavigate: (Screen) -> Unit = {}) {
    val strings            = LocalizationManager.strings
    val sessionState    by FocusSessionService.state.collectAsState()
    val standaloneBlock by StandaloneBlockService.block.collectAsState()
    val scope           = rememberCoroutineScope()

    var blockRules        by remember { mutableStateOf(listOf<BlockRule>()) }
    var schedules         by remember { mutableStateOf(listOf<BlockSchedule>()) }
    var allowances        by remember { mutableStateOf(listOf<DailyAllowance>()) }
    var todayFocusMins    by remember { mutableStateOf(0) }
    var todaySessions     by remember { mutableStateOf(0) }
    var todayCompleted    by remember { mutableStateOf(0) }
    var todayTotal        by remember { mutableStateOf(0) }
    var currentStreak     by remember { mutableStateOf(0) }
    var alwaysOnEnabled   by remember { mutableStateOf(false) }
    var keywordsEnabled   by remember { mutableStateOf(false) }
    var keywordCount      by remember { mutableStateOf(0) }
    var tick              by remember { mutableStateOf(0) }

    fun reload() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    blockRules      = Database.getBlockRules()
                    schedules       = Database.getBlockSchedules()
                    allowances      = Database.getDailyAllowances()
                    todayFocusMins  = Database.getTotalFocusMinutesToday()
                    currentStreak   = Database.getCurrentStreak()
                    alwaysOnEnabled = Database.getSetting("always_on_enforcement") == "true"
                    keywordsEnabled = Database.isKeywordBlockerEnabled()
                    keywordCount    = Database.getBlockedKeywords().size
                    val sessions = Database.getSessionsInDateRange(LocalDate.now(), LocalDate.now())
                    todaySessions   = sessions.size
                    val tasks = Database.getTasksForDate(LocalDate.now())
                    todayCompleted  = tasks.count { it.completed }
                    todayTotal      = tasks.size
                }
            } catch (_: Exception) {
                // DB temporarily unavailable — keep showing last known values
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        while (true) { delay(10_000); tick++; reload() }
    }

    val scrollState = rememberScrollState()
    val now = LocalDateTime.now()

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape)
                        .background(if (sessionState.isActive || standaloneBlock != null || alwaysOnEnabled) Success else OnSurface2)
                )
                Text(strings.activeLiveBlockStatus, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            }
            Text(strings.activeTapAnyCard, style = MaterialTheme.typography.bodyMedium, color = OnSurface2)

            // Focus Session card
            StatusCard(
                icon  = Icons.Default.Timer,
                title = strings.activeFocusSession,
                color = if (sessionState.isActive) Purple80 else OnSurface2,
                status = when {
                    sessionState.isActive && !sessionState.isPaused -> {
                        val remaining = sessionState.totalSeconds - sessionState.elapsedSeconds
                        "Active · ${remaining / 60}m ${remaining % 60}s remaining — ${sessionState.taskName.take(30)}"
                    }
                    sessionState.isActive && sessionState.isPaused -> "Paused · ${sessionState.taskName.take(30)}"
                    else -> "Inactive — tap to start a session"
                },
                active = sessionState.isActive,
                onClick = { onNavigate(Screen.FOCUS) }
            )

            // Standalone block card
            StatusCard(
                icon   = Icons.Default.Block,
                title  = strings.activeStandaloneBlock,
                color  = if (standaloneBlock != null) Warning else OnSurface2,
                status = if (standaloneBlock != null) {
                    val minsLeft = ((standaloneBlock!!.untilMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                    "Active · ${minsLeft}m remaining · ${standaloneBlock!!.processNames.size} app(s)"
                } else "Inactive — tap to block apps for a set time",
                active = standaloneBlock != null,
                onClick = { onNavigate(Screen.BLOCK_APPS) }
            )

            // Always-on enforcement
            StatusCard(
                icon   = Icons.Default.Shield,
                title  = strings.activeAlwaysOnEnforcement,
                color  = if (alwaysOnEnabled) Success else OnSurface2,
                status = if (alwaysOnEnabled)
                    "Enabled · ${blockRules.count { it.enabled }} app(s) on the block list"
                else
                    "Disabled · ${blockRules.size} app(s) configured — tap to manage",
                active = alwaysOnEnabled,
                onClick = { onNavigate(Screen.BLOCK_APPS) }
            )

            // Keyword blocker
            StatusCard(
                icon   = Icons.Default.TextFields,
                title  = strings.activeKeywordBlocker,
                color  = if (keywordsEnabled) Warning else OnSurface2,
                status = if (keywordsEnabled)
                    "Enabled · $keywordCount keyword(s) active"
                else
                    "Disabled · $keywordCount keyword(s) configured — tap to manage",
                active = keywordsEnabled,
                onClick = { onNavigate(Screen.KEYWORD_BLOCKER) }
            )

            // Block schedules
            val activeSchedules = schedules.filter { s ->
                s.isActiveAt(now)
            }
            StatusCard(
                icon   = Icons.Default.Schedule,
                title  = strings.activeBlockSchedules,
                color  = if (activeSchedules.isNotEmpty()) Warning else OnSurface2,
                status = when {
                    activeSchedules.isNotEmpty() -> "${activeSchedules.size} schedule(s) active now"
                    schedules.isEmpty() -> "No schedules configured — tap to add"
                    else -> "${schedules.count { it.enabled }} schedule(s) configured · none active now"
                },
                active = activeSchedules.isNotEmpty(),
                onClick = { onNavigate(Screen.BLOCK_DEFENSE) }
            )

            // Daily allowances
            val usingAllowance = allowances.isNotEmpty()
            StatusCard(
                icon   = Icons.Default.HourglassFull,
                title  = strings.activeDailyAllowances,
                color  = if (usingAllowance) Purple80 else OnSurface2,
                status = if (allowances.isEmpty()) "No allowances configured — tap to add"
                else "${allowances.size} app(s) with daily time limits",
                active = usingAllowance,
                onClick = { onNavigate(Screen.BLOCK_APPS) }
            )

            // Today's stats
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Surface2).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(strings.activeTodaysProgress, style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatMini(label = strings.activeFocusTime, value = "${todayFocusMins}m", modifier = Modifier.weight(1f))
                    StatMini(label = strings.activeSessions, value = "$todaySessions", modifier = Modifier.weight(1f))
                    StatMini(label = strings.activeTasksDone, value = "$todayCompleted/$todayTotal", modifier = Modifier.weight(1f))
                    StatMini(label = strings.activeStreak, value = "${currentStreak}d", modifier = Modifier.weight(1f))
                }
            }

            // Block list summary
            if (blockRules.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Surface2).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${strings.activeBlockedApps} (${blockRules.size})", style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onNavigate(Screen.BLOCK_APPS) }) {
                            Text(strings.activeManage, color = Purple80, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    blockRules.take(6).forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(7.dp).clip(CircleShape)
                                    .background(if (rule.enabled) Success else OnSurface2)
                            )
                            Text(rule.displayName, color = OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(rule.processName, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (blockRules.size > 6) {
                        Text("+ ${blockRules.size - 6} more…", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        FfVerticalScrollbar(
            scrollState = scrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp)
        )
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    color: Color,
    status: String,
    active: Boolean,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(status, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
        }
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape)
                .background(if (active) color else Surface3)
        )
        Icon(Icons.Default.ChevronRight, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun StatMini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Purple80, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = OnSurface2, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
    }
}
