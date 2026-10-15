package com.yavin.wakeupsamurai.di

import android.content.Context
import androidx.room.Room
import com.yavin.wakeupsamurai.data.alarm.RoomAlarmRepository
import com.yavin.wakeupsamurai.data.alarm.RoomAudioAssetRepository
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.data.audio.AudioAssetStorage
import com.yavin.wakeupsamurai.data.audio.InMemoryAudioAssetUsageRegistry
import com.yavin.wakeupsamurai.data.audio.RoomAudioLibraryRepository
import com.yavin.wakeupsamurai.data.local.AlarmDao
import com.yavin.wakeupsamurai.data.local.AudioAssetDao
import com.yavin.wakeupsamurai.data.local.WakeUpSamuraiDatabase
import com.yavin.wakeupsamurai.data.local.MIGRATION_1_2
import com.yavin.wakeupsamurai.data.local.MIGRATION_2_3
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import com.yavin.wakeupsamurai.domain.alarm.AudioLibraryRepository
import com.yavin.wakeupsamurai.domain.alarm.SystemTimeProvider
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import com.yavin.wakeupsamurai.alarm.AndroidOneShotAlarmScheduler
import com.yavin.wakeupsamurai.alarm.OneShotAlarmScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PersistenceBindingsModule {
    @Binds abstract fun bindAlarmRepository(implementation: RoomAlarmRepository): AlarmRepository
    @Binds abstract fun bindAudioAssetRepository(implementation: RoomAudioAssetRepository): AudioAssetRepository
    @Binds abstract fun bindAudioLibraryRepository(implementation: RoomAudioLibraryRepository): AudioLibraryRepository
    @Binds abstract fun bindAudioAssetUsageRegistry(implementation: InMemoryAudioAssetUsageRegistry): AudioAssetUsageRegistry
    @Binds abstract fun bindTimeProvider(implementation: SystemTimeProvider): TimeProvider
    @Binds abstract fun bindAudioAssetFileStore(implementation: AudioAssetStorage): AudioAssetFileStore
    @Binds abstract fun bindOneShotAlarmScheduler(implementation: AndroidOneShotAlarmScheduler): OneShotAlarmScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WakeUpSamuraiDatabase =
        Room.databaseBuilder(context, WakeUpSamuraiDatabase::class.java, "wake-up-samurai.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides fun provideAlarmDao(database: WakeUpSamuraiDatabase): AlarmDao = database.alarmDao()
    @Provides fun provideAudioAssetDao(database: WakeUpSamuraiDatabase): AudioAssetDao = database.audioAssetDao()
}
