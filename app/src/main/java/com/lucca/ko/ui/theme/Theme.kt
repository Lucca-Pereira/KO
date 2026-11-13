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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/*
 * Charcoal surfaces with a single coral accent. Every surface role is set explicitly: Material 3's
 * defaults for the `surfaceContainer*` family are purple-tinted, and drawers, sheets, cards and
 * menus all read from them — leaving any out shows up as an off-colour panel.
 */

private val Coral = Color(0xFFF0683E)
private val CoralDeep = Color(0xFFC8481F)

private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF2A0F05),
    primaryContainer = Color(0xFF4A2418),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFD8C2BA),
    onSecondary = Color(0xFF3B2D28),
    secondaryContainer = Color(0xFF3A2620),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFF6AA3E0),
    onTertiary = Color(0xFF0A2A4A),
    tertiaryContainer = Color(0xFF2F4F8F),
    onTertiaryContainer = Color(0xFFDCE9FF),
    background = Color(0xFF1B1B1D),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF1B1B1D),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF333337),
    onSurfaceVariant = Color(0xFF9B9BA1),
    surfaceTint = Coral,
    outline = Color(0xFF5A5A60),
    outlineVariant = Color(0xFF3A3A3E),
    surfaceDim = Color(0xFF161618),
    surfaceBright = Color(0xFF3A3A3E),
    surfaceContainerLowest = Color(0xFF161618),
    surfaceContainerLow = Color(0xFF222224),
    surfaceContainer = Color(0xFF29292C),
    surfaceContainerHigh = Color(0xFF2F2F33),
    surfaceContainerHighest = Color(0xFF38383C),
    error = Color(0xFFFF9A8A),
    onError = Color(0xFF4A0F08),
)

private val LightColors = lightColorScheme(
    primary = CoralDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0F03),
    secondary = Color(0xFF6E5A53),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE4DC),
    onSecondaryContainer = Color(0xFF3A0F03),
    tertiary = Color(0xFF2F5F9F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE9FF),
    onTertiaryContainer = Color(0xFF0A2A4A),
    background = Color(0xFFF7F5F2),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFF7F5F2),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFE6E2DD),
    onSurfaceVariant = Color(0xFF66666B),
    surfaceTint = CoralDeep,
    outline = Color(0xFF8C8882),
    outlineVariant = Color(0xFFD6D1CB),
    surfaceDim = Color(0xFFDFDCD7),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2EFEB),
    surfaceContainer = Color(0xFFEDE9E4),
    surfaceContainerHigh = Color(0xFFE7E3DE),
    surfaceContainerHighest = Color(0xFFE1DDD8),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Semantic colours for ingredient availability, resolved against the theme. */
object AppColors {
    val haveLight = Color(0xFF1B7F3B)
    val haveDark = Color(0xFF7FC77F)
    val missingLight = Color(0xFFC62828)
    val missingDark = Color(0xFFFF9A8A)
    val lowLight = Color(0xFFB26A00)
    val lowDark = Color(0xFFE0B040)
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
