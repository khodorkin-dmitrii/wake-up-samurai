package com.yavin.wakeupsamurai

import android.os.Bundle
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
import com.yavin.wakeupsamurai.ui.navigation.WakeUpSamuraiNavHost
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val alarmListViewModel: AlarmListViewModel by viewModels()
    private val alarmEditorViewModel: AlarmEditorViewModel by viewModels()

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
                    )
                }
            }
        }
    }
}
