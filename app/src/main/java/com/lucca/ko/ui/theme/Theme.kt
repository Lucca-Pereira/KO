package com.lucca.ko.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Green = Color(0xFF2E7D32)
private val GreenDark = Color(0xFF7FD98A)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F2B8),
    onPrimaryContainer = Color(0xFF00210B),
    secondary = Color(0xFF52634F),
    background = Color(0xFFFBFDF8),
    surface = Color(0xFFFBFDF8),
    surfaceVariant = Color(0xFFDDE5DA),
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFB6F2B8),
    secondary = Color(0xFFB9CCB4),
    background = Color(0xFF111411),
    surface = Color(0xFF111411),
    surfaceVariant = Color(0xFF424940),
)

/** Semantic colours for ingredient availability, resolved against the theme. */
object AppColors {
    val haveLight = Color(0xFF1B7F3B)
    val haveDark = Color(0xFF7FD98A)
    val missingLight = Color(0xFFC62828)
    val missingDark = Color(0xFFFF9A8A)
    val lowLight = Color(0xFFB26A00)
    val lowDark = Color(0xFFFFC46B)
}

@Composable
fun KoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
