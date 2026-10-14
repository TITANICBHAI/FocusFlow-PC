package com.focusflow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.enforcement.isWindows
import com.focusflow.ui.theme.*

@Composable
fun OsBanner() {
    if (isWindows) return

    var visible by remember { mutableStateOf(true) }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Warning.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Enforcement inactive on this platform",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Warning
                )
                Text(
                    "FocusFlow's app blocking, network rules and process monitoring only work on Windows. The UI and data features are fully functional.",
                    fontSize = 12.sp,
                    color = OnSurface2
                )
            }
            IconButton(onClick = { visible = false }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "Dismiss", tint = OnSurface2, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A collapsible card listing a permission requirement and how to grant it. */
@Composable
fun PermissionSetupCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    needed: String,
    howTo: String,
    required: Boolean = true,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .clickable { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = OnSurface)
                    if (required) {
                        Text(
                            "Required",
                            fontSize = 10.sp,
                            color = Error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Error.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    } else {
                        Text(
                            "Recommended",
                            fontSize = 10.sp,
                            color = Warning,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Warning.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(needed, fontSize = 12.sp, color = OnSurface2)
            }
            if (actionLabel != null && onAction != null) {
                androidx.compose.material3.TextButton(
                    onClick = { onAction(); expanded = false },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(actionLabel, fontSize = 11.sp, color = Purple80, fontWeight = FontWeight.SemiBold)
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint = OnSurface2,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Purple80.copy(alpha = 0.06f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("How to grant:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Purple80)
                Spacer(Modifier.height(4.dp))
                howTo.lines().forEachIndexed { i, line ->
                    if (line.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${i + 1}.", fontSize = 12.sp, color = Purple80, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                            Text(line.trim(), fontSize = 12.sp, color = OnSurface2, lineHeight = 18.sp)
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
