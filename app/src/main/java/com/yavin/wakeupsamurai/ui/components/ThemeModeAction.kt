package com.yavin.wakeupsamurai.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode

@Composable
fun ThemeModeAction(
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
) {
    var buttonCenter by remember { mutableStateOf(Offset.Unspecified) }
    IconButton(
        onClick = { onCycleThemeMode(buttonCenter) },
        modifier = Modifier.onGloballyPositioned { coordinates ->
            buttonCenter = coordinates.boundsInRoot().center
        },
    ) {
        Icon(
            painter = painterResource(themeMode.iconRes),
            contentDescription = stringResource(themeMode.contentDescriptionRes),
        )
    }
}

private val AppThemeMode.iconRes: Int
    get() = when (this) {
        AppThemeMode.SYSTEM -> R.drawable.ic_routine_24
        AppThemeMode.LIGHT -> R.drawable.ic_light_mode_24
        AppThemeMode.DARK -> R.drawable.ic_dark_mode_24
    }

private val AppThemeMode.contentDescriptionRes: Int
    get() = when (this) {
        AppThemeMode.SYSTEM -> R.string.theme_system
        AppThemeMode.LIGHT -> R.string.theme_light
        AppThemeMode.DARK -> R.string.theme_dark
    }
