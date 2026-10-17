package com.yavin.wakeupsamurai.ui.alarmlist

data class AlarmListUiState(
    val alarms: List<AlarmListItemUi> = emptyList(),
    val message: String? = null,
    val schedulingAccessRequest: SchedulingAccessRequest? = null,
    val fullScreenIntentUnavailable: Boolean = false,
    val alarmChannelNotHighImportance: Boolean = false,
) {
    val isEmpty: Boolean get() = alarms.isEmpty()
}

data class AlarmListItemUi(
    val id: String,
    val time: String,
    val repeatSummary: String,
    val title: String,
    val nextOccurrence: String,
    val configuredEnabled: Boolean,
    val readinessProblem: String?,
    val schedulingState: String,
    val snoozedUntil: String? = null,
)

enum class SchedulingAccessRequest { EXACT_ALARM, NOTIFICATIONS, APP_NOTIFICATIONS, ALARM_CHANNEL }
