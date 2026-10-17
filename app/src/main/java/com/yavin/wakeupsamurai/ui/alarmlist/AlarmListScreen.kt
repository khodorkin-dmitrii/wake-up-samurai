package com.yavin.wakeupsamurai.ui.alarmlist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.app.Notification
import com.yavin.wakeupsamurai.alarm.RingingNotificationChannel
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yavin.wakeupsamurai.R
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
    modifier: Modifier = Modifier,
) {
    val addAlarmLabel = stringResource(R.string.add_alarm)
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onNotificationPermissionResult(it)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarm_list_title)) },
                actions = { TextButton(onClick = onManageAudio) { Text("Manage audio") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAlarm,
                modifier = Modifier.semantics {
                    contentDescription = addAlarmLabel
                },
                text = { Text(addAlarmLabel) },
                icon = { Text("+") },
            )
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
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, RingingNotificationChannel.ID)
                            })
                        }) { Text("Enable Ringing alarms channel") }
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
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, RingingNotificationChannel.ID)
                            })
                        }) { Text("Make Ringing alarms high importance") }
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
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenAlarm(alarm.id) },
                    ) {
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
                                    onCheckedChange = { onEnabledChanged(alarm.id, it) },
                                )
                            }
                            Text(alarm.title, style = MaterialTheme.typography.titleMedium)
                            Text(alarm.nextOccurrence, style = MaterialTheme.typography.bodyMedium)
                            alarm.snoozedUntil?.let { until ->
                                Text("Snoozed until $until", color = MaterialTheme.colorScheme.primary)
                                OutlinedButton(onClick = { onCancelSnooze(alarm.id) }) { Text("Cancel snooze") }
                            }
                            alarm.readinessProblem?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
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
            uiState = AlarmListUiState(),
            onAddAlarm = {},
            onOpenAlarm = {},
            onEnabledChanged = { _, _ -> },
            onCancelSnooze = {},
            onManageAudio = {},
            onNotificationPermissionResult = {},
        )
    }
}
