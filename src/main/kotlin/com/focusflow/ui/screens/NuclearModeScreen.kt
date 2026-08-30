package com.focusflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.InstallVariant
import com.focusflow.services.NuclearPin
import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.NuclearPinGateDialog
import com.focusflow.ui.components.NuclearPinSetupDialog
import com.focusflow.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun NuclearModeScreen() {
    var nuclearActive   by remember { mutableStateOf(NuclearMode.isActive) }
    var nuclearPinSet   by remember { mutableStateOf(NuclearPin.isSet()) }
    var sessionAttempts by remember { mutableStateOf(NuclearMode.sessionEscapeAttempts()) }
    var breakdown       by remember { mutableStateOf(NuclearMode.escapeAttemptBreakdown()) }
    var showPinGate          by remember { mutableStateOf(false) }
    var showPinSetup         by remember { mutableStateOf(false) }
    var escapeRoutesExpanded by remember { mutableStateOf(false) }

    // Refresh live data every second so status / attempt counts stay current
    LaunchedEffect(Unit) {
        while (true) {
            nuclearActive   = NuclearMode.isActive
            nuclearPinSet   = NuclearPin.isSet()
            sessionAttempts = NuclearMode.sessionEscapeAttempts()
            breakdown       = NuclearMode.escapeAttemptBreakdown()
            delay(1_000)
        }
    }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Surface)
                .verticalScroll(scrollState)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint     = if (nuclearActive) Error else OnSurface,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    "Nuclear Mode",
                    style      = MaterialTheme.typography.headlineLarge,
                    color      = OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                // Live status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (nuclearActive) Error.copy(alpha = 0.15f) else Surface3)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (nuclearActive) "ACTIVE" else "OFF",
                        color         = if (nuclearActive) Error else OnSurface2,
                        fontWeight    = FontWeight.Bold,
                        fontSize      = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            // ── Big status + enable/disable card ──────────────────────────────
            NuclearCard(title = "Enforcement Status") {
                Row(
                    modifier             = Modifier.fillMaxWidth(),
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Pulsing status dot
                    if (nuclearActive) {
                        val pulse = rememberInfiniteTransition(label = "nuclearDot")
                        val dotScale by pulse.animateFloat(
                            initialValue  = 0.7f,
                            targetValue   = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation  = tween(700, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dotScale"
                        )
                        Box(
                            modifier = Modifier
                                .scale(dotScale)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Error)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(OnSurface2.copy(alpha = 0.4f))
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (nuclearActive) "NUCLEAR MODE ACTIVE" else "Nuclear Mode is OFF",
                            color      = if (nuclearActive) Error else OnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 15.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (nuclearActive)
                                if (InstallVariant.isWindowsDirectInstall)
                                    "Monitoring ${NuclearMode.escapeProcessCount} escape routes every 500 ms, including EXE/MSI uninstallers"
                                else
                                    "Monitoring ${NuclearMode.escapeProcessCount} escape routes every 500 ms"
                            else
                                "Enable to block Task Manager, terminals, registry editors and ${NuclearMode.escapeProcessCount - 3} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }

                    Button(
                        onClick = {
                            if (nuclearActive) {
                                if (nuclearPinSet) {
                                    showPinGate = true
                                } else {
                                    NuclearMode.disable()
                                    nuclearActive = NuclearMode.isActive
                                }
                            } else {
                                NuclearMode.enable()
                                nuclearActive = NuclearMode.isActive
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (nuclearActive) Error.copy(alpha = 0.15f) else Purple80
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (nuclearActive) "Disable" else "Enable",
                            color = if (nuclearActive) Error else androidx.compose.ui.graphics.Color.White
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Surface3, thickness = 1.dp)
                Spacer(Modifier.height(10.dp))

                Text(
                    "⚠ Nuclear Mode kills system utilities every 500 ms. " +
                    if (InstallVariant.isWindowsDirectInstall)
                        "It also blocks common EXE/MSI uninstallers until you disable it from within FocusFlow."
                    else
                        "Use during deep work sessions only — you must disable it from within FocusFlow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning
                )
            }

            // ── PIN management (directly under Enforcement Status) ────────────
            NuclearCard(title = "PIN Protection") {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint     = if (nuclearPinSet) Warning else OnSurface2,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                "Nuclear Mode PIN",
                                color = OnSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (nuclearPinSet) "4-char PIN active — required to turn Nuclear Mode off"
                                else              "Optional: require a PIN to turn Nuclear Mode off",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface2
                            )
                        }
                    }
                    Button(
                        onClick = { showPinSetup = true },
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = if (nuclearPinSet) Error.copy(alpha = 0.15f)
                                             else              Purple80.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (nuclearPinSet) "Change / Clear" else "Set PIN",
                            color = if (nuclearPinSet) Error else Purple80
                        )
                    }
                }

                if (nuclearPinSet) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A PIN is required to disable Nuclear Mode from the UI. " +
                        "Kiosk mode (Focus Launcher) bypasses the PIN gate automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface2
                    )
                }
            }

            // ── Blocked attempts this session ─────────────────────────────────
            NuclearCard(title = "Blocked Attempts — This Session") {
                if (sessionAttempts == 0) {
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint     = Success,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "No escape attempts detected this session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }
                } else {
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Error.copy(alpha = 0.08f))
                            .padding(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint     = Error,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                "$sessionAttempts attempt${if (sessionAttempts != 1) "s" else ""} blocked",
                                color      = Error,
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 15.sp
                            )
                            Text(
                                "Counts reset each time Nuclear Mode is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface2
                            )
                        }
                    }

                    if (breakdown.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Per-process breakdown:",
                            style      = MaterialTheme.typography.bodySmall,
                            color      = OnSurface2,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        breakdown.entries
                            .sortedByDescending { it.value }
                            .forEach { (process, count) ->
                                Row(
                                    modifier             = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment    = Alignment.CenterVertically
                                ) {
                                    Text(
                                        process,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "×$count",
                                        style      = MaterialTheme.typography.bodySmall,
                                        color      = Error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                    }
                }
            }

            // ── Escape routes coverage (collapsible) ──────────────────────────
            CollapsibleNuclearCard(
                title    = "Escape Routes Covered — ${NuclearMode.escapeProcessCount} processes",
                expanded = escapeRoutesExpanded,
                onToggle = { escapeRoutesExpanded = !escapeRoutesExpanded }
            ) {
                Text(
                    "These processes are killed immediately when detected while Nuclear Mode is active:",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface2
                )
                if (InstallVariant.isWindowsDirectInstall) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On direct EXE/MSI installs, common uninstallers are included in this protection. " +
                            "MSIX package removal remains controlled by Windows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Display in a 2-column grid using chunked rows
                val names = NuclearMode.escapeProcessNames.sorted()
                val chunks = names.chunked(2)
                chunks.forEach { pair ->
                    Row(
                        modifier             = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { name ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Surface3)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    name,
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = OnSurface2,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        // If odd number in chunk, fill remaining space
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        FfVerticalScrollbar(
            scrollState = scrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }

    // ── PIN dialogs ───────────────────────────────────────────────────────────
    if (showPinGate) {
        NuclearPinGateDialog(
            onDismiss  = { showPinGate = false },
            onVerified = {
                showPinGate   = false
                NuclearMode.disable()
                nuclearActive = NuclearMode.isActive
            }
        )
    }
    if (showPinSetup) {
        NuclearPinSetupDialog(
            pinAlreadySet = nuclearPinSet,
            onDismiss     = { showPinSetup = false },
            onChanged     = {
                showPinSetup  = false
                nuclearPinSet = NuclearPin.isSet()
            }
        )
    }
}

// ── Private card composables ───────────────────────────────────────────────────

@Composable
private fun NuclearCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            title,
            style      = MaterialTheme.typography.titleSmall,
            color      = OnSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 13.sp,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun CollapsibleNuclearCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
    ) {
        // Clickable header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(18.dp),
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title,
                style      = MaterialTheme.typography.titleSmall,
                color      = OnSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                letterSpacing = 0.3.sp,
                modifier   = Modifier.weight(1f)
            )
            Icon(
                imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint               = OnSurface2,
                modifier           = Modifier.size(20.dp)
            )
        }

        // Collapsible content
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
            ) {
                content()
            }
        }
    }
}
