package com.yavin.wakeupsamurai.ui.ringing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.alarm.RingingAlarmController
import com.yavin.wakeupsamurai.alarm.RingingOccurrence
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface RingingUiState {
    data object Loading : RingingUiState
    data class Active(
        val occurrence: RingingOccurrence,
        val time: String,
        val title: String,
        val message: String?,
        val isDismissing: Boolean = false,
        val isSnoozing: Boolean = false,
        val actionError: String? = null,
    ) : RingingUiState
    data object Ended : RingingUiState
}

@HiltViewModel
class RingingViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val ringingController: RingingAlarmController,
) : ViewModel() {
    private val _uiState = MutableStateFlow<RingingUiState>(RingingUiState.Loading)
    val uiState: StateFlow<RingingUiState> = _uiState.asStateFlow()
    private var observation: Job? = null
    private var currentOccurrence: RingingOccurrence? = null

    fun observe(occurrence: RingingOccurrence?) {
        if (occurrence == null) {
            _uiState.value = RingingUiState.Ended
            return
        }
        if (occurrence == currentOccurrence && observation?.isActive == true) return
        currentOccurrence = occurrence
        observation?.cancel()
        _uiState.value = RingingUiState.Loading
        observation = viewModelScope.launch {
            alarmRepository.observeAll().collectLatest { alarms ->
                val row = alarms.firstOrNull { item ->
                    item.alarm.id == occurrence.alarmId &&
                        item.alarm.ringingOccurrenceId == occurrence.occurrenceId &&
                        item.alarm.ringingRevision == occurrence.revision
                }
                _uiState.value = row?.let {
                    RingingUiState.Active(
                        occurrence = occurrence,
                        time = it.alarm.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        title = it.alarm.label.ifBlank { "Wake up, Samurai" },
                        message = it.alarm.messageText.trim().takeIf(String::isNotBlank),
                    )
                } ?: RingingUiState.Ended
            }
        }
    }

    fun dismiss() {
        val active = _uiState.value as? RingingUiState.Active ?: return
        if (active.isDismissing) return
        _uiState.value = active.copy(isDismissing = true)
        viewModelScope.launch {
            runCatching { ringingController.dismiss(active.occurrence) }.onFailure {
                _uiState.value = active.copy(actionError = "Couldn't dismiss this alarm. Please try again.")
            }
        }
    }

    fun snooze() {
        val active = _uiState.value as? RingingUiState.Active ?: return
        if (active.isDismissing || active.isSnoozing) return
        _uiState.value = active.copy(isSnoozing = true, actionError = null)
        viewModelScope.launch {
            when (val result = ringingController.snooze(active.occurrence)) {
                is com.yavin.wakeupsamurai.alarm.SnoozeResult.AccessRequired ->
                    _uiState.value = active.copy(actionError = result.message)
                is com.yavin.wakeupsamurai.alarm.SnoozeResult.Failed ->
                    _uiState.value = active.copy(actionError = result.message)
                com.yavin.wakeupsamurai.alarm.SnoozeResult.Stale ->
                    _uiState.value = RingingUiState.Ended
                is com.yavin.wakeupsamurai.alarm.SnoozeResult.Snoozed -> Unit
            }
        }
    }
}
