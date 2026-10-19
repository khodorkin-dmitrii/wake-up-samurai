package com.yavin.wakeupsamurai.ui.theme

import com.yavin.wakeupsamurai.domain.theme.AppThemeMode

fun AppThemeMode.resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
    AppThemeMode.SYSTEM -> systemInDarkTheme
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}
