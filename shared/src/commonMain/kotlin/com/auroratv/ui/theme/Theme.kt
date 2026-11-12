package com.auroratv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.auroratv.settings.AnimationLevel
import com.auroratv.settings.ThemeMode

/** Aurora's semantic palette. Every screen, dialog and the player read colours from here. */
@Immutable
data class AuroraColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceGlass: Color,
    val onBackground: Color,
    val onSurfaceMuted: Color,
    val onSurfaceFaint: Color,
    val focusRing: Color,
    val focusFill: Color,
    val accent: Color,
    val onAccent: Color,
    val divider: Color,
    val error: Color,
    val scrim: Color,
    val skeleton: Color,
    val skeletonHighlight: Color,
)

val DarkAurora = AuroraColors(
    isDark = true,
    background = Color(0xFF0B0C0F),
    surface = Color(0xFF15171C),
    surfaceRaised = Color(0xFF1E2128),
    surfaceGlass = Color(0xCC1A1C22),
    onBackground = Color(0xFFF4F5F7),
    onSurfaceMuted = Color(0xFFB4B8C2),
    onSurfaceFaint = Color(0xFF7A7F8C),
    focusRing = Color(0xFFFFFFFF),
    focusFill = Color(0xFFF4F5F7),
    accent = Color(0xFF8AB4FF),
    onAccent = Color(0xFF0B0C0F),
    divider = Color(0x1FFFFFFF),
    error = Color(0xFFFF6B6B),
    scrim = Color(0xE6000000),
    skeleton = Color(0xFF1C1F25),
    skeletonHighlight = Color(0xFF272B33),
)

val LightAurora = AuroraColors(
    isDark = false,
    background = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceGlass = Color(0xD9FFFFFF),
    onBackground = Color(0xFF15171C),
    onSurfaceMuted = Color(0xFF4A505C),
    onSurfaceFaint = Color(0xFF858B97),
    focusRing = Color(0xFF15171C),
    focusFill = Color(0xFF15171C),
    accent = Color(0xFF2F6BFF),
    onAccent = Color(0xFFFFFFFF),
    divider = Color(0x1F000000),
    error = Color(0xFFD93636),
    scrim = Color(0xCCF2F3F5),
    skeleton = Color(0xFFE3E5E9),
    skeletonHighlight = Color(0xFFEDEFF2),
)

@Immutable
data class AuroraMotion(val level: AnimationLevel) {
    val enabled get() = level != AnimationLevel.Off
    /** Scale multiplier on focus. tvOS-like lift, smaller when reduced. */
    val focusScale get() = when (level) { AnimationLevel.Full -> 1.08f; AnimationLevel.Reduced -> 1.04f; AnimationLevel.Off -> 1f }
    fun duration(fullMs: Int) = when (level) { AnimationLevel.Full -> fullMs; AnimationLevel.Reduced -> fullMs / 2; AnimationLevel.Off -> 0 }
}

object AuroraType {
    val hero = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 50.sp)
    val title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp)
    val section = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val body = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp)
    val bodyStrong = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    val caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
    val label = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
}

val LocalAuroraColors = staticCompositionLocalOf { DarkAurora }
val LocalAuroraMotion = staticCompositionLocalOf { AuroraMotion(AnimationLevel.Full) }
/** Accent derived from the focused artwork; falls back to the theme accent. */
val LocalArtworkAccent = staticCompositionLocalOf { Color.Unspecified }

object Aurora {
    val colors: AuroraColors @Composable get() = LocalAuroraColors.current
    val motion: AuroraMotion @Composable get() = LocalAuroraMotion.current
}

@Composable
fun AuroraTheme(mode: ThemeMode, animations: AnimationLevel, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val target = if (dark) DarkAurora else LightAurora
    val motion = AuroraMotion(animations)
    // Cross-fade the main surfaces when switching theme so the change feels deliberate, not abrupt.
    val bg by animateColorAsState(target.background, tween(motion.duration(350)))
    val fg by animateColorAsState(target.onBackground, tween(motion.duration(350)))
    val colors = target.copy(background = bg, onBackground = fg)
    val material = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
        surface = colors.surface, onSurface = colors.onBackground, onBackground = colors.onBackground, error = colors.error,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
        surface = colors.surface, onSurface = colors.onBackground, onBackground = colors.onBackground, error = colors.error,
    )
    CompositionLocalProvider(LocalAuroraColors provides colors, LocalAuroraMotion provides motion) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
