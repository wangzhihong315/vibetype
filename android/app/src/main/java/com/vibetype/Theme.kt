package com.vibetype

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VibeBackground = Color(0xFFF5F5F7)
val VibeInk = Color(0xFF1D1D1F)
val VibeMuted = Color(0xFF6E6E73)
val VibeFaint = Color(0xFF8E8E93)
val VibeBlue = Color(0xFF0A84FF)
val VibeGreen = Color(0xFF30D158)
val VibeOrange = Color(0xFFFF9F0A)
val VibeCoral = Color(0xFFFF6B4A)
val VibeCard = Color(0xFFFFFFFF)
val VibeLine = Color(0x11000000)

private val LightColors = lightColorScheme(
    primary = VibeBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF003A66),
    secondary = VibeMuted,
    onSecondary = Color.White,
    background = VibeBackground,
    onBackground = VibeInk,
    surface = VibeCard,
    onSurface = VibeInk,
    surfaceVariant = Color(0xFFECECEF),
    onSurfaceVariant = VibeMuted,
    // M3 默认的 surfaceContainer 系列带一点粉，底部弹层会发红，这里全压成中性白。
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF5F5F7),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFEBEBEF),
    outline = VibeLine,
    outlineVariant = Color(0x0F000000),
    error = Color(0xFFFF453A),
)

@Composable
fun VibeTypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
