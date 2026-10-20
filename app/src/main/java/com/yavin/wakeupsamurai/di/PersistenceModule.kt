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
import com.yavin.wakeupsamurai.data.local.MIGRATION_3_4
import com.yavin.wakeupsamurai.data.local.MIGRATION_4_5
import com.yavin.wakeupsamurai.data.local.MIGRATION_5_6
import com.yavin.wakeupsamurai.data.local.MIGRATION_6_7
import com.yavin.wakeupsamurai.data.local.AlarmNamingStateDao
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import com.yavin.wakeupsamurai.domain.alarm.AudioLibraryRepository
import com.yavin.wakeupsamurai.domain.alarm.SystemTimeProvider
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import com.yavin.wakeupsamurai.alarm.AndroidOneShotAlarmScheduler
import com.yavin.wakeupsamurai.alarm.OneShotAlarmScheduler
import com.yavin.wakeupsamurai.alarm.AndroidRingingAlarmController
import com.yavin.wakeupsamurai.alarm.RingingAlarmController
import com.yavin.wakeupsamurai.alarm.AndroidFullScreenIntentAccess
import com.yavin.wakeupsamurai.alarm.FullScreenIntentAccess
import com.yavin.wakeupsamurai.alarm.AlarmBootClock
import com.yavin.wakeupsamurai.alarm.AndroidAlarmBootClock
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
    @Binds abstract fun bindRingingAlarmController(implementation: AndroidRingingAlarmController): RingingAlarmController
    @Binds abstract fun bindFullScreenIntentAccess(implementation: AndroidFullScreenIntentAccess): FullScreenIntentAccess
    @Binds abstract fun bindAlarmBootClock(implementation: AndroidAlarmBootClock): AlarmBootClock
}

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WakeUpSamuraiDatabase =
        Room.databaseBuilder(context, WakeUpSamuraiDatabase::class.java, "wake-up-samurai.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()

    @Provides fun provideAlarmDao(database: WakeUpSamuraiDatabase): AlarmDao = database.alarmDao()
    @Provides fun provideAudioAssetDao(database: WakeUpSamuraiDatabase): AudioAssetDao = database.audioAssetDao()
    @Provides fun provideAlarmNamingStateDao(database: WakeUpSamuraiDatabase): AlarmNamingStateDao = database.alarmNamingStateDao()
}
