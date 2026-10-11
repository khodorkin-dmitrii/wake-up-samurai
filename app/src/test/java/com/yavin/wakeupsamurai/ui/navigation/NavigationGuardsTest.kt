package com.yavin.wakeupsamurai.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuardsTest {
    @Test
    fun pushIfNotTop_ignoresDuplicateDestination() {
        val backStack = mutableListOf(AlarmListKey, AlarmEditorKey)

        val changed = backStack.pushIfNotTop(AlarmEditorKey)

        assertFalse(changed)
        assertEquals(listOf(AlarmListKey, AlarmEditorKey), backStack)
    }

    @Test
    fun popIfTop_onlyPopsExpectedDestinationOnce() {
        val backStack = mutableListOf(AlarmListKey, AlarmEditorKey)

        assertTrue(backStack.popIfTop(AlarmEditorKey))
        assertFalse(backStack.popIfTop(AlarmEditorKey))
        assertEquals(listOf(AlarmListKey), backStack)
    }

    @Test
    fun popIfTop_neverRemovesRoot() {
        val backStack = mutableListOf(AlarmListKey)

        assertFalse(backStack.popIfTop(AlarmListKey))
        assertEquals(listOf(AlarmListKey), backStack)
    }
}
