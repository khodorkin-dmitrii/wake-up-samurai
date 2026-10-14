package com.yavin.wakeupsamurai.ui.navigation

internal fun <T> MutableList<T>.pushIfNotTop(destination: T): Boolean {
    if (lastOrNull() == destination) return false
    add(destination)
    return true
}

internal fun <T> MutableList<T>.popIfTop(expectedTop: T): Boolean {
    if (lastOrNull() != expectedTop || size <= 1) return false
    removeAt(lastIndex)
    return true
}

internal fun MutableList<androidx.navigation3.runtime.NavKey>.pushEditorIfAbsentOnTop(
    destination: AlarmEditorKey,
): Boolean {
    if (lastOrNull() is AlarmEditorKey) return false
    add(destination)
    return true
}

internal fun MutableList<androidx.navigation3.runtime.NavKey>.pushSavedAudioPickerIfAbsentOnTop(): Boolean {
    if (lastOrNull() is SavedAudioPickerKey) return false
    add(SavedAudioPickerKey)
    return true
}

internal fun MutableList<androidx.navigation3.runtime.NavKey>.pushAudioLibraryIfAbsentOnTop(): Boolean {
    if (lastOrNull() is AudioLibraryKey) return false
    add(AudioLibraryKey)
    return true
}
