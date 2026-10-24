package com.watchreader.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.basic.MochiSheet
import ui.feice.mochi.basic.MochiSheetContent
import ui.feice.mochi.basic.MochiSheetDetent
import ui.feice.mochi.basic.MochiSheetHeader
import ui.feice.mochi.basic.MochiToastHost
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.rememberMochiSheetState
import ui.feice.mochi.basic.rememberMochiToastState
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.list.MochiListSliderRow
import ui.feice.mochi.navigation.MochiCompactTitleScaffold
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.MochiPeerTransition
import ui.feice.mochi.navigation.rememberMochiNavigator
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.utils.mochiEnableEdgeToEdge

private const val HomeRoute = "home"
private const val SettingsRoute = "settings"

private val ImportMimeTypes = arrayOf("text/plain", "text/*", "application/octet-stream")

private enum class RootTab { Home, Fanqie, Settings }

private fun rootTab(value: String?): RootTab = when (value) {
    RootTabFanqieValue -> RootTab.Fanqie
    RootTabSettingsValue -> RootTab.Settings
    else -> RootTab.Home
}

class PhoneMainActivity : ComponentActivity() {

    /** onResume（含从旧阅读页/官方缓存页返回）自增，用于只刷新本地进度。 */
    private val libraryRevision = mutableIntStateOf(1)

    private lateinit var fanqie: FanqieController
    private val requestedTab = mutableStateOf(RootTabHomeValue)
    private var tabRequestId by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        mochiEnableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PhoneLibrary.ensurePhoneFontSize(this)
        // Controller 在 STARTED 之前创建，插件 ActivityResult 只注册一次；首次进入番茄才连接。
        fanqie = FanqieController(this)
        requestedTab.value = intent?.getStringExtra(ExtraRootTab) ?: RootTabHomeValue
        setContent {
            PhoneApp(
                revision = libraryRevision.intValue,
                fanqie = fanqie,
                requestedTab = requestedTab.value,
                requestId = tabRequestId,
                applyInitialTab = savedInstanceState == null,
                onRefreshLibrary = { libraryRevision.intValue++ },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        libraryRevision.intValue++
        fanqie.onHostResume()
    }

    override fun onPause() {
        fanqie.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        fanqie.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab.value = intent.getStringExtra(ExtraRootTab) ?: RootTabHomeValue
        tabRequestId++
    }
}

@Composable
private fun PhoneApp(
    revision: Int,
    fanqie: FanqieController,
    requestedTab: String,
    requestId: Int,
    applyInitialTab: Boolean,
    onRefreshLibrary: () -> Unit,
) {
    ReadingAppTheme {
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

        var tabIndex by rememberSaveable { mutableIntStateOf(rootTab(requestedTab).ordinal) }
        val tab = RootTab.entries[tabIndex.coerceIn(RootTab.entries.indices)]
        val homeNav = rememberMochiNavigator(HomeRoute)
        val fanqieNav = rememberMochiNavigator(FanqieRootRoute)
        val settingsNav = rememberMochiNavigator(SettingsRoute)
        val toast = rememberMochiToastState()

        LaunchedEffect(requestId) {
            if (requestId > 0) tabIndex = rootTab(requestedTab).ordinal
        }
        LaunchedEffect(Unit) {
            if (applyInitialTab) tabIndex = rootTab(requestedTab).ordinal
        }

        val selectTab: (Int) -> Unit = { index ->
            val next = RootTab.entries[index.coerceIn(RootTab.entries.indices)]
            if (next == tab) {
                when (next) {
                    RootTab.Home -> homeNav.popTo(0)
                    RootTab.Fanqie -> fanqieNav.popTo(0)
                    RootTab.Settings -> settingsNav.popTo(0)
                }
            } else {
                tabIndex = next.ordinal
            }
        }

        // 分组/详情/阅读等子页由 MochiNavHost 处理返回；根页返回主页。
        val subPageHandlesBack = tab == RootTab.Fanqie && fanqieNav.canPop
        BackHandler(enabled = tab != RootTab.Home && !subPageHandlesBack) {
            tabIndex = RootTab.Home.ordinal
        }

        val showRootBar = tab != RootTab.Fanqie || fanqieNav.current == FanqieRootRoute

        ProvideMochiOverscroll {
            Box(Modifier.fillMaxSize()) {
                // 三个根标签共用同一 Activity；底栏在 PeerTransition 外，固定不随页面滑动。
                MochiPeerTransition(
                    targetState = tab,
                    pages = if (subPageHandlesBack) null else RootTab.entries,
                    onPageChange = { tabIndex = it.ordinal },
                    modifier = Modifier.fillMaxSize(),
                ) { root ->
                    when (root) {
                        RootTab.Home -> MochiNavHost(homeNav, Modifier.fillMaxSize()) {
                            LibraryPage(
                                revision = revision,
                                onMessage = { message -> toast.show(message) },
                            )
                        }

                        RootTab.Fanqie -> fanqie.FanqieContent(
                            nav = fanqieNav,
                            onGoHome = { tabIndex = RootTab.Home.ordinal },
                        )

                        RootTab.Settings -> MochiNavHost(settingsNav, Modifier.fillMaxSize()) {
                            SettingsPage(
                                onRefreshLibrary = onRefreshLibrary,
                                onGoHome = { tabIndex = RootTab.Home.ordinal },
                            )
                        }
                    }
                }
                if (showRootBar) {
                    RootTabBar(
                        selected = tab.ordinal,
                        onSelect = selectTab,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                MochiToastHost(toast)
            }
        }
    }
}

@Composable
private fun LibraryPage(
    revision: Int,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    var books by remember { mutableStateOf(emptyList<PhoneBook>()) }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val filter = ShelfReadFilter.entries[filterIndex.coerceIn(ShelfReadFilter.entries.indices)]
    val sort = ShelfSort.entries[sortIndex.coerceIn(ShelfSort.entries.indices)]

    val reload: suspend () -> Unit = {
        scanning = true
        error = null
        try {
            books = withContext(Dispatchers.IO) { PhoneLibrary.scan(context) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            error = t.message ?: t.javaClass.simpleName
        } finally {
            scanning = false
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = runInterruptible(Dispatchers.IO) { PhoneLibrary.importBook(context, uri) }
                onMessage("已导入 $name")
                reload()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onMessage("导入失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onMessage("未授权存储：只显示 App 私有目录里的书")
        scope.launch { reload() }
    }

    LaunchedEffect(revision) {
        if (!askedPermission && !PhoneLibrary.hasPublicAccess(context)) {
            askedPermission = true
            permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        reload()
    }

    val visible = remember(books, query, filter, sort) {
        ShelfRules.localVisible(books, query, filter, sort)
    }
    val subtitle = if (scanning && books.isEmpty()) "正在扫描本机 TXT…" else "本地书库 · ${visible.size}/${books.size} 本"
    LaunchedEffect(query, filter, sort) { gridState.scrollToItem(0) }

    val importSheet = rememberMochiSheetState(listOf(MochiSheetDetent.Medium))
    LaunchedEffect(showImport) { if (showImport) importSheet.show() else importSheet.hide() }

    ShelfGridScreen(
        title = "主页",
        subtitle = subtitle,
        actionLabel = "导入",
        actionNavId = "phone.home.import",
        onAction = { showImport = true },
        reserveBottomBar = true,
        state = gridState,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "home-search") {
            MochiSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "按书名 / 作者筛选",
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "home-filter") {
            ShelfFilterSortCard(
                filter = filter,
                sort = sort,
                onFilterClick = { showFilter = true },
                onSortClick = { showSort = true },
            )
        }

        if (error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home-error") {
                MochiListSection(header = "扫描失败", footer = error) {
                    MochiListRow("无法读取书库", subtitle = error, destructive = true)
                    MochiListButton("重试", onClick = { scope.launch { reload() } })
                }
            }
        }
        if (scanning && books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home-scanning") {
                MochiListSection {
                    MochiListRow("正在扫描本机 TXT…", loading = true)
                }
            }
        }
        if (!scanning && error == null && books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home-empty") {
                Column(Modifier.fillMaxWidth()) {
                    MochiListSection(
                        header = "本机书库",
                        footer = "支持 UTF-8 / GBK 的 TXT。导入后会复制到 App 私有目录，卸载时一起删除。",
                    ) {
                        MochiListRow(
                            title = "还没有书",
                            subtitle = "点右上角「导入」选择 TXT，或放到 Download 目录后重新扫描",
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    MochiButton(
                        onClick = { showImport = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入 TXT", style = MochiTheme.textStyles.bodyLarge)
                    }
                }
            }
        }
        if (books.isNotEmpty() && visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home-no-match") {
                MochiListSection(header = "筛选结果", footer = "换个关键词，或把阅读状态改回「全部」。") {
                    MochiListRow("没有匹配的书", subtitle = "当前共 ${books.size} 本")
                }
            }
        }

        items(visible, key = { it.file.absolutePath }) { book ->
            ShelfBookCard(
                title = book.title,
                info = ShelfRules.localInfoText(book),
                onClick = { openBook(context, book.file, onMessage) },
            ) {
                CoverPlaceholder(
                    title = book.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ShelfRules.CoverAspect),
                    shape = MochiTheme.shapes.medium,
                )
            }
        }
    }

    MochiSheet(state = importSheet, onDismiss = { showImport = false }) {
        MochiSheetHeader(
            "导入",
            actions = { MochiBarButton("关闭", onClick = { showImport = false }) },
        )
        MochiSheetContent {
            MochiListSection(
                footer = "官方缓存需要 root；没有 root 时会直接放弃，主程序不读取 Cookie 或账号文件。",
            ) {
                MochiListRow(
                    "导入本机 TXT",
                    subtitle = "通过系统文件选择器复制到私有书库",
                    chevron = true,
                    onClick = {
                        showImport = false
                        importer.launch(ImportMimeTypes)
                    },
                )
                MochiListRow(
                    "导入官方番茄缓存（Root）",
                    subtitle = "只读官方 App 已下载的本地缓存",
                    chevron = true,
                    onClick = {
                        showImport = false
                        if (!openOfficialCache(context)) onMessage("缓存导入入口尚未就绪")
                    },
                )
                MochiListRow(
                    "重新扫描书库",
                    subtitle = "刷新新文件与阅读进度",
                    chevron = true,
                    onClick = {
                        showImport = false
                        if (!scanning) scope.launch { reload() }
                    },
                )
            }
        }
    }

    ShelfChoiceSheet(
        visible = showFilter,
        title = "阅读状态",
        options = ShelfReadFilter.entries.map { it.label },
        selected = ShelfReadFilter.entries.indexOf(filter),
        onSelect = { filterIndex = it },
        onDismiss = { showFilter = false },
    )
    ShelfChoiceSheet(
        visible = showSort,
        title = "排序",
        options = ShelfSort.entries.map { it.label },
        selected = ShelfSort.entries.indexOf(sort),
        onSelect = { sortIndex = it },
        onDismiss = { showSort = false },
    )
}

@Suppress("DEPRECATION")
@Composable
private fun SettingsPage(
    onRefreshLibrary: () -> Unit,
    onGoHome: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PhoneLibrary.PREFS, Context.MODE_PRIVATE) }
    var fontSize by remember {
        mutableIntStateOf(
            prefs.getInt(PhoneLibrary.FONT_SIZE, PhoneLibrary.PHONE_FONT_SIZE).coerceIn(12, 30),
        )
    }
    var lineSpacing by remember {
        mutableStateOf(ReadingAppearance.clampLineHeight(prefs.getFloat(ReadingAppearance.KEY_LINE_HEIGHT, ReadingAppearance.DEFAULT_LINE_HEIGHT)))
    }
    var readingTheme by remember {
        mutableStateOf(ReadingAppearance.normalizeTheme(prefs.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME)))
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
            when (key) {
                PhoneLibrary.FONT_SIZE -> fontSize = source.getInt(PhoneLibrary.FONT_SIZE, PhoneLibrary.PHONE_FONT_SIZE).coerceIn(12, 30)
                ReadingAppearance.KEY_LINE_HEIGHT -> lineSpacing = ReadingAppearance.clampLineHeight(source.getFloat(ReadingAppearance.KEY_LINE_HEIGHT, ReadingAppearance.DEFAULT_LINE_HEIGHT))
                ReadingAppearance.KEY_THEME -> readingTheme = ReadingAppearance.normalizeTheme(source.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "未知"
    }

    Box(Modifier.fillMaxSize()) {
        MochiCompactTitleScaffold(
            title = "设置",
            subtitle = "阅读外观 · 书库",
        ) {
            MochiListSection(
                header = "正文字号",
                footer = "当前 ${fontSize}sp，作用于阅读页；下次打开书生效。",
            ) {
                MochiListRow("字号", value = "$fontSize sp")
                MochiListSliderRow(
                    value = fontSize.toFloat(),
                    onValueChange = {
                        val next = it.roundToInt().coerceIn(12, 30)
                        if (next != fontSize) {
                            fontSize = next
                            prefs.edit().putInt(PhoneLibrary.FONT_SIZE, next).apply()
                        }
                    },
                    valueRange = 12f..30f,
                    steps = 17,
                )
            }

            ReaderAppearanceSections(
                lineSpacing = lineSpacing,
                readingTheme = readingTheme,
                onLineSpacing = { requested ->
                    val next = ReadingAppearance.clampLineHeight(requested)
                    lineSpacing = next
                    prefs.edit().putFloat(ReadingAppearance.KEY_LINE_HEIGHT, next).apply()
                },
                onReadingTheme = { requested ->
                    val next = ReadingAppearance.normalizeTheme(requested)
                    readingTheme = next
                    prefs.edit().putString(ReadingAppearance.KEY_THEME, next).apply()
                },
            )
            MochiListSection(
                header = "书库",
                footer = "卸载 App 会删除私有书库里的书，重要文件请保留原文件。",
            ) {
                MochiListButton("返回主页并重新扫描", onClick = {
                    onRefreshLibrary()
                    onGoHome()
                })
            }

            MochiListSection(header = "关于") {
                MochiListRow("版本", value = versionName)
            }

            // 根底栏固定浮在页面外，这里留出与底栏等高的可点击区。
            Spacer(Modifier.height(84.dp))
        }
    }
}

private fun openBook(context: Context, file: File, onMessage: (String) -> Unit) {
    val intent = Intent(context, PhoneReaderActivity::class.java)
        .putExtra(PhoneReaderActivity.EXTRA_PATH, file.absolutePath)
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        onMessage("无法打开：${t.message ?: t.javaClass.simpleName}")
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
