package com.yavin.wakeupsamurai.data.audio

import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class InMemoryAudioAssetUsageRegistry @Inject constructor() : AudioAssetUsageRegistry {
    private val mutex = Mutex()

    @Volatile
    private var editorDraftAssetId: String? = null

    override fun setEditorDraftAsset(assetId: String?) {
        editorDraftAssetId = assetId
    }

    override fun isInUse(assetId: String): Boolean = editorDraftAssetId == assetId

    override suspend fun <T> withWriteLock(block: suspend () -> T): T = mutex.withLock { block() }
}
