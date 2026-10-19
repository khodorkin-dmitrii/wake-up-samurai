package com.yavin.wakeupsamurai

import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yavin.wakeupsamurai.alarm.AndroidOneShotAlarmScheduler
import com.yavin.wakeupsamurai.alarm.RingingOccurrence
import com.yavin.wakeupsamurai.ui.ringing.RingingScreen
import com.yavin.wakeupsamurai.ui.ringing.RingingUiState
import com.yavin.wakeupsamurai.ui.ringing.RingingViewModel
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import com.yavin.wakeupsamurai.ui.theme.AppThemeViewModel
import com.yavin.wakeupsamurai.ui.theme.resolveDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.yavin.wakeupsamurai.alarm.OneShotAlarmCoordinator
import com.yavin.wakeupsamurai.alarm.RingingRecoveryStarter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class RingingActivity : ComponentActivity() {
    @Inject lateinit var alarmCoordinator: OneShotAlarmCoordinator
    @Inject lateinit var ringingRecoveryStarter: RingingRecoveryStarter
    private val viewModel: RingingViewModel by viewModels()
    private val appThemeViewModel: AppThemeViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val recoverable = withContext(Dispatchers.IO) { alarmCoordinator.reconcile() }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ringingRecoveryStarter.start(recoverable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenPresentation()
        enableEdgeToEdge()
        val occurrence = intent.toRingingOccurrence()
        viewModel.observe(occurrence)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val themeMode by appThemeViewModel.themeMode.collectAsStateWithLifecycle()
            LaunchedEffect(state) {
                if (state == RingingUiState.Ended) finish()
            }
            WakeUpSamuraiTheme(darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RingingScreen(state = state, onDismiss = viewModel::dismiss, onSnooze = viewModel::snooze)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.observe(intent.toRingingOccurrence())
    }
}

@Suppress("DEPRECATION")
private fun ComponentActivity.configureLockScreenPresentation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
    }
}

fun android.content.Intent.toRingingOccurrence(): RingingOccurrence? {
    val alarmId = getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return null
    val occurrenceId = getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return null
    val revision = getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
    return RingingOccurrence(alarmId, occurrenceId, revision).takeIf { it.revision >= 0L }
}
