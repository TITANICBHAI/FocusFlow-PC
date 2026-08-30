package com.focusflow

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.focusflow.data.Database
import com.focusflow.data.models.Screen
import com.focusflow.data.models.Task
import com.focusflow.enforcement.AppBlocker
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.RegistryLockdown
import com.focusflow.enforcement.VpnBlocker
import com.focusflow.enforcement.isRunningAsAdmin
import com.focusflow.services.HostsBlocker
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.FocusSessionService
import kotlin.system.exitProcess
import com.focusflow.ui.components.AndroidPromoDialog
import com.focusflow.ui.components.BlockOverlay
import com.focusflow.ui.components.EdgeExtensionPromoDialog
import com.focusflow.ui.components.openEdgeExtensionStore
import com.focusflow.ui.components.FocusLauncherBreakBanner
import com.focusflow.ui.components.FocusLauncherOverlay
import com.focusflow.ui.components.GlobalPinSetupDialog
import com.focusflow.ui.components.OsBanner
import com.focusflow.ui.components.OnboardingDialog
import com.focusflow.services.ReviewPromptService
import com.focusflow.ui.components.ReviewPromptDialog
import com.focusflow.ui.components.SideNav
import com.focusflow.ui.components.TelemetryConsentDialog
import com.focusflow.services.FocusLauncherService
import com.focusflow.services.GlobalPin
import com.focusflow.ui.screens.*
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.*
import com.focusflow.ui.LocalNavigate

private const val APP_VERSION = "1.1.9"
private const val EDGE_EXTENSION_PROMO_DISMISSED = "edge_extension_promo_dismissed"

/**
 * Screens where the floating "Restart as Admin" button is shown.
 * Only surfaces on screens where admin-gated features (firewall rules,
 * hosts-file blocking, Nuclear Mode firewall layer) are directly relevant.
 * Pure data/productivity screens are excluded to avoid noise.
 */
private val ADMIN_BUTTON_SCREENS = setOf(
    Screen.DASHBOARD,
    Screen.ACTIVE,
    Screen.SETTINGS,
    Screen.BLOCK_APPS,
    Screen.BLOCK_DEFENSE,
    Screen.VPN_NETWORK,
    Screen.NUCLEAR_MODE,
    Screen.STANDALONE_BLOCK,
    Screen.KEYWORD_BLOCKER,
    Screen.WINDOWS_SETUP
)

@Composable
fun App() {
    var currentScreen         by remember { mutableStateOf(Screen.DASHBOARD) }
    var dashboardRefreshKey   by remember { mutableStateOf(0) }
    var focusPreloadTask by remember { mutableStateOf<Task?>(null) }
    var overlayVisible   by remember { mutableStateOf(false) }
    var overlayAppName   by remember { mutableStateOf("") }
    var showOnboarding      by remember { mutableStateOf(false) }
    var showGlobalPinSetup  by remember { mutableStateOf(false) }
    var showAndroidPromo    by remember { mutableStateOf(false) }
    var showEdgeExtensionPromo by remember { mutableStateOf(false) }
    val showReviewPrompt    by ReviewPromptService.shouldShow.collectAsState()
    var showTelemetryConsent     by remember { mutableStateOf(false) }
    var showRegistryOrphanDialog by remember { mutableStateOf(false) }
    var sidebarCollapsed    by remember { mutableStateOf(false) }
    // Default false = show button until confirmed elevated (safe default per user requirement).
    var isAdmin             by remember { mutableStateOf(false) }
    // Admin-requiring features that are currently active — drives urgent button styling.
    var activeAdminFeatures by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope               = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        LocalizationManager.loadSavedLanguage()
        withContext(Dispatchers.IO) {
            when (Database.getSetting("theme_mode")) {
                "light" -> applyLightTheme()
                else    -> applyDarkTheme()
            }
            // Restore sidebar collapsed state from last session
            sidebarCollapsed = Database.getSetting("sidebar_collapsed") == "true"
            // Check elevation — default is false (show button); only hide if confirmed elevated
            isAdmin = isRunningAsAdmin()
        }
        AppBlocker.onOverlayShow = { appName ->
            overlayAppName = appName
            overlayVisible = true
        }
        AppBlocker.onOverlayHide = {
            overlayVisible = false
        }
        val launchData = withContext(Dispatchers.IO) {
            val fl = Database.getSetting("onboarding_complete") != "true"

            val openCount = (Database.getSetting("app_open_count")?.toIntOrNull() ?: 0) + 1
            Database.setSetting("app_open_count", openCount.toString())

            // PIN prompt: open 3+ only — user has had a session to explore before being asked to secure.
            val pn = !GlobalPin.isSet() && !GlobalPin.isDeclined() && openCount >= 3

            // 30-day cooldown: store last-shown date instead of a permanent boolean.
            val lastShownDateStr = Database.getSetting("android_promo_shown_date")
            val daysSinceShown: Long = if (lastShownDateStr != null) {
                try {
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(lastShownDateStr),
                        java.time.LocalDate.now()
                    )
                } catch (_: Exception) { 31L }
            } else 31L

            // Version-update trigger: show once per new app version.
            val lastPromoVersion = Database.getSetting("android_promo_last_version")
            val isNewVersion = lastPromoVersion != APP_VERSION

            // Show at open 5+: cooldown elapsed OR new version — open count gate applies in both
            // cases so a fresh install or version update never fires this before open 5.
            val showAndroid = !fl && openCount >= 5 && (daysSinceShown >= 30 || isNewVersion)
            // Show telemetry consent on the 2nd launch (after onboarding is done)
            // if the user has never been asked (null = never set, as opposed to "true"/"false").
            val showConsent = !fl
                && Database.getSetting("crash_reports_enabled") == null
            // Show the Edge extension exactly on the 20th completed app open.
            // Dismissal is permanent so this is never shown again on this install.
            val showEdgeExtension = !fl
                && openCount == 20
                && Database.getSetting(EDGE_EXTENSION_PROMO_DISMISSED) != "true"

            if (showAndroid) {
                Database.setSetting("android_promo_shown_date", java.time.LocalDate.now().toString())
                Database.setSetting("android_promo_last_version", APP_VERSION)
            }

            listOf(fl, pn, showAndroid, showConsent, showEdgeExtension)
        }
        val firstLaunch  = launchData[0]
        val pinNeeded    = launchData[1]
        val androidPromo = launchData[2]
        val needsConsent = launchData[3]
        val edgeExtensionPromo = launchData[4]

        if (firstLaunch) showOnboarding = true
        if (pinNeeded && !firstLaunch) showGlobalPinSetup = true
        if (androidPromo) showAndroidPromo = true
        if (needsConsent) showTelemetryConsent = true
        if (edgeExtensionPromo) showEdgeExtensionPromo = true
    }

    // ── Registry orphan check ─────────────────────────────────────────────────
    // After services have fully settled (4 s), check whether DisableTaskMgr is
    // stuck in HKCU when no enforcement session is active.  Two-pass detection
    // inside detectOrphanedKeys() means we only reach here if (a) the key is
    // confirmed == 1 AND (b) a fresh disable() pass still couldn't clear it —
    // virtually eliminating false positives.  Runs on IO dispatcher (JNA blocks).
    LaunchedEffect(Unit) {
        delay(4_000L)
        val orphaned = withContext(Dispatchers.IO) {
            val nuclearOn = try { NuclearMode.isActive } catch (_: Throwable) { true }
            val kioskOn   = try { FocusLauncherService.isActive.value } catch (_: Throwable) { true }
            if (nuclearOn || kioskOn) false
            else RegistryLockdown.detectOrphanedKeys()
        }
        if (orphaned) showRegistryOrphanDialog = true
    }

    // ── Poll admin-requiring services every 3 s ───────────────────────────────
    // Drives the urgent/subtle styling of the Restart-as-Admin button.
    // Only runs when the process is NOT already elevated.
    LaunchedEffect(isAdmin) {
        if (isAdmin) return@LaunchedEffect
        while (true) {
            val features = withContext(Dispatchers.IO) {
                buildList {
                    if (NuclearMode.isActive) add("Nuclear Mode")
                    try { if (VpnBlocker.isEnabled) add("VPN Shield") } catch (_: Throwable) {}
                    try { if (NetworkBlocker.activeRuleCount() > 0) add("Network Cutoff Rules") } catch (_: Throwable) {}
                    try { if (HostsBlocker.getBlockedDomains().isNotEmpty()) add("Website Blocking") } catch (_: Throwable) {}
                }
            }
            activeAdminFeatures = features
            delay(3_000L)
        }
    }

    // During any launcher session (kiosk active OR break active), the fullscreen
    // overlay covers the UI. ThemeToggleButton is declared AFTER FocusLauncherOverlay
    // in the Box, giving it higher hit-test priority in Compose. Hide it completely
    // while the launcher is running so no UI element can receive clicks above the overlay.
    val launcherActive by FocusLauncherService.isActive.collectAsState()

    FocusFlowTheme {
        val sessionState by FocusSessionService.state.collectAsState()
        val navigate: (Screen) -> Unit = { dest ->
            if (dest == Screen.DASHBOARD) dashboardRefreshKey++
            currentScreen = dest
        }
        CompositionLocalProvider(LocalNavigate provides navigate) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Surface)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                    // Ctrl+H works even during an active session (read-only help)
                    if (event.key == Key.H) { navigate(Screen.HOW_TO_USE); return@onPreviewKeyEvent true }
                    if (sessionState.isActive) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.One   -> { navigate(Screen.DASHBOARD);  true }
                        Key.Two   -> { navigate(Screen.TASKS);      true }
                        Key.Three -> { navigate(Screen.FOCUS);      true }
                        Key.Four  -> { navigate(Screen.BLOCK_APPS); true }
                        Key.Five  -> { navigate(Screen.STATS);      true }
                        Key.Comma -> { navigate(Screen.SETTINGS);   true }
                        else      -> false
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FocusLauncherBreakBanner()
                OsBanner()

                Row(modifier = Modifier.weight(1f)) {
                    SideNav(
                        current           = currentScreen,
                        onNavigate        = navigate,
                        collapsed         = sidebarCollapsed,
                        onToggleCollapse  = {
                            sidebarCollapsed = !sidebarCollapsed
                            val newValue = sidebarCollapsed
                            scope.launch(Dispatchers.IO) {
                                Database.setSetting("sidebar_collapsed", if (newValue) "true" else "false")
                            }
                        }
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                fadeIn() + slideInHorizontally { it / 8 } togetherWith
                                fadeOut() + slideOutHorizontally { -it / 8 }
                            }
                        ) { screen ->
                            when (screen) {
                                Screen.DASHBOARD -> DashboardScreen(
                                    refreshKey = dashboardRefreshKey,
                                    onStartFocus = { task ->
                                        focusPreloadTask = task
                                        currentScreen = Screen.FOCUS
                                    },
                                    onNavigateTasks = { currentScreen = Screen.TASKS }
                                )
                                Screen.TASKS -> TasksScreen(
                                    onStartFocus = { task ->
                                        focusPreloadTask = task
                                        currentScreen = Screen.FOCUS
                                    }
                                )
                                Screen.FOCUS           -> FocusScreen(preloadTask = focusPreloadTask)
                                Screen.FOCUS_LAUNCHER  -> FocusLauncherScreen()
                                Screen.BLOCK_APPS      -> AppBlockerScreen(
                                    onNavigateToBlockDefense = { currentScreen = Screen.BLOCK_DEFENSE }
                                )
                                Screen.STATS          -> StatsScreen()
                                Screen.NOTES          -> DailyNotesScreen()
                                Screen.HABITS         -> HabitsScreen()
                                Screen.REPORTS        -> ReportsScreen()
                                Screen.PROFILE        -> ProfileScreen()
                                Screen.SETTINGS       -> SettingsScreen()
                                Screen.ACTIVE         -> ActiveScreen(onNavigate = { currentScreen = it })
                                Screen.KEYWORD_BLOCKER -> KeywordBlockerScreen()
                                Screen.BLOCK_DEFENSE  -> BlockDefenseScreen(onNavigateToVpn = { currentScreen = Screen.VPN_NETWORK }, onNavigateToAppBlocker = { currentScreen = Screen.BLOCK_APPS })
                                Screen.NUCLEAR_MODE      -> NuclearModeScreen()
                                Screen.STANDALONE_BLOCK  -> StandaloneBlockScreen()
                                Screen.VPN_NETWORK    -> VpnNetworkScreen()
                                Screen.HOW_TO_USE     -> HowToUseScreen()
                                Screen.CHANGELOG      -> ChangelogScreen()
                                Screen.WINDOWS_SETUP  -> WindowsSetupScreen()
                                Screen.CONTACT        -> ContactScreen()
                            }
                        }
                    }
                }
            }

            FocusLauncherOverlay()

            BlockOverlay(
                visible   = overlayVisible,
                appName   = overlayAppName,
                onDismiss = { AppBlocker.hideOverlay() }
            )

            // Hidden during any launcher session — the overlay must be fully impenetrable.
            // ThemeToggleButton is declared after FocusLauncherOverlay in this Box, which
            // gives it higher Compose hit-test priority; hiding it prevents clicks from
            // leaking through the overlay to the theme toggle in the top-right corner.
            if (!launcherActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 14.dp)
                ) {
                    if (!isAdmin && currentScreen in ADMIN_BUTTON_SCREENS) {
                        RestartAsAdminButton(
                            activeAdminFeatures = activeAdminFeatures
                        )
                    }
                    ThemeToggleButton()
                }
            }
        }

        if (showOnboarding) {
            OnboardingDialog(onDismiss = {
                showOnboarding = false
                scope.launch(Dispatchers.IO) {
                    Database.setSetting("onboarding_complete", "true")
                }
                // PIN setup intentionally deferred — shown on the 2nd launch
                // so the user finishes onboarding before being asked about it.
            })
        }

        if (showGlobalPinSetup) {
            GlobalPinSetupDialog(onDismiss = { showGlobalPinSetup = false })
        }

        if (showAndroidPromo) {
            AndroidPromoDialog(onDismiss = { showAndroidPromo = false })
        }

        if (showEdgeExtensionPromo) {
            EdgeExtensionPromoDialog(
                onInstall = {
                    openEdgeExtensionStore()
                    showEdgeExtensionPromo = false
                    scope.launch(Dispatchers.IO) {
                        Database.setSetting(EDGE_EXTENSION_PROMO_DISMISSED, "true")
                    }
                },
                onDismiss = {
                    showEdgeExtensionPromo = false
                    scope.launch(Dispatchers.IO) {
                        Database.setSetting(EDGE_EXTENSION_PROMO_DISMISSED, "true")
                    }
                }
            )
        }

        if (showReviewPrompt) {
            ReviewPromptDialog()
        }

        if (showTelemetryConsent) {
            TelemetryConsentDialog(
                onAllow = {
                    showTelemetryConsent = false
                    scope.launch(Dispatchers.IO) {
                        Database.setSetting("crash_reports_enabled", "true")
                    }
                },
                onDecline = {
                    showTelemetryConsent = false
                    scope.launch(Dispatchers.IO) {
                        Database.setSetting("crash_reports_enabled", "false")
                    }
                }
            )
        }

        if (showRegistryOrphanDialog) {
            AlertDialog(
                onDismissRequest = { showRegistryOrphanDialog = false },
                containerColor   = Surface2,
                titleContentColor = OnSurface,
                textContentColor  = OnSurface2,
                title = {
                    Text(
                        "Task Manager May Be Disabled",
                        style = MaterialTheme.typography.titleMedium,
                        color = Warning
                    )
                },
                text = {
                    Text(
                        "FocusFlow detected that Windows Task Manager appears disabled " +
                        "in the registry, but no blocking session is currently active.\n\n" +
                        "This is usually caused by a previous session that closed " +
                        "unexpectedly before it could restore your settings.\n\n" +
                        "Restarting FocusFlow as Administrator will allow it to " +
                        "remove the stuck registry entry and restore normal access.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRegistryOrphanDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val exe = ProcessHandle.current().info().command().orElse(null)
                                    if (exe != null) {
                                        val escaped = exe.replace("'", "''")
                                        ProcessBuilder(
                                            "powershell", "-WindowStyle", "Hidden",
                                            "-Command",
                                            "Start-Process -FilePath '$escaped' -Verb RunAs"
                                        ).start()
                                        exitProcess(0)
                                    }
                                } catch (_: Throwable) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) {
                        Text("Restart as Admin", color = Surface)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRegistryOrphanDialog = false }) {
                        Text("Dismiss", color = OnSurface2)
                    }
                }
            )
        }
        } // CompositionLocalProvider
    }
}

/**
 * Floating "Restart as Administrator" button.
 *
 * Two visual states driven by [activeAdminFeatures]:
 *  • Empty  → subtle pill (Surface3 bg, muted text) — easy to ignore
 *  • Non-empty → urgent red pill with pulsing alpha, Warning icon, and a
 *    tooltip listing every feature that currently needs admin rights.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RestartAsAdminButton(
    activeAdminFeatures: List<String>,
    modifier: Modifier = Modifier
) {
    val scope  = rememberCoroutineScope()
    val urgent = activeAdminFeatures.isNotEmpty()

    // Smooth colour transition between subtle ↔ urgent
    val bgColor by animateColorAsState(
        targetValue   = if (urgent) Error else Purple80.copy(alpha = 0.18f),
        animationSpec = tween(400),
        label         = "adminBtnBg"
    )
    val contentColor by animateColorAsState(
        targetValue   = if (urgent) androidx.compose.ui.graphics.Color.White else OnSurface,
        animationSpec = tween(400),
        label         = "adminBtnContent"
    )

    // Continuous pulse when urgent — always composed, applied conditionally
    val pulse = rememberInfiniteTransition(label = "adminBtnPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue  = 0.65f,
        targetValue   = 1.00f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "adminBtnAlpha"
    )

    val relaunch: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            try {
                val exe = ProcessHandle.current().info().command().orElse(null)
                if (exe != null) {
                    val escaped = exe.replace("'", "''")
                    ProcessBuilder(
                        "powershell", "-WindowStyle", "Hidden",
                        "-Command",
                        "Start-Process -FilePath '$escaped' -Verb RunAs"
                    ).start()
                    exitProcess(0)
                }
            } catch (_: Throwable) {}
        }
    }

    TooltipArea(
        tooltip = {
            Column(
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .background(Surface3, RoundedCornerShape(8.dp))
                    .border(1.dp, OnSurface2.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (urgent) {
                    Text(
                        "⚠  Admin required for:",
                        color      = Error,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    activeAdminFeatures.forEach { feature ->
                        Text("  •  $feature", color = OnSurface, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Click to restart with Administrator privileges.",
                        color    = OnSurface2,
                        fontSize = 10.sp
                    )
                } else {
                    Text(
                        "Restart as Administrator",
                        color      = OnSurface2,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        delayMillis      = 300,
        tooltipPlacement = TooltipPlacement.CursorPoint(
            alignment = Alignment.TopEnd,
            offset    = DpOffset((-8).dp, (-12).dp)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .alpha(if (urgent) pulseAlpha else 1f)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .clickable { relaunch() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector        = if (urgent) Icons.Default.Warning
                                     else Icons.Default.AdminPanelSettings,
                contentDescription = "Restart as Administrator",
                tint               = contentColor,
                modifier           = Modifier.size(if (urgent) 14.dp else 15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (urgent) "Run as Admin!" else "Run as Admin",
                style      = MaterialTheme.typography.bodySmall,
                color      = contentColor,
                fontSize   = 11.sp,
                fontWeight = if (urgent) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ThemeToggleButton(modifier: Modifier = Modifier) {
    val dark = isDarkTheme
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            if (dark) {
                applyLightTheme()
                scope.launch(Dispatchers.IO) { Database.setSetting("theme_mode", "light") }
            } else {
                applyDarkTheme()
                scope.launch(Dispatchers.IO) { Database.setSetting("theme_mode", "dark") }
            }
        },
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Surface3)
    ) {
        Icon(
            imageVector = if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
            contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode",
            tint = if (dark) Purple60 else Purple80,
            modifier = Modifier.size(18.dp)
        )
    }
}
