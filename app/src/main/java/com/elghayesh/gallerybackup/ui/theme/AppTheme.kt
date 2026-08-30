package com.elghayesh.gallerybackup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.ThemeMode

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
    val seed = Color(accentColor.seed)
    val colorScheme = if (useDarkTheme) {
        darkColorScheme(primary = seed, secondary = seed, tertiary = seed)
    } else {
        lightColorScheme(primary = seed, secondary = seed, tertiary = seed)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
