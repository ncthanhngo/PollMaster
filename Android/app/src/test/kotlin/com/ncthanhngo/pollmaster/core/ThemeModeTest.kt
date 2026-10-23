package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun toggleFlipsLightAndDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.toggled(systemDark = true))
        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.toggled(systemDark = false))
    }

    @Test
    fun toggleFromSystemFlipsDisplayedScheme() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.toggled(systemDark = true))
        assertEquals(ThemeMode.DARK, ThemeMode.SYSTEM.toggled(systemDark = false))
    }

    @Test
    fun resolveAndDefault() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("bogus"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("system"))
    }
}
