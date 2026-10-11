package com.yavin.wakeupsamurai.di

import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.audio.Media3AudioPreview
import com.yavin.wakeupsamurai.tts.EdgeSpeechGeneration
import com.yavin.wakeupsamurai.tts.SpeechGeneration
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSpeechGeneration(implementation: EdgeSpeechGeneration): SpeechGeneration

    @Binds
    @Singleton
    abstract fun bindAudioPreview(implementation: Media3AudioPreview): AudioPreview
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
}
