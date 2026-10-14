package com.yavin.wakeupsamurai

import android.app.Application
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import com.yavin.wakeupsamurai.data.audio.AudioAssetStorage
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class WakeUpSamuraiApplication : Application() {
    @Inject lateinit var audioAssetRepository: AudioAssetRepository
    @Inject lateinit var audioAssetStorage: AudioAssetStorage

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            audioAssetStorage.discardAbandonedTemporaryRecordings()
            audioAssetRepository.importExistingCompletedFiles()
        }
    }
}
