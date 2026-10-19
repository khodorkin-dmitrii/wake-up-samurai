package com.yavin.wakeupsamurai.ui.alarmlist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import com.yavin.wakeupsamurai.ui.components.ThemeModeAction
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    uiState: AlarmListUiState,
    onAddAlarm: () -> Unit,
    onOpenAlarm: (String) -> Unit,
    onEnabledChanged: (String, Boolean) -> Unit,
    onCancelSnooze: (String) -> Unit,
    onManageAudio: () -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onOpenAlarmChannelSettings: () -> Unit,
    onTimeTick: () -> Unit,
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addAlarmLabel = stringResource(R.string.add_alarm)
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val nowMillis = System.currentTimeMillis()
                val millisUntilNextMinute = MILLIS_PER_MINUTE - Math.floorMod(nowMillis, MILLIS_PER_MINUTE)
                kotlinx.coroutines.delay(millisUntilNextMinute + MINUTE_BOUNDARY_SETTLE_MILLIS)
                onTimeTick()
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onNotificationPermissionResult(it)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarm_list_title)) },
                actions = {
                    IconButton(onClick = onManageAudio) {
                        Icon(
                            painter = painterResource(R.drawable.ic_audio_file_24),
                            contentDescription = stringResource(R.string.manage_audio),
                        )
                    }
                    ThemeModeAction(themeMode = themeMode, onCycleThemeMode = onCycleThemeMode)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddAlarm,
                modifier = Modifier.semantics {
                    contentDescription = addAlarmLabel
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_notification_audio_24),
                    contentDescription = null,
                )
            }
        },
    ) { innerPadding ->
        if (uiState.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 440.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty_state_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.empty_state_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    top = 20.dp,
                    end = 20.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "One-shot alarms can ring after Android access is granted. Recurring execution is not supported yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (uiState.schedulingAccessRequest == SchedulingAccessRequest.EXACT_ALARM) {
                    item {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    },
                                )
                            },
                        ) { Text("Allow alarms & reminders") }
                    }
                }
                if (uiState.schedulingAccessRequest == SchedulingAccessRequest.NOTIFICATIONS) {
                    item {
                        OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("Allow notifications")
                        }
                    }
                }
                if (uiState.schedulingAccessRequest == SchedulingAccessRequest.APP_NOTIFICATIONS) {
                    item {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        }) { Text("Enable app notifications") }
                    }
                }
                if (uiState.schedulingAccessRequest == SchedulingAccessRequest.ALARM_CHANNEL) {
                    item {
                        OutlinedButton(onClick = onOpenAlarmChannelSettings) { Text("Enable Ringing alarms channel") }
                    }
                }
                if (uiState.fullScreenIntentUnavailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    item {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    },
                                )
                            },
                        ) { Text("Allow full-screen alarm screen") }
                    }
                    item {
                        Text(
                            "Without full-screen access, the alarm still rings and its notification opens the ringing screen.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (uiState.alarmChannelNotHighImportance) {
                    item {
                        OutlinedButton(onClick = onOpenAlarmChannelSettings) { Text("Make Ringing alarms high importance") }
                    }
                    item {
                        Text(
                            "Lower channel importance can keep sound playing but may prevent automatic alarm-screen presentation.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                uiState.message?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                items(uiState.alarms, key = { it.id }) { alarm ->
                    AlarmListItemCard(
                        alarm = alarm,
                        now = uiState.currentTime,
                        onOpen = { onOpenAlarm(alarm.id) },
                        onEnabledChanged = { enabled -> onEnabledChanged(alarm.id, enabled) },
                        onCancelSnooze = { onCancelSnooze(alarm.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmListItemCard(
    alarm: AlarmListItemUi,
    now: Instant,
    onOpen: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onCancelSnooze: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(alarm.time, style = MaterialTheme.typography.headlineMedium)
                    Text(alarm.repeatSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = alarm.configuredEnabled,
                    onCheckedChange = onEnabledChanged,
                )
            }
            Text(alarm.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = buildString {
                    append(alarm.nextOccurrence)
                    val countdown = alarm.nextOccurrenceAt
                        ?.takeIf { alarm.hasScheduledUpcomingOccurrence && it.isAfter(now) }
                        ?.let { countdownLabel(it, now) }
                    countdown?.let { append(" ($it)") }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            alarm.snoozedUntil?.let { until ->
                Text("Snoozed until $until", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = onCancelSnooze) { Text("Cancel snooze") }
            }
            alarm.readinessProblem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(
    name = "Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlarmListScreenPreview() {
    WakeUpSamuraiTheme {
        AlarmListScreen(
            uiState = AlarmListUiState(alarms = alarmListPreviewItems),
            onAddAlarm = {},
            onOpenAlarm = {},
            onEnabledChanged = { _, _ -> },
            onCancelSnooze = {},
            onManageAudio = {},
            onNotificationPermissionResult = {},
            onOpenAlarmChannelSettings = {},
            onTimeTick = {},
            themeMode = AppThemeMode.SYSTEM,
            onCycleThemeMode = {},
        )
    }
}

@Preview(name = "Empty alarm list", showBackground = true)
@Composable
private fun AlarmListEmptyPreview() {
    WakeUpSamuraiTheme {
        AlarmListScreen(
            uiState = AlarmListUiState(),
            onAddAlarm = {},
            onOpenAlarm = {},
            onEnabledChanged = { _, _ -> },
            onCancelSnooze = {},
            onManageAudio = {},
            onNotificationPermissionResult = {},
            onOpenAlarmChannelSettings = {},
            onTimeTick = {},
            themeMode = AppThemeMode.SYSTEM,
            onCycleThemeMode = {},
        )
    }
}

@Preview(name = "Alarm enabled", showBackground = true)
@Composable
private fun AlarmListItemEnabledPreview() = AlarmListItemPreview(alarmListPreviewItems[0])

@Preview(name = "Alarm disabled", showBackground = true)
@Composable
private fun AlarmListItemDisabledPreview() = AlarmListItemPreview(alarmListPreviewItems[1])

@Preview(name = "Recurring with snooze", showBackground = true)
@Composable
private fun AlarmListItemSnoozedPreview() = AlarmListItemPreview(alarmListPreviewItems[2])

@Preview(name = "Alarm needs attention", showBackground = true)
@Composable
private fun AlarmListItemProblemPreview() = AlarmListItemPreview(alarmListPreviewItems[3])

@Composable
private fun AlarmListItemPreview(alarm: AlarmListItemUi) {
    WakeUpSamuraiTheme {
        AlarmListItemCard(
            alarm = alarm,
            now = Instant.now(),
            onOpen = {},
            onEnabledChanged = {},
            onCancelSnooze = {},
        )
    }
}

private val alarmListPreviewItems = listOf(
    AlarmListItemUi(
        id = "weekday-morning",
        time = "07:00",
        repeatSummary = "Weekdays",
        title = "A quiet coffee before the day starts",
        nextOccurrence = "Tomorrow at 07:00",
        configuredEnabled = true,
        readinessProblem = null,
        schedulingState = "Scheduled",
        nextOccurrenceAt = Instant.now().plus(Duration.ofHours(14).plusMinutes(30)),
        hasScheduledUpcomingOccurrence = true,
    ),
    AlarmListItemUi(
        id = "weekend-walk",
        time = "09:30",
        repeatSummary = "Sat, Sun",
        title = "Go for that lakeside walk",
        nextOccurrence = "Saturday at 09:30",
        configuredEnabled = false,
        readinessProblem = null,
        schedulingState = "Disabled",
        nextOccurrenceAt = Instant.now().plus(Duration.ofDays(2)),
    ),
    AlarmListItemUi(
        id = "snoozed-alarm",
        time = "07:00",
        repeatSummary = "Mon, Tue, Wed, Thu, Fri",
        title = "Future me says: five more minutes",
        nextOccurrence = "Next regular: Tomorrow at 07:00",
        configuredEnabled = true,
        readinessProblem = null,
        schedulingState = "Snoozed",
        snoozedUntil = "Today at 07:05",
        nextOccurrenceAt = Instant.now().plus(Duration.ofDays(1)),
        hasScheduledUpcomingOccurrence = true,
    ),
    AlarmListItemUi(
        id = "blocked-alarm",
        time = "08:15",
        repeatSummary = "Once",
        title = "Get up and open the windows",
        nextOccurrence = "Today at 08:15",
        configuredEnabled = true,
        readinessProblem = "Allow alarms & reminders to schedule this alarm.",
        schedulingState = "Blocked",
        nextOccurrenceAt = Instant.now().plus(Duration.ofMinutes(30)),
    ),
)

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTE_BOUNDARY_SETTLE_MILLIS = 25L
private const val SECONDS_PER_HOUR = 60L * 60L
private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR

@Composable
private fun countdownLabel(occurrence: Instant, now: Instant): String {
    val secondsRemaining = Duration.between(now, occurrence).seconds
    if (secondsRemaining < 60) return stringResource(R.string.alarm_countdown_soon)

    val days = (secondsRemaining + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY
    return when {
        secondsRemaining >= SECONDS_PER_DAY -> pluralStringResource(
            R.plurals.alarm_countdown_days,
            days.toInt(),
            days.toInt(),
        )
        secondsRemaining >= SECONDS_PER_HOUR -> {
            val hours = (secondsRemaining / SECONDS_PER_HOUR).toInt()
            val minutes = ((secondsRemaining % SECONDS_PER_HOUR) / 60).toInt()
            stringResource(
                R.string.alarm_countdown_hours_minutes,
                hours,
                minutes,
            )
        }
        else -> stringResource(
            R.string.alarm_countdown_minutes,
            (secondsRemaining / 60).toInt(),
        )
    }
}
