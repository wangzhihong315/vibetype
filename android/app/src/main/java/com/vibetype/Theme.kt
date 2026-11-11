package com.vibetype

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 语义色。白天和黑夜各一套，组件只引用语义名，不再写死色值。
 * 触控板本身始终是深色的，所以 pad 两档不变。
 */
data class VibePalette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val coral: Color,
    val card: Color,
    val selected: Color,
    val field: Color,
    val line: Color,
    val capsuleTintTop: Color,
    val capsuleTintBottom: Color,
    val capsulePressed: Color,
    val capsuleIdleTop: Color,
    val capsuleIdleBottom: Color,
    val greenText: Color,
    val greenTint: Color,
    val padTop: Color,
    val padBottom: Color,
)

private val Light = VibePalette(
    background = Color(0xFFF5F5F7),
    ink = Color(0xFF1D1D1F),
    muted = Color(0xFF6E6E73),
    faint = Color(0xFF8E8E93),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    coral = Color(0xFFFF6B4A),
    card = Color(0xFFFFFFFF),
    selected = Color(0xFFFFFFFF),
    field = Color(0xFFF5F5F7),
    line = Color(0x11000000),
    capsuleTintTop = Color(0xFFFFF4F0),
    capsuleTintBottom = Color(0xFFFFE8E0),
    capsulePressed = Color(0xFFFFE1D7),
    capsuleIdleTop = Color(0xFFF5F5F7),
    capsuleIdleBottom = Color(0xFFEFEFF1),
    greenText = Color(0xFF1F9D43),
    greenTint = Color(0x1F30D158),
    padTop = Color(0xFF1D1E24),
    padBottom = Color(0xFF111216),
)

private val Dark = VibePalette(
    background = Color(0xFF0B0C0E),
    ink = Color(0xFFF2F2F7),
    muted = Color(0xFFAEAEB2),
    faint = Color(0xFF8A8A8E),
    blue = Color(0xFF4DA3FF),
    green = Color(0xFF32D74B),
    orange = Color(0xFFFF9F0A),
    coral = Color(0xFFFF7A5C),
    card = Color(0xFF17181D),
    selected = Color(0xFF3A3B42),
    field = Color(0xFF232429),
    line = Color(0x1FFFFFFF),
    capsuleTintTop = Color(0xFF2A1A16),
    capsuleTintBottom = Color(0xFF35211B),
    capsulePressed = Color(0xFF3A211B),
    capsuleIdleTop = Color(0xFF232429),
    capsuleIdleBottom = Color(0xFF1C1D22),
    greenText = Color(0xFF4ADE80),
    greenTint = Color(0x262FD158),
    padTop = Color(0xFF1D1E24),
    padBottom = Color(0xFF111216),
)

/**
 * 用全局状态而不是 CompositionLocal：Canvas 的 draw lambda 不是 @Composable，
 * 读不到 CompositionLocal；全局快照状态在两种上下文里都能读。
 */
object Vibe {
    var dark by mutableStateOf(false)

    val p: VibePalette get() = if (dark) Dark else Light
}

val VibeBackground get() = Vibe.p.background
val VibeInk get() = Vibe.p.ink
val VibeMuted get() = Vibe.p.muted
val VibeFaint get() = Vibe.p.faint
val VibeBlue get() = Vibe.p.blue
val VibeGreen get() = Vibe.p.green
val VibeOrange get() = Vibe.p.orange
val VibeCoral get() = Vibe.p.coral
val VibeCard get() = Vibe.p.card
val VibeSelected get() = Vibe.p.selected
val VibeField get() = Vibe.p.field
val VibeLine get() = Vibe.p.line
val VibeCapsuleTintTop get() = Vibe.p.capsuleTintTop
val VibeCapsuleTintBottom get() = Vibe.p.capsuleTintBottom
val VibeCapsulePressed get() = Vibe.p.capsulePressed
val VibeCapsuleIdleTop get() = Vibe.p.capsuleIdleTop
val VibeCapsuleIdleBottom get() = Vibe.p.capsuleIdleBottom
val VibeGreenText get() = Vibe.p.greenText
val VibeGreenTint get() = Vibe.p.greenTint
val VibePadTop get() = Vibe.p.padTop
val VibePadBottom get() = Vibe.p.padBottom

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF003A66),
    secondary = Color(0xFF6E6E73),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1D1F),
    surface = Color.White,
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFF6E6E73),
    // M3 默认的 surfaceContainer 系列带一点粉，底部弹层会发红，这里全压成中性白。
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF5F5F7),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFEBEBEF),
    outline = Color(0x11000000),
    outlineVariant = Color(0x0F000000),
    error = Color(0xFFFF453A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12324F),
    onPrimaryContainer = Color(0xFFD3E7FF),
    secondary = Color(0xFFAEAEB2),
    onSecondary = Color(0xFF0B0C0E),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF17181D),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF232429),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainerLowest = Color(0xFF0B0C0E),
    surfaceContainerLow = Color(0xFF17181D),
    surfaceContainer = Color(0xFF17181D),
    surfaceContainerHigh = Color(0xFF1C1D22),
    surfaceContainerHighest = Color(0xFF232429),
    surfaceBright = Color(0xFF2A2B31),
    surfaceDim = Color(0xFF0B0C0E),
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFFF453A),
)

@Composable
fun VibeTypeTheme(dark: Boolean = Vibe.dark, content: @Composable () -> Unit) {
    if (Vibe.dark != dark) Vibe.dark = dark
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
