package com.watchreader.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.watchreader.Book
import com.watchreader.BookCache
import com.watchreader.ReaderView
import com.watchreader.fanqie.DownloadedNovel
import com.watchreader.fanqie.DownloadedReading
import com.watchreader.fanqie.RemotePluginClient
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.rememberMochiNavigator
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.utils.mochiEnableEdgeToEdge

private const val RouteRead = "read"
private const val RouteToc = "toc"
private const val RouteDisplay = "display"
private const val RouteSeek = "seek"

/**
 * 手机本地 TXT 阅读页：正文仍由 ReaderView 排版/翻页，页眉页脚和菜单换成 Mochi 顶栏、底栏与独立设置页。
 * 进度键与旧 ReaderActivity 完全一致，所以书库百分比、番茄来源同步都沿用原机制。
 */
class PhoneReaderActivity : ComponentActivity(), ReaderView.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: SharedPreferences
    private lateinit var reader: ReaderView

    private var bookFile: File? = null
    private var book: Book? = null
    private var lastOffset = 0
    private var lastPercent = 0f
    private var lastTimestamp = 0L
    private var progressLoaded = false
    /** 打开时准备恢复到的偏移；非 0 时用它挡掉 setBook 首次回调的 0 偏移。 */
    private var restoreAnchor = 0
    private var destroyed = false

    private var controlsVisible by mutableStateOf(false)
    private var passiveHeader by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var loadError by mutableStateOf<String?>(null)
    private var loadPhase by mutableStateOf("正在打开…")
    private var bookTitle by mutableStateOf("")
    private var pageLabel by mutableStateOf("")
    private var percent by mutableIntStateOf(0)
    private var tocVersion by mutableIntStateOf(0)
    private var fontState by mutableIntStateOf(18)
    private var familyState by mutableStateOf("sans-serif")
    private var lineSpacingState by mutableFloatStateOf(ReadingAppearance.DEFAULT_LINE_HEIGHT)
    private var readingThemeState by mutableStateOf(ReadingAppearance.DEFAULT_THEME)
    private var pageAnimationState by mutableStateOf(ReadingAppearance.DEFAULT_ANIMATION)
    private var pageTurnSerial by mutableIntStateOf(0)
    private var pageTurnForward by mutableStateOf(true)
    private var lastAnimatedPage = -1
    /** 进入显示设置时捕获的字符偏移；格式改动期间只认这个 anchor，避免逐次重排漂移。 */
    private var displayAnchor = -1
    private var formattingDisplay = false
    private var sourceStatus by mutableStateOf("")
    private var routeRequest by mutableStateOf<String?>(null)

    private var sourceClient: RemotePluginClient? = null
    private var downloaded: DownloadedReading? = null

    private val pluginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            sourceStatus = "插件未连接，本地进度已保留"
            return@registerForActivityResult
        }
        connectSource()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mochiEnableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PhoneLibrary.PREFS, Context.MODE_PRIVATE)
        PhoneLibrary.ensurePhoneFontSize(this)
        fontState = ReadingRules.clampFontSize(prefs.getInt(PhoneLibrary.FONT_SIZE, ReadingRules.DEFAULT_PHONE_FONT_SIZE))
        familyState = prefs.getString("fontFamily", "sans-serif") ?: "sans-serif"
        lineSpacingState = ReadingAppearance.clampLineHeight(
            prefs.getFloat(ReadingAppearance.KEY_LINE_HEIGHT, ReadingAppearance.DEFAULT_LINE_HEIGHT),
        )
        readingThemeState = ReadingAppearance.normalizeTheme(
            prefs.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME),
        )
        pageAnimationState = ReadingAppearance.normalizeAnimation(
            prefs.getString(ReadingAppearance.KEY_ANIMATION, ReadingAppearance.DEFAULT_ANIMATION),
        )

        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialPalette = ReadingAppearance.resolve(readingThemeState, systemDark)
        reader = ReaderView(this).apply {
            setPhoneLayout(true)
            setChromeVisible(false)
            setChapterLayout(true)
            setImmersiveTap(true)
            setPhoneContentInsets(78f, 30f)
            setListener(this@PhoneReaderActivity)
            setFontSize(fontState)
            setLineHeightMultiplier(lineSpacingState)
            setFontFamily(familyState)
            setReadingColors(
                initialPalette.background,
                initialPalette.body,
                initialPalette.muted,
                initialPalette.accent,
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ReadingAppTheme(readingThemeState) {
                val readingDark = MochiTheme.colorScheme.isDark
                val palette = remember(readingThemeState, readingDark) {
                    ReadingAppearance.resolve(readingThemeState, readingDark)
                }
                BindReaderTheme(reader, palette)
                val view = LocalView.current
                val dark = MochiTheme.colorScheme.isDark
                SideEffect {
                    view.context.findActivity()?.window?.let { window ->
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                }
                ProvideMochiOverscroll {
                    PhoneReaderHost(palette)
                }
            }
        }
        loadBook()
    }

    @Composable
    private fun PhoneReaderHost(palette: ReadingPalette) {
        val nav = rememberMochiNavigator(RouteRead)
        var showToc by remember { mutableStateOf(false) }
        ReadingSystemBars(nav.current == RouteRead && !controlsVisible)
        androidx.activity.compose.BackHandler(nav.current == RouteRead && controlsVisible) { controlsVisible = false }

        LaunchedEffect(routeRequest) {
            val target = routeRequest ?: return@LaunchedEffect
            routeRequest = null
            if (target == RouteToc) showToc = true else nav.push(target)
        }

        LaunchedEffect(nav.current) {
            if (nav.current != RouteDisplay) endDisplayFormatting()
        }

        MochiNavHost(nav, Modifier.fillMaxSize()) { route ->
            when (route) {
                RouteRead -> ReaderPage(
                    title = bookTitle.ifEmpty { "阅读" },
                    subtitle = subtitleText(),
                    controlsVisible = controlsVisible,
                    passiveTitle = passiveHeader.ifEmpty { bookTitle },
                    readingProgress = percent,
                    onBack = { finish() },
                    onToc = { showToc = true },
                    onDisplay = {
                        if (book != null) {
                            beginDisplayFormatting()
                            nav.push(RouteDisplay)
                        }
                    },
                    onSeek = { nav.push(RouteSeek) },
                    onPrevPage = { reader.turnPage(false, true) },
                    onNextPage = { reader.turnPage(true, true) },
                    readerContent = {
                        ReaderPageTurn(
                            pageSerial = pageTurnSerial,
                            forward = pageTurnForward,
                            animation = pageAnimationState,
                            enabled = nav.current == RouteRead,
                        ) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { reader },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                    // 来源操作与字号/进度共用底栏；顶栏只留目录。
                    bottomExtraActions = if (sourceStatus.isEmpty()) {
                        null
                    } else {
                        { MochiBarButton("番茄", onClick = { requestSource() }) }
                    },
                    overlay = {
                        when {
                            loadError != null -> Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MochiDefaults.groupedBackground())
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                MochiListSection(header = "打开失败", footer = loadError) {
                                    MochiListRow("无法打开这本书", subtitle = loadError, destructive = true)
                                }
                            }

                            loading -> Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MochiDefaults.groupedBackground())
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                MochiListSection {
                                    MochiListRow(loadPhase, loading = true)
                                }
                            }
                        }
                    },
                    readingPalette = palette,
                )

                RouteToc -> {
                    val titles = remember(tocVersion) { reader.chapterTitles() }
                    val offsets = remember(tocVersion) { reader.chapterOffsets() }
                    var limit by remember { mutableIntStateOf(100) }
                    ReaderTocPage(
                        title = bookTitle.ifEmpty { "目录" },
                        chapterTitles = titles,
                        selectedIndex = currentChapterIndex(offsets),
                        limit = limit,
                        onShowMore = { limit += 100 },
                        onPick = { index ->
                            offsets.getOrNull(index)?.let { reader.seekTo(it) }
                            nav.pop()
                        },
                        onBack = { nav.pop() },
                    )
                }

                RouteDisplay -> ReaderDisplayPage(
                    title = bookTitle.ifEmpty { "阅读设置" },
                    fontSize = fontState,
                    fontFamily = familyState,
                    onFontSize = { requested ->
                        val size = ReadingRules.clampFontSize(requested)
                        if (size != fontState) {
                            fontState = size
                            reader.setFontSize(size)
                            prefs.edit().putInt(PhoneLibrary.FONT_SIZE, size).apply()
                        }
                    },
                    onFontFamily = { family ->
                        if (family != familyState) {
                            familyState = family
                            reader.setFontFamily(family)
                            prefs.edit().putString("fontFamily", family).apply()
                        }
                    },
                    lineSpacing = lineSpacingState,
                    readingTheme = readingThemeState,
                    pageAnimation = pageAnimationState,
                    onLineSpacing = { requested ->
                        val next = ReadingAppearance.clampLineHeight(requested)
                        if (next != lineSpacingState) {
                            lineSpacingState = next
                            reader.setLineHeightMultiplier(next)
                            prefs.edit().putFloat(ReadingAppearance.KEY_LINE_HEIGHT, next).apply()
                        }
                    },
                    onReadingTheme = { requested ->
                        val next = ReadingAppearance.normalizeTheme(requested)
                        if (next != readingThemeState) {
                            readingThemeState = next
                            prefs.edit().putString(ReadingAppearance.KEY_THEME, next).apply()
                        }
                    },
                    onPageAnimation = { requested ->
                        val next = ReadingAppearance.normalizeAnimation(requested)
                        if (next != pageAnimationState) {
                            pageAnimationState = next
                            prefs.edit().putString(ReadingAppearance.KEY_ANIMATION, next).apply()
                        }
                    },
                    onBack = { nav.pop() },
                )

                RouteSeek -> ReaderSeekPage(
                    title = bookTitle.ifEmpty { "跳转" },
                    percent = percent,
                    onSeek = { value ->
                        val total = reader.charCount
                        if (total > 0) reader.seekTo((total.toLong() * value / 100L).toInt())
                        nav.pop()
                    },
                    onBack = { nav.pop() },
                )
            }
        }
        val titles = remember(tocVersion, showToc) { reader.chapterTitles() }
        val offsets = remember(tocVersion, showToc) { reader.chapterOffsets() }
        ReaderChapterSheet(showToc, bookTitle, titles, currentChapterIndex(offsets),
            onPick = { index -> offsets.getOrNull(index)?.let { reader.seekTo(it) } },
            onDismiss = { showToc = false })
    }

    private fun subtitleText(): String {
        val parts = ArrayList<String>(3)
        if (pageLabel.isNotEmpty()) parts += pageLabel
        parts += "$percent%"
        if (sourceStatus.isNotEmpty()) parts += sourceStatus
        return parts.joinToString(" · ")
    }

    private fun currentChapterIndex(offsets: List<Int>): Int {
        val current = reader.charOffset
        var matched = 0
        for (index in offsets.indices) {
            if (offsets[index] <= current) matched = index else break
        }
        return matched
    }

    /**
     * 进入显示设置时只记一次 anchor。之后字号/字体/行距连续重排都围绕这个字符偏移，
     * 不再把每次 current.startChar 当成新进度，避免 6174→5119 这类累计漂移。
     */
    private fun beginDisplayFormatting() {
        if (formattingDisplay) return
        // lastOffset / restoreAnchor 是已保存的权威字符偏移；它们可能落在当前页中间，
        // 比 current.startChar 精确。首帧尚未回调时也优先用 restoreAnchor。
        val current = when {
            progressLoaded -> lastOffset
            restoreAnchor > 0 -> restoreAnchor
            else -> reader.charOffset
        }
        displayAnchor = current.coerceAtLeast(0)
        formattingDisplay = true
    }

    /** 离开设置页：按 anchor 重新落页，最终保存的仍是进入设置时的字符偏移。 */
    private fun endDisplayFormatting() {
        if (!formattingDisplay) return
        val anchor = displayAnchor.coerceAtLeast(0)
        // 仍处于 formatting 状态，seekTo 触发的回调不会覆盖 anchor/时间戳。
        reader.seekTo(anchor)
        formattingDisplay = false
        displayAnchor = -1
        lastOffset = anchor
        lastPercent = reader.percent
        progressLoaded = true
        saveProgress()
    }

    private fun loadBook() {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            loading = false
            loadError = "缺少书籍路径"
            return
        }
        val file = File(path)
        bookFile = file
        scope.launch {
            loading = true
            loadError = null
            try {
                val dir = PhoneLibrary.indexDir(this@PhoneReaderActivity)
                val opened = withContext(Dispatchers.IO) {
                    BookCache.open(file, dir) { phase ->
                        val text = if (phase == Book.LoadPhase.BUILD_INDEX) "正在建立索引…" else "正在加载…"
                        runOnUiThread { if (!destroyed) loadPhase = text }
                    }
                }
                if (destroyed) return@launch
                book = opened
                reader.setPageCacheDir(dir)
                bookTitle = opened.title.ifEmpty { file.name }
                val offset = ReadingRules.clampOffset(prefs.getInt(offsetKey(), 0), opened.charCount())
                lastOffset = offset
                lastTimestamp = prefs.getLong(timestampKey(), 0)
                restoreAnchor = offset
                reader.setBook(opened)
                reader.restoreTo(offset)
                loading = false
                tocVersion++
                if (isSourceBook()) sourceStatus = "可连接番茄同步"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                loading = false
                loadError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    private fun isSourceBook(): Boolean {
        val file = bookFile ?: return false
        return try {
            DownloadedNovel.candidate(file, File(filesDir, "books"))
        } catch (t: Throwable) {
            false
        }
    }

    private fun requestSource() {
        if (book == null) return
        if (downloaded != null) {
            downloaded?.synchronize(true)
            sourceStatus = "正在同步…"
            return
        }
        val client = sourceClient ?: RemotePluginClient(this).also { sourceClient = it }
        try {
            pluginLauncher.launch(client.bootstrapIntent())
        } catch (t: Throwable) {
            sourceStatus = "插件未连接：${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun connectSource() {
        val client = sourceClient ?: return
        val current = book ?: return
        scope.launch {
            try {
                val accountScope = withContext(Dispatchers.IO) {
                    client.refresh()
                    if (!client.loggedIn()) throw IOException("插件未登录")
                    client.accountScope()
                }
                if (destroyed) return@launch
                val reading = DownloadedReading(
                    this@PhoneReaderActivity,
                    current,
                    client,
                    accountScope,
                    lastOffset,
                    lastTimestamp,
                    object : DownloadedReading.Listener {
                        override fun restore(offset: Int, timestamp: Long) {
                            lastTimestamp = timestamp
                            val target = ReadingRules.clampOffset(offset, reader.charCount)
                            // 设置页打开时收到云端进度：把它作为新的显示 anchor，避免离开设置时被旧 anchor 覆盖。
                            if (formattingDisplay) displayAnchor = target
                            reader.seekTo(target)
                            saveProgress()
                        }

                        override fun status(status: String) {
                            sourceStatus = status
                        }
                    },
                )
                downloaded = reading
                reading.synchronize(false)
                sourceStatus = reading.status()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                sourceStatus = "同步失败，本地进度已保留"
            }
        }
    }

    // ---------------- 进度 ----------------

    private fun cacheId(): String = bookFile?.let { Book.cacheId(it) } ?: ""

    private fun offsetKey(): String = "progress.${cacheId()}.offset"

    private fun timestampKey(): String = "progress.${cacheId()}.timestamp"

    private fun saveProgress(sync: Boolean = false) {
        val file = bookFile ?: return
        if (!progressLoaded) return
        val offset = if (formattingDisplay && displayAnchor >= 0) displayAnchor else lastOffset
        val editor = prefs.edit()
            .putInt("progress.${Book.cacheId(file)}.offset", offset)
            .putLong("progress.${Book.cacheId(file)}.timestamp", lastTimestamp)
            .putString("progress.${Book.cacheId(file)}", "$offset|${reader.pageIndex}|$lastPercent")
        // 翻页路径用 apply 避免每页同步 commit 卡 UI；onPause/onDestroy 再 commit 收口。
        if (sync) editor.commit() else editor.apply()
    }

    override fun onProgressChanged(charOffset: Int, percentValue: Float) {
        if (destroyed) return
        percent = percentValue.roundToInt()
        passiveHeader = if (reader.isChapterStartPage) bookTitle else reader.currentChapterTitle() ?: bookTitle
        if (formattingDisplay) {
            // 显示设置里的字号/字体/行距重排不是阅读翻页：不写 offset、不改时间戳、不通知云端。
            return
        }
        if (!progressLoaded && restoreAnchor > 0) {
            if (ReadingRules.isRestorePlaceholder(restoreAnchor, charOffset)) {
                // setBook 的初始页：只刷新显示，不写回，避免把旧进度冲成 0
                return
            }
            // 恢复目标可能落在页中间：首帧页首（如 5119）不是新进度，保留进入设置前的
            // 精确 anchor（如 6174），避免下次打开又漂移。
            lastOffset = restoreAnchor
            lastPercent = percentValue
            progressLoaded = true
            saveProgress()
            return
        }
        val moved = progressLoaded && lastOffset != charOffset
        lastOffset = charOffset
        lastPercent = percentValue
        if (moved) lastTimestamp = maxOf(System.currentTimeMillis(), lastTimestamp + 1)
        progressLoaded = true
        saveProgress()
        if (moved) downloaded?.changed(charOffset, lastTimestamp)
    }

    override fun onPageChanged(pageIndex: Int, pageCount: Int) {
        pageLabel = "${pageIndex + 1}/$pageCount"
        if (pageIndex != lastAnimatedPage) {
            if (lastAnimatedPage >= 0) pageTurnForward = pageIndex > lastAnimatedPage
            lastAnimatedPage = pageIndex
            pageTurnSerial++
        }
    }

    override fun onChromeToggleRequested() { controlsVisible = !controlsVisible }

    override fun onSeekRequested() {
        routeRequest = RouteSeek
    }

    override fun onTocRequested() {
        routeRequest = RouteToc
    }

    override fun onPause() {
        super.onPause()
        saveProgress(sync = true)
        downloaded?.flush()
        try {
            reader.flushPageIndex()
        } catch (t: Throwable) {
            // 页码缓存失败只是下次多量一遍
        }
    }

    override fun onDestroy() {
        destroyed = true
        saveProgress(sync = true)
        try {
            reader.flushPageIndex()
        } catch (t: Throwable) {
            // 同上
        }
        downloaded?.flush()
        downloaded?.close()
        runCatching { sourceClient?.close() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
