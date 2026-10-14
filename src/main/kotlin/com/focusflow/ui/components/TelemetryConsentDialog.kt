package com.focusflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusflow.ui.theme.*

/**
 * TelemetryConsentDialog
 *
 * Shown once on first launch (after onboarding) to give users an explicit
 * opt-in/out choice before any crash report is ever sent.
 *
 * Crash reports are opt-in and are delivered to the FocusFlow developers
 * through Discord. Discord receives the sender's IP address. Diagnostic text
 * can contain paths or other error context; the current username, home path,
 * and computer name are redacted before sending, but other identifiers may
 * remain.
 */
@Composable
fun TelemetryConsentDialog(
    onAllow:  () -> Unit,
    onDecline: () -> Unit,
    saving: Boolean = false,
    saveError: String? = null,
) {
    Dialog(
        // Both flags false: user must click a button to choose.
        // onDismissRequest is a no-op — Escape / outside-click cannot accidentally
        // opt the user in or out; this is the correct pattern for a consent dialog.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress    = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape  = RoundedCornerShape(24.dp),
            color  = Surface,
            modifier = Modifier.width(460.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    Icons.Default.Analytics,
                    contentDescription = null,
                    tint     = Purple80,
                    modifier = Modifier.size(42.dp)
                )

                Text(
                    "Allow crash diagnostics?",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = OnSurface,
                    textAlign  = TextAlign.Center
                )

                Text(
                    "If you allow reports, FocusFlow sends crash/error diagnostics to the " +
                    "developers through Discord, a third-party service. Discord receives your " +
                    "IP address when a report is sent. Diagnostic text may contain local paths " +
                    "or other error context. FocusFlow redacts your current username, home path, " +
                    "and computer name, but cannot guarantee every identifier is removed.",
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = OnSurface2,
                    textAlign  = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Surface3,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                         ConsentBullet(label = "Error details, source, and software/OS information", allowed = true)
                         ConsentBullet(label = "Task names, notes, or session data",                allowed = false)
                         ConsentBullet(label = "Username, home path, and computer name (redacted)", allowed = false)
                         ConsentBullet(label = "Your IP address visible to Discord",                 allowed = true)
                    }
                }

                Text(
                    "You can change this any time in Settings → Privacy.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = OnSurface2.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )

                if (saveError != null) {
                    Text(
                        saveError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(2.dp))

                Button(
                    onClick  = onAllow,
                    enabled  = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) {
                    Text(if (saving) "Saving…" else "Allow crash reports", fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = onDecline, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("No thanks", color = OnSurface2.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ConsentBullet(label: String, allowed: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (allowed) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = null,
            tint     = if (allowed) Success else Error,
            modifier = Modifier.size(16.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface2,
            modifier = Modifier.weight(1f)
        )
    }
}
