package com.focusflow.ui.launcher

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.focusflow.ui.theme.Error
import com.focusflow.ui.theme.Purple80

object LauncherColors {
    val Background = Color(0xFF0C0B14)
    val Bar = Color(0xFF13121F)
    val Tile = Color(0xFF1C1A2E)
    val Dialog = Color(0xFF1A1828)
    val Text = Color(0xFFE8E6F0)
    val TextDim = Color(0xFFB0AEC8)
}

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple80,
            onPrimary = Color(0xFF1A1830),
            background = LauncherColors.Background,
            onBackground = LauncherColors.Text,
            surface = LauncherColors.Dialog,
            onSurface = LauncherColors.Text,
            onSurfaceVariant = LauncherColors.TextDim,
            outline = Color(0xFF49475E),
            error = Error
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LauncherColors.Background,
            contentColor = LauncherColors.Text
        ) {
            content()
        }
    }
}
