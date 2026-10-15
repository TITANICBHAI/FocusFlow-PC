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
import com.focusflow.enforcement.ElevationManager
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.RegistryLockdown
import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.WindowsStartupManager
import com.focusflow.enforcement.isWindows
import com.focusflow.enforcement.VpnBlocker
import com.focusflow.services.HostsBlocker
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.FocusSessionService
import com.focusflow.services.RecoveryNotice
import com.focusflow.services.RecoveryNoticeService
import com.focusflow.ui.components.AndroidPromoDialog
import com.focusflow.ui.components.BlockOverlay
import com.focusflow.ui.components.EdgeExtensionPromoDialog
import com.focusflow.ui.components.openEdgeExtensionStore
import com.focusflow.ui.components.GlobalPinSetupDialog
import com.focusflow.ui.components.PostPinRecommendationsDialog
import com.focusflow.ui.components.openUrl
import com.focusflow.ui.components.OsBanner
import com.focusflow.ui.components.OnboardingDialog
import com.focusflow.services.ReviewPromptService
import com.focusflow.ui.components.ReviewPromptDialog
import com.focusflow.ui.components.SideNav
import com.focusflow.ui.components.TelemetryConsentDialog
import com.focusflow.ui.components.WindowsStartupPromptDialog
import com.focusflow.services.FocusLauncherService
import com.focusflow.services.GlobalPin
import com.focusflow.services.CrashReporter
import com.focusflow.services.TelemetryConsent
import com.focusflow.services.TelemetryConsentState
import com.focusflow.services.ElevationRelaunchService
import com.focusflow.services.ElevationRelaunchStatus
import com.focusflow.ui.screens.*
import com.focusflow.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.*
import com.focusflow.ui.LocalNavigate

private const val EDGE_EXTENSION_PROMO_DISMISSED = "edge_extension_promo_dismissed"
private const val POST_PIN_RECOMMENDATIONS_SHOWN = "post_pin_recommendations_shown"
private const val WINDOWS_STARTUP_PROMPT_PENDING = "windows_startup_prompt_pending"
private const val WINDOWS_STARTUP_PROMPT_SHOWN = "windows_startup_prompt_shown"
private const val DIRECT_RELEASES_URL =
    "https://github.com/TITANICBHAI/FocusFlow-PC/releases"

internal fun nextAppOpenCount(read: Database.SettingRead): Int? =
    when (read) {
        is Database.SettingRead.Present ->
            read.value.toIntOrNull()?.takeIf { it >= 0 && it < Int.MAX_VALUE }?.plus(1)
        Database.SettingRead.Absent -> 1
        is Database.SettingRead.Unreadable -> null
    }

private fun readAppSetting(key: String): Database.SettingRead =
    Database.readSetting(key).also { result ->
        if (result is Database.SettingRead.Unreadable) {
            EnforcementLog.warn("AppSettings", "Could not read setting '$key'", result.cause)
        }
    }

private fun readAppBoolean(
    key: String,
    read: Database.SettingRead,
    absentValue: Boolean
): Boolean? =
    when (read) {
        is Database.SettingRead.Present -> when (read.value) {
            "true" -> true
            "false" -> false
            else -> {
                EnforcementLog.warn("AppSettings", "Boolean setting '$key' is malformed")
                null
            }
        }
        Database.SettingRead.Absent -> absentValue
        is Database.SettingRead.Unreadable -> null
    }

private fun writeAppSetting(key: String, value: String): Boolean =
    when (val result = Database.writeSetting(key, value)) {
        Database.SettingWrite.Ok -> true
        is Database.SettingWrite.Failed -> {
            EnforcementLog.warn("AppSettings", "Could not save setting '$key'", result.cause)
            false
        }
    }

private fun writeAppSettings(values: Map<String, String>): Boolean =
    when (val result = Database.writeSettings(values)) {
        Database.SettingWrite.Ok -> true
        is Database.SettingWrite.Failed -> {
            EnforcementLog.warn("AppSettings", "Could not save grouped app settings", result.cause)
            false
        }
    }

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
    var openBackupRecoveryInSettings by remember { mutableStateOf(false) }
    var dashboardRefreshKey   by remember { mutableStateOf(0) }
    var focusPreloadTask by remember { mutableStateOf<Task?>(null) }
    var blockerInitialTab by remember { mutableStateOf(AppBlockerTabs.ALWAYS_BLOCK) }
    var blockerTabResetKey by remember { mutableStateOf(0) }
    var overlayVisible   by remember { mutableStateOf(false) }
    var overlayAppName   by remember { mutableStateOf("") }
    var showOnboarding      by remember { mutableStateOf(false) }
    var showGlobalPinSetup  by remember { mutableStateOf(false) }
    var showWindowsStartupPrompt by remember { mutableStateOf(false) }
    var pendingWindowsStartupPrompt by remember { mutableStateOf(false) }
    var showAndroidPromo    by remember { mutableStateOf(false) }
    var showEdgeExtensionPromo by remember { mutableStateOf(false) }
    var showPostPinRecommendations by remember { mutableStateOf(false) }
    var postPinRecommendationsShown by remember { mutableStateOf(false) }
    val showReviewPrompt    by ReviewPromptService.shouldShow.collectAsState()
    var showTelemetryConsent     by remember { mutableStateOf(false) }
    var telemetryConsentError by remember { mutableStateOf<String?>(null) }
    var telemetryConsentSaving by remember { mutableStateOf(false) }
    var showRegistryOrphanDialog by remember { mutableStateOf(false) }
    var startupNotice by remember { mutableStateOf<RecoveryNotice?>(null) }
    var appLaunchDialogsResolved by remember { mutableStateOf(false) }
    var sidebarCollapsed    by remember { mutableStateOf(false) }
    val isAdmin = remember { ElevationManager.isElevated }
    // Admin-requiring features that are currently active — drives urgent button styling.
    var activeAdminFeatures by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope               = rememberCoroutineScope()

    fun saveTelemetryConsent(granted: Boolean) {
        if (telemetryConsentSaving) return
        telemetryConsentSaving = true
        telemetryConsentError = null
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                TelemetryConsent.save(
                    if (granted) TelemetryConsentState.GRANTED else TelemetryConsentState.DENIED
                )
            }
            telemetryConsentSaving = false
            if (saved) {
                showTelemetryConsent = false
            } else {
                telemetryConsentError = "Could not save your choice. Crash reports remain disabled."
            }
        }
    }

    LaunchedEffect(Unit) {
        LocalizationManager.loadSavedLanguage()
        withContext(Dispatchers.IO) {
            when (val setting = readAppSetting("theme_mode")) {
                is Database.SettingRead.Present ->
                    if (setting.value == "light") applyLightTheme() else applyDarkTheme()
                Database.SettingRead.Absent -> applyDarkTheme()
                is Database.SettingRead.Unreadable -> Unit
            }
            // Restore sidebar collapsed state from last session
            readAppBoolean("sidebar_collapsed", readAppSetting("sidebar_collapsed"), false)
                ?.let { sidebarCollapsed = it }
        }
        AppBlocker.onOverlayShow = { appName ->
            overlayAppName = appName
            overlayVisible = true
        }
        AppBlocker.onOverlayHide = {
            overlayVisible = false
        }
        val launchData = withContext(Dispatchers.IO) {
            val onboardingRead = readAppSetting("onboarding_complete")
            val onboardingComplete = readAppBoolean("onboarding_complete", onboardingRead, false)
            val fl = onboardingComplete?.not() ?: false

            val openCountRead = readAppSetting("app_open_count")
            val openCount = nextAppOpenCount(openCountRead)
            if (openCount == null && openCountRead is Database.SettingRead.Present) {
                EnforcementLog.warn("AppSettings", "App open count is malformed; skipping launch-based prompts")
            }
            if (openCount != null) writeAppSetting("app_open_count", openCount.toString())

            val startupEnabled = isWindows && WindowsStartupManager.isEnabled()
            val startupShownRead = readAppSetting(WINDOWS_STARTUP_PROMPT_SHOWN)
            val startupPendingRead = readAppSetting(WINDOWS_STARTUP_PROMPT_PENDING)
            val startupPromptShown =
                readAppBoolean(WINDOWS_STARTUP_PROMPT_SHOWN, startupShownRead, false)
            val startupPromptPending =
                readAppBoolean(WINDOWS_STARTUP_PROMPT_PENDING, startupPendingRead, false)
            val shouldOfferStartupPrompt = isWindows
                && !fl
                && !startupEnabled
                && startupPromptShown == false
                && startupPromptPending != null
                && openCount != null
                && (openCount == 3 || startupPromptPending == true)

            if (startupEnabled || startupPromptShown == true) {
                if (startupPromptPending != null) {
                    writeAppSetting(WINDOWS_STARTUP_PROMPT_PENDING, "false")
                }
            } else if (shouldOfferStartupPrompt) {
                // Keep the reminder queued if another launch dialog is still open.
                if (startupPromptPending != null) {
                    writeAppSetting(WINDOWS_STARTUP_PROMPT_PENDING, "true")
                }
            }

            // PIN prompt: show on the second app open, after the first-run onboarding.
            val pn = openCount != null && !GlobalPin.isSet() && !GlobalPin.isDeclined() && openCount >= 2

            // 30-day cooldown: store last-shown date instead of a permanent boolean.
            val lastShownDateRead = readAppSetting("android_promo_shown_date")
            val daysSinceShown: Long? = when (lastShownDateRead) {
                is Database.SettingRead.Present -> runCatching {
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(lastShownDateRead.value),
                        java.time.LocalDate.now()
                    )
                }.getOrElse {
                    EnforcementLog.warn("AppSettings", "Android promo date is malformed; skipping promo", it)
                    null
                }
                Database.SettingRead.Absent -> 31L
                is Database.SettingRead.Unreadable -> null
            }

            // Version-update trigger: show once per new app version.
            val lastPromoVersionRead = readAppSetting("android_promo_last_version")
            val isNewVersion = when (lastPromoVersionRead) {
                is Database.SettingRead.Present -> lastPromoVersionRead.value != CrashReporter.APP_VERSION
                Database.SettingRead.Absent -> true
                is Database.SettingRead.Unreadable -> false
            }

            // Show at open 5+: cooldown elapsed OR new version — open count gate applies in both
            // cases so a fresh install or version update never fires this before open 5.
            val showAndroid = !fl && openCount != null && openCount >= 5 &&
                ((daysSinceShown ?: Long.MIN_VALUE) >= 30 || isNewVersion)
            // Migrate the legacy DB preference once. The file-backed consent remains
            // authoritative and reporting stays disabled unless it explicitly says GRANTED.
            val legacyConsent = if (Database.isReady) {
                Database.readSettingForConsentMigration(TelemetryConsent.LEGACY_SETTING_KEY)
            } else {
                Database.SettingReadResult.NotReady
            }
            TelemetryConsent.migrateLegacyOnce(legacyConsent)
            val consent = TelemetryConsent.state()
            val showConsent = !fl && (
                consent == TelemetryConsentState.UNKNOWN ||
                    consent == TelemetryConsentState.UNREADABLE
                )
            // Show the Edge extension exactly on the 20th completed app open.
            // Dismissal is permanent so this is never shown again on this install.
            val showEdgeExtension = !fl
                && openCount == 20
                && readAppBoolean(
                    EDGE_EXTENSION_PROMO_DISMISSED,
                    readAppSetting(EDGE_EXTENSION_PROMO_DISMISSED),
                    false
                ) == false
            val recommendationsShown = readAppBoolean(
                POST_PIN_RECOMMENDATIONS_SHOWN,
                readAppSetting(POST_PIN_RECOMMENDATIONS_SHOWN),
                false
            ) ?: true

            if (showAndroid) {
                writeAppSettings(
                    linkedMapOf(
                        "android_promo_shown_date" to java.time.LocalDate.now().toString(),
                        "android_promo_last_version" to CrashReporter.APP_VERSION
                    )
                )
            }

            listOf(
                fl, pn, showAndroid, showConsent, showEdgeExtension,
                recommendationsShown, shouldOfferStartupPrompt
            )
        }
        val firstLaunch  = launchData[0]
        val pinNeeded    = launchData[1]
        val androidPromo = launchData[2]
        val needsConsent = launchData[3]
        val edgeExtensionPromo = launchData[4]
        postPinRecommendationsShown = launchData[5]
        pendingWindowsStartupPrompt = launchData[6]

        if (firstLaunch) showOnboarding = true
        if (pinNeeded && !firstLaunch) showGlobalPinSetup = true
        if (androidPromo) showAndroidPromo = true
        if (needsConsent) showTelemetryConsent = true
        if (edgeExtensionPromo) showEdgeExtensionPromo = true
        appLaunchDialogsResolved = true
    }

    LaunchedEffect(Unit) {
        startupNotice = withContext(Dispatchers.IO) {
            RecoveryNoticeService.pendingNotice()
        }
    }

    // Remind on the third launch, but wait until startup/security/consent dialogs
    // are closed so two modal windows are never shown on top of each other.
    LaunchedEffect(
        pendingWindowsStartupPrompt,
        showWindowsStartupPrompt,
        showOnboarding,
        showGlobalPinSetup,
        showPostPinRecommendations,
        showAndroidPromo,
        showEdgeExtensionPromo,
        showTelemetryConsent,
        showRegistryOrphanDialog,
        showReviewPrompt
    ) {
        if (!pendingWindowsStartupPrompt || showWindowsStartupPrompt) return@LaunchedEffect

        // Allow the delayed registry-recovery check to surface first as well.
        delay(4_500L)
        val anotherDialogIsOpen = showOnboarding
            || showGlobalPinSetup
            || showPostPinRecommendations
            || showAndroidPromo
            || showEdgeExtensionPromo
            || showTelemetryConsent
            || showRegistryOrphanDialog
            || showReviewPrompt

        if (!anotherDialogIsOpen) {
            val stillDisabled = withContext(Dispatchers.IO) {
                !WindowsStartupManager.isEnabled()
            }
            withContext(Dispatchers.IO) {
                writeAppSettings(
                    linkedMapOf(
                        WINDOWS_STARTUP_PROMPT_PENDING to "false",
                        WINDOWS_STARTUP_PROMPT_SHOWN to "true"
                    )
                )
            }
            pendingWindowsStartupPrompt = false
            if (stillDisabled) showWindowsStartupPrompt = true
        }
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

    // ── Poll admin-requiring services every 5 s ───────────────────────────────
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
            delay(5_000L)
        }
    }

    FocusFlowTheme {
        val sessionState by FocusSessionService.state.collectAsState()
        val launcherSessionActive by FocusLauncherService.isActive.collectAsState()
        val nuclearModeActive by NuclearMode.activeState.collectAsState()
        val navigate: (Screen) -> Unit = { dest ->
            if (dest == Screen.DASHBOARD) dashboardRefreshKey++
            blockerInitialTab = AppBlockerTabs.ALWAYS_BLOCK
            if (dest == Screen.BLOCK_APPS) blockerTabResetKey++
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
                                writeAppSetting("sidebar_collapsed", newValue.toString())
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
                                Screen.FOCUS           -> FocusScreen(
                                    preloadTask = focusPreloadTask,
                                    onOpenAllowances = {
                                        blockerInitialTab = AppBlockerTabs.DAILY_ALLOWANCE
                                        currentScreen = Screen.BLOCK_APPS
                                    }
                                )
                                Screen.FOCUS_LAUNCHER  -> FocusLauncherScreen()
                                Screen.BLOCK_APPS      -> AppBlockerScreen(
                                    onNavigateToBlockDefense = { currentScreen = Screen.BLOCK_DEFENSE },
                                    initialTab = blockerInitialTab,
                                    tabResetKey = blockerTabResetKey,
                                    onInitialTabConsumed = {
                                        blockerInitialTab = AppBlockerTabs.ALWAYS_BLOCK
                                    }
                                )
                                Screen.STATS          -> StatsScreen()
                                Screen.NOTES          -> DailyNotesScreen()
                                Screen.HABITS         -> HabitsScreen()
                                Screen.REPORTS        -> ReportsScreen()
                                Screen.PROFILE        -> ProfileScreen()
                                Screen.SETTINGS       -> SettingsScreen(
                                    openBackupRecovery = openBackupRecoveryInSettings,
                                    onBackupRecoveryOpened = { openBackupRecoveryInSettings = false }
                                )
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

            BlockOverlay(
                visible   = overlayVisible,
                appName   = overlayAppName,
                onDismiss = { AppBlocker.hideOverlay() }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 14.dp)
            ) {
                if (!isAdmin && currentScreen in ADMIN_BUTTON_SCREENS) {
                    RestartAsAdminButton(
                        activeAdminFeatures = activeAdminFeatures,
                        sessionActive = sessionState.isActive || launcherSessionActive,
                        nuclearModeActive = nuclearModeActive
                    )
                }
                ThemeToggleButton()
            }
        }

        if (showOnboarding) {
            OnboardingDialog(onDismiss = {
                showOnboarding = false
                scope.launch {
                    val needsConsent = withContext(Dispatchers.IO) {
                        writeAppSetting("onboarding_complete", "true")
                        val consent = TelemetryConsent.state()
                        consent == TelemetryConsentState.UNKNOWN ||
                            consent == TelemetryConsentState.UNREADABLE
                    }
                    if (needsConsent) showTelemetryConsent = true
                }
                // PIN setup intentionally deferred — shown on the 2nd launch
                // so the user finishes onboarding before being asked about it.
            })
        }

        if (showGlobalPinSetup) {
            GlobalPinSetupDialog(
                onDismiss = { showGlobalPinSetup = false },
                onComplete = {
                    showGlobalPinSetup = false
                    if (!postPinRecommendationsShown) {
                        postPinRecommendationsShown = true
                        showPostPinRecommendations = true
                        scope.launch(Dispatchers.IO) {
                            writeAppSetting(POST_PIN_RECOMMENDATIONS_SHOWN, "true")
                        }
                    }
                }
            )
        }

        if (showPostPinRecommendations) {
            PostPinRecommendationsDialog(
                onOpenExtension = { openEdgeExtensionStore() },
                onOpenNetworkShield = { showPostPinRecommendations = false; navigate(Screen.VPN_NETWORK) },
                onOpenReleases = { openUrl(DIRECT_RELEASES_URL) },
                onDismiss = { showPostPinRecommendations = false }
            )
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
                        writeAppSetting(EDGE_EXTENSION_PROMO_DISMISSED, "true")
                    }
                },
                onDismiss = {
                    showEdgeExtensionPromo = false
                    scope.launch(Dispatchers.IO) {
                        writeAppSetting(EDGE_EXTENSION_PROMO_DISMISSED, "true")
                    }
                }
            )
        }

        if (showReviewPrompt) {
            ReviewPromptDialog()
        }

        if (showTelemetryConsent) {
            TelemetryConsentDialog(
                saving = telemetryConsentSaving,
                saveError = telemetryConsentError,
                onAllow = { saveTelemetryConsent(granted = true) },
                onDecline = { saveTelemetryConsent(granted = false) },
            )
        }

        if (showWindowsStartupPrompt) {
            WindowsStartupPromptDialog(
                onEnable = {
                    showWindowsStartupPrompt = false
                    scope.launch(Dispatchers.IO) {
                        WindowsStartupManager.enable()
                    }
                },
                onDismiss = { showWindowsStartupPrompt = false }
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
                            ElevationRelaunchService.request()
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

        val anotherDialogIsOpen =
            showOnboarding ||
                showGlobalPinSetup ||
                showPostPinRecommendations ||
                showAndroidPromo ||
                showEdgeExtensionPromo ||
                showTelemetryConsent ||
                showWindowsStartupPrompt ||
                showRegistryOrphanDialog ||
                showReviewPrompt
        startupNotice?.takeIf { appLaunchDialogsResolved && !anotherDialogIsOpen }?.let { notice ->
            fun acknowledgeNotice(openBackups: Boolean) {
                startupNotice = null
                if (openBackups) {
                    openBackupRecoveryInSettings = true
                    navigate(Screen.SETTINGS)
                }
                scope.launch(Dispatchers.IO) {
                    RecoveryNoticeService.acknowledge(notice)
                }
            }

            AlertDialog(
                onDismissRequest = { acknowledgeNotice(openBackups = false) },
                containerColor = Surface2,
                titleContentColor = OnSurface,
                textContentColor = OnSurface2,
                title = { Text(notice.title, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        notice.message,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { acknowledgeNotice(openBackups = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                    ) {
                        Text("Open Backup & Recovery")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { acknowledgeNotice(openBackups = false) }) {
                        Text("Continue")
                    }
                }
            )
        }
        ElevationRelaunchStatusDialog()
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
    sessionActive: Boolean,
    nuclearModeActive: Boolean,
    modifier: Modifier = Modifier
) {
    val urgent = activeAdminFeatures.isNotEmpty()
    val relaunchStatus by ElevationRelaunchService.status.collectAsState()
    val pending = relaunchStatus is ElevationRelaunchStatus.Pending
    val canRelaunch = ElevationRelaunchService.canRelaunch() && !pending

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

    val pulseAlpha = if (urgent) UrgentAdminPulseAlpha() else 1f

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
                        when {
                            pending -> "Waiting for the Windows permission prompt…"
                            sessionActive -> "End your Focus or Focus Launcher session first."
                            nuclearModeActive -> "Restart as Administrator; Nuclear Mode will resume."
                            else -> "Click to restart with Administrator privileges."
                        },
                        color    = OnSurface2,
                        fontSize = 10.sp
                    )
                } else {
                    Text(
                        when {
                            pending -> "Waiting for the Windows permission prompt…"
                            sessionActive -> "End your Focus or Focus Launcher session before restarting."
                            nuclearModeActive -> "Restart as Administrator; Nuclear Mode will resume."
                            else -> "Restart as Administrator"
                        },
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
                .clickable(enabled = canRelaunch) { ElevationRelaunchService.request() }
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
                when {
                    pending -> "Waiting…"
                    urgent -> "Run as Admin!"
                    else -> "Run as Admin"
                },
                style      = MaterialTheme.typography.bodySmall,
                color      = contentColor,
                fontSize   = 11.sp,
                fontWeight = if (urgent) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun UrgentAdminPulseAlpha(): Float {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            alpha.animateTo(0.65f, tween(350, easing = FastOutSlowInEasing))
            alpha.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
        }
    }
    return alpha.value
}

@Composable
private fun ElevationRelaunchStatusDialog() {
    val status by ElevationRelaunchService.status.collectAsState()
    val (title, message) = when (val current = status) {
        ElevationRelaunchStatus.Idle,
        ElevationRelaunchStatus.HandedOff -> return
        ElevationRelaunchStatus.Pending -> "Waiting for Windows" to
            "Waiting for the Windows permission prompt. FocusFlow will stay open until the elevated instance confirms startup."
        ElevationRelaunchStatus.UserDeclined -> "Permission declined" to
            "Windows permission was declined. FocusFlow is still running with its current permissions."
        is ElevationRelaunchStatus.Blocked -> "Restart unavailable" to current.reason
        is ElevationRelaunchStatus.Failed -> "Could not restart as Administrator" to current.reason
        is ElevationRelaunchStatus.NotSupported -> "Start FocusFlow as Administrator" to current.reason
    }
    val pending = status is ElevationRelaunchStatus.Pending

    AlertDialog(
        onDismissRequest = {
            if (!pending) ElevationRelaunchService.dismissStatus()
        },
        containerColor = Surface2,
        titleContentColor = OnSurface,
        textContentColor = OnSurface2,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (!pending) {
                TextButton(onClick = { ElevationRelaunchService.dismissStatus() }) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun ThemeToggleButton(modifier: Modifier = Modifier) {
    val dark = isDarkTheme
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            if (dark) {
                applyLightTheme()
                scope.launch(Dispatchers.IO) { writeAppSetting("theme_mode", "light") }
            } else {
                applyDarkTheme()
                scope.launch(Dispatchers.IO) { writeAppSetting("theme_mode", "dark") }
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
