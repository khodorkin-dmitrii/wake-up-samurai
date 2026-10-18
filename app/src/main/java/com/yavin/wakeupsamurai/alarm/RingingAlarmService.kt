package com.yavin.wakeupsamurai.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.audio.VoiceRecorder
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import android.util.Log

@AndroidEntryPoint
class RingingAlarmService : Service() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var storage: AudioAssetFileStore
    @Inject lateinit var usageRegistry: AudioAssetUsageRegistry
    @Inject lateinit var preview: AudioPreview
    @Inject lateinit var voiceRecorder: VoiceRecorder
    @Inject lateinit var coordinator: OneShotAlarmCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var active: ActiveRinging? = null
    private var timeoutJob: Job? = null
    private var observationJob: Job? = null
    private var startingJob: Job? = null
    private var terminalJob: Job? = null
    private var fallbackPlaying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return START_NOT_STICKY
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return START_NOT_STICKY
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return START_NOT_STICKY
        val delivery = ActiveRinging(alarmId, occurrenceId, revision)
        if (intent.action == ACTION_STOP_AFTER_SNOOZE) {
            if (active == delivery) stopPlaybackAndSelf()
            else if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_SNOOZE_FAILED) {
            if (active == delivery) runCatching {
                startForegroundNow(delivery, intent.getStringExtra(EXTRA_STATUS_MESSAGE))
            } else if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_DISMISS) {
            if (active != null && active != delivery) return START_NOT_STICKY
            if (active == null && !startForegroundSafely(delivery)) return START_NOT_STICKY
            completeRinging(delivery, AlarmSchedulingState.COMPLETED)
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START) return START_NOT_STICKY
        if (active == delivery) return START_REDELIVER_INTENT
        if (active != null) {
            val prior = requireNotNull(active)
            serviceScope.launch {
                runCatching {
                    if (coordinator.ringingStatus(prior.toOccurrence()) != null) {
                        coordinator.finishOccurrence(alarmId, occurrenceId, revision, AlarmSchedulingState.SKIPPED)
                    } else {
                        stopPlaybackAndSelf()
                        // The trigger was accepted after the old session became stale; Android may redeliver it.
                        runCatching { ContextCompat.startForegroundService(
                            this@RingingAlarmService, startIntent(this@RingingAlarmService, alarmId, occurrenceId, revision),
                        ) }.onFailure {
                            coordinator.finishOccurrence(alarmId, occurrenceId, revision, AlarmSchedulingState.FAILED,
                                "Android could not start alarm playback. Check alarm and notification access.")
                        }
                    }
                }.onFailure { Log.w("RingingAlarm", "Could not resolve competing alarm", it) }
            }
            return START_REDELIVER_INTENT
        }
        active = delivery
        if (!startForegroundSafely(delivery)) return START_NOT_STICKY
        startingJob = serviceScope.launch {
            try {
                val status = coordinator.ringingStatus(delivery.toOccurrence())
                if (active != delivery || status == null || terminalJob != null) {
                    stopPlaybackAndSelf()
                    return@launch
                }
                timeoutJob = launch {
                    delay(status.remainingMillis)
                    if (active == delivery) completeRinging(delivery, AlarmSchedulingState.COMPLETED,
                        stopOnDatabaseFailure = true)
                }
                observationJob = launch {
                    repository.observeAll().collectLatest { rows ->
                        if (active == delivery && rows.none { it.alarm.id == alarmId &&
                                it.alarm.ringingOccurrenceId == occurrenceId &&
                                it.alarm.ringingRevision == revision }) stopPlaybackAndSelf()
                    }
                }
                preview.stop()
                voiceRecorder.discard()
                val row = withContext(Dispatchers.IO) { repository.get(alarmId) }
                if (active != delivery || terminalJob != null || row?.alarm?.ringingOccurrenceId != occurrenceId ||
                    row.alarm.ringingRevision != revision) return@launch
                val asset = row.audioAsset
                val file = withContext(Dispatchers.IO) {
                    asset?.takeIf { storage.isReadable(it.relativePath) }?.let { storage.resolve(it.relativePath) }
                }
                if (active != delivery || terminalJob != null) return@launch
                asset?.id?.let(usageRegistry::setRingingAsset)
                play(file)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (active == delivery) failPlayback(delivery,
                    "Alarm audio could not start; check the saved file and notification access.")
                Log.w("RingingAlarm", "Alarm playback setup failed", exception)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        active = null
        serviceScope.cancel()
        releaseAudio()
        super.onDestroy()
    }

    private fun play(file: File?) {
        fallbackPlaying = file == null
        try {
            playUri(file?.let(Uri::fromFile) ?: fallbackUri())
        } catch (error: Exception) {
            if (fallbackPlaying) throw error
            fallbackPlaying = true
            playUri(fallbackUri())
        }
    }

    private fun playUri(uri: android.net.Uri) {
        val currentPlayer = player ?: ExoPlayer.Builder(this).build().also {
            it.addListener(FallbackOnErrorListener())
            player = it
        }
        currentPlayer.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_ALARM)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SONIFICATION)
                .build(),
            true,
        )
        currentPlayer.repeatMode = Player.REPEAT_MODE_ONE
        currentPlayer.setMediaItem(MediaItem.fromUri(uri))
        currentPlayer.prepare()
        currentPlayer.play()
    }

    private fun fallbackUri(): android.net.Uri =
        android.net.Uri.parse("android.resource://$packageName/${R.raw.fallback_alarm}")

    private inner class FallbackOnErrorListener : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!fallbackPlaying && active != null) {
                fallbackPlaying = true
                runCatching { playUri(fallbackUri()) }.onFailure {
                    active?.let { delivery -> failPlayback(delivery,
                        "The saved audio and backup sound could not play.") }
                }
            } else active?.let { delivery -> failPlayback(delivery,
                "The saved audio and backup sound could not play.") }
        }
    }

    private fun failPlayback(delivery: ActiveRinging, message: String) {
        completeRinging(delivery, AlarmSchedulingState.FAILED, message, stopOnDatabaseFailure = true)
    }

    private fun completeRinging(
        delivery: ActiveRinging,
        finalState: AlarmSchedulingState,
        failureMessage: String? = null,
        stopOnDatabaseFailure: Boolean = false,
    ) {
        if (terminalJob?.isActive == true) return
        startingJob?.cancel()
        terminalJob = serviceScope.launch {
            val completed = runCatching {
                coordinator.finishOccurrence(delivery.alarmId, delivery.occurrenceId, delivery.revision,
                    finalState, failureMessage)
            }.getOrDefault(false)
            val stillCurrent = if (completed) false else runCatching {
                coordinator.ringingStatus(delivery.toOccurrence()) != null
            }.getOrDefault(true)
            if (completed || !stillCurrent || stopOnDatabaseFailure || active != delivery) {
                if (active == delivery) stopPlaybackAndSelf()
                else if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } else {
                terminalJob = null
                if (active == delivery) runCatching {
                    startForegroundNow(delivery, "Couldn't dismiss this alarm. Please try again.")
                }
            }
        }
    }

    private fun stopPlaybackAndSelf() {
        active = null
        startingJob?.cancel()
        startingJob = null
        observationJob?.cancel()
        observationJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        releaseAudio()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAudio() {
        try {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        } finally {
            player = null
            usageRegistry.setRingingAsset(null)
        }
    }

    private fun startForegroundSafely(delivery: ActiveRinging): Boolean = try {
        startForegroundNow(delivery)
        true
    } catch (_: Exception) {
        serviceScope.launch {
            try {
                coordinator.finishOccurrence(delivery.alarmId, delivery.occurrenceId, delivery.revision,
                    AlarmSchedulingState.FAILED,
                    "Android could not show the alarm notification. Check notification access.")
            } finally {
                stopPlaybackAndSelf()
            }
        }
        false
    }

    private fun startForegroundNow(delivery: ActiveRinging, statusMessage: String? = null) {
        createChannel()
        val screenIntent = PendingIntent.getActivity(
            this,
            "ringing:${delivery.alarmId}:${delivery.occurrenceId}".hashCode(),
            Intent(this, com.yavin.wakeupsamurai.RingingActivity::class.java)
                .setAction(ACTION_SHOW_RINGING)
                .setData(Uri.parse("wakeupsamurai://ringing/${delivery.alarmId}/${delivery.occurrenceId}"))
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID, delivery.alarmId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID, delivery.occurrenceId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, delivery.revision),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            this,
            delivery.requestCode(),
            Intent(this, AlarmDismissReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID, delivery.alarmId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID, delivery.occurrenceId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, delivery.revision),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            this,
            "snooze:${delivery.alarmId}:${delivery.occurrenceId}".hashCode(),
            Intent(this, AlarmSnoozeReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID, delivery.alarmId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID, delivery.occurrenceId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, delivery.revision),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Wake Up, Samurai")
            .setContentText(statusMessage ?: "Your alarm is ringing")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(screenIntent)
            .setFullScreenIntent(screenIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Dismiss", dismissIntent)
            .addAction(0, "Snooze 5 min", snoozeIntent)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        RingingNotificationChannel.ensureAndIsEnabled(this)
    }

    private data class ActiveRinging(val alarmId: String, val occurrenceId: String, val revision: Long) {
        fun requestCode(): Int = "$alarmId:$occurrenceId".hashCode()
        fun toOccurrence() = RingingOccurrence(alarmId, occurrenceId, revision)
    }

    companion object {
        private const val CHANNEL_ID = RingingNotificationChannel.ID
        private const val NOTIFICATION_ID = 5001
        const val ACTION_DISMISS = "com.yavin.wakeupsamurai.action.DISMISS_ALARM"
        private const val ACTION_START = "com.yavin.wakeupsamurai.action.START_ALARM"
        const val ACTION_SNOOZE = "com.yavin.wakeupsamurai.action.SNOOZE_ALARM"
        private const val ACTION_STOP_AFTER_SNOOZE = "com.yavin.wakeupsamurai.action.STOP_AFTER_SNOOZE"
        private const val ACTION_SNOOZE_FAILED = "com.yavin.wakeupsamurai.action.SNOOZE_FAILED"
        const val ACTION_SHOW_RINGING = "com.yavin.wakeupsamurai.action.SHOW_RINGING"
        private const val EXTRA_STATUS_MESSAGE = "status_message"

        fun startIntent(context: Context, alarmId: String, occurrenceId: String, revision: Long): Intent =
            Intent(context, RingingAlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID, occurrenceId)
                .putExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, revision)

        fun dismissIntent(context: Context, alarmId: String, occurrenceId: String, revision: Long): Intent =
            startIntent(context, alarmId, occurrenceId, revision).setAction(ACTION_DISMISS)

        fun stopAfterSnoozeIntent(context: Context, alarmId: String, occurrenceId: String, revision: Long): Intent =
            startIntent(context, alarmId, occurrenceId, revision).setAction(ACTION_STOP_AFTER_SNOOZE)

        fun snoozeFailedIntent(
            context: Context,
            alarmId: String,
            occurrenceId: String,
            revision: Long,
            message: String,
        ): Intent = startIntent(context, alarmId, occurrenceId, revision)
            .setAction(ACTION_SNOOZE_FAILED)
            .putExtra(EXTRA_STATUS_MESSAGE, message)
    }
}
