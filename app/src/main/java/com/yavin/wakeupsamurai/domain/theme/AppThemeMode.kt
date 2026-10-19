package com.yavin.wakeupsamurai.domain.theme

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun next(): AppThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}
