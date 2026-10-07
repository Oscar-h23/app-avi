package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AviLightColorScheme = lightColorScheme(
    primary = AviNavy,
    onPrimary = Color.White,
    primaryContainer = AviBlueAccent,
    onPrimaryContainer = Color.White,
    secondary = AviCyan,
    onSecondary = Color(0xFF0D1B2A),
    secondaryContainer = AviCyanLight,
    onSecondaryContainer = Color(0xFF0D1B2A),
    background = AviBackgroundLight,
    onBackground = AviTextPrimary,
    surface = AviSurfaceLight,
    onSurface = AviTextPrimary,
    surfaceVariant = AviSurfaceVariant,
    onSurfaceVariant = AviTextSecondary,
    error = AviActionFuga,
    onError = Color.White
)

private val AviDarkColorScheme = darkColorScheme(
    primary = AviCyan,
    onPrimary = Color(0xFF0D1B2A),
    primaryContainer = AviBlueMedium,
    onPrimaryContainer = Color.White,
    secondary = AviCyanLight,
    onSecondary = Color(0xFF0D1B2A),
    background = AviPrimaryDark,
    onBackground = Color.White,
    surface = AviNavy,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AviDarkColorScheme else AviLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
