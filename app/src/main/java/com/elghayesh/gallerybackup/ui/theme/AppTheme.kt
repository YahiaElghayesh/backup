package com.elghayesh.gallerybackup.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.ThemeMode

/** Softer, more contemporary corner radii than Material3's defaults -- used app-wide for cards,
 * buttons, dialogs, chips, and sheets so every surface reads as one deliberate style. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(
    themeMode: ThemeMode,
    accentColor: AccentColor,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = remember(accentColor, useDarkTheme) {
        tonalColorScheme(Color(accentColor.seed), useDarkTheme)
    }
    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, content = content)
}

/**
 * Builds a full, cohesive Material3 palette from one accent seed. The previous scheme only
 * overrode primary/secondary/tertiary and left every other role -- surface, background, outline,
 * containers -- at Material3's unrelated generic-purple default, so every accent color ended up
 * looking like a thin coat of paint over the same generic scheme rather than a real theme. Here,
 * every neutral surface is tinted a few percent toward the seed, and container/outline tones are
 * derived from it too, the way a designed palette (not just `primary = seed`) would be.
 */
private fun tonalColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val seedArgb = seed.toArgb()
    fun towardWhite(ratio: Float) = Color(ColorUtils.blendARGB(seedArgb, AndroidColor.WHITE, ratio))
    fun towardBlack(ratio: Float) = Color(ColorUtils.blendARGB(seedArgb, AndroidColor.BLACK, ratio))
    fun tintNeutral(neutral: Long, ratio: Float) = Color(ColorUtils.blendARGB(neutral.toInt(), seedArgb, ratio))
    fun onColorFor(background: Color) =
        if (ColorUtils.calculateLuminance(background.toArgb()) > 0.5) Color(0xFF1B1B1B) else Color(0xFFF5F5F5)

    return if (dark) {
        val primary = towardWhite(0.32f)
        val primaryContainer = towardBlack(0.55f)
        darkColorScheme(
            primary = primary,
            onPrimary = onColorFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColorFor(primaryContainer),
            secondary = primary,
            onSecondary = onColorFor(primary),
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onColorFor(primaryContainer),
            tertiary = primary,
            onTertiary = onColorFor(primary),
            background = tintNeutral(0xFF121212, 0.06f),
            onBackground = Color(0xFFEDEDED),
            surface = tintNeutral(0xFF161616, 0.05f),
            onSurface = Color(0xFFEDEDED),
            surfaceVariant = tintNeutral(0xFF2B2B2E, 0.10f),
            onSurfaceVariant = Color(0xFFC7C6CA),
            outline = tintNeutral(0xFF8A8A8E, 0.22f),
            outlineVariant = tintNeutral(0xFF444448, 0.15f),
            inverseSurface = Color(0xFFEDEDED),
            inverseOnSurface = Color(0xFF1B1B1B),
        )
    } else {
        val primaryContainer = towardWhite(0.85f)
        lightColorScheme(
            primary = seed,
            onPrimary = onColorFor(seed),
            primaryContainer = primaryContainer,
            onPrimaryContainer = towardBlack(0.35f),
            secondary = seed,
            onSecondary = onColorFor(seed),
            secondaryContainer = primaryContainer,
            onSecondaryContainer = towardBlack(0.35f),
            tertiary = seed,
            onTertiary = onColorFor(seed),
            background = tintNeutral(0xFFFDFDFD, 0.035f),
            onBackground = Color(0xFF1B1B1B),
            surface = tintNeutral(0xFFFFFFFF, 0.03f),
            onSurface = Color(0xFF1B1B1B),
            surfaceVariant = tintNeutral(0xFFF0EFF2, 0.10f),
            onSurfaceVariant = Color(0xFF48454E),
            outline = tintNeutral(0xFF79747E, 0.18f),
            outlineVariant = tintNeutral(0xFFCAC4CF, 0.12f),
            inverseSurface = Color(0xFF1B1B1B),
            inverseOnSurface = Color(0xFFF5F5F5),
        )
    }
}
