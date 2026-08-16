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
 * What we collect (anonymous only):
 *   • Crash stack traces — so we can fix bugs faster
 *
 * What we NEVER collect:
 *   • Task names, notes, or any user-created content
 *   • Which apps you block
 *   • Your name, email, or any personal identifier
 *   • Your IP address
 *
 * Calling [onAllow] saves crash_reports_enabled = "true".
 * Calling [onDecline] saves crash_reports_enabled = "false".
 * Both paths prevent this dialog from ever showing again.
 */
@Composable
fun TelemetryConsentDialog(
    onAllow:  () -> Unit,
    onDecline: () -> Unit
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
                    "Help improve FocusFlow?",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = OnSurface,
                    textAlign  = TextAlign.Center
                )

                Text(
                    "We'd like to send anonymous diagnostic data so we can fix bugs " +
                    "and improve performance. No personal information is ever collected.",
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
                         ConsentBullet(label = "Crash reports & stack traces",       allowed = true)
                         ConsentBullet(label = "Task names, notes, or session data", allowed = false)
                         ConsentBullet(label = "Which apps you block",               allowed = false)
                         ConsentBullet(label = "Your name, email, or IP address",    allowed = false)
                    }
                }

                Text(
                    "You can change this any time in Settings → Privacy.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = OnSurface2.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(2.dp))

                Button(
                    onClick  = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) {
                    Text("Allow anonymous diagnostics", fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
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
