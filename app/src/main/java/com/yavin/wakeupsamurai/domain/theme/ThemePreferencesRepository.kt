package com.yavin.wakeupsamurai.domain.theme

import kotlinx.coroutines.flow.Flow

interface ThemePreferencesRepository {
    val themeMode: Flow<AppThemeMode>

    suspend fun saveThemeMode(mode: AppThemeMode)
}
