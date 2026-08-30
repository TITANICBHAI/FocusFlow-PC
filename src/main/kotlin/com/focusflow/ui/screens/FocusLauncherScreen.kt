package com.focusflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.enforcement.InstalledAppsScanner
import com.focusflow.enforcement.isWindows
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.FocusLauncherApp
import com.focusflow.services.FocusLauncherService
import com.focusflow.ui.components.isRunningAsAdmin
import com.focusflow.ui.components.ShortcutTooltip
import com.focusflow.ui.components.relaunchAsAdmin
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DURATION_PRESETS = listOf(
    "No limit" to null,
    "30 min"   to 30,
    "1 hour"   to 60,
    "2 hours"  to 120,
    "4 hours"  to 240
)

@Composable
fun FocusLauncherScreen() {
    val strings = LocalizationManager.strings

    var selectedApps     by remember { mutableStateOf<Set<String>>(emptySet()) }
    var availableApps    by remember { mutableStateOf<List<FocusLauncherApp>>(emptyList()) }
    var searchQuery      by remember { mutableStateOf("") }
    var searchResults    by remember { mutableStateOf<List<FocusLauncherApp>>(emptyList()) }
    var durationIndex    by remember { mutableStateOf(0) }
    var isLoading        by remember { mutableStateOf(true) }
    var confirmEnter     by remember { mutableStateOf(false) }
    var showAdminWarning by remember { mutableStateOf(false) }
    var breaksAllowed     by remember { mutableStateOf(1) }
    var breakDurationMins by remember { mutableStateOf(5) }
    var showPinBeforeEnter by remember { mutableStateOf(false) }
    var generatedPin       by remember { mutableStateOf("") }

    // Checked once on composition — running "net session" is a blocking call so we
    // do it inside remember{} rather than on every recomposition.
    val isAdmin = remember { isRunningAsAdmin() }

    val isActive  by FocusLauncherService.isActive.collectAsState()
    val canBreak  by FocusLauncherService.canTakeBreak.collectAsState()
    val scope     = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            InstalledAppsScanner.getCuratedApps()
                .map { FocusLauncherApp(it.processName, it.displayName, it.exePath) }
                .sortedBy { it.displayName }
        }
        availableApps = apps

        // Restore the last selection, intersected with apps currently installed.
        val persisted = withContext(Dispatchers.IO) { Database.getSetting("launcher_selected_apps") }
        selectedApps = if (persisted != null && persisted.isNotBlank()) {
            val saved     = persisted.split(",").filter { it.isNotBlank() }
                .map { it.trim().lowercase() }.toSet()
            val available = apps.map { it.processName.lowercase() }.toSet()
            val matching  = available.intersect(saved)
            if (matching.isEmpty()) available else matching
        } else {
            apps.map { it.processName.lowercase() }.toSet()
        }

        isLoading = false
    }

    LaunchedEffect(searchQuery, availableApps) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        val q = searchQuery.trim().lowercase()
        val availableProcessNames = availableApps
            .asSequence()
            .map { it.processName.lowercase() }
            .toSet()
        searchResults = withContext(Dispatchers.IO) {
            InstalledAppsScanner.getCuratedApps()
                .filter {
                    it.displayName.lowercase().contains(q) ||
                    it.processName.lowercase().contains(q)
                }
                .filter { it.processName.lowercase() !in availableProcessNames }
                .distinctBy { it.processName.lowercase() }
                .take(10)
                .map { FocusLauncherApp(it.processName, it.displayName, it.exePath) }
        }
    }

    if (isActive) {
        ActiveLauncherBanner()
        return
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier         = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GridView, null, tint = Purple80, modifier = Modifier.size(26.dp))
                }
                Column {
                    Text(strings.launcherTitle, style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface, fontWeight = FontWeight.Bold)
                    Text(strings.launcherSubtitle,
                        style = MaterialTheme.typography.bodySmall, color = OnSurface2)
                }
            }
        }

        // ── Warning banner ───────────────────────────────────────────────────
        item {
            Row(
                modifier              = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Warning.copy(alpha = 0.08f))
                    .border(1.dp, Warning.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.Top
            ) {
                Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(strings.launcherFullOsLockdown, color = Warning, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Taskbar hidden, keyboard shortcuts disabled, all non-selected apps killed. " +
                        (if (canBreak) "One 5-minute break available today. " else "Break already used today. ") +
                        "Requires PIN to exit if hard-locked.",
                        color = OnSurface2, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── Admin status card ─────────────────────────────────────────────────
        if (isWindows) {
            item(key = "adminStatus") {
                if (isAdmin) {
                    // Green "all clear" chip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Success.copy(alpha = 0.09f))
                            .border(1.dp, Success.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint     = Success,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Running as Administrator — full kiosk lockdown enabled",
                            color      = Success,
                            fontWeight = FontWeight.Medium,
                            style      = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    // Red warning card with relaunch button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Error.copy(alpha = 0.08f))
                            .border(1.dp, Error.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint     = Error,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Not running as Administrator",
                                color      = Error,
                                fontWeight = FontWeight.SemiBold,
                                style      = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Sign-Out and Fast User Switching won't be hidden. Two OS escape routes remain open.",
                                color = OnSurface2,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = { relaunchAsAdmin() },
                            colors  = ButtonDefaults.buttonColors(containerColor = Error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Run as Admin",
                                fontSize   = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // ── App selection ─────────────────────────────────────────────────────
        item {
            Text(strings.launcherAppsToInclude, color = OnSurface, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text("Installed apps detected on this computer. Uncheck any you don't want this session.",
                color = OnSurface2, style = MaterialTheme.typography.bodySmall)
        }

        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Purple80, modifier = Modifier.size(28.dp))
                }
            }
        } else if (availableApps.isEmpty()) {
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Surface3).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                    Text(strings.launcherNoAppsYet,
                        color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            // Namespaces are required because searchResults is rendered in this
            // same LazyColumn below. Process name is the app identity; the
            // section prefix prevents cross-section collisions.
            items(availableApps, key = { "available:${it.processName.lowercase()}" }) { app ->
                val key      = app.processName.lowercase()
                val checked  = key in selectedApps
                AppSelectRow(
                    app     = app,
                    checked = checked,
                    onToggle = {
                        selectedApps = if (checked) selectedApps - key else selectedApps + key
                    }
                )
            }
        }

        // ── Search & add ──────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text(strings.launcherAddMoreApps, color = OnSurface, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value          = searchQuery,
                onValueChange  = { searchQuery = it },
                placeholder    = { Text(strings.launcherSearchApps, color = OnSurface2) },
                leadingIcon    = { Icon(Icons.Default.Search, null, tint = OnSurface2, modifier = Modifier.size(18.dp)) },
                trailingIcon   = if (searchQuery.isNotEmpty()) {{
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, null, tint = OnSurface2, modifier = Modifier.size(16.dp))
                    }
                }} else null,
                singleLine     = true,
                colors         = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Purple80,
                    unfocusedBorderColor = Surface3
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (searchResults.isNotEmpty()) {
            items(searchResults, key = { "search:${it.processName.lowercase()}" }) { app ->
                val key     = app.processName.lowercase()
                val added   = availableApps.any { it.processName.equals(app.processName, ignoreCase = true) }
                val checked = key in selectedApps
                Row(
                    modifier              = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Surface3).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column {
                        Text(app.displayName, color = OnSurface,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(app.processName, color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                    }
                    if (!added) {
                        ShortcutTooltip("Add to session") {
                            IconButton(
                                onClick = {
                                    availableApps = availableApps + app
                                    selectedApps  = selectedApps + key
                                    searchQuery   = ""
                                },
                                modifier = Modifier.size(32.dp).clip(CircleShape)
                                    .background(Purple80.copy(alpha = 0.15f))
                            ) {
                                Icon(Icons.Default.Add, null, tint = Purple80, modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        Checkbox(
                            checked  = checked,
                            onCheckedChange = {
                                selectedApps = if (checked) selectedApps - key else selectedApps + key
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Purple80)
                        )
                    }
                }
            }
        }

        // ── Duration ─────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text(strings.launcherSessionDuration, color = OnSurface, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DURATION_PRESETS.forEachIndexed { idx, (label, _) ->
                    val selected = durationIndex == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Purple80 else Surface3)
                            .border(1.dp, if (selected) Purple80 else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { durationIndex = idx }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label,
                            color      = if (selected) Color.White else OnSurface2,
                            style      = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }

        // ── Break configuration ──────────────────────────────────────────────
        item {
            Text("Breaks", color = OnSurface, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "None", 1 to "1", 2 to "2", -1 to "∞").forEach { (count, label) ->
                    FilterChip(
                        selected = breaksAllowed == count,
                        onClick = { breaksAllowed = count },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple80.copy(alpha = .2f),
                            selectedLabelColor = Purple80
                        )
                    )
                }
            }
            if (breaksAllowed != 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 15).forEach { mins ->
                        FilterChip(
                            selected = breakDurationMins == mins,
                            onClick = { breakDurationMins = mins },
                            label = { Text("${mins}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Purple80.copy(alpha = .2f),
                                selectedLabelColor = Purple80
                            )
                        )
                    }
                }
            }
        }

        // ── Enter button ──────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            val appsForSession = availableApps.filter {
                it.processName.lowercase() in selectedApps
            }
            Button(
                onClick = {
                    // Gate on admin elevation: registry lockdown and fast-user-switching
                    // block both need admin rights.  Show a warning first so the user
                    // can relaunch elevated before entering a session they can't escape cleanly.
                    if (!isAdmin && isWindows) {
                        showAdminWarning = true
                    } else {
                        confirmEnter = true
                    }
                },
                enabled  = appsForSession.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (appsForSession.isEmpty()) strings.launcherSelectAtLeastOne
                    else "${strings.launcherEnterLauncher} · ${appsForSession.size} app${if (appsForSession.size == 1) "" else "s"}",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Admin elevation warning ───────────────────────────────────────────────
    // Shown when the user tries to enter Focus Launcher without admin rights.
    // Without admin: DisableTaskMgr (HKCU) still works, but NoLogOff (HKCU)
    // and HideFastUserSwitching (HKLM) silently skip — leaving two escape routes open.
    if (showAdminWarning) {
        AlertDialog(
            onDismissRequest = { showAdminWarning = false },
            containerColor   = Surface2,
            shape            = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.AdminPanelSettings,
                    null,
                    tint     = Warning,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    strings.launcherAdminRequired,
                    color      = OnSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "FocusFlow is not running as Administrator. Without admin rights " +
                        "two lockdown features are skipped:",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", color = Warning, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Sign Out is NOT removed from the Start / Ctrl+Alt+Del screen",
                                color = Warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", color = Warning, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Fast User Switching (Switch User button) is NOT hidden",
                                color = Warning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "Relaunch as Administrator for a complete kiosk with no OS escape routes.",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAdminWarning = false
                        relaunchAsAdmin()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Warning)
                ) {
                    Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.launcherRelaunchAdmin, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdminWarning = false
                        confirmEnter     = true   // proceed anyway with degraded lockdown
                    }
                ) {
                    Text(strings.launcherContinueAnyway, color = OnSurface2)
                }
            }
        )
    }

    // ── Confirm dialog ────────────────────────────────────────────────────────
    if (confirmEnter) {
        val appsForSession = availableApps.filter { it.processName.lowercase() in selectedApps }
        val duration       = DURATION_PRESETS[durationIndex].second

        AlertDialog(
            onDismissRequest = { confirmEnter = false },
            containerColor   = Surface2,
            shape            = RoundedCornerShape(20.dp),
            title = {
                Text(strings.launcherEnterConfirmTitle, color = OnSurface, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This will hide the taskbar, disable system shortcuts, and restrict you " +
                        "to ${appsForSession.size} app${if (appsForSession.size == 1) "" else "s"}" +
                        if (duration != null) " for $duration minutes." else ". No time limit.",
                        color = OnSurface2, style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        when {
                            breaksAllowed == 0 -> "Breaks are disabled for this session."
                            breaksAllowed == -1 -> "Breaks are unlimited; each lasts $breakDurationMins minutes."
                            else -> "$breaksAllowed break${if (breaksAllowed == 1) "" else "s"} available; each lasts $breakDurationMins minutes."
                        },
                        color = Warning, style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmEnter = false
                        val saved = appsForSession.joinToString(",") { it.processName.lowercase() }
                        scope.launch(Dispatchers.IO) {
                            Database.setSetting("launcher_selected_apps", saved)
                            val pin = FocusLauncherService.preparePin()
                            withContext(Dispatchers.Main) {
                                generatedPin = pin
                                showPinBeforeEnter = true
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) { Text(strings.launcherEnterLauncher) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnter = false }) {
                    Text(strings.btnCancel, color = OnSurface2)
                }
            }
        )
    }

    // ── One-time session PIN ──────────────────────────────────────────────────
    if (showPinBeforeEnter) {
        val duration = DURATION_PRESETS[durationIndex].second
        AlertDialog(
            onDismissRequest = { },
            containerColor = Surface2,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(Icons.Default.Key, null, tint = Purple80, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Your Session PIN", color = OnSurface, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Write this down — you'll need it to take breaks and end the session.",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface3)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            generatedPin,
                            color = Purple80,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "This PIN will not be shown again.",
                        color = Warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPinBeforeEnter = false
                        generatedPin = ""
                        val appsForSession = availableApps.filter {
                            it.processName.lowercase() in selectedApps
                        }
                        scope.launch(Dispatchers.IO) {
                            FocusLauncherService.enter(
                                apps = appsForSession,
                                durationMinutes = duration,
                                breaksAllowed = breaksAllowed,
                                breakSeconds = breakDurationMins * 60
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) { Text("I've noted it — Start") }
            }
        )
    }
}

@Composable
private fun AppSelectRow(app: FocusLauncherApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (checked) Purple80.copy(alpha = 0.07f) else Surface3)
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(app.displayName, color = OnSurface,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(app.processName, color = OnSurface2,
                style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
        }
        Checkbox(
            checked         = checked,
            onCheckedChange = { onToggle() },
            colors          = CheckboxDefaults.colors(checkedColor = Purple80)
        )
    }
}

@Composable
private fun ActiveLauncherBanner() {
    val sessionApps  by FocusLauncherService.sessionApps.collectAsState()
    val sessionEndMs by FocusLauncherService.sessionEndMs.collectAsState()
    var remaining    by remember { mutableStateOf<Long>(-1L) }

    LaunchedEffect(sessionEndMs) {
        while (true) {
            remaining = FocusLauncherService.remainingSeconds()
            kotlinx.coroutines.delay(1_000)
        }
    }

    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(40.dp)
        ) {
            Box(
                modifier         = Modifier.size(64.dp).clip(CircleShape)
                    .background(Purple80.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Lock, null, tint = Purple80, modifier = Modifier.size(32.dp))
            }

            Text("Focus Launcher is active", color = OnSurface,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            if (sessionApps.isNotEmpty()) {
                Text(
                    "${sessionApps.size} app${if (sessionApps.size == 1) "" else "s"} in session",
                    color = OnSurface2, style = MaterialTheme.typography.bodyMedium
                )
            }

            if (remaining >= 0L) {
                val mins = remaining / 60
                val secs = remaining % 60
                Text(
                    if (remaining > 0L) "%02d:%02d remaining".format(mins, secs) else "Session ending…",
                    color      = if (remaining in 1..299L) Warning else Purple80,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text("No time limit", color = OnSurface2, style = MaterialTheme.typography.bodyMedium)
            }

            Text(
                "Switch to the launcher overlay to manage your session.",
                color     = OnSurface2,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
