package com.watchreader.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.watchreader.ReaderView
import java.util.Locale
import kotlin.math.roundToInt
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.Text
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.list.MochiListSliderRow
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiCompactTitleScaffold
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** 阅读页顶栏的稳定 navId：阅读页与目录/设置页共用同一组顶栏控件。 */
internal const val ReaderTocNav = "phone.reader.toc"
internal const val ReaderDisplayNav = "phone.reader.display"
internal const val ReaderSeekNav = "phone.reader.seek"

/**
 * 把 Mochi 配色同步给 ReaderView 自绘的正文。只改颜色，不改排版；
 * 手表阅读页不调用这里，仍用 ReaderView 的默认暗色。
 */
@Composable
internal fun BindReaderTheme(reader: ReaderView, palette: ReadingPalette? = null) {
    val scheme = MochiTheme.colorScheme
    val background = palette?.background ?: MochiDefaults.groupedBackground().toArgb()
    val body = palette?.body ?: scheme.onSurface.toArgb()
    val bar = palette?.muted ?: scheme.onSurfaceVariant.toArgb()
    val accent = palette?.accent ?: scheme.primary.toArgb()
    LaunchedEffect(background, body, bar, accent) {
        reader.setReadingColors(background, body, bar, accent)
    }
}

/**
 * 目录：按批显示，避免上千章一次性堆进 Column；点一行跳到该章偏移。
 * @param selectedIndex 当前章节，用于「在读」标记
 */
@Composable
internal fun ReaderTocPage(
    title: String,
    chapterTitles: List<String>,
    selectedIndex: Int,
    limit: Int,
    onShowMore: () -> Unit,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val shown = minOf(limit, chapterTitles.size)
    MochiCompactTitleScaffold(
        title = title,
        subtitle = "共 ${chapterTitles.size} 章",
        navigationIcon = { MochiBackButton(history = { title }, onClick = onBack) },
    ) {
        MochiListSection(
            header = "目录",
            footer = if (shown < chapterTitles.size) "已显示 $shown 章" else null,
        ) {
            for (index in 0 until shown) {
                MochiListRow(
                    title = chapterTitles[index],
                    subtitle = "第 ${index + 1} 章",
                    value = if (index == selectedIndex) "在读" else null,
                    onClick = { onPick(index) },
                )
            }
            if (shown < chapterTitles.size) {
                MochiListButton("显示更多章节", onClick = onShowMore)
            }
            if (chapterTitles.isEmpty()) {
                MochiListRow("没有识别出章节", subtitle = "继续阅读正文即可，进度仍会保存")
            }
        }
    }
}

/** 字号/字体设置：改完立即作用于当前 ReaderView，并写入旧阅读页读取的 watchreader 偏好。 */
@Suppress("DEPRECATION")
@Composable
internal fun ReaderDisplayPage(
    title: String,
    fontSize: Int,
    fontFamily: String,
    onFontSize: (Int) -> Unit,
    onFontFamily: (String) -> Unit,
    onBack: () -> Unit,
    lineSpacing: Float = ReadingAppearance.DEFAULT_LINE_HEIGHT,
    readingTheme: String = ReadingAppearance.DEFAULT_THEME,
    pageAnimation: String = ReadingAppearance.DEFAULT_ANIMATION,
    onLineSpacing: ((Float) -> Unit)? = null,
    onReadingTheme: ((String) -> Unit)? = null,
    onPageAnimation: ((String) -> Unit)? = null,
) {
    val families = remember {
        listOf(
            "sans-serif" to "黑体",
            "monospace" to "等宽",
            "serif" to "衬线",
        )
    }
    val normalizedAnimation = ReadingAppearance.normalizeAnimation(pageAnimation)
    val pickAnimation = onPageAnimation
    MochiCompactTitleScaffold(
        title = "阅读设置",
        subtitle = title,
        navigationIcon = { MochiBackButton(history = { title }, onClick = onBack) },
    ) {
        MochiListSection(
            header = "正文字号",
            footer = "当前 ${fontSize}sp；可选 12–30sp。",
        ) {
            MochiListRow("字号", value = "$fontSize sp")
            MochiListSliderRow(
                value = fontSize.toFloat(),
                onValueChange = { onFontSize(it.roundToInt()) },
                valueRange = 12f..30f,
                steps = 17,
            )
        }
        ReaderAppearanceSections(lineSpacing, readingTheme, onLineSpacing, onReadingTheme)
        if (pickAnimation != null) {
            MochiListSection(
                header = "翻页动画",
                footer = "选择翻页效果；关闭系统动画时自动停用。",
            ) {
                ReadingAppearance.animationOptions.forEach { option ->
                    MochiListRow(
                        title = option.label,
                        value = if (option.key == normalizedAnimation) "已选" else null,
                        onClick = { pickAnimation(option.key) },
                    )
                }
            }
        }
        MochiListSection(
            header = "字体",
            footer = "选择正文字体，阅读位置会保留。",
        ) {
            families.forEach { (key, label) ->
                MochiListRow(
                    title = label,
                    value = if (key == fontFamily) "已选" else null,
                    onClick = { onFontFamily(key) },
                )
            }
        }
        MochiListSection(
            header = "进度",
            footer = "进度按字符偏移保存，换字号不会丢；返回书库后列表会刷新百分比。",
        ) {
            MochiListRow("返回阅读", chevron = true, onClick = onBack)
        }
    }
}

/** 阅读页和主页设置共用同一组行距与背景控件。 */
@Composable
internal fun ReaderAppearanceSections(
    lineSpacing: Float,
    readingTheme: String,
    onLineSpacing: ((Float) -> Unit)?,
    onReadingTheme: ((String) -> Unit)?,
) {
    val spacing = ReadingAppearance.clampLineHeight(lineSpacing)
    val normalizedTheme = ReadingAppearance.normalizeTheme(readingTheme)
    val pickLineSpacing = onLineSpacing
    val pickTheme = onReadingTheme
    val dark = MochiTheme.colorScheme.isDark
    if (pickLineSpacing != null) {
        MochiListSection(
            header = "行间距",
            footer = String.format(
                Locale.US,
                "当前 %.2f×；可选 %.2f–%.2f×。",
                spacing,
                ReadingAppearance.MIN_LINE_HEIGHT,
                ReadingAppearance.MAX_LINE_HEIGHT,
            ),
        ) {
            MochiListRow("行距倍率", value = String.format(Locale.US, "%.2f×", spacing))
            MochiListSliderRow(
                value = spacing,
                onValueChange = { pickLineSpacing(ReadingAppearance.clampLineHeight(it)) },
                valueRange = ReadingAppearance.MIN_LINE_HEIGHT..ReadingAppearance.MAX_LINE_HEIGHT,
                steps = 19,
            )
        }
    }
    if (pickTheme != null) {
        MochiListSection(
            header = "背景",
            footer = "选择适合当前光线的阅读背景。",
        ) {
            ReadingAppearance.themeOptions.forEach { option ->
                MochiListRow(
                    title = option.label,
                    value = if (option.key == normalizedTheme) "已选" else null,
                    leading = {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(
                                    Color(ReadingAppearance.resolve(option.key, dark).background),
                                    CircleShape,
                                )
                                .border(1.dp, MochiTheme.colorScheme.outline, CircleShape),
                        )
                    },
                    onClick = { pickTheme(option.key) },
                )
            }
        }
    }
}

/** 按百分比跳转：与旧阅读菜单的「按百分比跳转」同一语义。 */
@Composable
internal fun ReaderSeekPage(
    title: String,
    percent: Int,
    onSeek: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var value by remember { mutableIntStateOf(percent) }
    MochiCompactTitleScaffold(
        title = "跳转进度",
        subtitle = title,
        navigationIcon = { MochiBackButton(history = { title }, onClick = onBack) },
    ) {
        MochiListSection(header = "全书百分比", footer = "拖动后点下方按钮生效。") {
            MochiListRow("当前位置", value = "$percent%")
            MochiListRow("跳转到", value = "$value%")
            MochiListSliderRow(
                value = value.toFloat(),
                onValueChange = { value = it.roundToInt().coerceIn(0, 100) },
                valueRange = 0f..100f,
                steps = 99,
            )
        }
        MochiButton(
            onClick = { onSeek(value) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("跳转", style = MochiTheme.textStyles.bodyLarge)
        }
    }
}
