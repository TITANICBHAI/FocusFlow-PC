package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.foundation.background
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
import com.focusflow.enforcement.WindowsStartupManager
import com.focusflow.enforcement.isWindows
import com.focusflow.ui.components.PermissionSetupCard
import com.focusflow.ui.theme.*

@Composable
fun WindowsSetupScreen() {
    val scrollState = rememberScrollState()
    val isAdmin = remember { com.focusflow.enforcement.ElevationManager.isElevated }

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AdminPanelSettings, null, tint = Purple80, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text("Windows Setup & Permissions", style = MaterialTheme.typography.headlineSmall, color = OnSurface, fontWeight = FontWeight.Bold)
                    Text("Grant the right access so every feature works reliably.", fontSize = 13.sp, color = OnSurface2)
                }
            }

            // Status badge
            if (!isWindows) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Warning.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(16.dp))
                    Text("Running on a non-Windows platform — enforcement features inactive.", fontSize = 12.sp, color = Warning)
                }
            } else if (isAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Success.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Success, modifier = Modifier.size(16.dp))
                    Text("Running as Administrator — all features available.", fontSize = 12.sp, color = Success)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Error.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = Error, modifier = Modifier.size(16.dp))
                    Column {
                        Text("Not running as Administrator", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Error)
                        Text("Network blocking, Nuclear Mode, and hosts-file blocking won't work. Re-launch with admin rights.", fontSize = 12.sp, color = OnSurface2)
                    }
                }
            }

            HorizontalDivider(color = OnSurface.copy(alpha = 0.08f))

            Text("Required Permissions", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)

            PermissionSetupCard(
                icon = Icons.Default.AdminPanelSettings,
                iconTint = Error,
                title = "Run as Administrator",
                needed = "Needed for: process kill, network firewall rules, hosts file, Nuclear Mode",
                howTo = """
                    Right-click FocusFlow.exe and choose "Run as administrator".
                    To make it permanent: right-click FocusFlow.exe → Properties → Compatibility → check "Run this program as an administrator" → OK.
                    If installed from MSIX, find the shortcut in Start Menu, right-click → More → Run as administrator.
                """.trimIndent(),
                required = true,
                actionLabel = if (!isAdmin && isWindows) "Relaunch as Admin →" else null,
                onAction = if (!isAdmin && isWindows) ({
                    com.focusflow.services.ElevationRelaunchService.request()
                }) else null
            )

            PermissionSetupCard(
                icon = Icons.Default.Security,
                iconTint = Warning,
                title = "Windows Defender Exclusion",
                needed = "Needed for: prevents Defender from blocking FocusFlow when it kills processes",
                howTo = """
                    Open Windows Security → Virus & threat protection → Manage settings.
                    Scroll to Exclusions → Add or remove exclusions → Add an exclusion → Folder.
                    Select the folder where FocusFlow.exe is installed (e.g. C:\Program Files\FocusFlow).
                    Click Select Folder — done.
                """.trimIndent(),
                required = false,
                actionLabel = if (isWindows) "Open Security →" else null,
                onAction = if (isWindows) ({
                    try {
                        if (java.awt.Desktop.isDesktopSupported())
                            java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:windowsdefender"))
                    } catch (_: Exception) {}
                }) else null
            )

            PermissionSetupCard(
                icon = Icons.Default.Wifi,
                iconTint = Purple80,
                title = "Windows Firewall Access",
                needed = "Needed for: network blocking (blocks apps from accessing the internet)",
                howTo = """
                    Run FocusFlow as Administrator (see above).
                    The first time network blocking activates, Windows may show a UAC prompt — click Yes.
                    FocusFlow uses "netsh advfirewall" to add and remove outbound block rules.
                    You can verify rules in Windows Defender Firewall → Outbound Rules (look for "FocusFlow - Block ..." entries).
                """.trimIndent(),
                required = true,
                actionLabel = if (isWindows) "Open Firewall →" else null,
                onAction = if (isWindows) ({
                    try { ProcessBuilder("cmd", "/c", "start", "wf.msc").start() } catch (_: Exception) {}
                }) else null
            )

            PermissionSetupCard(
                icon = Icons.Default.Edit,
                iconTint = Warning,
                title = "Hosts File Write Access",
                needed = "Needed for: domain blocking (blocks websites system-wide, all browsers)",
                howTo = """
                    Run FocusFlow as Administrator — this automatically grants hosts file access.
                    The hosts file is at C:\Windows\System32\drivers\etc\hosts.
                    FocusFlow appends block entries and removes them when unblocked.
                    No manual action needed beyond running as admin.
                """.trimIndent(),
                required = false
            )

            PermissionSetupCard(
                icon = Icons.Default.Autorenew,
                iconTint = Success,
                title = "Auto-Start with Windows",
                needed = "Needed for: FocusFlow starts automatically on login (no admin required)",
                howTo = """
                    Open FocusFlow Settings → scroll to "Launch at startup" and toggle it on.
                    This writes a HKCU\Software\Microsoft\Windows\CurrentVersion\Run registry key.
                    No admin rights needed — runs for your user account only.
                    To verify: open Task Manager → Startup Apps — look for FocusFlow.
                """.trimIndent(),
                required = false,
                actionLabel = if (isWindows) "Open Startup Apps →" else null,
                onAction = if (isWindows) ({
                    try { ProcessBuilder("cmd", "/c", "start", "taskmgr.exe").start() } catch (_: Exception) {}
                }) else null
            )

            PermissionSetupCard(
                icon = Icons.Default.Notifications,
                iconTint = Purple80,
                title = "Allow FocusFlow Notifications",
                needed = "Needed for: session alerts, blocked-app warnings and weekly focus reports",
                howTo = """
                    Open Windows Settings → System → Notifications.
                    Scroll down to the app list and find FocusFlow.
                    Make sure the toggle is ON and "Banners" is enabled.
                    This allows FocusFlow to show pop-up alerts during focus sessions.
                """.trimIndent(),
                required = false,
                actionLabel = if (isWindows) "Open Notifications →" else null,
                onAction = if (isWindows) ({
                    try {
                        if (java.awt.Desktop.isDesktopSupported())
                            java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:notifications"))
                    } catch (_: Exception) {}
                }) else null
            )

            PermissionSetupCard(
                icon = Icons.Default.DoNotDisturb,
                iconTint = Warning,
                title = "Disable Focus Assist (Do Not Disturb)",
                needed = "Needed for: FocusFlow alerts get through — Windows DND silences all app notifications",
                howTo = """
                    Open Windows Settings → System → Focus Assist (Windows 10) or Notifications → Focus (Windows 11).
                    Set it to "Off" or add FocusFlow as a priority app exception.
                    Adding as an exception lets you keep DND on while still receiving FocusFlow alerts.
                    Windows 11: Settings → System → Notifications → Turn on do not disturb → Add priority apps → FocusFlow.
                """.trimIndent(),
                required = false,
                actionLabel = if (isWindows) "Open Focus Assist →" else null,
                onAction = if (isWindows) ({
                    try {
                        if (java.awt.Desktop.isDesktopSupported())
                            java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:quiethours"))
                    } catch (_: Exception) {}
                }) else null
            )

            HorizontalDivider(color = OnSurface.copy(alpha = 0.08f))

            Text("Feature — Permission Map", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = OnSurface)

            val featureMap = listOf(
                Triple(Icons.Default.Block,         "App Blocking (kill process)",         "Administrator (for taskkill on elevated processes)"),
                Triple(Icons.Default.Wifi,           "Network Blocking (firewall rules)",    "Administrator"),
                Triple(Icons.Default.Language,       "Domain / Hosts Blocking",              "Administrator (writes C:\\Windows\\System32\\drivers\\etc\\hosts)"),
                Triple(Icons.Default.Lock,            "Nuclear Mode",                         "Administrator"),
                Triple(Icons.Default.Timer,          "Focus Sessions & Pomodoro",            "None — works without admin"),
                Triple(Icons.Default.Schedule,       "Block Schedules",                      "None — schedule engine runs in JVM"),
                Triple(Icons.Default.HourglassBottom, "Daily Allowances",                     "None — tracked locally in SQLite"),
                Triple(Icons.Default.TextFields,     "Keyword Blocker",                      "None — reads window titles via JNA"),
                Triple(Icons.Default.Notifications,  "System Tray & Notifications",          "None — Java AWT SystemTray"),
                Triple(Icons.Default.BarChart,       "Stats & Reports",                      "None — SQLite, fully local"),
                Triple(Icons.Default.Loop,           "Auto-Start with Windows",              "None — HKCU registry key, no admin")
            )

            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                featureMap.forEachIndexed { idx, (icon, feature, perm) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (idx % 2 == 0) Surface2 else OnSurface.copy(alpha = 0.03f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(icon, null, tint = Purple80.copy(alpha = 0.7f), modifier = Modifier.size(16.dp).padding(top = 1.dp))
                        Column(modifier = Modifier.weight(0.45f)) {
                            Text(feature, fontSize = 12.sp, color = OnSurface, fontWeight = FontWeight.Medium)
                        }
                        Text(perm, fontSize = 12.sp, color = OnSurface2, modifier = Modifier.weight(0.55f), lineHeight = 16.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        FfVerticalScrollbar(
            scrollState = scrollState,
            modifier    = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp)
        )
    }
}
