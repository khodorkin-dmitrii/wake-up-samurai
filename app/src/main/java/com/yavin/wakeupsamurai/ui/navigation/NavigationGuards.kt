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
