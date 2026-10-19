package com.yavin.wakeupsamurai.domain.theme

import com.yavin.wakeupsamurai.ui.theme.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun `theme mode cycles through system light and dark`() {
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.SYSTEM.next())
        assertEquals(AppThemeMode.DARK, AppThemeMode.LIGHT.next())
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.DARK.next())
    }

    @Test
    fun `system mode follows the device preference`() {
        assertEquals(true, AppThemeMode.SYSTEM.resolveDarkTheme(systemInDarkTheme = true))
        assertEquals(false, AppThemeMode.SYSTEM.resolveDarkTheme(systemInDarkTheme = false))
        assertEquals(false, AppThemeMode.LIGHT.resolveDarkTheme(systemInDarkTheme = true))
        assertEquals(true, AppThemeMode.DARK.resolveDarkTheme(systemInDarkTheme = false))
    }
}
