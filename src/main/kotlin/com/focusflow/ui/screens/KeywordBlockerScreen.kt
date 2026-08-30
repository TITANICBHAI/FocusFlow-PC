package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.ShortcutTooltip
import com.focusflow.ui.components.PinGateDialog
import com.focusflow.ui.components.openEdgeExtensionStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.NetworkCutoffRule
import com.focusflow.data.models.NetworkRuleMode
import com.focusflow.data.models.Screen
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.GlobalPin
import com.focusflow.services.KeywordMatchLogger
import com.focusflow.ui.LocalNavigate
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class KeywordPreset(
    val label: String,
    val emoji: String,
    val keywords: List<String>
)

private val PRESETS = listOf(
    KeywordPreset("Doomscroll Bait", "📰", listOf("breaking news","trending","viral","outrage","shocking","drama","exposed")),
    KeywordPreset("Social Drama", "🎭", listOf("beef","callout","canceled","toxic","fight","clout","tea")),
    KeywordPreset("Shorts Bait", "📱", listOf("pov:","wait for it","you won't believe","try not to laugh","insane reaction","plot twist")),
    KeywordPreset("Shopping", "🛒", listOf("deal","sale","% off","limited time","add to cart","buy now","flash deal","coupon")),
    KeywordPreset("Gambling", "🎰", listOf("bet","casino","jackpot","slots","poker","spin","wager","odds","prize")),
    KeywordPreset("NSFW", "🔞", listOf("nude","nsfw","explicit","adult content","18+","onlyfans","xxx"))
)

@Composable
fun KeywordBlockerScreen() {
    val strings = LocalizationManager.strings
    val scope = rememberCoroutineScope()

    var enabled              by remember { mutableStateOf(false) }
    var keywords             by remember { mutableStateOf(listOf<String>()) }
    var newKeyword           by remember { mutableStateOf("") }
    var expandPresets        by remember { mutableStateOf(false) }
    var expandLog            by remember { mutableStateOf(true) }
    var recentMatches        by remember { mutableStateOf(KeywordMatchLogger.getRecent()) }
    var showClearAllConfirm  by remember { mutableStateOf(false) }
    var networkSuggestionCount by remember { mutableStateOf(0) }
    var showPinGate           by remember { mutableStateOf(false) }
    var pendingPinAction      by remember { mutableStateOf<(() -> Unit)?>(null) }
    val navigate = LocalNavigate.current

    fun withPin(action: () -> Unit) {
        if (GlobalPin.isSet()) {
            pendingPinAction = action
            showPinGate = true
        } else {
            action()
        }
    }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                enabled  = Database.isKeywordBlockerEnabled()
                keywords = Database.getBlockedKeywords()
                val networkKeywords = Database.getNetworkCutoffRules()
                    .filter { it.mode == NetworkRuleMode.KEYWORD }
                    .map { it.pattern.lowercase() }
                    .toSet()
                networkSuggestionCount = keywords.count { it.lowercase() !in networkKeywords }
            }
        }
    }

    fun save(newList: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) { Database.setBlockedKeywords(newList) }
            keywords = newList
        }
    }

    fun toggleEnabled(v: Boolean) {
        withPin {
            scope.launch {
                withContext(Dispatchers.IO) { Database.setKeywordBlockerEnabled(v) }
                enabled = v
            }
        }
    }

    fun addKeywordsToNetworkShield() {
        withPin {
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    val existing = Database.getNetworkCutoffRules()
                        .filter { it.mode == NetworkRuleMode.KEYWORD }
                        .map { it.pattern.lowercase() }
                        .toSet()
                    val missing = keywords.filter { it.lowercase() !in existing }
                    missing.forEach { keyword ->
                        Database.upsertNetworkCutoffRule(
                            NetworkCutoffRule(
                                id = UUID.randomUUID().toString(),
                                pattern = keyword.lowercase(),
                                mode = NetworkRuleMode.KEYWORD,
                                enabled = true
                            )
                        )
                    }
                    missing.size
                }
                ProcessMonitor.invalidateCaches()
                networkSuggestionCount = (networkSuggestionCount - added).coerceAtLeast(0)
                navigate(Screen.VPN_NETWORK)
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        // Refresh the match log every 3 seconds so new blocks appear without navigating away
        while (true) {
            delay(3_000)
            recentMatches = KeywordMatchLogger.getRecent()
        }
    }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Text(strings.kwbTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            Text(strings.kwbSubtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurface2)

            // ── Known limitations banner ──────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Warning.copy(alpha = 0.08f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Warning, modifier = Modifier.size(16.dp))
                    Text(
                        "How keyword blocking works — and its limits",
                        color = OnSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Keywords are matched against the foreground window title on Windows. When a match is detected, the browser window is closed. FocusFlow cannot read a browser tab's URL or page description without a browser extension.",
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Extension, null, tint = Purple80, modifier = Modifier.size(16.dp))
                    Text(
                        "Need URL-level browser protection? Use the official FocusFlow Edge extension.",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { openEdgeExtensionStore() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Install", color = Purple80, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = Warning.copy(alpha = 0.15f), thickness = 1.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(13.dp).padding(top = 2.dp))
                    Text(
                        "Incognito / private windows hide the page title — keywords cannot match and blocking is bypassed in private mode.",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(13.dp).padding(top = 2.dp))
                    Text(
                        "Chrome and Edge use one process per browser window. When a blocked keyword is detected, only that specific window is closed — other browser windows stay open.",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                }
            }

            // ── Enable card ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface2)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(strings.kwbEnable, color = OnSurface, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Active · matching tabs will be blocked"
                        else "Disabled · keywords are saved but not enforced",
                        color = if (enabled) Success else OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { toggleEnabled(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Surface, checkedTrackColor = Purple80)
                )
            }

            // ── Network Shield suggestion ─────────────────────────────────────
            if (keywords.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Purple80.copy(alpha = 0.08f))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Shield, null, tint = Purple80, modifier = Modifier.size(18.dp))
                        Text(
                            "Also suggest these for Network Shield?",
                            color = OnSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "Keyword blocking closes the matching browser window. Network Shield is a softer alternative: it keeps the app open and cuts its network access when the title matches. Nothing is added automatically.",
                        color = OnSurface2,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { addKeywordsToNetworkShield() },
                            enabled = networkSuggestionCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (networkSuggestionCount > 0)
                                    "Add $networkSuggestionCount missing"
                                else
                                    "Already added"
                            )
                        }
                        TextButton(onClick = { navigate(Screen.VPN_NETWORK) }) {
                            Text("Open Network Shield", color = Purple80)
                        }
                    }
                }
            }

            // ── Add keyword ───────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface2)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(strings.kwbAddKeyword, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        placeholder = { Text("e.g. trending, viral, breaking…", color = OnSurface2) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple80,
                            unfocusedBorderColor = OnSurface2
                        )
                    )
                    Button(
                        onClick = {
                            val kw = newKeyword.trim().lowercase()
                            if (kw.isNotEmpty() && !keywords.contains(kw)) {
                                val updated = keywords + kw
                                withPin {
                                    save(updated)
                                    newKeyword = ""
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = strings.btnAdd)
                        Spacer(Modifier.width(4.dp))
                        Text(strings.btnAdd)
                    }
                }
            }

            // ── Current keyword list ──────────────────────────────────────────
            if (keywords.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface2)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${strings.kwbActiveKeywords} (${keywords.size})", color = OnSurface, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showClearAllConfirm = true }) {
                            Text(strings.kwbClearAll, color = Error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    keywords.forEach { kw ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface3)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(kw, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                            ShortcutTooltip("Remove keyword") {
                                IconButton(
                                    onClick = { withPin { save(keywords - kw) } },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = OnSurface2, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── Recent keyword match log ──────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface2)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expandLog = !expandLog },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = Purple80,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                strings.kwbRecentBlocks,
                                color = OnSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (recentMatches.isEmpty()) strings.kwbNoBlocksYet
                                else "${recentMatches.size} recent block${if (recentMatches.size == 1) "" else "s"} · updates every 3s",
                                color = OnSurface2,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (recentMatches.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    KeywordMatchLogger.clear()
                                    recentMatches = emptyList()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(strings.kwbClear, color = OnSurface2, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                            }
                        }
                        Icon(
                            if (expandLog) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = OnSurface2
                        )
                    }
                }

                if (expandLog) {
                    if (recentMatches.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface3)
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                strings.kwbBlocksRealTime,
                                color = OnSurface2,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recentMatches.forEach { match ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Surface3)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Block,
                                        contentDescription = null,
                                        tint = Error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp).padding(top = 2.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                match.appDisplayName,
                                                color = OnSurface,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                match.timeLabel,
                                                color = OnSurface2,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(Purple80.copy(alpha = 0.12f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    "\"${match.keyword}\"",
                                                    color = Purple80,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                        if (match.windowTitle.isNotBlank()) {
                                            Text(
                                                match.windowTitle.take(80) + if (match.windowTitle.length > 80) "…" else "",
                                                color = OnSurface2,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Quick presets ─────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface2)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expandPresets = !expandPresets },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(strings.kwbQuickPresets, color = OnSurface, fontWeight = FontWeight.SemiBold)
                        Text("Add curated keyword sets with one click", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        if (expandPresets) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, tint = OnSurface2
                    )
                }

                if (expandPresets) {
                    PRESETS.forEach { preset ->
                        val allAdded = preset.keywords.all { keywords.contains(it) }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surface3)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${preset.emoji} ${preset.label}", color = OnSurface, fontWeight = FontWeight.Medium)
                                Text(
                                    preset.keywords.take(4).joinToString(", ") + if (preset.keywords.size > 4) "…" else "",
                                    color = OnSurface2,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            if (allAdded) {
                                TextButton(onClick = { withPin { save(keywords - preset.keywords.toSet()) } }) {
                                    Text(strings.kwbRemove, color = Error)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val merged = (keywords + preset.keywords).distinct()
                                        withPin { save(merged) }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                                ) {
                                    Text(strings.kwbAddAll)
                                }
                            }
                        }
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

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            containerColor   = Surface2,
            title = {
                Text("Clear All Keywords?", color = OnSurface, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    "This will permanently remove all ${keywords.size} keyword${if (keywords.size == 1) "" else "s"} from your block list.",
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        withPin {
                            save(emptyList())
                            showClearAllConfirm = false
                        }
                    },
                    colors  = ButtonDefaults.buttonColors(containerColor = Error)
                ) { Text("Clear All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(strings.btnCancel, color = OnSurface2)
                }
            }
        )
    }

    if (showPinGate) {
        PinGateDialog(
            title = "Global PIN required",
            subtitle = "Enter your Global PIN to change keyword enforcement.",
            onSuccess = {
                showPinGate = false
                pendingPinAction?.invoke()
                pendingPinAction = null
                com.focusflow.services.ReviewPromptService.triggerCheck()
            },
            onDismiss = {
                showPinGate = false
                pendingPinAction = null
            }
        )
    }
}
