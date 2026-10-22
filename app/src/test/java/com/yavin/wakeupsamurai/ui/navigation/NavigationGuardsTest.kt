package com.yavin.wakeupsamurai.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuardsTest {
    @Test
    fun debugGuardRejectsDuplicateDestination() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(AlarmListKey)

        assertTrue(stack.pushDebugIfAbsentOnTop())
        assertFalse(stack.pushDebugIfAbsentOnTop())
        assertEquals(listOf(AlarmListKey, DebugKey), stack)
    }

    @Test
    fun savedAudioPickerGuardRejectsDuplicateDestination() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(AlarmListKey, AlarmEditorKey())

        assertTrue(stack.pushSavedAudioPickerIfAbsentOnTop())
        assertFalse(stack.pushSavedAudioPickerIfAbsentOnTop())
        assertEquals(listOf(AlarmListKey, AlarmEditorKey(), SavedAudioPickerKey), stack)
    }

    @Test
    fun editorGuardRejectsRapidNavigationToAnotherEditor() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(AlarmListKey)
        assertTrue(stack.pushEditorIfAbsentOnTop(AlarmEditorKey("first")))
        assertFalse(stack.pushEditorIfAbsentOnTop(AlarmEditorKey("second")))
        assertEquals(listOf(AlarmListKey, AlarmEditorKey("first")), stack)
    }
    @Test
    fun pushIfNotTop_ignoresDuplicateDestination() {
        val editor = AlarmEditorKey()
        val backStack = mutableListOf(AlarmListKey, editor)

        val changed = backStack.pushIfNotTop(editor)

        assertFalse(changed)
        assertEquals(listOf(AlarmListKey, editor), backStack)
    }

    @Test
    fun popIfTop_onlyPopsExpectedDestinationOnce() {
        val editor = AlarmEditorKey()
        val backStack = mutableListOf(AlarmListKey, editor)

        assertTrue(backStack.popIfTop(editor))
        assertFalse(backStack.popIfTop(editor))
        assertEquals(listOf(AlarmListKey), backStack)
    }

    @Test
    fun popIfTop_neverRemovesRoot() {
        val backStack = mutableListOf(AlarmListKey)

        assertFalse(backStack.popIfTop(AlarmListKey))
        assertEquals(listOf(AlarmListKey), backStack)
    }
}
