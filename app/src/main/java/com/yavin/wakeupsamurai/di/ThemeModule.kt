package com.yavin.wakeupsamurai.di

import com.yavin.wakeupsamurai.data.theme.DataStoreThemePreferencesRepository
import com.yavin.wakeupsamurai.domain.theme.ThemePreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ThemeModule {
    @Binds
    abstract fun bindThemePreferencesRepository(
        implementation: DataStoreThemePreferencesRepository,
    ): ThemePreferencesRepository
}
