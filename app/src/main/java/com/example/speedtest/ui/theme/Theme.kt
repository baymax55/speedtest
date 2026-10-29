package com.example.speedtest.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentCyan,
    tertiary = AccentPurple,
    background = DarkBackground,
    onBackground = Color(0xFFE9EEF9),
    surface = DarkSurface,
    onSurface = Color(0xFFE9EEF9),
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = Color(0xFFA9B6CE),
    outline = Color(0xFF35415C)
)

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentCyan,
    tertiary = AccentPurple,
    background = LightBackground,
    onBackground = Color(0xFF101828),
    surface = LightSurface,
    onSurface = Color(0xFF101828),
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = Color(0xFF54607A),
    outline = Color(0xFFCBD6E8)
)

@Composable
fun SpeedtestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
