package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import com.focusflow.ui.components.ShortcutTooltip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.data.Database
import com.focusflow.data.models.NetworkCutoffRule
import com.focusflow.data.models.NetworkRuleMode
import com.focusflow.i18n.LocalizationManager
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.VpnBlocker
import com.focusflow.services.GlobalPin
import com.focusflow.services.HostsBlocker
import com.focusflow.ui.components.PinGateDialog
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val POPULAR_VPN_DISPLAY = listOf(
    "NordVPN" to "nordvpn.exe",
    "ExpressVPN" to "expressvpn.exe",
    "ProtonVPN" to "protonvpn.exe",
    "Surfshark" to "surfshark.exe",
    "CyberGhost" to "cyberghost.exe",
    "Windscribe" to "windscribe.exe",
    "Mullvad" to "mullvad.exe",
    "PIA" to "pia.exe",
    "IPVanish" to "ipvanish.exe",
    "TunnelBear" to "tunnelbear.exe",
    "VyprVPN" to "vyprvpn.exe",
    "TorGuard" to "torguard.exe",
    "OpenVPN" to "openvpn.exe",
    "WireGuard" to "wireguard.exe",
    "Tor" to "tor.exe",
    "Cisco AnyConnect" to "anyconnect.exe",
    "GlobalProtect" to "globalprotect.exe",
    "Psiphon" to "psiphon3.exe"
)

@Composable
fun VpnNetworkScreen() {
    val strings           = LocalizationManager.strings
    val scope             = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var vpnEnabled          by remember { mutableStateOf(false) }
    var customVpnProcesses  by remember { mutableStateOf(listOf<String>()) }
    var expandKnownVpn      by remember { mutableStateOf(false) }
    var newCustomVpn        by remember { mutableStateOf("") }

    var rules               by remember { mutableStateOf(listOf<NetworkCutoffRule>()) }
    var newPattern          by remember { mutableStateOf("") }
    var newMode             by remember { mutableStateOf(NetworkRuleMode.DOMAIN) }
    var appSpecific         by remember { mutableStateOf(false) }
    var newTargetProcess    by remember { mutableStateOf("") }
    var newTargetDisplay    by remember { mutableStateOf("") }

    var showPinGate         by remember { mutableStateOf(false) }
    var pendingAction       by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun showAdminError(context: String) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "$context requires administrator privileges. Re-launch FocusFlow as Administrator.",
                duration = SnackbarDuration.Long
            )
        }
    }

    fun withPin(action: () -> Unit) {
        if (GlobalPin.isSet()) {
            pendingAction = action
            showPinGate = true
        } else {
            action()
        }
    }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                vpnEnabled         = VpnBlocker.isEnabled
                customVpnProcesses = VpnBlocker.getCustomProcesses()
                rules              = Database.getNetworkCutoffRules()
                ProcessMonitor.networkCutoffKeywordEnabled =
                    rules.any { it.mode == NetworkRuleMode.KEYWORD && it.enabled }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(strings.vpnTitle, style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            Text(
                "Block VPN clients and manage domain/keyword network cut-off rules.",
                style = MaterialTheme.typography.bodyMedium, color = OnSurface2
            )
            Text(
                "Network rules require Administrator access. Domain rules use the Windows hosts file; browser Secure DNS, VPN routing, cached DNS, and existing connections can bypass or delay a cutoff.",
                style = MaterialTheme.typography.bodySmall,
                color = Warning
            )

            // ── VPN Shield ────────────────────────────────────────────────────────
            VpnSection(
                icon  = Icons.Default.VpnLock,
                title = strings.vpnShieldLabel,
                color = Error
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(strings.vpnBlockClients, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (vpnEnabled) "Active · known VPN apps are killed when detected"
                            else "Disabled · VPN apps can run freely",
                            color = if (vpnEnabled) Success else OnSurface2,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = vpnEnabled,
                        onCheckedChange = { v ->
                            if (!v) {
                                withPin {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { VpnBlocker.isEnabled = false }
                                        vpnEnabled = false
                                    }
                                }
                            } else {
                                withPin {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { VpnBlocker.isEnabled = true }
                                        vpnEnabled = true
                                    }
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Surface, checkedTrackColor = Error)
                    )
                }

                HorizontalDivider(color = Surface3, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

                // Known VPN apps collapsible
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        withPin { expandKnownVpn = !expandKnownVpn }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(strings.vpnBuiltInList, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${VpnBlocker.KNOWN_VPN_PROCESSES.size} VPN processes detected and blocked",
                            color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(
                        if (expandKnownVpn) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, tint = OnSurface2
                    )
                }

                if (expandKnownVpn) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface3)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        POPULAR_VPN_DISPLAY.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { (display, _) ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Error.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(display, color = Error, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "+ ${VpnBlocker.KNOWN_VPN_PROCESSES.size - POPULAR_VPN_DISPLAY.size} more process names covered",
                            color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp
                        )
                    }
                }

                HorizontalDivider(color = Surface3, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

                // Add custom VPN process
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.vpnAddCustom, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                    Text(strings.vpnAddCustomHint, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newCustomVpn,
                            onValueChange = { newCustomVpn = it },
                            placeholder = { Text("e.g. myvpn.exe", color = OnSurface2) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Error, unfocusedBorderColor = OnSurface2)
                        )
                        Button(
                            onClick = {
                                val name = newCustomVpn.trim()
                                if (name.isNotBlank()) {
                                    withPin {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { VpnBlocker.addCustomProcess(name) }
                                            ProcessMonitor.invalidateCaches()
                                            newCustomVpn = ""
                                            reload()
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Error)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.btnAdd)
                        }
                    }

                    if (customVpnProcesses.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${strings.vpnCustomEntries} (${customVpnProcesses.size})", color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                            customVpnProcesses.forEach { proc ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Surface3)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(proc, color = OnSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    ShortcutTooltip("Remove from VPN block") {
                                        IconButton(
                                            onClick = {
                                                withPin {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) { VpnBlocker.removeCustomProcess(proc) }
                                                        reload()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Close, null, tint = OnSurface2, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Network Cutoff Rules ──────────────────────────────────────────────
            VpnSection(
                icon  = Icons.Default.NetworkCheck,
                title = strings.vpnNetworkCutoff,
                color = Warning
            ) {
                Text(
                    "Type a domain to block via hosts file, or a keyword to cut network access for any app whose window title matches — optionally restricted to one specific app.",
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(4.dp))

                // Add rule form
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface3)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(strings.vpnAddRule, color = OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)

                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        placeholder = {
                            Text(
                                if (newMode == NetworkRuleMode.DOMAIN) "e.g. reddit.com" else "e.g. casino, betting",
                                color = OnSurface2
                            )
                        },
                        label = { Text(if (newMode == NetworkRuleMode.DOMAIN) "Domain" else "Keyword") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Warning, unfocusedBorderColor = OnSurface2)
                    )

                    // Mode selector
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NetworkModeChip(
                            label    = strings.vpnDomainBlock,
                            icon     = Icons.Default.Language,
                            selected = newMode == NetworkRuleMode.DOMAIN,
                            onClick  = { newMode = NetworkRuleMode.DOMAIN }
                        )
                        NetworkModeChip(
                            label    = strings.vpnKeywordCutoff,
                            icon     = Icons.Default.TextFields,
                            selected = newMode == NetworkRuleMode.KEYWORD,
                            onClick  = { newMode = NetworkRuleMode.KEYWORD }
                        )
                    }

                    // Mode info
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (newMode == NetworkRuleMode.DOMAIN) Purple80.copy(alpha = 0.07f)
                                else Warning.copy(alpha = 0.07f)
                            )
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info, null,
                            tint = if (newMode == NetworkRuleMode.DOMAIN) Purple80 else Warning,
                            modifier = Modifier.size(14.dp).padding(top = 1.dp)
                        )
                        Text(
                            if (newMode == NetworkRuleMode.DOMAIN)
                                "Blocks the domain via the Windows hosts file (requires admin). If app-specific is enabled, also adds a firewall rule for that app only."
                            else
                                "When the foreground window title contains this keyword, FocusFlow cuts network for the matching app via Windows Firewall — without killing the process.",
                            color = OnSurface2,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp
                        )
                    }

                    // App-specific toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(strings.vpnAppSpecific, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    appSpecific && newMode == NetworkRuleMode.DOMAIN ->
                                        "Domain blocked globally via hosts file; firewall rule also added for this app"
                                    appSpecific -> "Rule applies only to the app below"
                                    newMode == NetworkRuleMode.DOMAIN -> "Rule blocks domain system-wide via hosts file"
                                    else -> "Rule matches any foreground app"
                                },
                                color = OnSurface2, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = appSpecific,
                            onCheckedChange = { appSpecific = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Surface, checkedTrackColor = Warning)
                        )
                    }

                    if (appSpecific) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newTargetProcess,
                                onValueChange = { newTargetProcess = it },
                                label = { Text(strings.vpnProcessNameLabel) },
                                placeholder = { Text("e.g. chrome.exe", color = OnSurface2) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Warning, unfocusedBorderColor = OnSurface2)
                            )
                            OutlinedTextField(
                                value = newTargetDisplay,
                                onValueChange = { newTargetDisplay = it },
                                label = { Text(strings.vpnDisplayNameOpt) },
                                placeholder = { Text("e.g. Chrome", color = OnSurface2) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Warning, unfocusedBorderColor = OnSurface2)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val pat = newPattern.trim().lowercase()
                            if (pat.isBlank()) return@Button
                            val targetProc = if (appSpecific && newTargetProcess.isNotBlank()) {
                                newTargetProcess.trim().lowercase().let {
                                    if (!it.endsWith(".exe")) "$it.exe" else it
                                }
                            } else null
                            val targetDisp = if (appSpecific && newTargetDisplay.isNotBlank()) newTargetDisplay.trim()
                                             else targetProc?.removeSuffix(".exe")?.replaceFirstChar { it.uppercase() }

                            val rule = NetworkCutoffRule(
                                id                = UUID.randomUUID().toString(),
                                pattern           = pat,
                                mode              = newMode,
                                targetProcess     = targetProc,
                                targetDisplayName = targetDisp,
                                enabled           = true
                            )
                            withPin {
                                scope.launch {
                                    var hostsOk = true
                                    var firewallOk = true
                                    withContext(Dispatchers.IO) {
                                        Database.upsertNetworkCutoffRule(rule)
                                        // Apply immediately for domain rules.
                                        if (rule.mode == NetworkRuleMode.DOMAIN) {
                                            if (!HostsBlocker.canWriteHostsFile()) {
                                                hostsOk = false
                                            } else {
                                                val blockResult = HostsBlocker.blockDomain(pat)
                                                when (blockResult) {
                                                    is HostsBlocker.BlockResult.Success,
                                                    is HostsBlocker.BlockResult.AlreadyBlocked ->
                                                        HostsBlocker.startMonitor()
                                                    is HostsBlocker.BlockResult.VerificationFail,
                                                    is HostsBlocker.BlockResult.Error ->
                                                        hostsOk = false
                                                    else -> {}
                                                }
                                            }
                                            if (targetProc != null) {
                                                val added = NetworkBlocker.addRule(targetProc)
                                                if (!added) firewallOk = false
                                            }
                                        }
                                    }
                                    ProcessMonitor.invalidateCaches()
                                    if (!hostsOk) showAdminError("Hosts file domain blocking")
                                    if (!firewallOk) showAdminError("Firewall rule creation")
                                    newPattern       = ""
                                    newTargetProcess = ""
                                    newTargetDisplay = ""
                                    appSpecific      = false
                                    reload()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Warning),
                        enabled = newPattern.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.vpnAddRule, color = Surface)
                    }
                }

                // Active rules list
                if (rules.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("${strings.vpnActiveRules} (${rules.size})", color = OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)

                    rules.forEach { rule ->
                        NetworkRuleRow(
                            rule       = rule,
                            onToggle   = { enabled ->
                                if (!enabled) {
                                    withPin {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                Database.setNetworkCutoffRuleEnabled(rule.id, false)
                                                if (rule.mode == NetworkRuleMode.DOMAIN) {
                                                    HostsBlocker.unblockDomain(rule.pattern)
                                                    if (rule.targetProcess != null) NetworkBlocker.removeRule(rule.targetProcess)
                                                }
                                            }
                                            reload()
                                        }
                                    }
                                } else {
                                    withPin {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                Database.setNetworkCutoffRuleEnabled(rule.id, true)
                                                if (rule.mode == NetworkRuleMode.DOMAIN) {
                                                    val re = HostsBlocker.blockDomain(rule.pattern)
                                                    if (re is HostsBlocker.BlockResult.Success ||
                                                        re is HostsBlocker.BlockResult.AlreadyBlocked) {
                                                        HostsBlocker.startMonitor()
                                                    }
                                                    if (rule.targetProcess != null) NetworkBlocker.addRule(rule.targetProcess)
                                                }
                                            }
                                            ProcessMonitor.invalidateCaches()
                                            reload()
                                        }
                                    }
                                }
                            },
                            onRemove   = {
                                withPin {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Database.deleteNetworkCutoffRule(rule.id)
                                            if (rule.mode == NetworkRuleMode.DOMAIN) {
                                                HostsBlocker.unblockDomain(rule.pattern)
                                                if (rule.targetProcess != null) NetworkBlocker.removeRule(rule.targetProcess)
                                            }
                                        }
                                            ProcessMonitor.invalidateCaches()
                                        reload()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Info footer
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Purple80.copy(alpha = 0.07f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = Purple80, modifier = Modifier.size(18.dp))
                Text(
                    "Both VPN Shield and Network Cutoff Rules require FocusFlow to run with administrator privileges. Domain blocks modify the Windows hosts file; keyword cutoffs and VPN blocks use Windows Firewall rules.",
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        FfVerticalScrollbar(
            scrollState = scrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp)
        )
    }

    if (showPinGate) {
        PinGateDialog(
            title    = strings.defPinRequired,
            subtitle = strings.defEnterPin,
            onSuccess = {
                showPinGate = false
                pendingAction?.invoke()
                pendingAction = null
                com.focusflow.services.ReviewPromptService.triggerCheck()
            },
            onDismiss = {
                showPinGate = false
                pendingAction = null
            }
        )
    }
}

@Composable
private fun VpnSection(
    icon: ImageVector,
    title: String,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Text(title, color = OnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun NetworkModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Warning.copy(alpha = 0.15f) else Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (selected) Warning else OnSurface2, modifier = Modifier.size(14.dp))
        Text(label, color = if (selected) Warning else OnSurface2, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun NetworkRuleRow(
    rule: NetworkCutoffRule,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val modeColor = if (rule.mode == NetworkRuleMode.DOMAIN) Purple80 else Warning
    val modeIcon  = if (rule.mode == NetworkRuleMode.DOMAIN) Icons.Default.Language else Icons.Default.TextFields
    val modeLabel = if (rule.mode == NetworkRuleMode.DOMAIN) "Domain" else "Keyword"

    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface3)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(modeColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(modeIcon, null, tint = modeColor, modifier = Modifier.size(15.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rule.pattern, color = OnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(modeColor.copy(alpha = 0.12f)).padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(modeLabel, color = modeColor, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
                if (!rule.enabled) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(OnSurface2.copy(alpha = 0.1f)).padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("Paused", color = OnSurface2, fontSize = 9.sp)
                    }
                }
            }
            Text(
                if (rule.targetDisplayName != null) "→ ${rule.targetDisplayName}" else "→ All apps",
                color = OnSurface2,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp
            )
        }

        Switch(
            checked  = rule.enabled,
            onCheckedChange = onToggle,
            colors   = SwitchDefaults.colors(checkedThumbColor = Surface, checkedTrackColor = modeColor),
            modifier = Modifier.height(24.dp)
        )

        ShortcutTooltip("Remove network rule") {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, null, tint = Error, modifier = Modifier.size(16.dp))
            }
        }
    }
}
