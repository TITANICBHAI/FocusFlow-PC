package com.focusflow.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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

private const val URL_APPGALLERY  = "https://appgallery.huawei.com/app/C117761461"
private const val URL_GITHUB_APK  = "https://github.com/TITANICBHAI/FocusFlow/releases"
private const val URL_SAI_FDROID  = "https://f-droid.org/packages/com.aefyr.sai.fdroid/"
private const val URL_SAI_GITHUB  = "https://github.com/Aefyr/SAI/releases"

private enum class PromoStep { MAIN, SIDELOAD_GUIDE }

@Composable
fun AndroidPromoDialog(onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(PromoStep.MAIN) }

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
            AnimatedContent(
                targetState  = step,
                transitionSpec = {
                    if (targetState == PromoStep.SIDELOAD_GUIDE)
                        fadeIn() + slideInHorizontally { it / 6 } togetherWith
                        fadeOut() + slideOutHorizontally { -it / 6 }
                    else
                        fadeIn() + slideInHorizontally { -it / 6 } togetherWith
                        fadeOut() + slideOutHorizontally { it / 6 }
                }
            ) { current ->
                when (current) {
                    PromoStep.MAIN          -> MainPromoPage(
                        onDismiss      = onDismiss,
                        onAppGallery   = { openUrl(URL_APPGALLERY); onDismiss() },
                        onGithubApk    = { step = PromoStep.SIDELOAD_GUIDE }
                    )
                    PromoStep.SIDELOAD_GUIDE -> SideloadGuidePage(
                        onBack    = { step = PromoStep.MAIN },
                        onConfirm = { openUrl(URL_GITHUB_APK); onDismiss() },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun MainPromoPage(
    onDismiss:    () -> Unit,
    onAppGallery: () -> Unit,
    onGithubApk:  () -> Unit
) {
    val s = LocalizationManager.strings
    Column(
        modifier                = Modifier.padding(32.dp),
        horizontalAlignment     = Alignment.CenterHorizontally,
        verticalArrangement     = Arrangement.spacedBy(14.dp)
    ) {
        PromoIcon(Icons.Default.PhoneAndroid)

        Text(
            s.promoTitle,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color      = OnSurface,
            textAlign  = TextAlign.Center
        )

        Text(
            s.promoBody,
            style      = MaterialTheme.typography.bodyMedium,
            color      = OnSurface2,
            textAlign  = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(2.dp))

        Button(
            onClick  = onAppGallery,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
        ) {
            Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.promoGetAppGallery, fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick  = onGithubApk,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface2)
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.promoDownloadApk, fontSize = 13.sp)
        }

        TextButton(onClick = onDismiss) {
            Text(s.promoMaybeLater, color = OnSurface2.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SideloadGuidePage(
    onBack:    () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalizationManager.strings
    Column(
        modifier                = Modifier.padding(32.dp),
        horizontalAlignment     = Alignment.CenterHorizontally,
        verticalArrangement     = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint     = OnSurface2,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.weight(1f))
        }

        PromoIcon(Icons.Default.InstallMobile)

        Text(
            s.promoInstallTitle,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color      = OnSurface,
            textAlign  = TextAlign.Center
        )

        Text(
            s.promoInstallBody,
            style      = MaterialTheme.typography.bodyMedium,
            color      = OnSurface2,
            textAlign  = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(2.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepRow(number = "1", text = s.promoStep1)
            StepRow(number = "2", text = s.promoStep2)
            StepRow(number = "3", text = s.promoStep3)
            StepRow(number = "4", text = s.promoStep4)
        }

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick  = { openUrl(URL_SAI_FDROID) },
                modifier = Modifier.weight(1f),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface2),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(s.promoGetSaiFdroid, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick  = { openUrl(URL_SAI_GITHUB) },
                modifier = Modifier.weight(1f),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface2),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(s.promoSaiGithub, fontSize = 12.sp)
            }
        }

        Button(
            onClick  = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.promoOkDownload, fontWeight = FontWeight.SemiBold)
        }

        TextButton(onClick = onDismiss) {
            Text(s.btnCancel, color = OnSurface2.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PromoIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Purple80.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Purple80, modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun StepRow(number: String, text: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment     = Alignment.Top
    ) {
        Box(
            modifier         = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Purple80.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                style      = MaterialTheme.typography.labelSmall,
                color      = Purple80,
                fontWeight = FontWeight.Bold,
                fontSize   = 11.sp
            )
        }
        Text(
            text,
            style      = MaterialTheme.typography.bodySmall,
            color      = OnSurface2,
            lineHeight = 17.sp,
            modifier   = Modifier.weight(1f)
        )
    }
}

@Composable
fun ReviewPromptDialog(onDismiss: () -> Unit) {
    val s = LocalizationManager.strings
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
            modifier = Modifier.width(400.dp)
        ) {
            Column(
                modifier            = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PromoIcon(Icons.Default.Star)

                Text(
                    s.reviewTitle,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = OnSurface,
                    textAlign  = TextAlign.Center
                )

                Text(
                    s.reviewBody,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = OnSurface2,
                    textAlign  = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick  = {
                        // Try the Store deep-link first (opens the in-app review dialog directly).
                        // Falls back to the web listing if the ms-windows-store handler isn't
                        // registered (e.g. Store app uninstalled on LTSC editions).
                        val launched = runCatching {
                            val desktop = java.awt.Desktop.getDesktop()
                            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                                desktop.browse(java.net.URI("ms-windows-store://review/?ProductId=9NJN9FPRQ7T1"))
                                true
                            } else false
                        }.getOrElse { false }
                        if (!launched) openUrl("https://apps.microsoft.com/detail/9NJN9FPRQ7T1")
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Purple80)
                ) {
                    Icon(Icons.Default.Star, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.reviewRateMsStore, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick  = { openUrl("https://github.com/TITANICBHAI/FocusFlow-PC"); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface2)
                ) {
                    Icon(Icons.Default.Star, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.reviewStarGithub, fontSize = 13.sp)
                }

                TextButton(onClick = onDismiss) {
                    Text(s.reviewNoThanks, color = OnSurface2.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        }
    }
}

fun openUrl(url: String) {
    val opened = runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            desktop.browse(java.net.URI(url))
            true
        } else false
    }.getOrElse { false }

    // Fallback for environments where Desktop.Action.BROWSE is unsupported
    // (e.g. headless JVM, LTSC Windows editions with stripped Desktop APIs).
    if (!opened) {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("explorer.exe", url))
            // Close all three streams immediately — we don't read output and never
            // write to stdin. Failing to close them leaks file descriptors.
            p.outputStream.close()
            p.inputStream.close()
            p.errorStream.close()
        }
    }
}
