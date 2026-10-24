package com.watchreader.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 配置测试；真实分页/绘制由 ReaderPagingInstrumentation 覆盖。 */
class ReadingAppearanceTest {

    @Test
    fun lineHeightIsClampedToSupportedRange() {
        assertEquals(1.0f, ReadingAppearance.clampLineHeight(0.4f), 0.0001f)
        assertEquals(2.0f, ReadingAppearance.clampLineHeight(2.6f), 0.0001f)
        assertEquals(1.35f, ReadingAppearance.clampLineHeight(1.35f), 0.0001f)
        assertEquals(ReadingAppearance.DEFAULT_LINE_HEIGHT, ReadingAppearance.clampLineHeight(Float.NaN), 0.0001f)
    }

    @Test
    fun unknownThemeOrAnimationFallsBackToDefaults() {
        assertEquals(ReadingAppearance.DEFAULT_THEME, ReadingAppearance.normalizeTheme("bogus"))
        assertEquals(ReadingAppearance.DEFAULT_THEME, ReadingAppearance.normalizeTheme(null))
        assertEquals(ReadingAppearance.DEFAULT_ANIMATION, ReadingAppearance.normalizeAnimation("bogus"))
        assertEquals(ReadingAppearance.DEFAULT_ANIMATION, ReadingAppearance.normalizeAnimation(null))
    }

    @Test
    fun systemThemeResolvesToPairedLightAndDarkPalettes() {
        val light = ReadingAppearance.resolve(ReadingAppearance.THEME_SYSTEM, dark = false)
        val dark = ReadingAppearance.resolve(ReadingAppearance.THEME_SYSTEM, dark = true)
        assertFalse(light.dark)
        assertTrue(dark.dark)
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.body, light.background)
        assertNotEquals(dark.body, dark.background)
        assertEquals(ReadingAppearance.THEME_SYSTEM, light.key)
        assertEquals(ReadingAppearance.THEME_SYSTEM, dark.key)
    }

    @Test
    fun fixedThemesAreNamedAndSeparateFromSystem() {
        val keys = ReadingAppearance.themeOptions.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(5, keys.size)
        assertTrue(keys.contains(ReadingAppearance.THEME_PAPER))
        assertTrue(keys.contains(ReadingAppearance.THEME_WARM))
        assertTrue(keys.contains(ReadingAppearance.THEME_GREEN))
        assertTrue(keys.contains(ReadingAppearance.THEME_NIGHT))
        assertNull(ReadingAppearance.palette(ReadingAppearance.THEME_SYSTEM))
        for (key in keys.filter { it != ReadingAppearance.THEME_SYSTEM }) {
            val palette = ReadingAppearance.palette(key)!!
            assertNotEquals(palette.background, palette.body)
            assertNotEquals(palette.background, palette.muted)
        }
    }

    @Test
    fun animationOptionsAllNormalizeToThemselves() {
        for (option in ReadingAppearance.animationOptions) {
            assertEquals(option.key, ReadingAppearance.normalizeAnimation(option.key))
        }
    }
}
