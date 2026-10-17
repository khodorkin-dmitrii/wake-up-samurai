package com.yavin.wakeupsamurai.ui.ringing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import android.content.res.Configuration
import com.yavin.wakeupsamurai.alarm.RingingOccurrence
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme

@Composable
fun RingingScreen(
    state: RingingUiState,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RingingUiState.Loading -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        RingingUiState.Ended -> Unit
        is RingingUiState.Active -> Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.time,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(48.dp))
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            state.message?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(48.dp))
            state.actionError?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(bottom = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = onSnooze,
                enabled = !state.isDismissing && !state.isSnoozing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isSnoozing) "Snoozing…" else "Snooze 5 min") }
            Button(
                onClick = onDismiss,
                enabled = !state.isDismissing && !state.isSnoozing,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(if (state.isDismissing) "Dismissing…" else "Dismiss")
            }
        }
    }
}

@Preview(name = "Ringing light", showBackground = true)
@Preview(name = "Ringing dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RingingScreenPreview() {
    WakeUpSamuraiTheme {
        RingingScreen(
            state = RingingUiState.Active(
                occurrence = RingingOccurrence("preview-alarm", "preview-occurrence", 1),
                time = "07:15",
                title = "Breakfast with pancakes",
                message = "The coffee is waiting. You have a day to live.",
            ),
            onDismiss = {},
            onSnooze = {},
        )
    }
}
