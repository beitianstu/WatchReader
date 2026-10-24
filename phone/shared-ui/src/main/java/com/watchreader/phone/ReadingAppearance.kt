package com.watchreader.phone

/**
 * 阅读外观的纯配置：行距、背景配色、翻页动效。
 *
 * 这里不依赖 Android/Compose，方便 JVM 测试；真正的 ReaderView 排版仍由
 * `ReaderView.setLineHeightMultiplier()` 使用同一倍率参与 Paginator/绘制/PageMap。
 * 颜色是 ARGB Int，UI 层用 `Color(int)` 转换。
 */
internal data class ReadingPalette(
    val key: String,
    val label: String,
    val background: Int,
    val body: Int,
    val muted: Int,
    val accent: Int,
    val dark: Boolean,
)

internal data class ReadingThemeOption(val key: String, val label: String)

internal data class ReadingAnimationOption(val key: String, val label: String)

internal object ReadingAppearance {

    const val KEY_LINE_HEIGHT = "readingLineHeight"
    const val KEY_THEME = "readingTheme"
    const val KEY_ANIMATION = "readingAnimation"

    const val DEFAULT_LINE_HEIGHT = 1.3f
    const val MIN_LINE_HEIGHT = 1.0f
    const val MAX_LINE_HEIGHT = 2.0f

    const val THEME_SYSTEM = "system"
    const val THEME_PAPER = "paper"
    const val THEME_WARM = "warm"
    const val THEME_GREEN = "green"
    const val THEME_NIGHT = "night"
    const val DEFAULT_THEME = THEME_SYSTEM

    const val ANIMATION_NONE = "none"
    const val ANIMATION_FADE = "fade"
    const val ANIMATION_SLIDE = "slide"
    const val DEFAULT_ANIMATION = ANIMATION_FADE

    val themeOptions: List<ReadingThemeOption> = listOf(
        ReadingThemeOption(THEME_SYSTEM, "跟随系统"),
        ReadingThemeOption(THEME_PAPER, "米白"),
        ReadingThemeOption(THEME_WARM, "纸黄"),
        ReadingThemeOption(THEME_GREEN, "浅绿"),
        ReadingThemeOption(THEME_NIGHT, "夜间"),
    )

    val animationOptions: List<ReadingAnimationOption> = listOf(
        ReadingAnimationOption(ANIMATION_NONE, "不启用"),
        ReadingAnimationOption(ANIMATION_FADE, "淡入"),
        ReadingAnimationOption(ANIMATION_SLIDE, "轻滑"),
    )

    private val paper = ReadingPalette(
        key = THEME_PAPER,
        label = "米白",
        background = 0xFFF7F2E7.toInt(),
        body = 0xFF2C2A26.toInt(),
        muted = 0xFF6E675C.toInt(),
        accent = 0xFF8A5A2B.toInt(),
        dark = false,
    )

    private val warm = ReadingPalette(
        key = THEME_WARM,
        label = "纸黄",
        background = 0xFFF3E6C9.toInt(),
        body = 0xFF3B2F22.toInt(),
        muted = 0xFF7A6A53.toInt(),
        accent = 0xFFA05A2C.toInt(),
        dark = false,
    )

    private val green = ReadingPalette(
        key = THEME_GREEN,
        label = "浅绿",
        background = 0xFFE8F0E4.toInt(),
        body = 0xFF263229.toInt(),
        muted = 0xFF5F6F5C.toInt(),
        accent = 0xFF3E6B4A.toInt(),
        dark = false,
    )

    private val night = ReadingPalette(
        key = THEME_NIGHT,
        label = "夜间",
        background = 0xFF10151A.toInt(),
        body = 0xFFD7DEE7.toInt(),
        muted = 0xFF8A99A9.toInt(),
        accent = 0xFF7FA7D9.toInt(),
        dark = true,
    )

    fun normalizeTheme(key: String?): String {
        val value = key?.trim().orEmpty()
        return themeOptions.firstOrNull { it.key == value }?.key ?: DEFAULT_THEME
    }

    fun normalizeAnimation(key: String?): String {
        val value = key?.trim().orEmpty()
        return animationOptions.firstOrNull { it.key == value }?.key ?: DEFAULT_ANIMATION
    }

    fun clampLineHeight(value: Float): Float {
        if (value.isNaN()) return DEFAULT_LINE_HEIGHT
        return value.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
    }

    /** 跟随系统时按明暗落到米白/夜间；其它主题直接返回固定配色。 */
    fun resolve(key: String?, dark: Boolean): ReadingPalette {
        return when (normalizeTheme(key)) {
            THEME_SYSTEM -> (if (dark) night else paper).copy(
                key = THEME_SYSTEM,
                label = "跟随系统",
            )
            THEME_WARM -> warm
            THEME_GREEN -> green
            THEME_NIGHT -> night
            else -> paper
        }
    }

    fun palette(key: String?): ReadingPalette? = when (normalizeTheme(key)) {
        THEME_PAPER -> paper
        THEME_WARM -> warm
        THEME_GREEN -> green
        THEME_NIGHT -> night
        else -> null
    }
}
