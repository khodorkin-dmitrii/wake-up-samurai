package com.yavin.wakeupsamurai.ui.alarmlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
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
) : ViewModel() {
    private val refresh = MutableStateFlow(0L)
    private val _uiState = MutableStateFlow(AlarmListUiState())
    val uiState: StateFlow<AlarmListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeAll(), refresh) { alarms, _ ->
                val now = timeProvider.now()
                val zone = timeProvider.zoneId()
                AlarmListUiState(alarms = alarms.map { row ->
                    val alarm = row.alarm
                    val audio = row.audioAsset
                    val problem = when {
                        audio == null -> "Add or generate audio before enabling."
                        !storage.isReadable(audio.relativePath) -> "The selected audio file is unavailable."
                        alarm.audioSelectionKind != AudioSelectionKind.SAVED_ASSET &&
                            audio.sourceText != alarm.messageText.trim() ->
                            "The message changed; regenerate audio or restore its text."
                        else -> null
                    }
                    val next = if (alarm.repeatDays.isEmpty()) alarm.resolvedOneShotAt else {
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
                    )
                })
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
            repository.save(
                alarm.copy(
                    configuredEnabled = enabled,
                    resolvedOneShotAt = if (enabled && alarm.repeatDays.isEmpty()) {
                        calculator.oneShotStrictlyAfter(now, timeProvider.zoneId(), alarm.time)
                    } else alarm.resolvedOneShotAt,
                    updatedAt = now,
                ),
            )
        }
    }

    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }
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
