package com.myrunningapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    primaryContainer = GreenContainer,
    secondary = AmberAccent,
    surface = NeutralSurface,
    background = NeutralSurface,
)

private val DarkColors = darkColorScheme(
    primary = GreenPrimaryDark,
    primaryContainer = GreenContainerDark,
    secondary = AmberAccentDark,
    surface = NeutralSurfaceDark,
    background = NeutralSurfaceDark,
)

@Composable
fun MyRunningTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You dynamic colour on Android 12+, our palette otherwise.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
