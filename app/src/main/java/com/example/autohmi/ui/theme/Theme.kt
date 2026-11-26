package com.example.autohmi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DashboardColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF163D35),
    onPrimaryContainer = Mint,
    secondary = Color(0xFF9CCAFF),
    secondaryContainer = Color(0xFF233C48),
    onSecondaryContainer = Mint,
    background = DashboardBackground,
    surface = DashboardSurface,
    surfaceVariant = Color(0xFF202E3B),
    surfaceContainerHighest = Color(0xFF202E3B),
    surfaceContainerHigh = Color(0xFF1B2835),
    surfaceContainer = DashboardSurface,
    surfaceContainerLow = Color(0xFF101A24),
    surfaceContainerLowest = DashboardBackground,
    onBackground = DashboardText,
    onSurface = DashboardText,
    onSurfaceVariant = DashboardMuted,
    outline = DashboardOutline,
    outlineVariant = Color(0xFF2B3B49),
    error = Color(0xFFFFB4AB)
)

@Composable
fun AutoHMITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DashboardColors,
        typography = Typography,
        content = content
    )
}
