package com.focusflow.ui.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.focusflow.enforcement.User32Extra
import com.focusflow.enforcement.isWindows
import com.focusflow.services.FocusLauncherService
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame

/**
 * Hosts one undecorated fullscreen launcher window per monitor. The primary
 * screen gets the interactive launcher; secondary screens show a lock screen.
 */
@Composable
fun LauncherWindowHost() {
    val isActive by FocusLauncherService.isActive.collectAsState()
    if (!isActive) return

    val environment = remember { GraphicsEnvironment.getLocalGraphicsEnvironment() }
    val screens = remember { environment.screenDevices.toList() }
    val primaryBounds = remember {
        environment.defaultScreenDevice.defaultConfiguration.bounds
    }

    screens.forEach { screen ->
        val bounds = screen.defaultConfiguration.bounds
        key(bounds.x, bounds.y) {
            LauncherWindowForScreen(
                bounds = bounds,
                isPrimary = bounds == primaryBounds
            )
        }
    }
}

@Composable
private fun LauncherWindowForScreen(bounds: Rectangle, isPrimary: Boolean) {
    val overlayVisible by FocusLauncherService.overlayVisible.collectAsState()
    val breakActive by FocusLauncherService.breakActive.collectAsState()
    val windowState = rememberWindowState(
        placement = WindowPlacement.Floating,
        position = WindowPosition(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.width.dp, bounds.height.dp)
    )
    val shouldBeTopmost = overlayVisible && !breakActive

    Window(
        onCloseRequest = { },
        state = windowState,
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = true,
        title = "FocusFlow Launcher"
    ) {
        LaunchedEffect(Unit) {
            (window as? JFrame)?.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        }

        LaunchedEffect(shouldBeTopmost) {
            if (!isWindows) return@LaunchedEffect
            val hwnd = try {
                Native.getComponentPointer(window)?.let { WinDef.HWND(it) }
            } catch (_: Throwable) {
                null
            } ?: return@LaunchedEffect

            if (shouldBeTopmost) {
                User32Extra.INSTANCE.SetWindowPos(
                    hwnd,
                    User32Extra.HWND_TOPMOST,
                    bounds.x,
                    bounds.y,
                    bounds.width,
                    bounds.height,
                    User32Extra.SWP_SHOWWINDOW
                )
            } else {
                User32Extra.INSTANCE.SetWindowPos(
                    hwnd,
                    User32Extra.HWND_NOTOPMOST,
                    bounds.x,
                    bounds.y,
                    bounds.width,
                    bounds.height,
                    User32Extra.SWP_NOACTIVATE or
                        User32Extra.SWP_NOMOVE or
                        User32Extra.SWP_NOSIZE
                )
            }
        }

        if (isPrimary) LauncherContent() else SecondaryScreenLock()
    }
}