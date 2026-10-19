package com.yavin.wakeupsamurai.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NightEmber,
    onPrimary = Color(0xFF4F210B),
    primaryContainer = NightEmberContainer,
    onPrimaryContainer = NightInk,
    background = NightBackground,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = EmberContainer,
    onPrimaryContainer = Ink,
    background = WarmPaper,
    onBackground = Ink,
    surface = WarmSurface,
    onSurface = Ink,
    surfaceVariant = WarmSurfaceVariant,
)

@Composable
fun WakeUpSamuraiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val activity = LocalActivity.current as? ComponentActivity
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT,
                detectDarkMode = { darkTheme },
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = LIGHT_NAVIGATION_BAR_SCRIM,
                darkScrim = DARK_NAVIGATION_BAR_SCRIM,
                detectDarkMode = { darkTheme },
            ),
        )
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}

private const val LIGHT_NAVIGATION_BAR_SCRIM = 0xE6FFFFFF.toInt()
private const val DARK_NAVIGATION_BAR_SCRIM = 0x801B1B1B.toInt()
