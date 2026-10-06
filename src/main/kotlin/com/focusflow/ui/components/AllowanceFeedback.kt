package com.focusflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.focusflow.enforcement.KillSwitchService
import com.focusflow.i18n.LocalizationManager
import com.focusflow.ui.theme.Error
import com.focusflow.ui.theme.OnSurface2
import com.focusflow.ui.theme.Surface2
import com.focusflow.ui.theme.Warning

@Composable
fun AllowanceLoadBanner(
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!loading && !failed) return
    val strings = LocalizationManager.strings
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (failed) Error.copy(alpha = 0.08f) else Surface2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Warning,
                strokeWidth = 2.dp
            )
            Text(strings.blockerLoading, color = OnSurface2, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                strings.blockerLoadFailed,
                modifier = Modifier.weight(1f),
                color = Error,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRetry) { Text(strings.blockerRetry, color = Error) }
        }
    }
}

@Composable
fun EmergencyBreakAllowanceNotice(modifier: Modifier = Modifier) {
    val active by KillSwitchService.isActive.collectAsState()
    if (!active) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Warning.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            LocalizationManager.strings.blockerEmergencyBreakActive,
            color = Warning,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
