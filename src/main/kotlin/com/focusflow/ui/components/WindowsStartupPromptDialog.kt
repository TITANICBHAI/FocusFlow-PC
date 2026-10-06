package com.focusflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusflow.ui.theme.OnSurface
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Purple80
import com.focusflow.ui.theme.Success
import com.focusflow.ui.theme.Surface2
import com.focusflow.ui.theme.Surface3

@Composable
fun WindowsStartupPromptDialog(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        titleContentColor = OnSurface,
        textContentColor = OnSurface2,
        icon = {
            Icon(
                imageVector = Icons.Default.Autorenew,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                "Start FocusFlow with Windows?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "FocusFlow can open automatically when you sign in, so your app blocks " +
                        "and scheduled focus rules can start without you remembering to launch it."
                )

                Surface(
                    color = Surface3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Success,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "No administrator permission needed. You can change this later in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface2
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Start automatically at sign-in",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = false,
                        onCheckedChange = { enabled ->
                            if (enabled) onEnable()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Purple80,
                            checkedTrackColor = Purple80.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Maybe later", color = OnSurface2)
            }
        },
        confirmButton = {}
    )
}
