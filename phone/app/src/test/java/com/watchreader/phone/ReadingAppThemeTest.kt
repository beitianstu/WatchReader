package com.watchreader.phone

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAppThemeTest {

    @Test
    fun everyReaderPaletteMapsToAllSurfaceAndTextRoles() {
        for (key in ReadingAppearance.themeOptions.map { it.key }) {
            for (systemDark in listOf(false, true)) {
                val palette = ReadingAppearance.resolve(key, systemDark)
                val scheme = readingMochiColorScheme(palette)

                assertEquals(palette.dark, scheme.isDark)
                assertEquals(Color(palette.background), scheme.background)
                assertEquals(Color(palette.background), scheme.surface)
                assertEquals(Color(palette.body), scheme.onBackground)
                assertEquals(Color(palette.body), scheme.onSurface)
                assertTrue(contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5)
                assertEquals(Color(palette.accent), scheme.primary)
                assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
                assertTrue(contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5)
                assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= 4.5)
            }
        }
    }

    @Test
    fun systemFollowsDarkModeAndFixedThemesIgnoreIt() {
        val systemLight = readingMochiColorScheme(
            ReadingAppearance.resolve(ReadingAppearance.THEME_SYSTEM, dark = false),
        )
        val systemDark = readingMochiColorScheme(
            ReadingAppearance.resolve(ReadingAppearance.THEME_SYSTEM, dark = true),
        )
        val warmInDarkSystem = readingMochiColorScheme(
            ReadingAppearance.resolve(ReadingAppearance.THEME_WARM, dark = true),
        )

        assertFalse(systemLight.isDark)
        assertTrue(systemDark.isDark)
        assertNotEquals(systemLight.surface, systemDark.surface)
        assertFalse(warmInDarkSystem.isDark)
        assertEquals(Color(ReadingAppearance.resolve(ReadingAppearance.THEME_WARM, false).background), warmInDarkSystem.surface)
    }
}
