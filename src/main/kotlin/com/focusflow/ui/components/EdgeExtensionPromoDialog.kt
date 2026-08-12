package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusflow.ui.theme.OnSurface
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Purple80
import com.focusflow.ui.theme.Surface
import com.focusflow.ui.theme.Surface2

private const val EDGE_EXTENSION_URL =
    "https://microsoftedge.microsoft.com/addons/detail/mmjhejklaaljidnmabjiloceniacihkk"
private const val EDGE_EXTENSION_STORE_ID = "0RDCKB7XB54T"

@Composable
fun EdgeExtensionPromoDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Surface,
            modifier = Modifier.width(460.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = Purple80,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Text(
                    "FocusFlow for Microsoft Edge",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = OnSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    "Take your focus protection into the browser. " +
                        "Install our official Edge extension to keep distracting websites blocked while you work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface2,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Microsoft Edge Add-ons", color = OnSurface2, fontSize = 12.sp)
                    Text(
                        "Store ID  $EDGE_EXTENSION_STORE_ID",
                        color = OnSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Install Edge Extension", fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        "Not now",
                        color = OnSurface2.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

fun openEdgeExtensionStore() {
    openUrl(EDGE_EXTENSION_URL)
}