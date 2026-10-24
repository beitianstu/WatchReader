package com.watchreader.phone

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import ui.feice.mochi.theme.MochiColorScheme
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.animateMochiColorScheme
import ui.feice.mochi.theme.darkColorScheme
import ui.feice.mochi.theme.lightColorScheme

/** Applies the selected reader palette to all Mochi semantic color roles. */
@Composable
internal fun ReadingAppTheme(
    themeKey: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("watchreader", Context.MODE_PRIVATE)
    }
    var savedTheme by remember(preferences) {
        mutableStateOf(preferences.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME))
    }

    DisposableEffect(preferences, themeKey == null) {
        if (themeKey == null) {
            savedTheme = preferences.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME)
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (themeKey == null && key == ReadingAppearance.KEY_THEME) {
                savedTheme = prefs.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME)
            }
        }
        if (themeKey == null) preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            if (themeKey == null) preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val palette = ReadingAppearance.resolve(themeKey ?: savedTheme, isSystemInDarkTheme())
    val colors = remember(palette) { readingMochiColorScheme(palette) }
    MochiTheme(colors = animateMochiColorScheme(colors), content = content)
}

/** Pure palette mapping, kept separate so semantic roles can be verified without composing UI. */
internal fun readingMochiColorScheme(palette: ReadingPalette): MochiColorScheme {
    val surface = Color(palette.background)
    val body = Color(palette.body)
    val muted = Color(palette.muted)
    val accent = Color(palette.accent)
    val base = if (palette.dark) darkColorScheme(accent) else lightColorScheme(accent)
    val onAccent = if (contrastRatio(Color.Black, accent) >= contrastRatio(Color.White, accent)) {
        Color.Black
    } else {
        Color.White
    }
    fun surfaceAt(amount: Float) = lerp(surface, body, amount)
    val accentContainer = lerp(surface, accent, if (palette.dark) 0.28f else 0.16f)
    val variantSurface = surfaceAt(0.08f)
    val accessibleMuted = contrastingVariantText(muted, body, variantSurface)

    return base.copy(
        isDark = palette.dark,
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = body,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = body,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = body,
        background = surface,
        onBackground = body,
        surface = surface,
        onSurface = body,
        surfaceVariant = variantSurface,
        onSurfaceVariant = accessibleMuted,
        surfaceTint = accent,
        inverseSurface = body,
        inverseOnSurface = surface,
        outline = muted,
        outlineVariant = surfaceAt(0.18f),
        surfaceBright = surfaceAt(if (palette.dark) 0.18f else 0.0f),
        surfaceDim = surfaceAt(if (palette.dark) 0.0f else 0.08f),
        surfaceContainerLowest = surfaceAt(if (palette.dark) 0.025f else 0.0f),
        surfaceContainerLow = surfaceAt(if (palette.dark) 0.06f else 0.035f),
        surfaceContainer = surfaceAt(0.09f),
        surfaceContainerHigh = surfaceAt(0.14f),
        surfaceContainerHighest = surfaceAt(0.19f),
    )
}

private fun contrastingVariantText(muted: Color, body: Color, background: Color): Color {
    if (contrastRatio(muted, background) >= 4.5) return muted
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (contrastRatio(lerp(muted, body, middle), background) >= 4.5) high = middle else low = middle
    }
    return lerp(muted, body, high)
}

internal fun contrastRatio(foreground: Color, background: Color): Double {
    val lighter = maxOf(foreground.luminance(), background.luminance()).toDouble()
    val darker = minOf(foreground.luminance(), background.luminance()).toDouble()
    return (lighter + 0.05) / (darker + 0.05)
}
