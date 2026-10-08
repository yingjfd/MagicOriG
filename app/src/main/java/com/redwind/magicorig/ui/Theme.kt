package com.redwind.magicorig.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Liquid Glass color palette
// Inspired by https://liquidglass.qmdeve.com/zh/
val GlassBg = Color(0xFFF2F2F7)
val GlassSurface = Color(0xE6FFFFFF)
val GlassCard = Color(0xCCFFFFFF)
val GlassNav = Color(0xCCF0F0F5)
val GlassBlue = Color(0xFF007AFF)
val GlassTextPrimary = Color(0xFF1C1C1E)
val GlassTextSecondary = Color(0xFF8E8E93)
val GlassDivider = Color(0x30999999)
val GlassGreen = Color(0xFF34C759)
val GlassRed = Color(0xFFFF3B30)

private val LiquidGlassLight = lightColorScheme(
    primary = GlassBlue,
    onPrimary = Color.White,
    surface = GlassSurface,
    background = GlassBg,
    onSurface = GlassTextPrimary,
    onSurfaceVariant = GlassTextSecondary,
    surfaceVariant = GlassCard,
    outline = GlassDivider,
)

@Composable
fun MagicOriGTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiquidGlassLight,
        content = content
    )
}
