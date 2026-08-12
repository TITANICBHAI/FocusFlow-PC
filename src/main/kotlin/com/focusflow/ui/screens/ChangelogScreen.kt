package com.focusflow.ui.screens

import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.ui.theme.*

internal data class ChangelogEntry(
    val version: String,
    val date: String,
    val badge: String,
    val badgeColor: Color,
    val changes: List<Pair<String, String>>
)

internal val CHANGELOG = listOf(
    ChangelogEntry(
        version    = "1.1.8",
        date       = "August 2026",
        badge      = "NEW",
        badgeColor = Purple80,
        changes    = listOf(
            "NEW" to "Microsoft Edge extension promotion — shown once on the 20th app launch with a direct link to the official FocusFlow Edge Add-ons listing",
            "IMP" to "Edge extension promotion can be dismissed permanently; it will not appear again after dismissal or installation",
            "FIX" to "Discord crash reporting now completes its bounded webhook request before the JVM exits, preventing fatal reports from being lost",
            "FIX" to "Feedback delivery now checks Discord responses and reports failures instead of showing a false success state"
        )
    ),
    ChangelogEntry(
        version    = "1.1.7",
        date       = "August 2026",
        badge      = "STABLE",
        badgeColor = Success,
        changes    = listOf(
            "NEW" to "Microsoft Store rating prompt — smart review dialog shown after the 10th app open, triggered by task completion, focus session end, standalone block expiry, or PIN unlock; 30-day snooze on decline, permanent dismiss on rate",
            "NEW" to "In-app feedback channel — 'Report an Issue' option inside the rating dialog sends feedback directly to the team via Discord; shown only when webhook is configured",
            "FIX" to "Microsoft Store deep-link now correctly targets the FocusFlow listing (Product ID wired to the live Store page)"
        )
    ),
    ChangelogEntry(
        version    = "1.1.6",
        date       = "July 2026",
        badge      = "STABLE",
        badgeColor = Success,
        changes    = listOf(
            "IMP" to "Restored all improvements and fixes from v1.1.1 through v1.1.4 — collapsible sidebar, Restart as Administrator button, Nuclear Mode PIN, Nuclear Mode screen, aversion sound fix, block overlay countdown fix, donate button, Android promo dialog, kiosk break countdown drift fix, and all associated stability and concurrency fixes"
        )
    ),
    ChangelogEntry(
        version    = "1.1.5",
        date       = "July 2026",
        badge      = "STABLE",
        badgeColor = Success,
        changes    = listOf(
            "NEW" to "Nuclear Mode PIN — 4-character PIN exclusively for the Nuclear Mode off-switch; set under Settings → Advanced → Nuclear Mode PIN; independent of Global PIN and Session PIN",
            "SEC" to "Nuclear Mode off-switch is now PIN-gated — toggling off in Settings requires the PIN when one is set; kiosk mode (FocusLauncherService) bypasses the UI gate as intended",
            "FIX" to "App picker duplicate-key crash — FocusLauncher app list (×2), Allowance picker, and Timed Block picker now use composite keys (processName + index) so duplicate process names from the scanner no longer cause an IllegalStateException",
            "FIX" to "Blocked Apps report duplicate-key crash — items list now uses composite key (displayName + index) so the same process stored under different display-name casing by different enforcement paths no longer causes IllegalArgumentException in LazyColumn"
        )
    ),
    ChangelogEntry(
        version    = "1.1.4",
        date       = "July 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW" to "Collapsible sidebar — collapse to icon-only mode; hover for tooltips; state remembered between sessions",
            "NEW" to "Restart as Administrator button — one-click button in bottom-right corner",
            "FIX" to "Ctrl+4 shortcut mismatch — tooltip said 'Block Defense' but navigated to App Blocker; both now correct",
            "NEW" to "Nuclear Mode moved into Block Controls — dedicated screen with live status, escape-route coverage, blocked attempts, enable/disable, and PIN management",
            "FIX" to "Block overlay countdown — repeated detections no longer reset the dismissal timer or hide the Dismiss button",
            "FIX" to "Aversion sound not playing — audio pipeline reinitialises clip on each call so sound fires reliably",
            "IMP" to "Localization keys added for 'Standalone Block' and 'Contact & Reports' sidebar labels (7 languages)",
            "NEW" to "CPU model name in diagnostics — reported in anonymous health telemetry; queried once via wmic and cached",
            "NEW" to "CPU load tracking — process and system-wide CPU % sampled every minute; period average and peak in hourly heartbeat",
            "NEW" to "Machine tier classification — Low / Mid / High based on core count and RAM",
            "NEW" to "CPU spike alert — yellow diagnostic alert if FocusFlow's own process exceeds 25% CPU",
            "UPD" to "Telemetry consent dialog updated to mention CPU snapshots"
        )
    ),
    ChangelogEntry(
        version    = "1.1.3",
        date       = "July 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW" to "Nuclear Mode PIN — 4-character PIN exclusively for the Nuclear Mode off-switch; independent of Global PIN and session PINs",
            "SEC" to "Nuclear Mode off-switch now PIN-gated — toggling off requires the PIN when set",
            "FIX" to "App picker duplicate-key crash (FocusLauncher ×2, Allowance picker, Timed Block picker) — list index appended as tiebreaker"
        )
    ),
    ChangelogEntry(
        version    = "1.1.2",
        date       = "July 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW" to "Donate button — passive ♥ icon in Dashboard header; opens UPI / international instructions",
            "NEW" to "Android promo on app update — AndroidPromoDialog fires automatically on first open of a new version",
            "NEW" to "Android promo on block screen — typed URL appears after 3rd blocked attempt; resets every 30 days",
            "NEW" to "Android promo cooldown — resets every 30 days; shows at 3 app opens instead of 10",
            "FIX" to "Kiosk mode break countdown drift — now uses wall-clock polled at 500 ms for drift-free display",
            "FIX" to "breakSecondsAccumulated race — converted to AtomicLong",
            "FIX" to "toggleHardLock() non-atomic flip — replaced with a compareAndSet loop",
            "FIX" to "Accidental kiosk exit — Exit button now shows a confirmation dialog instead of calling exit() directly"
        )
    ),
    ChangelogEntry(
        version    = "1.1.1",
        date       = "June 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "FIX" to "Privacy & Terms onboarding — no scroll modifier; accept checkbox clipped off-screen; page is now scrollable",
            "FIX" to "Add Task / Edit Task dialogs — no scroll modifier; fields below fold unreachable; both now scroll within 520 dp max height",
            "FIX" to "OutOfMemoryError in system tray — tray icon now pre-scaled to exact OS-requested size; autoSize disabled",
            "FIX" to "JNA Memory cleaner / coroutine OOM — downstream from tray OOM; fixed at root cause; JVM heap ceiling raised",
            "FIX" to "LazyColumn missing stable keys in Stats screen",
            "FIX" to "LazyVerticalGrid missing stable keys in Focus Launcher Overlay",
            "FIX" to "File descriptor leak in process enforcement — processes now started with DISCARD redirects",
            "FIX" to "NetworkBlocker PowerShell output reader — explicit stdin close, use{} stream scope, 64 KB output cap",
            "FIX" to "WinEventHook / GlobalKeyboardHook thread null-deref race — local val captured before use",
            "FIX" to "FloatingBlockOverlay Graphics2D unsafe cast — safe cast with early return",
            "FIX" to "FocusLauncherOverlay clock ticker coroutine leak — while(true) changed to while(isActive)",
            "FIX" to "App.kt stale task pre-population — focusPreloadTask now cleared on navigation away from Focus screen",
            "FIX" to "JMX physical memory unsafe cast — cast to Number first, then .toLong()",
            "FIX" to "Nuclear Mode tasklist OOM guard — output capped at 256 KB",
            "FIX" to "Focus Launcher Overlay icon unsafe !! — captured in immutable local val before use",
            "SEC" to "Hardcoded Discord webhook URLs removed — now injected at build time via JVM system property; absent by default in user builds",
            "IMP" to "JVM heap raised to 1 GB (-Xmx1g); initial allocation 128 MB; soft-reference eviction tuned"
        )
    ),
    ChangelogEntry(
        version    = "1.1.0",
        date       = "June 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "FIX" to "Privacy & Terms onboarding screen — page had no scroll modifier so the accept checkbox was clipped below the visible area on all standard displays, leaving the Next button permanently disabled; page is now scrollable",
            "FIX" to "Add Task and Edit Task dialogs — tall form (10+ fields, focus mode card, app picker, PIN toggle) had no scroll modifier; fields below the fold were completely unreachable; both dialogs now scroll within a 520 dp max height",
            "FIX" to "OutOfMemoryError in system tray — WTrayIconPeer.updateNativeImage OOM caused by isImageAutoSize=true triggering AWT's lazy FilteredImageSource scaling pipeline; tray icon is now scaled once to the exact OS-requested size and autoSize is disabled",
            "FIX" to "JNA Memory cleaner and coroutine OOM — downstream heap exhaustion from the tray OOM; fixed by eliminating the root cause and raising JVM heap ceiling",
            "FIX" to "LazyColumn items in Stats screen missing stable keys — sessions and tasks lists now use key = { it.id } preventing incorrect animations and scroll-position loss on list updates",
            "FIX" to "LazyVerticalGrid in Focus Launcher Overlay missing stable keys — app tiles now use key = { it.processName }",
            "IMP" to "JVM heap raised from 512 MB to 1 GB (-Xmx1g); initial allocation raised to 128 MB (-Xms128m); added -XX:SoftRefLRUPolicyMSPerMB=50 to evict Skiko/JNA soft-reference caches faster under memory pressure"
        )
    ),
    ChangelogEntry(
        version    = "1.0.9",
        date       = "June 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW" to "Resource Monitor Service — anonymous JVM / OS health telemetry sent to a private developer Discord channel on an hourly heartbeat",
            "NEW" to "Heap threshold alerts — instant Discord notification when heap exceeds 75 % (warning) or 90 % (critical) of max, with 30-minute cooldown to prevent flooding",
            "NEW" to "Thread spike alert — fires when live thread count surpasses 150, helping catch runaway executor leaks early",
            "NEW" to "Resource metrics collected: heap used / total / max MB, non-heap, physical RAM, CPU cores, live / daemon / peak thread counts, GC collections and pause time",
            "NEW" to "Full feature usage telemetry — Nuclear Mode enable/disable (with blocked-attempt count), Emergency Break activate/deactivate (budget snapshot), Focus Session start/end (planned vs actual, completion rate, blocked attempts), Focus Launcher enter/exit/break/hard-lock, Standalone Block start/stop, Pomodoro cycle completed + settings saved, Launch-at-Login toggles, and Onboarding completion — all anonymous, all opt-out via Settings",
            "IMP" to "Privacy policy, onboarding screen (all 7 languages), and Settings privacy section updated to accurately describe all anonymous telemetry collected",
            "IMP" to "Privacy section in Settings updated — crash reports and resource telemetry share a single 'Send anonymous diagnostics' toggle with a clear description of what is and is not collected",
            "IMP" to "Zero PII guarantee — no usernames, task names, file paths, IP addresses or app content are ever included in any telemetry payload",
            "FIX" to "Missing @Volatile on background Job references in 8 services (DailyAllowanceTracker, AutoBackupService, RecurringTaskService, TaskAlarmService, WeeklyReportService, BlockScheduleService, HostsBlocker, FocusSessionService) — shutdown thread could read a stale null and silently skip cancel(), leaving loops running after teardown",
            "FIX" to "StandaloneBlockService build error — missing import kotlinx.coroutines.flow.update caused compilation failure",
            "FIX" to "Missing @Volatile on NuclearMode.monitorJob — shutdown thread calling disable() could see a stale null and skip cancel(), leaving the escape-process kill loop running after teardown",
            "FIX" to "Missing @Volatile on KillSwitchService.countdownJob — countdown Job written on AWT tray thread was invisible to the IO/shutdown thread reading it in deactivate(), risking a leaked timer coroutine through shutdown",
            "FIX" to "Missing @Volatile on FocusLauncherService.breakJob and sessionTimerJob — both fields written on UI/IO threads and read by exit() on the shutdown thread; without visibility guarantee, cancel() could silently no-op on a stale null",
            "FIX" to "Missing @Volatile on BreakEnforcer.breakJob — field written on the Compose main thread and read by reset() called from FocusSessionService on an IO coroutine; cross-thread read could see stale null and skip cancel()",
            "FIX" to "Session not linked to task in database — FocusSessionService.start() was never passed the task ID (tid) when launching from a task card, quick-pick chip, or Ctrl+Enter shortcut; sessions were saved with taskId = null, breaking task–session correlation in stats",
            "FIX" to "deleteWithUndo showed 'deleted' snackbar while the task was still visible — deletion was deferred until snackbar expiry with no optimistic UI update; task now disappears immediately and is restored from DB if the user clicks Undo",
            "FIX" to "Keyword Blocker count in the enforcement panel was hardcoded to 0; always showed 'None configured' regardless of configured keywords — now loaded from DB on every reload()"
        )
    ),
    ChangelogEntry(
        version    = "1.0.8",
        date       = "June 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "FIX"  to "OutOfMemoryError on startup — tray icon load now catches Throwable (not just Exception), falling back to the programmatic icon gracefully on low-memory machines",
            "FIX"  to "Onboarding crash on rapid Back clicks — 300 ms debounce prevents 'mutex not locked' error caused by Compose gesture cleanup racing AnimatedContent exit animations",
            "IMP"  to "Scrollbars always visible at rest (subtle purple track) across all 17 scrollable screens — hover near the right edge to light it up, no more invisible guessing",
            "IMP"  to "Scrollbar thumb widened to 8 dp and native Compose Desktop hover colour now works correctly (alpha modifier that was overriding it removed)",
            "NEW"  to "Android app sidebar button now opens a popup with App Gallery and GitHub APK options — GitHub path includes a SAI sideload guide explaining restricted-settings lift",
            "NEW"  to "Keyboard Shortcuts page added to onboarding flow (page 5, after Permissions) — users see all shortcuts before finishing setup",
            "NEW"  to "Keyboard Shortcuts section added to How to Use screen — Ctrl+1–5, Ctrl+,, Ctrl+N, Ctrl+F, Ctrl+P, Ctrl+Enter all documented with context notes",
            "IMP"  to "MSIX store logos now scaled from the real focusflow_256.png source — preserves rounded-corner artwork instead of flat GDI+ redraw",
            "IMP"  to "MSIX StoreLogo.png (50×50) added — was missing, causing Store listing to show a generic placeholder icon",
            "IMP"  to "Build workflow reads app version dynamically from build.gradle.kts — bumping version in one place updates manifest, artifact filename and all CI outputs automatically"
        )
    ),
    ChangelogEntry(
        version    = "1.0.7",
        date       = "May 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "FIX"  to "Daily allowances — usage tracking and quota enforcement now work correctly",
            "NEW"  to "ShortcutTooltip + AppLocals components — reusable tooltip and app-wide DI primitives",
            "NEW"  to "Global Ctrl+1–5, Ctrl+, keyboard shortcuts for instant screen navigation",
            "NEW"  to "Screen-specific shortcuts: Dashboard, Tasks and Focus shortcuts documented in tooltips",
            "NEW"  to "Sound volume slider in Settings — live-adjust aversion-tone volume (0–100%)",
            "NEW"  to "Block Overlay Duration slider in Settings — set dismiss time from 2 s to 15 s",
            "NEW"  to "Task deletion with Undo snackbar — accidental deletes are fully recoverable",
            "NEW"  to "Keyword Blocker 'Clear All' now shows a confirmation dialog before wiping the list",
            "IMP"  to "Block Defense screen — 'Edit in App Blocker →' button opens the blocker directly",
            "IMP"  to "App Blocker manual-entry errors are now field-specific (duplicate / empty / invalid)",
            "IMP"  to "Focus screen shows helper hint when Focus Mode is disabled",
            "IMP"  to "Stats (Yesterday / Week) and Reports (Sessions) tabs show loading spinners",
            "IMP"  to "Empty-state cards in App Blocker (Always-Block tab), Stats and Reports screens"
        )
    ),
    ChangelogEntry(
        version    = "1.0.5",
        date       = "May 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW"  to "Keyword Blocker — block browser tabs matching custom words or presets",
            "NEW"  to "Block Defense screen — all enforcement layers in one place",
            "NEW"  to "Active/Live status screen — real-time view of every running block",
            "NEW"  to "How to Use guide — accordion help sections for every feature",
            "NEW"  to "Changelog screen (you're reading it!)",
            "NEW"  to "Emergency Break — 5-minute daily kill switch in the tray menu; pauses all enforcement layers instantly",
            "NEW"  to "Watchdog auto-restart — FocusFlow relaunches itself every 2 minutes via Task Scheduler if it ever gets killed or crashes",
            "NEW"  to "Standalone blocker: Date Range scheduling — pick exact start and end date/time for a block",
            "IMP"  to "SideNav now shows Android-style grouped sections",
            "IMP"  to "FocusScreen Enforcement panel expanded with sub-rows",
            "IMP"  to "Dashboard header shows X/Y tasks done subtitle",
            "IMP"  to "Tray menu shows live countdown of remaining break budget (resets at midnight)",
            "IMP"  to "Kill switch respects Nuclear Mode — even escape-process blocking is paused during the grace period",
            "IMP"  to "Scheduled blocks show a live countdown until the block begins, then switch to remaining time",
            "FIX"  to "CMD, PowerShell and other shells now killed during any enforcement — Nuclear Mode no longer required",
            "FIX"  to "App crash on process kill permission denied on non-Windows OS"
        )
    ),
    ChangelogEntry(
        version    = "1.0.4",
        date       = "April 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW"  to "Habits tracker with emoji support and calendar heatmap",
            "NEW"  to "Reports screen — weekly and all-time productivity summaries",
            "NEW"  to "Daily Notes with mood selector",
            "NEW"  to "Always-On enforcement toggle in Settings",
            "NEW"  to "Onboarding dialog on first launch",
            "IMP"  to "StatsScreen redesigned with Yesterday / Today / Week / All-Time tabs",
            "IMP"  to "Streak banner and bitter truth card in Stats",
            "FIX"  to "Block schedules not reloading after changes"
        )
    ),
    ChangelogEntry(
        version    = "1.0.3",
        date       = "March 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW"  to "Block Schedules — define recurring time windows for app blocking",
            "NEW"  to "Daily Allowances — allow apps for a limited daily usage time",
            "NEW"  to "Session PIN — require a PIN to end a focus session early",
            "NEW"  to "Sound Aversion toggle in Settings",
            "IMP"  to "AppBlockerScreen rewritten with pick-from-running-apps support",
            "IMP"  to "Standalone block panel added to Focus tab",
            "FIX"  to "Network block (Windows Firewall rule) not applied consistently"
        )
    ),
    ChangelogEntry(
        version    = "1.0.2",
        date       = "February 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW"  to "Pomodoro mode with configurable work / break / long-break intervals",
            "NEW"  to "Session notes field during focus session",
            "NEW"  to "Distraction counter during session",
            "NEW"  to "TasksScreen with priority, tags, recurring task support",
            "IMP"  to "DashboardScreen shows live session countdown",
            "FIX"  to "Recurring tasks not generating correctly on weekly cadence"
        )
    ),
    ChangelogEntry(
        version    = "1.0.1",
        date       = "January 2026",
        badge      = "",
        badgeColor = Color.Transparent,
        changes    = listOf(
            "NEW"  to "App Blocker with process-kill enforcement",
            "NEW"  to "Focus session timer with pause / resume",
            "NEW"  to "Block overlay shown when blocked app is detected",
            "NEW"  to "Temptation log — records every blocked-app attempt",
            "IMP"  to "SQLite database for persistent storage",
            "FIX"  to "Window not restoring from system tray on some machines"
        )
    ),
    ChangelogEntry(
        version    = "1.0.0",
        date       = "December 2025",
        badge      = "INITIAL",
        badgeColor = OnSurface2,
        changes    = listOf(
            "NEW"  to "Initial release of FocusFlow JVM desktop app",
            "NEW"  to "Dashboard, Tasks, Focus, Stats screens",
            "NEW"  to "Profile & Settings screens",
            "NEW"  to "Privacy & Permissions screen",
            "NEW"  to "TBTechs branding and FocusFlow logo"
        )
    )
)

private val BADGE_COLOR = mapOf(
    "NEW" to Purple80,
    "IMP" to Warning,
    "FIX" to Error,
    "SEC" to Color(0xFF29B6F6),
    "UPD" to Color(0xFF26C6DA)
)

@Composable
fun ChangelogScreen() {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = Purple80, modifier = Modifier.size(28.dp))
                Text("Changelog", style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            }
            Text("What's new in FocusFlow JVM by TBTechs", style = MaterialTheme.typography.bodyMedium, color = OnSurface2)

            CHANGELOG.forEach { entry ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface2)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "v${entry.version}",
                            color = OnSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        if (entry.badge.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(entry.badgeColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    entry.badge,
                                    color = entry.badgeColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(entry.date, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                    }

                    HorizontalDivider(color = Surface3, thickness = 1.dp)

                    entry.changes.forEach { (tag, text) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background((BADGE_COLOR[tag] ?: OnSurface2).copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    tag,
                                    color = BADGE_COLOR[tag] ?: OnSurface2,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                            Text(
                                text,
                                color = OnSurface2,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 1.dp)
                            )
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
}
