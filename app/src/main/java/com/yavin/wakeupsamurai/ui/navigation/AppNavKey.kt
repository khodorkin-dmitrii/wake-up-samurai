package com.yavin.wakeupsamurai.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object AlarmListKey : NavKey

@Serializable
data class AlarmEditorKey(val alarmId: String? = null) : NavKey
