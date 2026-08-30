package com.focusflow.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.services.FocusLauncherApp
import com.focusflow.services.FocusLauncherService
import com.focusflow.ui.theme.Error
import com.focusflow.ui.theme.OnSurface
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Purple80
import com.focusflow.ui.theme.Surface2
import com.focusflow.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun LauncherContent() {
    val breakActive by FocusLauncherService.breakActive.collectAsState()
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0C0B14))
    ) {
        if (breakActive) BreakScreen() else MainLauncherScreen()
    }
}

@Composable
private fun MainLauncherScreen() {
    val apps by FocusLauncherService.sessionApps.collectAsState()
    val hardLocked by FocusLauncherService.isHardLocked.collectAsState()
    val canBreak by FocusLauncherService.canTakeBreak.collectAsState()
    val breaksUsed by FocusLauncherService.breaksUsed.collectAsState()
    val breaksTotal by FocusLauncherService.breaksTotal.collectAsState()
    var showPinForExit by remember { mutableStateOf(false) }
    var showConfirmExit by remember { mutableStateOf(false) }
    var showPinForBreak by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LauncherTopBar(hardLocked)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(apps, key = { it.processName.lowercase() }) { AppTile(it) }
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF13121F))
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    if (hardLocked) showPinForExit = true
                    else FocusLauncherService.toggleHardLock()
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (hardLocked) Error else OnSurface2
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (hardLocked) Icons.Default.Lock else Icons.Default.LockOpen, null)
                Spacer(Modifier.width(6.dp))
                Text(if (hardLocked) "Hard Locked" else "Hard Lock")
            }
            Spacer(Modifier.weight(1f))
            val breakLabel = when {
                breaksTotal == -1 -> "Take Break"
                !canBreak -> "No Breaks Left"
                breaksTotal > 1 -> "Take Break (${breaksTotal - breaksUsed} left)"
                else -> "Take Break"
            }
            OutlinedButton(
                onClick = { if (!hardLocked) showPinForBreak = true },
                enabled = canBreak && !hardLocked,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FreeBreakfast, null)
                Spacer(Modifier.width(6.dp))
                Text(breakLabel)
            }
            Button(
                onClick = {
                    if (hardLocked) showPinForExit = true else showConfirmExit = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Error.copy(alpha = .85f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ExitToApp, null)
                Spacer(Modifier.width(6.dp))
                Text("End Session", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showPinForExit) {
        SessionPinDialog(
            title = if (hardLocked) "Unlock required" else "End session",
            subtitle = "Enter your session PIN to continue",
            onSuccess = {
                showPinForExit = false
                if (hardLocked) FocusLauncherService.toggleHardLock()
                else FocusLauncherService.exit()
            },
            onDismiss = { showPinForExit = false }
        )
    }
    if (showConfirmExit) {
        AlertDialog(
            onDismissRequest = { showConfirmExit = false },
            containerColor = Color(0xFF1A1828),
            title = { Text("End session?", color = OnSurface, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your session will end and enforcement will stop.",
                    color = OnSurface2
                )
            },
            confirmButton = {
                Button(
                    onClick = { showConfirmExit = false; FocusLauncherService.exit() },
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) { Text("End Session") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmExit = false }) {
                    Text("Keep going", color = OnSurface2)
                }
            }
        )
    }
    if (showPinForBreak) {
        SessionPinDialog(
            title = "Start break",
            subtitle = "Enter your session PIN to take a break",
            onSuccess = { showPinForBreak = false; FocusLauncherService.startBreak() },
            onDismiss = { showPinForBreak = false }
        )
    }
}

@Composable
private fun LauncherTopBar(hardLocked: Boolean) {
    var clock by remember { mutableStateOf("") }
    var timer by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            clock = LocalTime.now().format(formatter)
            val remaining = FocusLauncherService.remainingSeconds()
            timer = when {
                remaining < 0 -> ""
                remaining == 0L -> "Ending…"
                else -> "%02d:%02d".format(remaining / 60, remaining % 60)
            }
            delay(1_000)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF13121F))
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GridView, null, tint = Purple80, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "FOCUS LAUNCHER",
                color = OnSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hardLocked) {
                Text("HARD LOCKED", color = Error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
            }
            if (timer.isNotEmpty()) {
                Text(
                    timer,
                    color = if (FocusLauncherService.remainingSeconds() in 1..299) Warning else Purple80,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(clock, color = OnSurface2, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AppTile(app: FocusLauncherApp) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1A2E))
            .clickable {
                scope.launch(Dispatchers.IO) {
                    try {
                        val process = if (app.exePath != null) ProcessBuilder(app.exePath)
                        else ProcessBuilder("cmd", "/c", "start", "", app.processName)
                        process.start()
                        delay(400)
                        FocusLauncherService.onForegroundChanged(app.processName)
                    } catch (_: Exception) {
                        // Process launch failures do not end the focus session.
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(Purple80.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Apps, null, tint = Purple80, modifier = Modifier.size(28.dp))
            }
            Text(
                app.displayName,
                color = OnSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
                maxLines = 2
            )
        }
    }
}

@Composable
private fun BreakScreen() {
    val remaining by FocusLauncherService.breakRemainingSeconds.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("BREAK", color = Warning, fontSize = 12.sp, letterSpacing = 3.sp)
            Text(
                "%02d:%02d".format(remaining / 60, remaining % 60),
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Text("Focus Launcher resumes automatically", color = OnSurface2, fontSize = 13.sp)
            OutlinedButton(
                onClick = { FocusLauncherService.endBreak() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning),
                shape = RoundedCornerShape(12.dp)
            ) { Text("End Break Early") }
        }
    }
}

@Composable
fun SecondaryScreenLock() {
    var timer by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val remaining = FocusLauncherService.remainingSeconds()
            timer = if (remaining > 0) "%02d:%02d".format(remaining / 60, remaining % 60) else ""
            delay(1_000)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0C0B14)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, tint = Purple80.copy(alpha = .4f), modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text("Focus session in progress", color = OnSurface2, fontSize = 14.sp)
            if (timer.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(timer, color = Purple80, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SessionPinDialog(
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1828),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Purple80, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, color = OnSurface, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(subtitle, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; error = false },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    isError = error,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                                tint = OnSurface2
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (error) Error else Purple80,
                        unfocusedBorderColor = if (error) Error else OnSurface2
                    )
                )
                if (error) Text("Incorrect PIN", color = Error)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (FocusLauncherService.verifyPin(pin)) onSuccess()
                    else { pin = ""; error = true }
                },
                enabled = pin.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple80)
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurface2) }
        }
    )
}