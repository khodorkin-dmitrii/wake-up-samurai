package com.yavin.wakeupsamurai.ui.alarmlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.alarm.AlarmSaveResult
import com.yavin.wakeupsamurai.alarm.OneShotAlarmCoordinator
import com.yavin.wakeupsamurai.alarm.FullScreenIntentAccess
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioSelectionKind
import com.yavin.wakeupsamurai.domain.alarm.NextOccurrenceCalculator
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val repository: AlarmRepository,
    private val storage: AudioAssetFileStore,
    private val calculator: NextOccurrenceCalculator,
    private val timeProvider: TimeProvider,
    private val alarmCoordinator: OneShotAlarmCoordinator,
    private val fullScreenIntentAccess: FullScreenIntentAccess,
) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    private val _uiState = MutableStateFlow(AlarmListUiState())
    val uiState: StateFlow<AlarmListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeAll(), refresh) { alarms, _ ->
                val transient = _uiState.value
                val now = timeProvider.now()
                val zone = timeProvider.zoneId()
                AlarmListUiState(
                    alarms = alarms.map { row ->
                    val alarm = row.alarm
                    val audio = row.audioAsset
                    val problem = when {
                        audio == null -> "Add or generate audio before enabling."
                        !storage.isReadable(audio.relativePath) -> "The selected audio file is unavailable."
                        alarm.audioSelectionKind != AudioSelectionKind.SAVED_ASSET &&
                            audio.sourceText != alarm.messageText.trim() ->
                            "The message changed; regenerate audio or restore its text."
                        alarm.repeatDays.isNotEmpty() && alarm.configuredEnabled ->
                            "Recurring execution is not supported yet."
                        alarm.configuredEnabled && alarm.schedulingState.name.startsWith("BLOCKED") ->
                            "System access is needed before this alarm can ring."
                        alarm.schedulingState == AlarmSchedulingState.SNOOZED -> null
                        alarm.configuredEnabled && alarm.schedulingState != com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState.SCHEDULED ->
                            "This alarm has not been scheduled yet. Open it and save again."
                        else -> null
                    }
                    val next = if (alarm.schedulingState == AlarmSchedulingState.SNOOZED) {
                        alarm.scheduledAt
                    } else if (alarm.repeatDays.isEmpty()) alarm.resolvedOneShotAt else {
                        calculator.recurringStrictlyAfter(now, zone, alarm.time, alarm.repeatDays)
                    }
                    AlarmListItemUi(
                        id = alarm.id,
                        time = alarm.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        repeatSummary = repeatSummary(alarm.repeatDays),
                        title = alarm.label.ifBlank { alarm.messageText.ifBlank { "Untitled alarm" } },
                        nextOccurrence = occurrenceSummary(next, now, zone),
                        configuredEnabled = alarm.configuredEnabled,
                        readinessProblem = problem,
                        schedulingState = alarm.schedulingState.name,
                        snoozedUntil = if (alarm.schedulingState == AlarmSchedulingState.SNOOZED) {
                            alarm.scheduledAt?.let { occurrenceSummary(it, now, zone) }
                        } else null,
                    )
                    },
                    message = transient.message,
                    schedulingAccessRequest = transient.schedulingAccessRequest,
                    fullScreenIntentUnavailable = alarms.any { it.alarm.configuredEnabled } &&
                        !fullScreenIntentAccess.canUseFullScreenIntent(),
                    alarmChannelNotHighImportance = alarms.any { it.alarm.configuredEnabled } &&
                        !fullScreenIntentAccess.isAlarmChannelHighImportance(),
                )
            }.collect(_uiState)
        }
    }

    fun refreshTime() { refresh.value++ }

    fun setConfiguredEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val row = repository.get(id) ?: return@launch
            val alarm = row.alarm
            if (enabled) {
                val asset = row.audioAsset
                val problem = when {
                    asset == null -> "Generate audio before enabling this alarm."
                    !storage.isReadable(asset.relativePath) -> "The audio file is unavailable."
                    alarm.audioSelectionKind != AudioSelectionKind.SAVED_ASSET &&
                        asset.sourceText != alarm.messageText.trim() ->
                        "Regenerate audio or restore its original text first."
                    else -> null
                }
                if (problem != null) {
                    _uiState.value = _uiState.value.copy(message = problem)
                    return@launch
                }
            }
            val now = timeProvider.now()
            when (val result = alarmCoordinator.save(
                alarm.copy(
                    configuredEnabled = enabled,
                    resolvedOneShotAt = if (enabled && alarm.repeatDays.isEmpty()) {
                        calculator.oneShotStrictlyAfter(now, timeProvider.zoneId(), alarm.time)
                    } else alarm.resolvedOneShotAt,
                    updatedAt = now,
                ),
            )) {
                is AlarmSaveResult.Scheduled -> Unit
                is AlarmSaveResult.SavedWithoutSchedule -> _uiState.value = _uiState.value.copy(
                    message = if (enabled) "Recurring alarm execution is not supported yet." else null,
                )
                is AlarmSaveResult.BlockedExactAlarmAccess -> {
                    awaitingAccessAlarmId = result.alarm.id
                    _uiState.value = _uiState.value.copy(
                        message = "Allow alarms & reminders to schedule this alarm.",
                        schedulingAccessRequest = SchedulingAccessRequest.EXACT_ALARM,
                    )
                }
                is AlarmSaveResult.BlockedNotificationPermission -> {
                    awaitingAccessAlarmId = result.alarm.id
                    _uiState.value = _uiState.value.copy(
                        message = "Allow notifications for the alarm Dismiss action.",
                        schedulingAccessRequest = SchedulingAccessRequest.NOTIFICATIONS,
                    )
                }
                is AlarmSaveResult.BlockedNotificationsDisabled -> {
                    awaitingAccessAlarmId = result.alarm.id
                    _uiState.value = _uiState.value.copy(
                        message = "Enable app notifications to keep alarm controls available.",
                        schedulingAccessRequest = SchedulingAccessRequest.APP_NOTIFICATIONS,
                    )
                }
                is AlarmSaveResult.BlockedAlarmChannel -> {
                    awaitingAccessAlarmId = result.alarm.id
                    _uiState.value = _uiState.value.copy(
                        message = "Enable the Ringing alarms channel to keep notification controls available.",
                        schedulingAccessRequest = SchedulingAccessRequest.ALARM_CHANNEL,
                    )
                }
                is AlarmSaveResult.Failed -> _uiState.value = _uiState.value.copy(message = result.message)
            }
        }
    }

    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }

    fun cancelSnooze(id: String) {
        viewModelScope.launch { alarmCoordinator.cancelSnooze(id) }
    }

    private var awaitingAccessAlarmId: String? = null

    fun onSchedulingAccessReturned() {
        val id = awaitingAccessAlarmId ?: return
        retryScheduling(id)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.value = _uiState.value.copy(
                message = "Notifications are required for the Dismiss action.",
                schedulingAccessRequest = null,
            )
            return
        }
        awaitingAccessAlarmId?.let(::retryScheduling)
    }

    private fun retryScheduling(id: String) {
        viewModelScope.launch {
            when (val result = alarmCoordinator.retry(id)) {
                is AlarmSaveResult.Scheduled -> awaitingAccessAlarmId = null
                is AlarmSaveResult.BlockedExactAlarmAccess -> _uiState.value = _uiState.value.copy(message = "Alarm access is still disabled.")
                is AlarmSaveResult.BlockedNotificationPermission -> _uiState.value = _uiState.value.copy(
                    message = "Notification permission is still required.",
                    schedulingAccessRequest = SchedulingAccessRequest.NOTIFICATIONS,
                )
                is AlarmSaveResult.BlockedNotificationsDisabled -> _uiState.value = _uiState.value.copy(
                    message = "App notifications are still disabled.",
                    schedulingAccessRequest = SchedulingAccessRequest.APP_NOTIFICATIONS,
                )
                is AlarmSaveResult.BlockedAlarmChannel -> _uiState.value = _uiState.value.copy(
                    message = "The Ringing alarms channel is still disabled.",
                    schedulingAccessRequest = SchedulingAccessRequest.ALARM_CHANNEL,
                )
                is AlarmSaveResult.SavedWithoutSchedule -> Unit
                is AlarmSaveResult.Failed -> _uiState.value = _uiState.value.copy(message = result.message)
            }
        }
    }
}

private fun repeatSummary(days: Set<DayOfWeek>): String = if (days.isEmpty()) "Once" else {
    DayOfWeek.entries.filter { it in days }.joinToString(", ") {
        it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

private fun occurrenceSummary(instant: Instant?, now: Instant, zone: ZoneId): String {
    if (instant == null) return "No upcoming occurrence"
    if (!instant.isAfter(now)) return "Occurrence passed — save or re-enable to choose the next one"
    val zoned = instant.atZone(zone)
    val date = zoned.toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val prefix = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }
    return "$prefix at ${zoned.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))}"
}
