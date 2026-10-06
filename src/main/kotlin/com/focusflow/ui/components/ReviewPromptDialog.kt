package com.focusflow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusflow.i18n.LocalizationManager
import com.focusflow.services.CrashReporter
import com.focusflow.services.ReviewPromptService
import com.focusflow.ui.theme.*

/**
 * Microsoft Store rating prompt.
 *
 * Shows after the 10th app open, triggered by one of several meaningful
 * moments (task done, session done, standalone block ended, PIN unlock).
 *
 * Layout:
 *  • ⭐ icon + title + body copy
 *  • [Rate on Microsoft Store]  ← primary CTA; permanently dismisses
 *  • [Report an Issue]          ← optional; shown only when feedback webhook is set
 *    └─ expands to text field + [Send Feedback]
 *  • [Maybe Later]              ← 30-day snooze
 *
 * All dismiss/confirm actions go through [ReviewPromptService] which owns the
 * state flow that drives this dialog's visibility.
 */
@Composable
fun ReviewPromptDialog() {
    val s = LocalizationManager.strings

    // Feedback panel state
    var showFeedback    by remember { mutableStateOf(false) }
    var feedbackText    by remember { mutableStateOf("") }
    var feedbackSent    by remember { mutableStateOf(false) }
    var feedbackSending by remember { mutableStateOf(false) }
    var feedbackFailed  by remember { mutableStateOf(false) }
    var showFeedbackNotice by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = { ReviewPromptService.onDismiss() },
        containerColor   = Surface2,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Purple80.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint     = Purple80,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    s.reviewTitle,
                    color      = OnSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    s.reviewBody,
                    color = OnSurface2,
                    style = MaterialTheme.typography.bodyMedium
                )

                // ── Feedback panel ────────────────────────────────────────────
                AnimatedVisibility(
                    visible = showFeedback,
                    enter   = fadeIn() + expandVertically(),
                    exit    = fadeOut() + shrinkVertically()
                ) {
                    if (feedbackSent) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Success.copy(alpha = 0.10f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                s.reviewFeedbackThank,
                                color = Success,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showFeedbackNotice) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Purple80.copy(alpha = 0.08f))
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Purple80,
                                        modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            "One-way feedback",
                                            color = OnSurface,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "We can read your message, but this form has no reply address. " +
                                                "For a response, contact us another way:",
                                            color = OnSurface2,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = {
                                                    openUrl("mailto:${CrashReporter.SUPPORT_EMAIL}")
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Email,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("Email Support", fontSize = 11.sp)
                                            }
                                            TextButton(
                                                onClick = {
                                                    openUrl("https://github.com/TITANICBHAI/FocusFlow-PC/issues/new")
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) {
                                                Text("GitHub Issues", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = { showFeedbackNotice = false },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss feedback notice",
                                            tint = OnSurface2,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value         = feedbackText,
                                 onValueChange = { feedbackText = it; feedbackFailed = false },
                                placeholder   = { Text(s.reviewFeedbackHint, color = OnSurface2) },
                                minLines      = 3,
                                maxLines      = 6,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Purple80,
                                    unfocusedBorderColor = OnSurface2
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        feedbackSending = true
                                        feedbackFailed = false
                                        ReviewPromptService.sendFeedback(feedbackText) { delivered ->
                                            feedbackSending = false
                                            feedbackSent = delivered
                                            feedbackFailed = !delivered
                                        }
                                    },
                                    enabled = feedbackText.isNotBlank() && !feedbackSending,
                                    colors  = ButtonDefaults.buttonColors(containerColor = Purple80)
                                ) {
                                    Text(s.reviewFeedbackSend, fontSize = 13.sp)
                                }
                            }
                            if (feedbackFailed) {
                                Text(
                                    "Could not deliver feedback. Please try again.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                verticalArrangement   = Arrangement.spacedBy(4.dp),
                horizontalAlignment   = Alignment.End
            ) {
                // Primary CTA — Rate on MS Store
                Button(
                    onClick = { ReviewPromptService.onRateNow() },
                    colors  = ButtonDefaults.buttonColors(containerColor = Purple80),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.reviewRateMsStore)
                }

                // Optional feedback / issue report button
                if (ReviewPromptService.feedbackEnabled && !feedbackSent) {
                    OutlinedButton(
                        onClick = { showFeedback = !showFeedback },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface2)
                    ) {
                        Text(s.reviewReportIssue, fontSize = 13.sp)
                    }
                }

                // Maybe Later
                TextButton(
                    onClick  = { ReviewPromptService.onDecline() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s.reviewNoThanks, color = OnSurface2, fontSize = 13.sp)
                }
            }
        },
        dismissButton = null
    )
}
