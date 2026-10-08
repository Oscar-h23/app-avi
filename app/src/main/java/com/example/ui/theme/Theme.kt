package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AviLightColorScheme = lightColorScheme(
    primary = AviBlueMedium,
    onPrimary = Color.White,
    primaryContainer = AviCyanLight,
    onPrimaryContainer = AviNavy,
    secondary = AviBlueAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = AviNavy,
    background = AviBackgroundLight,
    onBackground = AviTextPrimary,
    surface = AviSurfaceLight,
    onSurface = AviTextPrimary,
    surfaceVariant = AviSurfaceVariant,
    onSurfaceVariant = AviTextSecondary,
    outline = AviBorder,
    error = AviActionFuga,
    onError = Color.White
)

private val AviDarkColorScheme = darkColorScheme(
    primary = AviCyan,
    onPrimary = AviPrimaryDark,
    primaryContainer = AviBlueMedium,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF60A5FA),
    onSecondary = AviPrimaryDark,
    background = Color(0xFF07192D),
    onBackground = Color.White,
    surface = AviNavy,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF16304E),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AviDarkColorScheme else AviLightColorScheme,
        typography = Typography,
        content = content
    )
}
