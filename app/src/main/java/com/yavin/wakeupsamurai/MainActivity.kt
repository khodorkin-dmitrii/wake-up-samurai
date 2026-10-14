package com.yavin.wakeupsamurai

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.yavin.wakeupsamurai.ui.alarmeditor.AlarmEditorViewModel
import com.yavin.wakeupsamurai.ui.alarmlist.AlarmListViewModel
import com.yavin.wakeupsamurai.ui.audiolibrary.AudioLibraryViewModel
import com.yavin.wakeupsamurai.ui.navigation.WakeUpSamuraiNavHost
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.content.ContextCompat

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val alarmListViewModel: AlarmListViewModel by viewModels()
    private val alarmEditorViewModel: AlarmEditorViewModel by viewModels()
    private val audioLibraryViewModel: AudioLibraryViewModel by viewModels()
    private val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshTimeDependentUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WakeUpSamuraiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WakeUpSamuraiNavHost(
                        alarmListViewModel = alarmListViewModel,
                        alarmEditorViewModel = alarmEditorViewModel,
                        audioLibraryViewModel = audioLibraryViewModel,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTimeDependentUi()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            timeChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            alarmEditorViewModel.onHostBackgrounded()
        }
        unregisterReceiver(timeChangeReceiver)
        super.onStop()
    }

    private fun refreshTimeDependentUi() {
        alarmListViewModel.refreshTime()
        alarmEditorViewModel.refreshNextOccurrence()
    }
}
