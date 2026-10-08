package com.redwind.magicorig.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFF455A64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFD8DC),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF49454E),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1B1F),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFCAC4D0),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003C71),
    primaryContainer = Color(0xFF1565C0),
    secondary = Color(0xFF90A4AE),
    onSecondary = Color(0xFF1A237E),
    secondaryContainer = Color(0xFF37474F),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2E2E30),
    onSurfaceVariant = Color(0xFFCAC4D0),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    error = Color(0xFFEF5350),
    onError = Color.White,
    outline = Color(0xFF444446),
    outlineVariant = Color(0xFF49454E),
)

@Composable
fun MagicOriGTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
