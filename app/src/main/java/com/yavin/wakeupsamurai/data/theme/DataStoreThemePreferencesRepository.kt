package com.yavin.wakeupsamurai.data.theme

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import com.yavin.wakeupsamurai.domain.theme.ThemePreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.themePreferencesDataStore by preferencesDataStore(name = "theme_preferences")

@Singleton
class DataStoreThemePreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ThemePreferencesRepository {
    override val themeMode: Flow<AppThemeMode> = context.themePreferencesDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(Preferences::toAppThemeMode)

    override suspend fun saveThemeMode(mode: AppThemeMode) {
        context.themePreferencesDataStore.edit { preferences ->
            preferences[ThemePreferenceKeys.THEME_MODE] = mode.storageValue
        }
    }
}

private object ThemePreferenceKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
}

private val AppThemeMode.storageValue: String
    get() = name.lowercase()

private fun Preferences.toAppThemeMode(): AppThemeMode =
    this[ThemePreferenceKeys.THEME_MODE].toAppThemeMode()

private fun String?.toAppThemeMode(): AppThemeMode = when (this?.trim()?.lowercase()) {
    AppThemeMode.LIGHT.storageValue -> AppThemeMode.LIGHT
    AppThemeMode.DARK.storageValue -> AppThemeMode.DARK
    AppThemeMode.SYSTEM.storageValue -> AppThemeMode.SYSTEM
    else -> AppThemeMode.SYSTEM
}
