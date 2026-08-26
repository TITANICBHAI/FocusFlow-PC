package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusflow.i18n.LocalizationManager
import com.focusflow.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val SHARE_MESSAGE_GENERAL = """Hey! I've been using FocusFlow to block distracting apps and stay focused — it's completely free and actually works.

Get it for Android: https://focusflowapp.pages.dev/
Or grab the APK: https://github.com/TITANICBHAI/FocusFlow/releases"""

private val SHARE_MESSAGE_REDDIT = """Been using **FocusFlow** to block distracting apps on PC and Android — it's free, open-source, and has no paywalls.

- PC (Windows): https://github.com/TITANICBHAI/FocusFlow-PC
- Android: https://appgallery.huawei.com/app/C117761461 or APK at https://github.com/TITANICBHAI/FocusFlow/releases

Works great for deep work sessions."""

private val SHARE_MESSAGE_LINK = "https://focusflowapp.pages.dev/"

private data class ShareChannel(val label: String, val icon: ImageVector, val message: String)

@Composable
fun ShareDialog(onDismiss: () -> Unit) {
    val s = LocalizationManager.strings
    val scope = rememberCoroutineScope()
    var copiedIndex by remember { mutableStateOf<Int?>(null) }

    val channels = listOf(
        ShareChannel(s.shareCopyMessage, Icons.Default.ContentCopy, SHARE_MESSAGE_GENERAL),
        ShareChannel(s.shareCopyReddit,  Icons.Default.IosShare,    SHARE_MESSAGE_REDDIT),
        ShareChannel(s.shareWebsiteLink, Icons.Default.Share,       SHARE_MESSAGE_LINK)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress      = true,
            dismissOnClickOutside   = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape    = RoundedCornerShape(24.dp),
            color    = Surface,
            modifier = Modifier.width(480.dp)
        ) {
            Column(
                modifier                = Modifier.padding(32.dp),
                horizontalAlignment     = Alignment.CenterHorizontally,
                verticalArrangement     = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Share, null, tint = Purple80, modifier = Modifier.size(38.dp))
                }

                Text(
                    s.shareTitle,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = OnSurface,
                    textAlign  = TextAlign.Center
                )

                Text(
                    s.shareBody,
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = OnSurface2,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(2.dp))

                channels.forEachIndexed { index, channel ->
                    val isCopied = copiedIndex == index
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isCopied) Purple80.copy(alpha = 0.12f) else Surface2)
                            .border(
                                width = if (isCopied) 1.5.dp else 0.dp,
                                color = if (isCopied) Purple80 else androidx.compose.ui.graphics.Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                copyToClipboard(channel.message)
                                copiedIndex = index
                                scope.launch {
                                    delay(2000)
                                    if (copiedIndex == index) copiedIndex = null
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            if (isCopied) Icons.Default.Check else channel.icon,
                            contentDescription = null,
                            tint     = if (isCopied) Purple80 else OnSurface2,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            if (isCopied) s.shareCopied else channel.label,
                            style      = MaterialTheme.typography.bodyMedium,
                            color      = if (isCopied) Purple80 else OnSurface,
                            fontWeight = if (isCopied) FontWeight.SemiBold else FontWeight.Normal,
                            modifier   = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint     = OnSurface2.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(s.shareClose, color = OnSurface2.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        }
    }
}

fun copyToClipboard(text: String) {
    try {
        val clipboard  = Toolkit.getDefaultToolkit().systemClipboard
        val selection  = StringSelection(text)
        clipboard.setContents(selection, selection)
    } catch (_: Throwable) {}
}
