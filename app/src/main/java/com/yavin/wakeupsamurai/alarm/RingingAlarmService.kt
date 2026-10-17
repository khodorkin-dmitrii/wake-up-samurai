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

@AndroidEntryPoint
class RingingAlarmService : Service() {
    @Inject lateinit var repository: AlarmRepository
    @Inject lateinit var storage: AudioAssetFileStore
    @Inject lateinit var usageRegistry: AudioAssetUsageRegistry
    @Inject lateinit var preview: AudioPreview
    @Inject lateinit var voiceRecorder: VoiceRecorder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var active: ActiveRinging? = null
    private var timeoutJob: Job? = null
    private var fallbackPlaying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return START_NOT_STICKY
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return START_NOT_STICKY
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return START_NOT_STICKY
        val delivery = ActiveRinging(alarmId, occurrenceId, revision)
        if (intent.action == ACTION_STOP_AFTER_SNOOZE) {
            if (active == delivery) stopPlaybackAfterSnooze()
            else if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_SNOOZE_FAILED) {
            if (active == delivery) startForegroundNow(delivery, intent.getStringExtra(EXTRA_STATUS_MESSAGE))
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_DISMISS) {
            // A notification action can revive a process after the player died; satisfy FGS startup first.
            if (active == null) startForegroundNow(delivery)
            if (active == delivery) stopRinging(AlarmSchedulingState.COMPLETED)
            else {
                finishWithoutPlayback(delivery, AlarmSchedulingState.COMPLETED)
                if (active == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (active != null) {
            // Temporary simultaneous-alarm policy: retain the first one and mark later delivery skipped.
            finishWithoutPlayback(delivery, AlarmSchedulingState.SKIPPED)
            return START_NOT_STICKY
        }
        active = delivery
        startForegroundNow(delivery)
        preview.stop()
        voiceRecorder.discard()
        serviceScope.launch {
            val row = withContext(Dispatchers.IO) { repository.get(alarmId) }
            if (active != delivery || row == null) return@launch
            val asset = row.audioAsset
            val file = asset?.takeIf { storage.isReadable(it.relativePath) }?.let { storage.resolve(it.relativePath) }
            asset?.id?.let(usageRegistry::setRingingAsset)
            play(file)
            timeoutJob = launch {
                delay(MAX_RINGING_MILLIS)
                if (active == delivery) stopRinging(AlarmSchedulingState.COMPLETED)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        player?.release()
        player = null
        usageRegistry.setRingingAsset(null)
        super.onDestroy()
    }

    private fun play(file: File?) {
        fallbackPlaying = file == null
        playUri(file?.let(android.net.Uri::fromFile) ?: fallbackUri())
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
                playUri(fallbackUri())
            }
        }
    }

    private fun stopRinging(finalState: AlarmSchedulingState) {
        val delivery = active ?: return
        active = null
        timeoutJob?.cancel()
        timeoutJob = null
        player?.stop()
        player?.release()
        player = null
        usageRegistry.setRingingAsset(null)
        finishWithoutPlayback(delivery, finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPlaybackAfterSnooze() {
        active = null
        timeoutJob?.cancel()
        timeoutJob = null
        player?.stop()
        player?.release()
        player = null
        usageRegistry.setRingingAsset(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishWithoutPlayback(delivery: ActiveRinging, finalState: AlarmSchedulingState) {
        serviceScope.launch(Dispatchers.IO) {
            repository.finishOccurrence(delivery.alarmId, delivery.occurrenceId, delivery.revision, finalState)
        }
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
            .setSilent(true)
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
    }

    companion object {
        private const val CHANNEL_ID = RingingNotificationChannel.ID
        private const val NOTIFICATION_ID = 5001
        const val ACTION_DISMISS = "com.yavin.wakeupsamurai.action.DISMISS_ALARM"
        const val ACTION_SNOOZE = "com.yavin.wakeupsamurai.action.SNOOZE_ALARM"
        private const val ACTION_STOP_AFTER_SNOOZE = "com.yavin.wakeupsamurai.action.STOP_AFTER_SNOOZE"
        private const val ACTION_SNOOZE_FAILED = "com.yavin.wakeupsamurai.action.SNOOZE_FAILED"
        const val ACTION_SHOW_RINGING = "com.yavin.wakeupsamurai.action.SHOW_RINGING"
        private const val EXTRA_STATUS_MESSAGE = "status_message"
        private const val MAX_RINGING_MILLIS = 5 * 60 * 1_000L

        fun startIntent(context: Context, alarmId: String, occurrenceId: String, revision: Long): Intent =
            Intent(context, RingingAlarmService::class.java)
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
