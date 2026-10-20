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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yavin.wakeupsamurai.ui.alarmeditor.AlarmEditorViewModel
import com.yavin.wakeupsamurai.ui.alarmlist.AlarmListViewModel
import com.yavin.wakeupsamurai.ui.audiolibrary.AudioLibraryViewModel
import com.yavin.wakeupsamurai.ui.navigation.WakeUpSamuraiNavHost
import com.yavin.wakeupsamurai.ui.components.ThemeRevealTransition
import com.yavin.wakeupsamurai.ui.theme.AppThemeViewModel
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import com.yavin.wakeupsamurai.ui.theme.resolveDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.yavin.wakeupsamurai.alarm.OneShotAlarmCoordinator
import com.yavin.wakeupsamurai.alarm.RingingRecoveryStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var alarmCoordinator: OneShotAlarmCoordinator
    @Inject lateinit var ringingRecoveryStarter: RingingRecoveryStarter
    private val alarmListViewModel: AlarmListViewModel by viewModels()
    private val alarmEditorViewModel: AlarmEditorViewModel by viewModels()
    private val audioLibraryViewModel: AudioLibraryViewModel by viewModels()
    private val appThemeViewModel: AppThemeViewModel by viewModels()
    private val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshTimeDependentUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by appThemeViewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        alarmListViewModel.refreshTime()
                        val nowMillis = System.currentTimeMillis()
                        val millisUntilNextMinute = MILLIS_PER_MINUTE - Math.floorMod(nowMillis, MILLIS_PER_MINUTE)
                        delay(millisUntilNextMinute + MINUTE_BOUNDARY_SETTLE_MILLIS)
                    }
                }
            }
            ThemeRevealTransition(
                themeMode = themeMode,
                darkTheme = darkTheme,
                onCycleThemeMode = appThemeViewModel::cycleThemeMode,
            ) { requestThemeChange ->
                WakeUpSamuraiTheme(darkTheme = darkTheme) {
                    WakeUpSamuraiNavHost(
                        alarmListViewModel = alarmListViewModel,
                        alarmEditorViewModel = alarmEditorViewModel,
                        audioLibraryViewModel = audioLibraryViewModel,
                        themeMode = themeMode,
                        onCycleThemeMode = requestThemeChange,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val recoverable = withContext(Dispatchers.IO) { alarmCoordinator.reconcile() }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ringingRecoveryStarter.start(recoverable)
        }
        refreshTimeDependentUi()
        alarmEditorViewModel.onSchedulingAccessReturned()
        alarmListViewModel.onSchedulingAccessReturned()
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

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTE_BOUNDARY_SETTLE_MILLIS = 25L
