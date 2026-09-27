/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.tricked.solidverdant.data.local.AppThemeMode

// Default light/dark palettes: a near-black (or blue-grey) page, grey
// entry cards, a light-blue accent and an orange-red stop button (the error role).
// surfaceContainerHighest is the card colour so default M3 cards render as entry cards; grey
// controls placed on cards (chips, tracks) use surfaceVariant; surfaceContainerHigh is the docked
// timer sheet.
private val ZenLightColorScheme = lightColorScheme(
    primary = Color(0xFF03A9F4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F5FE),
    onPrimaryContainer = Color(0xFF01579B),
    inversePrimary = Color(0xFF4FC3F7),
    secondary = Color(0xFF5F6B73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EAEE),
    onSecondaryContainer = Color(0xFF1F2A30),
    tertiary = Color(0xFFC76A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8CC),
    onTertiaryContainer = Color(0xFF4A2800),
    error = Color(0xFFF4511E),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3DA),
    onErrorContainer = Color(0xFF7A1E04),
    background = Color(0xFFF2F6F8),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFEDF1F4),
    onSurfaceVariant = Color(0xFF6B757B),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2B2B2B),
    inverseOnSurface = Color(0xFFF2F6F8),
    outline = Color(0xFFC6D2D9),
    outlineVariant = Color(0xFFE4EAEE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF2F6F8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
)

private val ZenDarkColorScheme = darkColorScheme(
    primary = Color(0xFF03A9F4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B3A52),
    onPrimaryContainer = Color(0xFFB3E5FC),
    inversePrimary = Color(0xFF0288D1),
    secondary = Color(0xFFB0B0B0),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF4A2E00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    error = Color(0xFFFF5722),
    onError = Color.White,
    errorContainer = Color(0xFF4D1A0C),
    onErrorContainer = Color(0xFFFFB9A3),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF383838),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF1E1E1E),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383838),
    surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF333333),
    surfaceContainerHighest = Color(0xFF2B2B2B),
)

private val VerdantLightColorScheme = lightColorScheme(
    primary = Color(0xFF386A20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F397),
    onPrimaryContainer = Color(0xFF0C2000),
    secondary = Color(0xFF55624C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7CB),
    onSecondaryContainer = Color(0xFF131F0D),
    tertiary = Color(0xFF386666),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBBEBEB),
    onTertiaryContainer = Color(0xFF002020),
    background = Color(0xFFFDFDF5),
    onBackground = Color(0xFF1A1C18),
    surface = Color(0xFFFDFDF5),
    onSurface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFFE0E4D8),
    onSurfaceVariant = Color(0xFF44483F),
    outline = Color(0xFF74796E),
)

private val NeoColorScheme = darkColorScheme(
    primary = Color(0xFF1EF3FF),
    onPrimary = Color(0xFF03050A),
    primaryContainer = Color(0xFF0D141F),
    onPrimaryContainer = Color(0xFF1EF3FF),
    secondary = Color(0xFFFCEE0A),
    onSecondary = Color(0xFF03050A),
    secondaryContainer = Color(0xFF242F16),
    onSecondaryContainer = Color(0xFFFCEE0A),
    tertiary = Color(0xFF4DFFB0),
    onTertiary = Color(0xFF03050A),
    tertiaryContainer = Color(0xFF0D1F1A),
    onTertiaryContainer = Color(0xFF4DFFB0),
    error = Color(0xFFFF1F6D),
    onError = Color(0xFF03050A),
    errorContainer = Color(0xFF3B0A1E),
    onErrorContainer = Color(0xFFFFB1C8),
    background = Color(0xFF03050A),
    onBackground = Color(0xFFF0F6FF),
    surface = Color(0xFF080D15),
    onSurface = Color(0xFFF0F6FF),
    surfaceVariant = Color(0xFF0D141F),
    onSurfaceVariant = Color(0xFF8298AE),
    surfaceDim = Color(0xFF03050A),
    surfaceBright = Color(0xFF182536),
    surfaceContainerLowest = Color(0xFF03050A),
    surfaceContainerLow = Color(0xFF080D15),
    surfaceContainer = Color(0xFF0D141F),
    surfaceContainerHigh = Color(0xFF111B29),
    surfaceContainerHighest = Color(0xFF182536),
    surfaceTint = Color(0xFF1EF3FF),
    outline = Color(0xFF3D5876),
    outlineVariant = Color(0xFF243549),
)

/** Resolves the colour scheme for [themeMode]; [darkTheme] only matters for [AppThemeMode.SYSTEM]/[AppThemeMode.DYNAMIC]. */
@Composable
private fun colorSchemeFor(themeMode: AppThemeMode, darkTheme: Boolean): ColorScheme = when (themeMode) {
    AppThemeMode.SYSTEM -> if (darkTheme) ZenDarkColorScheme else ZenLightColorScheme
    AppThemeMode.LIGHT -> ZenLightColorScheme
    AppThemeMode.DARK -> ZenDarkColorScheme
    AppThemeMode.VERDANT -> VerdantLightColorScheme
    AppThemeMode.NEO -> NeoColorScheme
    AppThemeMode.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        ZenDarkColorScheme
    } else {
        ZenLightColorScheme
    }
}

@Composable
fun SolidVerdantTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = colorSchemeFor(themeMode, darkTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)

            val useLightSystemBars = colorScheme.isLight
            insetsController.isAppearanceLightStatusBars = useLightSystemBars
            insetsController.isAppearanceLightNavigationBars = useLightSystemBars
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZenTypography,
        shapes = ZenShapes,
        content = content,
    )
}
