package com.watchreader.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ui.feice.mochi.basic.*
import ui.feice.mochi.effect.content.MochiBlurVisibility
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import java.util.Date

@Composable
internal fun ReadingSystemBars(immersive: Boolean) {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity, immersive) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (immersive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReadingInfo(title: String, progress: Int, palette: ReadingPalette? = null) {
    val context = LocalContext.current
    val infoBackground = palette?.background?.let { Color(it) } ?: MochiDefaults.groupedBackground()
    val infoText = palette?.muted?.let { Color(it) } ?: MochiTheme.colorScheme.onSurfaceVariant
    var time by remember { mutableStateOf(DateFormat.getTimeFormat(context).format(Date())) }
    var battery by remember { mutableStateOf("—") }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                time = DateFormat.getTimeFormat(context).format(Date())
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0 && scale > 0) battery = "${level * 100 / scale}%"
                }
            }
        }
        var registered = false
        fun register() {
            if (registered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        fun unregister() { if (registered) { context.unregisterReceiver(receiver); registered = false } }
        val lifecycle = (context.activity() as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) register()
            if (event == Lifecycle.Event.ON_STOP) unregister()
        }
        lifecycle?.addObserver(observer)
        if (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
        onDispose { lifecycle?.removeObserver(observer); unregister() }
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(infoBackground)
        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(time, style = MochiTheme.textStyles.footnote, color = infoText)
            Text(title, Modifier.weight(1f), style = MochiTheme.textStyles.footnote,
                color = infoText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${progress.coerceIn(0, 100)}%", style = MochiTheme.textStyles.footnote, color = infoText,
                modifier = Modifier.semantics { contentDescription = "阅读进度 ${progress.coerceIn(0, 100)}%" })
        }
    }
    val color = infoText
    Row(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 8.dp)
        .semantics(mergeDescendants = true) { contentDescription = "电池电量 $battery" },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp, 10.dp)) {
            val width = size.width * 0.86f
            drawRoundRect(color, size = Size(width, size.height), cornerRadius = CornerRadius(1.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawRect(color, topLeft = Offset(width, size.height * 0.3f), size = Size(size.width - width, size.height * 0.4f))
            val fraction = (battery.removeSuffix("%").toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
            drawRect(color, topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size((width - 4.dp.toPx()) * fraction, size.height - 4.dp.toPx()))
        }
        Text(battery, style = MochiTheme.textStyles.footnote, color = color)
    }
    }
}

/** 操作栏覆盖正文；显隐不会改变 ReaderView 的测量宽高。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderPage(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onToc: () -> Unit,
    onDisplay: () -> Unit,
    onSeek: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    readerContent: @Composable () -> Unit,
    bottomExtraActions: (@Composable () -> Unit)? = null,
    controlsVisible: Boolean = false,
    passiveTitle: String = title,
    readingProgress: Int = 0,
    overlay: @Composable BoxScope.() -> Unit = {},
    readingPalette: ReadingPalette? = null,
) {
    val readingBackground = readingPalette?.background?.let { Color(it) } ?: MochiDefaults.groupedBackground()
    Box(Modifier.fillMaxSize().background(readingBackground)) {
        readerContent()
        if (!controlsVisible) ReadingInfo(passiveTitle, readingProgress, readingPalette)
        MochiBlurVisibility(controlsVisible, Modifier.align(Alignment.TopCenter)) {
            MochiTitleBar(title, subtitle = subtitle,
                navigationIcon = onBack?.let { action -> { MochiBackButton(onClick = action) } },
                actions = { MochiBarButton("目录", onClick = onToc, navId = ReaderTocNav) })
        }
        MochiBlurVisibility(controlsVisible, Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(MochiDefaults.groupedBackground())
                .navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickPageSwipeBar(onPrevPage = onPrevPage, onNextPage = onNextPage)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MochiBarButton("字号", onClick = onDisplay, navId = ReaderDisplayNav)
                    MochiBarButton("进度", onClick = onSeek, navId = ReaderSeekNav)
                    bottomExtraActions?.invoke()
                }
            }
        }
        overlay()
    }
}

/**
 * 底部快速翻页区：拖动每经过 44dp 触发一次上一/下一页，一划可多页；
 * 松开/取消清空累计，不惯性滚动，也不使用手工震动。
 * 只在操作栏可见时覆盖底栏区域；中间点击与正文左右翻页仍由 ReaderView 处理。
 */
@Composable
private fun QuickPageSwipeBar(onPrevPage: () -> Unit, onNextPage: () -> Unit) {
    val previous by rememberUpdatedState(onPrevPage)
    val next by rememberUpdatedState(onNextPage)
    val density = LocalDensity.current
    val threshold = with(density) { 44.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MochiTheme.shapes.large)
            .background(MochiTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .semantics { contentDescription = "左右滑动翻页，左滑下一页，右滑上一页" }
            .pointerInput(threshold) {
                var accumulated = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount
                        while (accumulated <= -threshold) {
                            next()
                            accumulated += threshold
                        }
                        while (accumulated >= threshold) {
                            previous()
                            accumulated -= threshold
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "←  左右滑动翻页  →",
            style = MochiTheme.textStyles.footnote,
            color = MochiTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 翻页外观动效：只在稳定 AndroidView 外层做 alpha/translationX，不重建 ReaderView、
 * 不抓全屏 Bitmap。pageSerial 变化触发一次短动画；快速连翻会取消上一段 LaunchedEffect，
 * 不排队。系统动画倍率为 0 时直接跳过。
 */
@Composable
internal fun ReaderPageTurn(
    pageSerial: Int,
    forward: Boolean,
    animation: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val motionScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f).coerceIn(0f, 1f)
    }
    val reduceMotion = motionScale <= 0f
    val mode = ReadingAppearance.normalizeAnimation(animation)
    val progress = remember { Animatable(1f) }
    LaunchedEffect(pageSerial, forward, mode, enabled, reduceMotion, motionScale) {
        if (!enabled || reduceMotion || mode == ReadingAppearance.ANIMATION_NONE || pageSerial <= 0) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = (((if (mode == ReadingAppearance.ANIMATION_SLIDE) 150 else 110)
                    * motionScale).toInt()).coerceAtLeast(1),
                easing = FastOutSlowInEasing,
            ),
        )
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val value = progress.value
            when (mode) {
                ReadingAppearance.ANIMATION_SLIDE -> {
                    alpha = 0.72f + 0.28f * value
                    translationX = (if (forward) 1f else -1f) * (1f - value) * 12.dp.toPx()
                }
                ReadingAppearance.ANIMATION_FADE -> alpha = 0.65f + 0.35f * value
                else -> {
                    alpha = 1f
                    translationX = 0f
                }
            }
        },
    ) {
        content()
    }
}

@Composable
internal fun ReaderChapterSheet(
    visible: Boolean, title: String, chapters: List<String>, selected: Int,
    onPick: (Int) -> Unit, onDismiss: () -> Unit,
) {
    val state = rememberMochiSheetState(listOf(MochiSheetDetent.Medium))
    val scroll = rememberLazyListState()
    LaunchedEffect(visible) {
        if (visible) {
            state.show()
            // LazyColumn 只在面板组合后才有首轮布局，先滚动会一直等待布局而无法打开。
            if (chapters.isNotEmpty()) scroll.scrollToItem(selected.coerceIn(chapters.indices))
        } else state.hide()
    }
    MochiSheet(state, onDismiss = onDismiss) {
        MochiSheetHeader("目录 · ${chapters.size} 章", actions = { MochiBarButton("关闭", onClick = onDismiss) })
        LazyColumn(state = scroll, modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            flingBehavior = ui.feice.mochi.effect.overscroll.MochiScrollDefaults.flingBehavior()) {
            itemsIndexed(chapters, key = { index, _ -> index }) { index, name ->
                MochiListCard {
                    MochiListRow(name, value = if (index == selected) "在读" else null,
                        onClick = { onPick(index); onDismiss() })
                }
            }
            if (chapters.isEmpty()) item { MochiListRow("未识别到章节", subtitle = title) }
        }
    }
}
