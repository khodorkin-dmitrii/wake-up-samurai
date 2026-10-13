package com.yavin.wakeupsamurai.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yavin.wakeupsamurai.data.alarm.RoomAlarmRepository
import com.yavin.wakeupsamurai.data.alarm.RoomAudioAssetRepository
import com.yavin.wakeupsamurai.data.audio.AudioAssetStorage
import com.yavin.wakeupsamurai.data.local.WakeUpSamuraiDatabase
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AudioAsset
import com.yavin.wakeupsamurai.tts.AudioFileValidator
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlarmRoomIntegrationTest {
    @Test fun crudAndSharedAudioReferencesRetainAssetAfterAlarmDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, WakeUpSamuraiDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val storage = AudioAssetStorage(context, AudioFileValidator())
            val audioRepository = RoomAudioAssetRepository(database.audioAssetDao(), storage)
            val alarmRepository = RoomAlarmRepository(database.alarmDao())
            val asset = AudioAsset("asset", "speech/shared.mp3", "Wake up", "en-US-test", "en-US", Instant.EPOCH, 1_000)
            audioRepository.register(asset)
            val first = alarm("first", asset.id)
            val second = alarm("second", asset.id)
            alarmRepository.save(first)
            alarmRepository.save(second)

            assertEquals(2, alarmRepository.observeAll().first().size)
            alarmRepository.save(first.copy(label = "Updated"))
            assertEquals("Updated", alarmRepository.get("first")?.alarm?.label)
            alarmRepository.delete("first")

            assertNull(alarmRepository.get("first"))
            assertNotNull(alarmRepository.get("second")?.audioAsset)
            assertNotNull(audioRepository.get("asset"))
        } finally {
            database.close()
        }
    }

    @Test fun importsExistingCompletedFileAndAlarmDeletionKeepsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val completedFile = File(context.filesDir, "speech").listFiles()
            .orEmpty().firstOrNull { it.extension == "mp3" }
        assumeTrue("Requires a completed Step 2 MP3", completedFile != null)
        val file = requireNotNull(completedFile)
        val database = Room.databaseBuilder(context, WakeUpSamuraiDatabase::class.java, "wake-up-samurai.db").build()
        try {
            val storage = AudioAssetStorage(context, AudioFileValidator())
            val audioRepository = RoomAudioAssetRepository(database.audioAssetDao(), storage)
            val alarmRepository = RoomAlarmRepository(database.alarmDao())
            audioRepository.importExistingCompletedFiles()
            val asset = database.audioAssetDao().getByPath(storage.relativePath(file))
            assertNotNull(asset)
            val id = "retention-probe"
            alarmRepository.save(alarm(id, requireNotNull(asset).id))
            alarmRepository.delete(id)
            assertNull(alarmRepository.get(id))
            assertNotNull(audioRepository.get(asset.id))
            org.junit.Assert.assertTrue(file.isFile && file.length() > 0L)
            org.junit.Assert.assertTrue(AudioFileValidator().validate(file) > 0L)
        } finally {
            database.close()
        }
    }

    @Test fun alarmSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = AudioAssetStorage(context, AudioFileValidator())
        var database = Room.databaseBuilder(context, WakeUpSamuraiDatabase::class.java, "wake-up-samurai.db").build()
        val asset = AudioAsset(
            "reopen-asset", "speech/reopen-probe.mp3", "Persist me", "test", "en-US", Instant.EPOCH, 1_000,
        )
        RoomAudioAssetRepository(database.audioAssetDao(), storage).register(asset)
        RoomAlarmRepository(database.alarmDao()).save(alarm("reopen-alarm", asset.id))
        database.close()

        database = Room.databaseBuilder(context, WakeUpSamuraiDatabase::class.java, "wake-up-samurai.db").build()
        try {
            val repository = RoomAlarmRepository(database.alarmDao())
            assertNotNull(repository.get("reopen-alarm"))
            repository.delete("reopen-alarm")
            assertNotNull(RoomAudioAssetRepository(database.audioAssetDao(), storage).get(asset.id))
        } finally {
            database.close()
        }
    }

    private fun alarm(id: String, assetId: String) = Alarm(
        id, id, "Wake up", LocalTime.of(7, 0), emptySet(), false,
        Instant.parse("2026-10-14T07:00:00Z"), assetId, Instant.EPOCH, Instant.EPOCH,
    )
}
