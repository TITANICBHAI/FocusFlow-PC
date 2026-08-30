package com.focusflow.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import com.focusflow.ui.components.FfVerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusflow.ui.theme.*

private data class HowToSection(
    val icon: ImageVector,
    val title: String,
    val steps: List<String>
)

private val HOW_TO_SECTIONS = listOf(
    HowToSection(
        icon  = Icons.AutoMirrored.Filled.TrendingUp,
        title = "Getting Started",
        steps = listOf(
            "FocusFlow runs in the background and monitors your apps at all times.",
            "On first launch, grant any permission prompts — these are needed to kill blocked processes.",
            "Start by adding tasks in the Tasks tab so FocusFlow knows what you're working on.",
            "Head to Block Apps and add the apps that distract you most. Chrome, Discord, and social media apps are common choices.",
            "You're ready to focus. Hit Start Focus on the Dashboard or Focus tab."
        )
    ),
    HowToSection(
        icon  = Icons.Default.CheckCircle,
        title = "Task Management",
        steps = listOf(
            "Create tasks with a title, duration, and optional scheduled date.",
            "Mark tasks as recurring (daily, weekly) to auto-generate them each period.",
            "Set priority (high / medium / low) to sort what matters most today.",
            "Tap Start Focus next to any task to launch a focused session directly for it.",
            "The Dashboard shows today's tasks and completion progress at a glance."
        )
    ),
    HowToSection(
        icon  = Icons.Default.Timer,
        title = "Focus Sessions & Pomodoro",
        steps = listOf(
            "Start a session from the Focus tab. Choose a task or type a custom goal.",
            "Enable Pomodoro mode to work in timed intervals with automatic break reminders.",
            "During a session, all apps on your block list are killed if they appear.",
            "Set it in Settings → PIN Management → Session PIN to prevent yourself from ending sessions early.",
            "Session notes let you capture thoughts or blockers mid-session.",
            "When done, your actual vs planned time is logged automatically."
        )
    ),
    HowToSection(
        icon  = Icons.Default.Block,
        title = "App Blocking",
        steps = listOf(
            "Go to Block Apps and add each app you want to restrict.",
            "Apps are blocked only during focus sessions by default.",
            "Enable Always-On Enforcement in Block Defense to block apps 24/7.",
            "Use Standalone Block (Focus tab) to block apps for a set duration without starting a full session.",
            "Block Schedules let you define recurring time windows when apps are automatically blocked."
        )
    ),
    HowToSection(
        icon  = Icons.Default.HourglassFull,
        title = "Daily Allowances",
        steps = listOf(
            "Daily Allowances let you use an app for a limited time each day instead of blocking it outright.",
            "Add an allowance in Focus → Daily Allowance or in Settings.",
            "Once an app hits its daily limit, it is blocked for the rest of the day.",
            "Allowances reset at midnight.",
            "Combine allowances with schedules for precise control over when and how long you use an app."
        )
    ),
    HowToSection(
        icon  = Icons.Default.TextFields,
        title = "Keyword Blocking: How It Works & Limits",
        steps = listOf(
            "The desktop Keyword Blocker watches the foreground browser window title for terms you define.",
            "Go to Keyword Blocker in the sidebar and add words or phrases (e.g. 'trending', 'casino').",
            "Use Quick Presets to instantly add curated sets: Doomscroll Bait, Shopping, NSFW, etc.",
            "Toggle the blocker on/off without losing your keyword list.",
            "Important: when a regular keyword matches, FocusFlow may close the whole browser process. This can close every tab and lose unsaved work.",
            "If you want to keep the browser open, use Block Defense → VPN & Network Shield → Network Cutoff Rules → Keyword. Network Cutoff blocks the matching app's internet connection without killing the browser.",
            "Native matching only sees foreground window titles. It cannot reliably read the current URL, page description, or page content.",
            "For URL-level browser protection, install the official FocusFlow Edge extension from the Keyword Blocker screen.",
            "Network Cutoff also needs administrator access and can be affected by VPNs, Secure DNS/DoH, cached connections, and other Windows networking limits."
        )
    ),
    HowToSection(
        icon  = Icons.Default.Shield,
        title = "Block Defense & Enforcement",
        steps = listOf(
            "Block Defense (sidebar) groups all enforcement layers in one place.",
            "Always-On Enforcement: kills any blocked app the moment it opens, session or not.",
            "Sound Aversion: plays an unpleasant tone when a blocked app launches — conditions avoidance over time.",
            "Overlay Message: customize the text shown when a blocked app is intercepted.",
            "Temptation Log: every blocked-app attempt is silently recorded and visible in Stats.",
            "Session PIN: set it at Settings → PIN Management → Session PIN. Ending a focus session then requires entering it — removing the 'just quit' temptation."
        )
    ),
    HowToSection(
        icon  = Icons.Default.Keyboard,
        title = "Keyboard Shortcuts",
        steps = listOf(
            "Ctrl + 1 — Dashboard (works when no session is active)",
            "Ctrl + 2 — Tasks",
            "Ctrl + 3 — Focus",
            "Ctrl + 4 — Block Apps",
            "Ctrl + 5 — Stats",
            "Ctrl + , — Settings",
            "Ctrl + H — How to Use (works even during an active session)",
            "Ctrl + N — New task (while on the Tasks screen)",
            "Ctrl + F — Search / filter tasks (while on the Tasks screen)",
            "Ctrl + P — Pause or resume the current focus session (while on the Focus screen)",
            "Ctrl + Enter — Start or stop a focus session (while on the Focus screen)",
            "Navigation shortcuts are disabled during an active session to keep you focused."
        )
    )
)

@Composable
fun HowToUseScreen() {
    var openSections by remember { mutableStateOf(setOf<String>()) }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("How to Use FocusFlow", style = MaterialTheme.typography.headlineLarge, color = OnSurface)
            Text(
                "Step-by-step guides for every feature. Tap a section to expand it.",
                style = MaterialTheme.typography.bodyMedium, color = OnSurface2
            )
            Spacer(Modifier.height(4.dp))

            HOW_TO_SECTIONS.forEach { section ->
                val isOpen = openSections.contains(section.title)

                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface2)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openSections = if (isOpen)
                                    openSections - section.title
                                else
                                    openSections + section.title
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(Purple80.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(section.icon, contentDescription = null, tint = Purple80, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            section.title,
                            color = OnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = OnSurface2,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = isOpen,
                        enter   = expandVertically(),
                        exit    = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(start = 72.dp, end = 20.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            section.steps.forEachIndexed { idx, step ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        "${idx + 1}",
                                        color = Purple80,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(18.dp).padding(top = 2.dp)
                                    )
                                    Text(step, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
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
}

