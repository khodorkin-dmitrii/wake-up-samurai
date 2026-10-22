package com.yavin.wakeupsamurai.ui.debug

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import com.yavin.wakeupsamurai.ui.components.ThemeModeAction
import com.yavin.wakeupsamurai.ui.ringing.DismissDragPad
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    ThemeModeAction(themeMode = themeMode, onCycleThemeMode = onCycleThemeMode)
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            DismissDragPad(
                onDismiss = onDismiss,
                enabled = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "Debug", showBackground = true)
@Composable
private fun DebugScreenPreview() {
    WakeUpSamuraiTheme {
        DebugScreen(
            onBack = {},
            onDismiss = {},
            themeMode = AppThemeMode.SYSTEM,
            onCycleThemeMode = {},
        )
    }
}
