package com.focusflow.ui.screens

import androidx.compose.foundation.Image
import com.focusflow.ui.components.EmptyStateCard
import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.ShortcutTooltip
import com.focusflow.ui.components.PinGateDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.BlockRule
import com.focusflow.i18n.LocalizationManager
import com.focusflow.data.models.CustomBlockPreset
import com.focusflow.data.models.DailyAllowance
import com.focusflow.enforcement.AppIconExtractor
import com.focusflow.enforcement.BlockPresets
import com.focusflow.enforcement.InstalledAppsScanner
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.ScannedApp
import com.focusflow.services.DailyAllowanceTracker
import com.focusflow.services.StandaloneBlockService
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// ── Brand colors for known apps ────────────────────────────────────────────────

private val appBrandColors = mapOf(
    "chrome.exe"            to Color(0xFF4285F4),
    "firefox.exe"           to Color(0xFFFF6611),
    "msedge.exe"            to Color(0xFF0078D7),
    "opera.exe"             to Color(0xFFCC1A22),
    "brave.exe"             to Color(0xFFFF3800),
    "discord.exe"           to Color(0xFF5865F2),
    "slack.exe"             to Color(0xFF4A154B),
    "teams.exe"             to Color(0xFF6264A7),
    "zoom.exe"              to Color(0xFF2196F3),
    "telegram.exe"          to Color(0xFF2AABEE),
    "whatsapp.exe"          to Color(0xFF25D366),
    "signal.exe"            to Color(0xFF3A76F0),
    "spotify.exe"           to Color(0xFF1DB954),
    "steam.exe"             to Color(0xFF1B2838),
    "epicgameslauncher.exe" to Color(0xFF2C2C2C),
    "origin.exe"            to Color(0xFFF56C2D),
    "battle.net.exe"        to Color(0xFF148EFF),
    "leagueclient.exe"      to Color(0xFFC89B3C),
    "twitch.exe"            to Color(0xFF9147FF),
    "obs64.exe"             to Color(0xFF302E31),
    "tiktok.exe"            to Color(0xFF010101),
    "netflix.exe"           to Color(0xFFE50914),
    "vlc.exe"               to Color(0xFFFF8800),
    "wmplayer.exe"          to Color(0xFF005A9E),
    "outlook.exe"           to Color(0xFF0078D4),
    "winword.exe"           to Color(0xFF2B579A),
    "excel.exe"             to Color(0xFF217346),
    "powerpnt.exe"          to Color(0xFFB7472A),
    "notepad++.exe"         to Color(0xFF81BF43),
    "code.exe"              to Color(0xFF007ACC),
    "devenv.exe"            to Color(0xFF68217A),
    "idea64.exe"            to Color(0xFFFF318C),
    "pycharm64.exe"         to Color(0xFF21D789),
    "webstorm64.exe"        to Color(0xFF00CDD7),
    "studio64.exe"          to Color(0xFF3DDC84)
)

@Composable
fun AppIcon(
    processName: String,
    displayName: String,
    size: Int = 38,
    exePath: String? = null
) {
    val key    = processName.lowercase()
    val brand  = appBrandColors[key]
    val color  = brand ?: Purple80.copy(alpha = 0.7f)
    val letter = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    // Resolve exe path: prefer explicit arg, then scanner cache
    val resolvedPath = remember(processName, exePath) {
        exePath ?: InstalledAppsScanner.getExePathFor(processName)
    }

    // Async icon loading — re-runs whenever the resolved path changes
    var iconBitmap by remember(resolvedPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(resolvedPath) {
        if (resolvedPath != null) {
            iconBitmap = withContext(Dispatchers.IO) {
                AppIconExtractor.extractIcon(resolvedPath)
            }
        }
    }

    val shape = RoundedCornerShape((size * 0.28f).dp)

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(if (iconBitmap != null) Color.Transparent else color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap           = iconBitmap!!,
                contentDescription = displayName,
                contentScale     = ContentScale.Fit,
                modifier         = Modifier.size(size.dp).clip(shape)
            )
        } else {
            Text(
                text       = letter,
                color      = color,
                fontSize   = (size * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
        }
    }
}

@Composable
fun AppBlockerScreen(onNavigateToBlockDefense: () -> Unit = {}) {
    val strings     = LocalizationManager.strings
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(strings.blockerTabAlwaysBlock, strings.blockerTabDailyAllowance)
    val tabIcons = listOf(
        Icons.Default.Block,
        Icons.Default.Timelapse
    )

    Column(modifier = Modifier.fillMaxSize().background(Surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Surface2)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.Block, null, tint = Purple80, modifier = Modifier.size(28.dp))
            Column {
                Text(
                    strings.blockerTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    strings.blockerSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
            }
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor   = Surface2,
            contentColor     = Purple80,
            edgePadding      = 0.dp
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick  = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selectedTab == index) Purple80 else OnSurface2
                        )
                    },
                    icon = {
                        Icon(
                            tabIcons[index], null,
                            tint = if (selectedTab == index) Purple80 else OnSurface2,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> AlwaysBlockTab(onNavigateToBlockDefense)
            1 -> DailyAllowanceTab()
        }
    }
}

// ── Standalone Block Screen (own nav entry) ────────────────────────────────────

@Composable
fun StandaloneBlockScreen() {
    val strings = LocalizationManager.strings
    Column(modifier = Modifier.fillMaxSize().background(Surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Surface2)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.Timer, null, tint = Purple80, modifier = Modifier.size(28.dp))
            Column {
                Text(
                    strings.activeStandaloneBlock,
                    style      = MaterialTheme.typography.headlineMedium,
                    color      = OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    strings.blockerTimedWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
            }
        }
        TimedBlockTab()
    }
}

// ── Always Block Tab ───────────────────────────────────────────────────────────

@Composable
private fun AlwaysBlockTab(onNavigateToBlockDefense: () -> Unit) {
    val scope   = rememberCoroutineScope()
    val strings = LocalizationManager.strings

    var blockRules    by remember { mutableStateOf(listOf<BlockRule>()) }
    var scannedApps   by remember { mutableStateOf(listOf<ScannedApp>()) }
    var isLoading     by remember { mutableStateOf(true) }
    var showPicker    by remember { mutableStateOf(false) }
    var manualEntry   by remember { mutableStateOf("") }
    var manualError   by remember { mutableStateOf<String?>(null) }
    var searchQuery   by remember { mutableStateOf("") }
    var showAllInline by remember { mutableStateOf(false) }
    var inlineSearch  by remember { mutableStateOf("") }
    var alwaysOnEnabled by remember { mutableStateOf(false) }
    var globalPinSet by remember { mutableStateOf(false) }
    var showGlobalPinGate by remember { mutableStateOf(false) }
    var pendingGlobalAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun reload() {
        scope.launch {
            val rules   = withContext(Dispatchers.IO) { Database.getBlockRules() }
            val running = withContext(Dispatchers.IO) { InstalledAppsScanner.getRunningApps() }
            val curated = withContext(Dispatchers.IO) { InstalledAppsScanner.getCuratedApps() }
            val runningNames = running.map { it.processName }.toSet()
            blockRules  = rules
            scannedApps = running + curated.filter { it.processName !in runningNames }
            alwaysOnEnabled = Database.getSetting("always_on_enforcement") == "true"
            globalPinSet = com.focusflow.services.GlobalPin.isSet()
            isLoading   = false
        }
    }

    fun guardedRemoval(action: () -> Unit) {
        if (globalPinSet) {
            pendingGlobalAction = action
            showGlobalPinGate = true
        } else {
            action()
        }
    }

    fun addManual(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) { manualError = "Enter a process name (e.g. chrome.exe)"; return }
        val proc = trimmed.lowercase().let { if (it.endsWith(".exe")) it else "$it.exe" }
        if (proc == ".exe" || proc.length <= 4) { manualError = "Name must end in .exe (e.g. chrome.exe)"; return }
        if (blockRules.any { it.processName.equals(proc, ignoreCase = true) }) {
            manualError = "\"$proc\" is already in your block list"; return
        }
        manualError = null
        scope.launch {
            withContext(Dispatchers.IO) {
                Database.upsertBlockRule(
                    BlockRule(
                        id           = UUID.randomUUID().toString(),
                        processName  = proc,
                        displayName  = InstalledAppsScanner.friendlyNameFor(proc),
                        enabled      = true,
                        blockNetwork = false
                    )
                )
            }
            manualEntry = ""
            reload()
        }
    }

    LaunchedEffect(Unit) { reload() }

    val filteredRules = remember(searchQuery, blockRules) {
        if (searchQuery.isBlank()) blockRules
        else blockRules.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.processName.contains(searchQuery, ignoreCase = true)
        }
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Info banner ──────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Purple80.copy(alpha = 0.10f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Info, null, tint = Purple80, modifier = Modifier.size(20.dp))
                    Text(
                        strings.blockerAlwaysOnDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!alwaysOnEnabled) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Error.copy(alpha = 0.14f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Error, modifier = Modifier.size(22.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "ATTENTION",
                                color = Error,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "These apps are saved in your block list, but Always-On Enforcement is OFF. " +
                                    "Turn it on in Block Defense or the apps will only be blocked during focus sessions.",
                                color = OnSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = onNavigateToBlockDefense,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                            ) {
                                Text("Open Block Defense →", color = Error, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ── Inline apps ──────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface2)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Apps, null, tint = OnSurface2, modifier = Modifier.size(14.dp))
                            Text(
                                if (showAllInline) strings.blockerAllApps else strings.blockerRunningNow,
                                style = MaterialTheme.typography.titleSmall,
                                color = OnSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "· tap + to block",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface2
                            )
                        }
                        TextButton(
                            onClick = { showAllInline = !showAllInline },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (showAllInline) strings.blockerRunningOnly else strings.blockerShowAll,
                                color = Purple80,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    // ── Search field ─────────────────────────────────────────
                    OutlinedTextField(
                        value         = inlineSearch,
                        onValueChange = { inlineSearch = it },
                        placeholder   = { Text("Search by name or .exe…", color = OnSurface2, fontSize = 12.sp) },
                        leadingIcon   = { Icon(Icons.Default.Search, null, tint = OnSurface2, modifier = Modifier.size(16.dp)) },
                        trailingIcon  = if (inlineSearch.isNotBlank()) {
                            { IconButton(onClick = { inlineSearch = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, null, tint = OnSurface2, modifier = Modifier.size(14.dp))
                            } }
                        } else null,
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth().height(46.dp),
                        textStyle     = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = OnSurface),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Purple80,
                            unfocusedBorderColor = Surface3,
                            cursorColor          = Purple80
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Purple80
                            )
                        }
                    } else {
                        val baseList = if (showAllInline) scannedApps
                            else scannedApps.filter { it.isRunning }.let { running ->
                                if (inlineSearch.isBlank()) running.take(10).ifEmpty { scannedApps.take(10) }
                                else running.ifEmpty { scannedApps }
                            }
                        val displayList = if (inlineSearch.isBlank()) baseList
                            else baseList.filter {
                                it.displayName.contains(inlineSearch, ignoreCase = true) ||
                                it.processName.contains(inlineSearch, ignoreCase = true)
                            }
                        if (displayList.isEmpty()) {
                            Text(
                                strings.blockerNoAppsDetected,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface2
                            )
                        } else {
                            displayList.forEach { app ->
                                val alreadyInList = blockRules.any {
                                    it.processName.equals(app.processName, ignoreCase = true)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (alreadyInList) Surface3.copy(alpha = 0.5f) else Surface3
                                        )
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AppIcon(app.processName, app.displayName, size = 30)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                app.displayName,
                                                color = if (alreadyInList) OnSurface2 else OnSurface,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (app.isRunning) {
                                                Box(
                                                    modifier = Modifier.size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(Success)
                                                )
                                            }
                                        }
                                        Text(
                                            app.processName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurface2,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (alreadyInList) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Purple80.copy(alpha = 0.12f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "blocked",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Purple80
                                            )
                                        }
                                    } else {
                                        ShortcutTooltip("Block this app") {
                                            IconButton(
                                                onClick = { addManual(app.processName) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Add, null,
                                                    tint = Purple80,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Manual entry ─────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface2)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit, null,
                            tint = OnSurface2, modifier = Modifier.size(16.dp)
                        )
                        Text(
                            strings.blockerManualEntry,
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        strings.blockerManualEntryHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualEntry,
                            onValueChange = { manualEntry = it; manualError = null },
                            placeholder = { Text("e.g. discord.exe", color = OnSurface2) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = manualError != null,
                            supportingText = manualError?.let { err -> { Text(err, color = Error) } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addManual(manualEntry) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = Purple80,
                                unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                                focusedTextColor     = OnSurface,
                                unfocusedTextColor   = OnSurface,
                                errorBorderColor     = Error
                            )
                        )
                        Button(
                            onClick = { addManual(manualEntry) },
                            enabled = manualEntry.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple80),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.blockerBlock, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Rules list ───────────────────────────────────────────────────
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Purple80) }
                }
            } else if (blockRules.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon        = Icons.Default.Block,
                        title       = strings.blockerNoAppsBlockedTitle,
                        message     = strings.blockerNoAppsBlockedBody,
                        actionLabel = strings.blockerPickFromList,
                        onAction    = { showPicker = true },
                        modifier    = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${blockRules.size} app${if (blockRules.size == 1) "" else "s"} permanently blocked",
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface2,
                            fontWeight = FontWeight.Medium
                        )
                        if (blockRules.size > 4) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(strings.blockerSearchPlaceholder, color = OnSurface2, fontSize = 12.sp) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search, null,
                                        tint = OnSurface2, modifier = Modifier.size(16.dp)
                                    )
                                },
                                trailingIcon = if (searchQuery.isNotBlank()) {
                                    {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close, null,
                                                tint = OnSurface2, modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                } else null,
                                modifier = Modifier.width(200.dp).height(46.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Purple80,
                                    unfocusedBorderColor = OnSurface2.copy(alpha = 0.3f),
                                    focusedTextColor     = OnSurface,
                                    unfocusedTextColor   = OnSurface
                                )
                            )
                        }
                    }
                }

                if (filteredRules.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${strings.blockerNoAppsMatch} \"$searchQuery\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface2
                            )
                        }
                    }
                } else {
                    // Composite key guards against legacy DB rows where id may equal
                    // processName, which causes: Key "discord.exe" was already used.
                    itemsIndexed(filteredRules, key = { i, it -> "${it.id}_$i" }) { _, rule ->
                        BlockRuleCard(
                            rule = rule,
                            onToggle = { enabled ->
                                val action: () -> Unit = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Database.upsertBlockRule(rule.copy(enabled = enabled))
                                        }
                                        if (!enabled) NetworkBlocker.removeRule(rule.processName)
                                        reload()
                                    }
                                }
                                if (enabled) action() else guardedRemoval(action)
                            },
                            onDelete = {
                                guardedRemoval {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { Database.deleteBlockRule(rule.id) }
                                        NetworkBlocker.removeRule(rule.processName)
                                        reload()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        FfVerticalScrollbar(
            listState = listState,
            modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }

    if (showPicker) {
        AppPickerDialog(
            scannedApps       = scannedApps,
            alreadyBlocked    = blockRules.map { it.processName.lowercase() }.toSet(),
            title             = strings.blockerPickAlwaysTitle,
            confirmLabel      = strings.blockerBlockSelected,
            confirmColor      = Purple80,
            showNetworkToggle = true,
            showPresets       = true,
            onDismiss = { showPicker = false },
            onConfirm = { picked, networkMap ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        picked.forEach { app ->
                            Database.upsertBlockRule(
                                BlockRule(
                                    id           = UUID.randomUUID().toString(),
                                    processName  = app.processName.lowercase(),
                                    displayName  = app.displayName,
                                    enabled      = true,
                                    blockNetwork = networkMap[app.processName] ?: false
                                )
                            )
                        }
                    }
                    showPicker = false
                    reload()
                }
            }
        )
    }

    if (showGlobalPinGate) {
        PinGateDialog(
            title = "Global PIN required",
            subtitle = "Enter your Global PIN to disable or remove a blocked app.",
            allowReset = false,
            onSuccess = {
                showGlobalPinGate = false
                pendingGlobalAction?.invoke()
                pendingGlobalAction = null
            },
            onDismiss = {
                showGlobalPinGate = false
                pendingGlobalAction = null
            }
        )
    }
}

@Composable
private fun BlockRuleCard(rule: BlockRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val strings = LocalizationManager.strings
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(processName = rule.processName, displayName = rule.displayName, size = 40)

        Column(modifier = Modifier.weight(1f)) {
            Text(rule.displayName, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    rule.processName,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (rule.blockNetwork) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Warning.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(strings.blockerNetworkBadge, style = MaterialTheme.typography.labelSmall, color = Warning)
                    }
                }
                if (!rule.enabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(OnSurface2.copy(alpha = 0.10f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(strings.blockerPausedBadge, style = MaterialTheme.typography.labelSmall, color = OnSurface2)
                    }
                }
            }
        }

        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Purple80,
                checkedTrackColor = Purple80.copy(alpha = 0.35f)
            )
        )
        ShortcutTooltip("Remove rule") {
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.DeleteOutline, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyBlockState() {
    val strings = LocalizationManager.strings
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Block, null, tint = OnSurface2, modifier = Modifier.size(36.dp))
        }
        Text(strings.blockerNoAppsBlockedTitle, style = MaterialTheme.typography.titleMedium, color = OnSurface)
        Text(
            strings.blockerNoAppsBlockedBody,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface2,
            textAlign = TextAlign.Center
        )
    }
}

// ── Daily Allowance Tab ────────────────────────────────────────────────────────

private val allowanceOptions = listOf(
    15  to "15m",
    30  to "30m",
    45  to "45m",
    60  to "1h",
    90  to "1h 30m",
    120 to "2h",
    180 to "3h",
    240 to "4h"
)

@Composable
private fun DailyAllowanceTab() {
    val scope   = rememberCoroutineScope()
    val strings = LocalizationManager.strings

    var allowances  by remember { mutableStateOf(listOf<DailyAllowance>()) }
    var scannedApps by remember { mutableStateOf(listOf<ScannedApp>()) }
    var isLoading   by remember { mutableStateOf(true) }
    var showPicker  by remember { mutableStateOf(false) }
    var editTarget  by remember { mutableStateOf<DailyAllowance?>(null) }
    var tick        by remember { mutableStateOf(0) }
    var globalPinSet by remember { mutableStateOf(false) }
    var showGlobalPinGate by remember { mutableStateOf(false) }
    var pendingGlobalAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun reload() {
        scope.launch {
            allowances  = withContext(Dispatchers.IO) { Database.getDailyAllowances() }
            val running = withContext(Dispatchers.IO) { InstalledAppsScanner.getRunningApps() }
            val curated = withContext(Dispatchers.IO) { InstalledAppsScanner.getCuratedApps() }
            val runningNames = running.map { it.processName }.toSet()
            scannedApps = running + curated.filter { it.processName !in runningNames }
            isLoading   = false
            DailyAllowanceTracker.reload()
            globalPinSet = withContext(Dispatchers.IO) { com.focusflow.services.GlobalPin.isSet() }
        }
    }

    fun guardedRemoval(action: () -> Unit) {
        if (globalPinSet) {
            pendingGlobalAction = action
            showGlobalPinGate = true
        } else {
            action()
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Live tick every second to update progress bars
    LaunchedEffect(Unit) {
        while (true) { delay(1000); tick++ }
    }

    val blockedToday = remember(tick) { DailyAllowanceTracker.blockedProcesses }
    val alreadyAllowed = remember(allowances) { allowances.map { it.processName.lowercase() }.toSet() }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Info banner ─────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Warning.copy(alpha = 0.08f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Timelapse, null,
                        tint = Warning, modifier = Modifier.size(20.dp)
                    )
                    Text(
                        strings.blockerAllowanceDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Add button ──────────────────────────────────────────────────
            item {
                Button(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Warning.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.blockerAddDailyAllowance, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            // ── Loading / empty ─────────────────────────────────────────────
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Warning) }
                }
            } else if (allowances.isEmpty()) {
                item { EmptyAllowanceState() }
            } else {
                item {
                    Text(
                        "${allowances.size} app${if (allowances.size == 1) "" else "s"} with daily limits",
                        style = MaterialTheme.typography.titleSmall,
                        color = OnSurface2,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Composite key guards against duplicate processName entries (e.g. the same
                // exe added twice before a UNIQUE constraint was enforced) which would cause
                // IllegalArgumentException: Key "x.exe" was already used in the LazyColumn.
                itemsIndexed(allowances, key = { i, it -> "${it.processName}_$i" }) { _, allowance ->
                    val usedMinutes = remember(tick) {
                        DailyAllowanceTracker.getUsageMinutes(allowance.processName)
                    }
                    val remaining = remember(tick) {
                        DailyAllowanceTracker.getRemainingMinutes(allowance)
                    }
                    val isBlockedToday = allowance.processName.lowercase() in blockedToday

                    AllowanceCard(
                        allowance      = allowance,
                        usedMinutes    = usedMinutes,
                        remainingMinutes = remaining,
                        isBlockedToday = isBlockedToday,
                        onEdit         = { editTarget = allowance },
                        onDelete       = {
                            guardedRemoval {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Database.deleteDailyAllowance(allowance.processName)
                                    }
                                    DailyAllowanceTracker.reload()
                                    reload()
                                }
                            }
                        }
                    )
                }
            }
        }

        FfVerticalScrollbar(
            listState = listState,
            modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }

    // ── Add allowance flow ─────────────────────────────────────────────────────
    if (showPicker) {
        AllowancePickerDialog(
            scannedApps    = scannedApps,
            alreadyAllowed = alreadyAllowed,
            onDismiss      = { showPicker = false },
            onConfirm      = { processName, displayName, minutes ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        Database.upsertDailyAllowance(
                            DailyAllowance(processName, displayName, minutes)
                        )
                    }
                    DailyAllowanceTracker.reload()
                    showPicker = false
                    reload()
                }
            }
        )
    }

    // ── Edit allowance minutes ─────────────────────────────────────────────────
    editTarget?.let { target ->
        EditAllowanceDialog(
            allowance = target,
            onDismiss = { editTarget = null },
            onSave    = { newMinutes ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        Database.upsertDailyAllowance(
                            target.copy(allowanceMinutes = newMinutes)
                        )
                    }
                    DailyAllowanceTracker.reload()
                    editTarget = null
                    reload()
                }
            }
        )
    }

    if (showGlobalPinGate) {
        PinGateDialog(
            title = "Global PIN required",
            subtitle = "Enter your Global PIN to remove a daily allowance.",
            allowReset = false,
            onSuccess = {
                showGlobalPinGate = false
                pendingGlobalAction?.invoke()
                pendingGlobalAction = null
            },
            onDismiss = {
                showGlobalPinGate = false
                pendingGlobalAction = null
            }
        )
    }
}

@Composable
private fun AllowanceCard(
    allowance:        DailyAllowance,
    usedMinutes:      Long,
    remainingMinutes: Long,
    isBlockedToday:   Boolean,
    onEdit:           () -> Unit,
    onDelete:         () -> Unit
) {
    val strings  = LocalizationManager.strings
    val progress = if (allowance.allowanceMinutes > 0)
        (usedMinutes.toFloat() / allowance.allowanceMinutes.toFloat()).coerceIn(0f, 1f)
    else 0f

    val barColor = when {
        isBlockedToday  -> Error
        progress > 0.8f -> Warning
        else            -> Success
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isBlockedToday  -> Error.copy(alpha = 0.06f)
                    progress > 0.8f -> Warning.copy(alpha = 0.05f)
                    else            -> Surface2
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(
                processName = allowance.processName,
                displayName = allowance.displayName,
                size = 42
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        allowance.displayName,
                        color = OnSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isBlockedToday) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Error.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                strings.blockerBlockedUntilMidnight,
                                style = MaterialTheme.typography.labelSmall,
                                color = Error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Text(
                    allowance.processName,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            ShortcutTooltip("Edit allowance") {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                }
            }
            ShortcutTooltip("Delete allowance") {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                }
            }
        }

        // ── Progress bar ───────────────────────────────────────────────────
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color     = barColor,
            trackColor = Surface3
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatMinutes(usedMinutes) + " used",
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${strings.blockerLimit} ${formatMinutes(allowance.allowanceMinutes.toLong())}",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurface2
            )
            if (!isBlockedToday) {
                Text(
                    formatMinutes(remainingMinutes) + " left",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurface2
                )
            }
        }
    }
}

private fun formatMinutes(mins: Long): String {
    if (mins <= 0L) return "0m"
    val h = mins / 60
    val m = mins % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0           -> "${h}h"
        else            -> "${m}m"
    }
}

@Composable
private fun EmptyAllowanceState() {
    val strings = LocalizationManager.strings
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Timelapse, null, tint = OnSurface2, modifier = Modifier.size(36.dp))
        }
        Text(strings.blockerNoDailyLimitsTitle, style = MaterialTheme.typography.titleMedium, color = OnSurface)
        Text(
            strings.blockerNoDailyLimitsBody,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

// ── Allowance Picker Dialog (pick app → pick minutes) ─────────────────────────

@Composable
private fun AllowancePickerDialog(
    scannedApps:    List<ScannedApp>,
    alreadyAllowed: Set<String>,
    onDismiss:      () -> Unit,
    onConfirm:      (processName: String, displayName: String, minutes: Int) -> Unit
) {
    val strings         = LocalizationManager.strings
    var step            by remember { mutableStateOf(0) } // 0 = pick app, 1 = pick minutes
    var pickedApp       by remember { mutableStateOf<ScannedApp?>(null) }
    var selectedMinutes by remember { mutableStateOf(60) }
    var customInput     by remember { mutableStateOf("") }
    var search          by remember { mutableStateOf("") }
    var showAll         by remember { mutableStateOf(false) }
    var manualExe       by remember { mutableStateOf("") }

    val runningApps = remember(scannedApps) { scannedApps.filter { it.isRunning } }
    val sourceList  = if (showAll) scannedApps else runningApps
    val filtered    = remember(search, sourceList) {
        if (search.isBlank()) sourceList
        else sourceList.filter {
            it.displayName.contains(search, ignoreCase = true) ||
            it.processName.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        modifier         = Modifier.width(520.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Timelapse, null, tint = Warning, modifier = Modifier.size(20.dp))
                    Text(
                        if (step == 0) strings.blockerChooseApp else "${strings.blockerSetDailyLimitFor} ${pickedApp?.displayName ?: ""}",
                        color = OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (step == 0) {
                    Text(
                        strings.blockerStep1,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2
                    )
                } else {
                    Text(
                        strings.blockerStep2,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2
                    )
                }
            }
        },
        text = {
            if (step == 0) {
                // ── Step 1: App picker ─────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text(strings.blockerSearchApps, color = OnSurface2) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                        },
                        modifier  = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Warning,
                            unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                            focusedTextColor     = OnSurface,
                            unfocusedTextColor   = OnSurface
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !showAll,
                            onClick  = { showAll = false },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Success))
                                    Text("${strings.blockerRunning} (${runningApps.size})", style = MaterialTheme.typography.labelSmall)
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
                            label    = {
                                Text("${strings.blockerAllApps} (${scannedApps.size})", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Warning.copy(alpha = 0.15f),
                                selectedLabelColor     = Warning
                            )
                        )
                    }

                    val pickerState = rememberLazyListState()
                    Box(modifier = Modifier.height(280.dp)) {
                        LazyColumn(
                            state = pickerState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Manual entry row
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Surface3)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit, null,
                                        tint = OnSurface2, modifier = Modifier.size(16.dp)
                                    )
                                    OutlinedTextField(
                                        value = manualExe,
                                        onValueChange = { manualExe = it },
                                        placeholder = { Text(strings.blockerTypeName, color = OnSurface2, fontSize = 12.sp) },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = {
                                            if (manualExe.isNotBlank()) {
                                                val proc = manualExe.trim().lowercase()
                                                    .let { if (it.endsWith(".exe")) it else "$it.exe" }
                                                pickedApp = ScannedApp(
                                                    processName = proc,
                                                    displayName = InstalledAppsScanner.friendlyNameFor(proc),
                                                    isRunning   = false
                                                )
                                                step = 1
                                            }
                                        }),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor   = Warning,
                                            unfocusedBorderColor = OnSurface2.copy(alpha = 0.3f),
                                            focusedTextColor     = OnSurface,
                                            unfocusedTextColor   = OnSurface
                                        )
                                    )
                                    TextButton(
                                        onClick = {
                                            if (manualExe.isNotBlank()) {
                                                val proc = manualExe.trim().lowercase()
                                                    .let { if (it.endsWith(".exe")) it else "$it.exe" }
                                                pickedApp = ScannedApp(
                                                    processName = proc,
                                                    displayName = InstalledAppsScanner.friendlyNameFor(proc),
                                                    isRunning   = false
                                                )
                                                step = 1
                                            }
                                        },
                                        enabled = manualExe.isNotBlank()
                                    ) { Text(strings.blockerUseArrow, color = Warning) }
                                }
                            }

                            if (filtered.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            strings.blockerNoAppsFound,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurface2,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                // Composite key guards against duplicate processName entries from
                                // the scanner causing an IllegalStateException in Compose.
                                itemsIndexed(filtered, key = { i, it -> "${it.processName}_$i" }) { _, app ->
                                    val isAlready = app.processName.lowercase() in alreadyAllowed
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isAlready) Surface3.copy(alpha = 0.5f) else Surface3)
                                            .clickable(enabled = !isAlready) {
                                                pickedApp = app
                                                step = 1
                                            }
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AppIcon(app.processName, app.displayName, size = 34)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    app.displayName,
                                                    color = if (isAlready) OnSurface2 else OnSurface,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 13.sp
                                                )
                                                if (app.isRunning) {
                                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Success))
                                                }
                                                if (isAlready) {
                                                    Box(
                                                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                                            .background(Warning.copy(alpha = 0.12f))
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("has limit", style = MaterialTheme.typography.labelSmall, color = Warning)
                                                    }
                                                }
                                            }
                                            Text(app.processName, style = MaterialTheme.typography.bodySmall, color = OnSurface2, fontSize = 10.sp)
                                        }
                                        Icon(
                                            Icons.Default.ChevronRight, null,
                                            tint = if (isAlready) OnSurface2.copy(alpha = 0.3f) else OnSurface2,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        FfVerticalScrollbar(
                            listState = pickerState,
                            modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            } else {
                // ── Step 2: Pick minutes ───────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface3)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pickedApp?.let { app ->
                            AppIcon(app.processName, app.displayName, size = 36)
                            Column {
                                Text(app.displayName, color = OnSurface, fontWeight = FontWeight.SemiBold)
                                Text(app.processName, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                            }
                        }
                    }

                    Text(
                        strings.blockerHowLongPerDay,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface2
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allowanceOptions.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (mins, label) ->
                                    FilterChip(
                                        selected = selectedMinutes == mins && customInput.isBlank(),
                                        onClick  = { selectedMinutes = mins; customInput = "" },
                                        label    = {
                                            Text(
                                                label,
                                                fontWeight = if (selectedMinutes == mins && customInput.isBlank()) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Warning.copy(alpha = 0.20f),
                                            selectedLabelColor     = Warning
                                        )
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { raw ->
                            customInput = raw
                            val parsed = raw.trim().toIntOrNull()
                            if (parsed != null && parsed in 1..1440) selectedMinutes = parsed
                        },
                        label = { Text("Custom minutes (1–1440)", color = OnSurface2) },
                        placeholder = { Text("e.g. 75", color = OnSurface2.copy(alpha = 0.5f)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = customInput.isNotBlank() && (customInput.trim().toIntOrNull()?.let { it in 1..1440 } != true),
                        supportingText = if (customInput.isNotBlank() && (customInput.trim().toIntOrNull()?.let { it in 1..1440 } != true))
                            { { Text("Enter a number between 1 and 1440", color = Error) } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Warning,
                            unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                            focusedLabelColor    = Warning,
                            focusedTextColor     = OnSurface,
                            unfocusedTextColor   = OnSurface
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Warning.copy(alpha = 0.07f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info, null,
                            tint = Warning, modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "${strings.blockerAfterLimit} ${formatMinutes(selectedMinutes.toLong())} ${strings.blockerWillBlockRest}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (step == 0) {
                // No confirm on step 1 — tapping an app advances the step
            } else {
                Button(
                    onClick = {
                        pickedApp?.let { app ->
                            onConfirm(app.processName, app.displayName, selectedMinutes)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Warning.copy(alpha = 0.85f))
                ) { Text(strings.blockerSetLimit) }
            }
        },
        dismissButton = {
            if (step == 1) {
                TextButton(onClick = { step = 0 }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.btnBack, color = OnSurface2)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) }
            }
        }
    )
}

@Composable
private fun EditAllowanceDialog(
    allowance: DailyAllowance,
    onDismiss: () -> Unit,
    onSave:    (Int) -> Unit
) {
    val strings         = LocalizationManager.strings
    var selectedMinutes by remember { mutableStateOf(allowance.allowanceMinutes) }
    var customInput     by remember {
        mutableStateOf(
            if (allowanceOptions.any { it.first == allowance.allowanceMinutes }) "" else allowance.allowanceMinutes.toString()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        modifier         = Modifier.width(420.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(allowance.processName, allowance.displayName, size = 36)
                Column {
                    Text(
                        strings.blockerEditDailyLimit,
                        color = OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(allowance.displayName, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    strings.blockerNewAllowance,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface2
                )
                allowanceOptions.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (mins, label) ->
                            FilterChip(
                                selected = selectedMinutes == mins && customInput.isBlank(),
                                onClick  = { selectedMinutes = mins; customInput = "" },
                                label    = {
                                    Text(
                                        label,
                                        fontWeight = if (selectedMinutes == mins && customInput.isBlank()) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Warning.copy(alpha = 0.20f),
                                    selectedLabelColor     = Warning
                                )
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { raw ->
                        customInput = raw
                        val parsed = raw.trim().toIntOrNull()
                        if (parsed != null && parsed in 1..1440) selectedMinutes = parsed
                    },
                    label = { Text("Custom minutes (1–1440)", color = OnSurface2) },
                    placeholder = { Text("e.g. 75", color = OnSurface2.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = customInput.isNotBlank() && (customInput.trim().toIntOrNull()?.let { it in 1..1440 } != true),
                    supportingText = if (customInput.isNotBlank() && (customInput.trim().toIntOrNull()?.let { it in 1..1440 } != true))
                        { { Text("Enter a number between 1 and 1440", color = Error) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Warning,
                        unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                        focusedLabelColor    = Warning,
                        focusedTextColor     = OnSurface,
                        unfocusedTextColor   = OnSurface
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedMinutes) },
                enabled = customInput.isBlank() || (customInput.trim().toIntOrNull()?.let { it in 1..1440 } == true),
                colors  = ButtonDefaults.buttonColors(containerColor = Warning.copy(alpha = 0.85f))
            ) { Text(LocalizationManager.strings.btnSave) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) }
        }
    )
}

// ── Timed Block Tab ────────────────────────────────────────────────────────────

@Composable
private fun TimedBlockTab() {
    val strings         = LocalizationManager.strings
    val standaloneBlock by StandaloneBlockService.block.collectAsState()
    var scannedApps     by remember { mutableStateOf(listOf<ScannedApp>()) }
    var showPicker      by remember { mutableStateOf(false) }
    var selectedHours   by remember { mutableStateOf(1) }
    var selectedApps    by remember { mutableStateOf(setOf<String>()) }
    var isLoading       by remember { mutableStateOf(true) }

    // Mode: 0 = Duration, 1 = Date Range
    var scheduleMode by remember { mutableStateOf(0) }

    // Date-range fields
    val todayDate = remember { LocalDate.now() }
    var startDate by remember { mutableStateOf(todayDate) }
    var startHour by remember { mutableStateOf(LocalTime.now().hour) }
    var startMin  by remember { mutableStateOf(0) }
    var endDate   by remember { mutableStateOf(todayDate) }
    var endHour   by remember { mutableStateOf((LocalTime.now().hour + 1).coerceAtMost(23)) }
    var endMin    by remember { mutableStateOf(0) }

    // Live tick so timer stays updated
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(standaloneBlock) {
        while (true) { delay(1000); tick++ }
    }

    val isScheduled  = standaloneBlock != null && StandaloneBlockService.isScheduled
    val remainingMs  = StandaloneBlockService.remainingMs()
    val startsInMs   = StandaloneBlockService.startsInMs()
    val blockedNames = standaloneBlock?.processNames ?: emptyList()

    LaunchedEffect(Unit) {
        val running = withContext(Dispatchers.IO) { InstalledAppsScanner.getRunningApps() }
        val curated = withContext(Dispatchers.IO) { InstalledAppsScanner.getCuratedApps() }
        val runningNames = running.map { it.processName }.toSet()
        scannedApps = running + curated.filter { it.processName !in runningNames }
        isLoading = false
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Warning.copy(alpha = 0.08f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Timer, null, tint = Warning, modifier = Modifier.size(20.dp))
                    Text(
                        strings.blockerTimedWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (isScheduled) {
                item {
                    ActiveTimedBlock(
                        remainingMs  = remainingMs,
                        startsInMs   = startsInMs,
                        blockedNames = blockedNames,
                        onAddTime    = { StandaloneBlockService.addTime(it * 60_000L) }
                    )
                }
            } else {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface2)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            strings.blockerConfigureTimedBlock,
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface,
                            fontWeight = FontWeight.SemiBold
                        )

                        // ── Mode toggle ────────────────────────────────────────────
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surface3)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(strings.blockerDuration, strings.blockerDateRange).forEachIndexed { idx, label ->
                                Box(
                                    modifier = Modifier.weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (scheduleMode == idx) Purple80.copy(alpha = 0.20f)
                                            else Color.Transparent
                                        )
                                        .clickable { scheduleMode = idx }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        color = if (scheduleMode == idx) Purple80 else OnSurface2,
                                        fontWeight = if (scheduleMode == idx) FontWeight.SemiBold
                                                     else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        if (scheduleMode == 0) {
                            // ── Duration mode ──────────────────────────────────────
                            Text(strings.blockerDuration, style = MaterialTheme.typography.bodyMedium, color = OnSurface2)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1 to "1h", 2 to "2h", 4 to "4h", 8 to "8h", 12 to "12h")
                                    .forEach { (h, label) ->
                                        FilterChip(
                                            selected = selectedHours == h,
                                            onClick  = { selectedHours = h },
                                            label    = { Text(label) },
                                            colors   = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Purple80.copy(alpha = 0.2f),
                                                selectedLabelColor     = Purple80
                                            )
                                        )
                                    }
                            }
                        } else {
                            // ── Date Range mode ────────────────────────────────────
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Start date/time
                                Text(
                                    strings.blockerStart,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OnSurface2,
                                    fontWeight = FontWeight.SemiBold
                                )
                                DateTimePicker(
                                    date      = startDate,
                                    hour      = startHour,
                                    minute    = startMin,
                                    accentColor = Purple80,
                                    onDateChange  = { startDate = it },
                                    onHourChange  = { startHour = it },
                                    onMinChange   = { startMin  = it }
                                )

                                HorizontalDivider(color = Surface3)

                                // End date/time
                                Text(
                                    strings.blockerEnd,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OnSurface2,
                                    fontWeight = FontWeight.SemiBold
                                )
                                DateTimePicker(
                                    date      = endDate,
                                    hour      = endHour,
                                    minute    = endMin,
                                    minDate   = startDate,
                                    accentColor = Error,
                                    onDateChange  = { endDate = it },
                                    onHourChange  = { endHour = it },
                                    onMinChange   = { endMin  = it }
                                )

                                // Duration preview
                                val startEpoch = LocalDateTime.of(startDate, LocalTime.of(startHour, startMin))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val endEpoch = LocalDateTime.of(endDate, LocalTime.of(endHour, endMin))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val durationMins = (endEpoch - startEpoch) / 60_000L
                                if (durationMins > 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Purple80.copy(alpha = 0.07f))
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Schedule, null,
                                            tint = Purple80, modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            "${strings.blockerBlockDuration} ${formatMinutes(durationMins)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurface2
                                        )
                                    }
                                } else if (durationMins <= 0) {
                                    Text(
                                        strings.blockerEndAfterStart,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Error
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = Surface3)

                        Text(strings.blockerAppsToBlock, style = MaterialTheme.typography.bodyMedium, color = OnSurface2)

                        if (selectedApps.isEmpty()) {
                            Text(
                                strings.blockerNoAppsSelected,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface2
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                selectedApps.forEach { proc ->
                                    val app = scannedApps.find { it.processName.equals(proc, ignoreCase = true) }
                                    val friendly = app?.displayName ?: InstalledAppsScanner.friendlyNameFor(proc)
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Surface3)
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AppIcon(processName = proc, displayName = friendly, size = 32)
                                        Text(friendly, color = OnSurface, modifier = Modifier.weight(1f))
                                        Text(proc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                                        ShortcutTooltip("Remove") {
                                            IconButton(
                                                onClick = { selectedApps = selectedApps - proc },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close, null,
                                                    tint = OnSurface2, modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Purple80)
                        ) {
                            Icon(Icons.Default.Apps, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (selectedApps.isEmpty()) strings.blockerPickApps else strings.blockerChangeAppSelection)
                        }

                        // ── Start button ───────────────────────────────────────────
                        val canStart = selectedApps.isNotEmpty() && run {
                            if (scheduleMode == 1) {
                                val sEpoch = LocalDateTime.of(startDate, LocalTime.of(startHour, startMin))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val eEpoch = LocalDateTime.of(endDate, LocalTime.of(endHour, endMin))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                eEpoch > sEpoch
                            } else true
                        }

                        Button(
                            onClick = {
                                if (!canStart) return@Button
                                if (scheduleMode == 0) {
                                    StandaloneBlockService.start(
                                        selectedApps.toList(),
                                        selectedHours * 3_600_000L
                                    )
                                } else {
                                    val sEpoch = LocalDateTime.of(startDate, LocalTime.of(startHour, startMin))
                                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                    val eEpoch = LocalDateTime.of(endDate, LocalTime.of(endHour, endMin))
                                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                    val now = System.currentTimeMillis()
                                    val scheduledStart = if (sEpoch > now) sEpoch else null
                                    StandaloneBlockService.start(
                                        selectedApps.toList(),
                                        eEpoch - maxOf(sEpoch, now),
                                        scheduledStart
                                    )
                                }
                            },
                            enabled  = canStart,
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = 0.85f)),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (scheduleMode == 1 && run {
                                    val sEpoch = LocalDateTime.of(startDate, LocalTime.of(startHour, startMin))
                                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                    sEpoch > System.currentTimeMillis()
                                }) Icons.Default.Schedule else Icons.Default.Block,
                                null, modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            val appWord = if (selectedApps.size == 1) strings.blockerApp else strings.blockerApps
                            val label = if (scheduleMode == 0) {
                                "${strings.blockerStartHourBlockFmt.format(selectedHours)} (${selectedApps.size} $appWord)"
                            } else {
                                val sEpoch = LocalDateTime.of(startDate, LocalTime.of(startHour, startMin))
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                if (sEpoch > System.currentTimeMillis()) "${strings.blockerScheduleBlockFmt} (${selectedApps.size} $appWord)"
                                else "${strings.blockerStartBlockNowFmt} (${selectedApps.size} $appWord)"
                            }
                            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        FfVerticalScrollbar(
            listState = listState,
            modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }

    if (showPicker) {
        AppPickerDialog(
            scannedApps       = scannedApps,
            alreadyBlocked    = emptySet(),
            title             = strings.blockerPickTimedTitle,
            confirmLabel      = strings.blockerSelectApps,
            confirmColor      = Error,
            showNetworkToggle = false,
            preSelected       = selectedApps,
            onDismiss         = { showPicker = false },
            onConfirm         = { picked, _ ->
                selectedApps = picked.map { it.processName }.toSet()
                showPicker = false
            }
        )
    }
}

/** Calendar + clock date/time selector used by both start and end fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(
    date: LocalDate,
    hour: Int,
    minute: Int,
    accentColor: Color,
    minDate: LocalDate = LocalDate.now(),
    onDateChange: (LocalDate) -> Unit,
    onHourChange: (Int) -> Unit,
    onMinChange:  (Int) -> Unit
) {
    val strings = LocalizationManager.strings
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val picked = Instant.ofEpochMilli(utcTimeMillis)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                return picked >= minDate
            }
        }
    )
    val timePickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = true
    )
    val dateLabel = date.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy"))
    val timeLabel = "%02d:%02d".format(hour, minute)

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.CalendarToday, null, tint = accentColor.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Date", color = OnSurface2, style = MaterialTheme.typography.labelSmall)
                Text(dateLabel, color = OnSurface, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { showDateDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Text("Change")
            }
        }
        HorizontalDivider(color = OnSurface2.copy(alpha = 0.12f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Schedule, null, tint = accentColor.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Time", color = OnSurface2, style = MaterialTheme.typography.labelSmall)
                Text(timeLabel, color = OnSurface, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { showTimeDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Text("Change")
            }
        }
    }

    if (showDateDialog) {
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            val picked = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                            onDateChange(if (picked < minDate) minDate else picked)
                        }
                        showDateDialog = false
                    }
                ) { Text("Apply", color = accentColor) }
            },
            dismissButton = {
                TextButton(onClick = { showDateDialog = false }) {
                    Text(strings.btnCancel, color = OnSurface2)
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = Surface2,
                    selectedDayContainerColor = accentColor,
                    selectedDayContentColor = Surface
                )
            )
        }
    }

    if (showTimeDialog) {
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            containerColor = Surface2,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Schedule, null, tint = accentColor, modifier = Modifier.size(18.dp))
                    Text("Select time", color = OnSurface, fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onHourChange(timePickerState.hour)
                        onMinChange(timePickerState.minute)
                        showTimeDialog = false
                    }
                ) { Text("Apply", color = accentColor) }
            },
            dismissButton = {
                TextButton(onClick = { showTimeDialog = false }) {
                    Text(strings.btnCancel, color = OnSurface2)
                }
            }
        )
    }
}

@Composable
private fun SpinnerField(
    value: String,
    label: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        IconButton(onClick = onInc, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, null, tint = accentColor, modifier = Modifier.size(16.dp))
        }
        Text(
            value,
            color = OnSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Text(
            label,
            color = OnSurface2,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onDec, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = accentColor, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ActiveTimedBlock(
    remainingMs:  Long,
    startsInMs:   Long,
    blockedNames: List<String>,
    onAddTime:    (Int) -> Unit
) {
    val strings    = LocalizationManager.strings
    val isWaiting  = startsInMs > 0L
    val accentColor = if (isWaiting) Warning else Error

    val remSec = ((if (isWaiting) startsInMs else remainingMs) / 1000).toInt().coerceAtLeast(0)
    val h = remSec / 3600
    val m = (remSec % 3600) / 60
    val s = remSec % 60

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accentColor.copy(alpha = 0.07f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accentColor))
            Text(
                if (isWaiting) strings.blockerBlockScheduled else strings.blockerBlockActive,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            if (isWaiting) {
                if (h > 0) "${strings.blockerStartsIn} ${h}h ${m}m ${s}s" else "${strings.blockerStartsIn} ${m}m ${s}s"
            } else {
                if (h > 0) "${h}h ${m}m ${s}s ${strings.dashRemaining}" else "${m}m ${s}s ${strings.dashRemaining}"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = OnSurface,
            fontWeight = FontWeight.Bold
        )

        if (isWaiting) {
            // Show how long the block will run once it starts
            val blockDurSec = (remainingMs / 1000).toInt().coerceAtLeast(0)
            val bh = blockDurSec / 3600
            val bm = (blockDurSec % 3600) / 60
            Text(
                "${strings.blockerBlockDuration} " + if (bh > 0) "${bh}h ${bm}m" else "${bm}m",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface2
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blockedNames.forEach { proc ->
                val display = InstalledAppsScanner.friendlyNameFor(proc)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface3)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppIcon(processName = proc, displayName = display, size = 28)
                    Text(display, style = MaterialTheme.typography.bodySmall, color = OnSurface, modifier = Modifier.weight(1f))
                    Text(proc, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                }
            }
        }
        if (!isWaiting) {
            HorizontalDivider(color = Surface3)
            Text(strings.focusExtendBlock, style = MaterialTheme.typography.bodySmall, color = OnSurface2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "+30m", 60 to "+1h", 120 to "+2h", 240 to "+4h").forEach { (mins, label) ->
                    OutlinedButton(
                        onClick = { onAddTime(mins) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                    ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

// ── App Picker Dialog ──────────────────────────────────────────────────────────

@Composable
private fun AppPickerDialog(
    scannedApps:       List<ScannedApp>,
    alreadyBlocked:    Set<String>,
    title:             String,
    confirmLabel:      String,
    confirmColor:      Color,
    showNetworkToggle: Boolean,
    showPresets:       Boolean = false,
    preSelected:       Set<String> = emptySet(),
    onDismiss:         () -> Unit,
    onConfirm:         (List<ScannedApp>, Map<String, Boolean>) -> Unit
) {
    val strings                            = LocalizationManager.strings
    val scope                              = rememberCoroutineScope()
    var search                             by remember { mutableStateOf("") }
    var selected                           by remember { mutableStateOf(preSelected) }
    var networkBlock                       by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var showAll                            by remember { mutableStateOf(false) }
    var presetsExpanded                    by remember { mutableStateOf(false) }
    var customPresets                      by remember { mutableStateOf(emptyList<CustomBlockPreset>()) }
    var showCreatePresetDialog             by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = Database.getCustomBlockPresets()
            withContext(Dispatchers.Main) { customPresets = loaded }
        }
    }

    val runningApps = remember(scannedApps) { scannedApps.filter { it.isRunning } }
    val sourceList  = if (showAll) scannedApps else runningApps
    val filtered    = remember(search, sourceList) {
        if (search.isBlank()) sourceList
        else sourceList.filter {
            it.displayName.contains(search, ignoreCase = true) ||
            it.processName.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        modifier         = Modifier.width(540.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = OnSurface, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value         = search,
                    onValueChange = { search = it },
                    placeholder   = { Text(strings.blockerSearchApps, color = OnSurface2) },
                    leadingIcon   = {
                        Icon(Icons.Default.Search, null, tint = OnSurface2, modifier = Modifier.size(18.dp))
                    },
                    modifier  = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = confirmColor,
                        unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                        focusedTextColor     = OnSurface,
                        unfocusedTextColor   = OnSurface
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !showAll,
                            onClick  = { showAll = false },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Success))
                                    Text("${strings.blockerRunning} (${runningApps.size})", style = MaterialTheme.typography.labelSmall)
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
                            label    = {
                                Text("${strings.blockerAllApps} (${scannedApps.size})", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Purple80.copy(alpha = 0.15f),
                                selectedLabelColor     = Purple80
                            )
                        )
                    }
                    if (selected.isNotEmpty()) {
                        Text(
                            "${selected.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = Purple80,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        text = {
            val pickerListState = rememberLazyListState()
            Box(modifier = Modifier.height(360.dp)) {
                LazyColumn(
                    state   = pickerListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (showPresets) {
                        // Collapsible "My Presets" header — collapsed by default
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Surface3)
                                    .clickable { presetsExpanded = !presetsExpanded }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(strings.blockerMyPresets, color = Purple80, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    if (customPresets.isNotEmpty()) {
                                        Text(
                                            "(${customPresets.size})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OnSurface2
                                        )
                                    }
                                }
                                Icon(
                                    if (presetsExpanded) Icons.Default.KeyboardArrowUp
                                    else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = OnSurface2,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (presetsExpanded) {
                            if (customPresets.isEmpty()) {
                                item {
                                    Text(
                                        strings.blockerNoPresets,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface2,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            } else {
                                items(customPresets, key = { "preset_${it.id}" }) { preset ->
                                    val presetProcs = preset.processNames.toSet()
                                    val allSel = presetProcs.isNotEmpty() && presetProcs.all { proc ->
                                        selected.any { it.equals(proc, ignoreCase = true) }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (allSel) Purple80.copy(alpha = 0.10f) else Surface3)
                                            .clickable {
                                                selected = if (allSel)
                                                    selected.filter { sel ->
                                                        presetProcs.none { it.equals(sel, ignoreCase = true) }
                                                    }.toSet()
                                                else selected + presetProcs
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(preset.emoji, fontSize = 16.sp)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                preset.name,
                                                color = OnSurface,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                "${preset.processNames.size} app${if (preset.processNames.size != 1) "s" else ""}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = OnSurface2
                                            )
                                        }
                                        if (allSel) {
                                            Icon(
                                                Icons.Default.CheckCircle, null,
                                                tint = Purple80,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        ShortcutTooltip("Delete preset") {
                                            IconButton(
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        Database.deleteCustomBlockPreset(preset.id)
                                                        val updated = Database.getCustomBlockPresets()
                                                        withContext(Dispatchers.Main) { customPresets = updated }
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete, null,
                                                    tint = OnSurface2.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { showCreatePresetDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Purple80)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (selected.isEmpty()) strings.blockerCreatePreset
                                        else strings.blockerSaveAsPreset,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        item {
                            HorizontalDivider(color = Surface3, modifier = Modifier.padding(vertical = 6.dp))
                            Text(
                                strings.blockerAppsLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurface2,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SearchOff, null,
                                        tint = OnSurface2, modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        if (!showAll) strings.blockerNoRunningApps
                                        else "${strings.blockerNoAppsMatch} \"$search\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface2,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(filtered, key = { i, it -> "${it.processName}_$i" }) { _, app ->
                            val isSelected = app.processName in selected
                            val isAlready  = app.processName.lowercase() in alreadyBlocked
                            val netEnabled = networkBlock[app.processName] ?: false

                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isAlready  -> Surface3.copy(alpha = 0.5f)
                                            isSelected -> confirmColor.copy(alpha = 0.10f)
                                            else       -> Surface3
                                        }
                                    )
                                    .clickable(enabled = !isAlready) {
                                        selected = if (isSelected) selected - app.processName
                                                   else            selected + app.processName
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppIcon(app.processName, app.displayName, size = 36)

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            app.displayName,
                                            color = if (isAlready) OnSurface2 else OnSurface,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                        if (app.isRunning) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Success))
                                        }
                                        if (isAlready) {
                                            Box(
                                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                                    .background(OnSurface2.copy(alpha = 0.12f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    "blocked",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = OnSurface2
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        app.processName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface2,
                                        fontSize = 10.sp
                                    )
                                }

                                if (showNetworkToggle && isSelected) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.WifiOff, null,
                                            tint = if (netEnabled) Warning else OnSurface2,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Switch(
                                            checked = netEnabled,
                                            onCheckedChange = { networkBlock = networkBlock + (app.processName to it) },
                                            modifier = Modifier.scale(0.52f).height(18.dp),
                                            colors = SwitchDefaults.colors(
                                                checkedTrackColor = Warning.copy(alpha = 0.4f),
                                                checkedThumbColor = Warning
                                            )
                                        )
                                    }
                                }

                                Checkbox(
                                    checked         = isSelected || isAlready,
                                    onCheckedChange = null,
                                    enabled         = !isAlready,
                                    colors          = CheckboxDefaults.colors(
                                        checkedColor = if (isAlready) OnSurface2 else confirmColor
                                    )
                                )
                            }
                        }
                    }
                }
                FfVerticalScrollbar(
                    listState = pickerListState,
                    modifier  = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val picked = scannedApps.filter {
                        it.processName in selected && it.processName.lowercase() !in alreadyBlocked
                    }
                    onConfirm(picked, networkBlock)
                },
                enabled = selected.isNotEmpty(),
                colors  = ButtonDefaults.buttonColors(containerColor = confirmColor)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalizationManager.strings.btnCancel, color = OnSurface2) }
        }
    )

    // ── Create / Save Preset Dialog ────────────────────────────────────────────
    if (showCreatePresetDialog) {
        var newPresetName  by remember { mutableStateOf("") }
        var newPresetEmoji by remember { mutableStateOf("🚫") }
        AlertDialog(
            onDismissRequest = { showCreatePresetDialog = false },
            containerColor   = Surface2,
            modifier         = Modifier.width(400.dp),
            title = {
                Text(strings.blockerSavePresetBtn, color = OnSurface, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (selected.isEmpty())
                            strings.blockerSelectAppsFirst
                        else
                            "${selected.size} app${if (selected.size != 1) "s" else ""} will be saved to this preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value         = newPresetEmoji,
                            onValueChange = { if (it.length <= 2) newPresetEmoji = it },
                            modifier      = Modifier.width(64.dp),
                            singleLine    = true,
                            label         = { Text(strings.blockerIconLabel, style = MaterialTheme.typography.labelSmall) },
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = Purple80,
                                unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                                focusedTextColor     = OnSurface,
                                unfocusedTextColor   = OnSurface,
                                focusedLabelColor    = Purple80,
                                unfocusedLabelColor  = OnSurface2
                            )
                        )
                        OutlinedTextField(
                            value         = newPresetName,
                            onValueChange = { newPresetName = it },
                            placeholder   = { Text(strings.blockerPresetName, color = OnSurface2) },
                            modifier      = Modifier.weight(1f),
                            singleLine    = true,
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = Purple80,
                                unfocusedBorderColor = OnSurface2.copy(alpha = 0.4f),
                                focusedTextColor     = OnSurface,
                                unfocusedTextColor   = OnSurface
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newPresetName.trim()
                        if (name.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                val preset = CustomBlockPreset(
                                    id           = UUID.randomUUID().toString(),
                                    name         = name,
                                    emoji        = newPresetEmoji.trim().ifBlank { "🚫" },
                                    processNames = selected.toList()
                                )
                                Database.upsertCustomBlockPreset(preset)
                                val updated = Database.getCustomBlockPresets()
                                withContext(Dispatchers.Main) {
                                    customPresets = updated
                                    showCreatePresetDialog = false
                                    presetsExpanded = true
                                }
                            }
                        }
                    },
                    enabled = newPresetName.isNotBlank(),
                    colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) { Text(LocalizationManager.strings.btnSave) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePresetDialog = false }) {
                    Text(LocalizationManager.strings.btnCancel, color = OnSurface2)
                }
            }
        )
    }
}
