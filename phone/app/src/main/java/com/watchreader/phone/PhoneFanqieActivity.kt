package com.watchreader.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.watchreader.Book
import com.watchreader.ReaderView
import com.watchreader.fanqie.ChapterPosition
import com.watchreader.fanqie.ReaderPlugin.BookDetails
import com.watchreader.fanqie.ReaderPlugin.BookInfo
import com.watchreader.fanqie.ReaderPlugin.ChapterContent
import com.watchreader.fanqie.ReaderPlugin.ChapterInfo
import com.watchreader.fanqie.ReaderPlugin.Comment
import com.watchreader.fanqie.ReaderPlugin.Paragraph
import com.watchreader.fanqie.ReaderPlugin.ReadingProgress
import com.watchreader.fanqie.ReaderPlugin.ShelfGroup
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.basic.MochiTextField
import ui.feice.mochi.basic.MochiToastHost
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.rememberMochiToastState
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.list.MochiListButton
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiCompactTitleScaffold
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.MochiNavigator
import ui.feice.mochi.navigation.rememberMochiNavigator
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.utils.mochiEnableEdgeToEdge

private const val RouteShelf = "shelf"
private const val RouteGroupPrefix = "group/"
private const val RouteSearch = "search"

/** 主壳创建番茄标签导航器时使用的根路由。 */
internal const val FanqieRootRoute = RouteShelf
private const val RouteBook = "book"
private const val RouteToc = "toc"
private const val RouteReader = "reader"
private const val RouteParagraphs = "paragraphs"
private const val RouteComments = "comments"
private const val RouteCompose = "compose"
private const val RouteCover = "cover"
private const val RouteDownload = "download"
private const val RouteAccount = "account"
private const val RouteFanqieDisplay = "fanqie-display"
private const val RouteFanqieSeek = "fanqie-seek"
private const val SearchNav = "fanqie.search"
private const val GroupTtlMs = 12L * 60 * 60 * 1000

/**
 * 番茄业务控制器：由 PhoneMainActivity 持有，走同一个主壳导航。
 * 只经 [FanqieSession] 调用既有插件接口，host 不接触 Cookie。
 * 旧 PhoneFanqieActivity 现在只是重定向到主壳的兼容入口。
 */
internal class FanqieController(private val host: ComponentActivity) : ContextWrapper(host) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: FanqieSession
    private lateinit var prefs: SharedPreferences
    private var destroyed = false

    private var controlsVisible by mutableStateOf(false)
    private var showToc by mutableStateOf(false)
    private var chapterStart by mutableStateOf(true)
    private var connecting by mutableStateOf(false)
    private var loggedIn by mutableStateOf(false)
    private var fatal by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var notice by mutableStateOf("")

    private var groups by mutableStateOf<List<ShelfGroup>>(emptyList())
    private var groupId by mutableStateOf("")
    private var shelf by mutableStateOf<List<BookInfo>>(emptyList())
    private var shelfNext by mutableStateOf("")
    private var shelfMore by mutableStateOf(false)

    private var shelfFilter by mutableStateOf(ShelfReadFilter.All)
    private var shelfSort by mutableStateOf(ShelfSort.Default)
    private var progressById by mutableStateOf<Map<String, ReadingProgress>>(emptyMap())
    /** 换账号后自增，让封面/进度状态整体失效，避免跨账号残留。 */
    private var sessionRevision by mutableIntStateOf(0)

    private var keyword by mutableStateOf("")
    private var searchResults by mutableStateOf<List<BookInfo>>(emptyList())
    private var searchNext by mutableStateOf("")
    private var searchMore by mutableStateOf(false)
    private var searched by mutableStateOf(false)

    private var selected by mutableStateOf<BookInfo?>(null)
    private var details by mutableStateOf<BookDetails?>(null)
    private var chapters by mutableStateOf<List<ChapterInfo>>(emptyList())
    private var chapterIndex by mutableIntStateOf(0)
    private var chapter by mutableStateOf<ChapterContent?>(null)
    private var chapterBook by mutableStateOf<Book?>(null)
    private var readerOffset by mutableIntStateOf(0)
    private var readerPercent by mutableIntStateOf(0)
    private var chapterLoading by mutableStateOf(false)
    private var syncStatus by mutableStateOf("尚未同步")
    private var resumeProgress by mutableStateOf<ReadingProgress?>(null)
    private var lastTimestamp = 0L
    /** 本章准备恢复到的偏移；用来挡掉 setBook 首次回调的 0 偏移，避免覆盖云端进度。 */
    private var restoreAnchor = 0

    private var paragraph by mutableStateOf<Paragraph?>(null)
    private var comments by mutableStateOf<List<Comment>>(emptyList())
    private var commentsNext by mutableStateOf("")
    private var commentsMore by mutableStateOf(false)
    private var draft by mutableStateOf("")
    private var confirming by mutableStateOf(false)
    private var publishing by mutableStateOf(false)
    private var publishError by mutableStateOf<String?>(null)

    private var cover by mutableStateOf<Bitmap?>(null)
    private var coverError by mutableStateOf<String?>(null)

    private var downloading by mutableStateOf(false)
    private var downloadDone by mutableIntStateOf(0)
    private var downloadTotal by mutableIntStateOf(0)
    private var downloadThreads by mutableIntStateOf(3)
    private var downloadResult by mutableStateOf<File?>(null)
    private var downloadError by mutableStateOf<String?>(null)
    private val cancelDownload = java.util.concurrent.atomic.AtomicBoolean(false)

    private var pendingRoute by mutableStateOf<String?>(null)
    /** 从书架卡片打开详情时的 mochiNavSource key；异步加载完成后随 push 一起使用。 */
    private var pendingSource by mutableStateOf<String?>(null)
    private var currentRoute by mutableStateOf(RouteShelf)
    private var fontSize by mutableIntStateOf(18)
    private var fontFamily by mutableStateOf("sans-serif")
    private var lineSpacing by mutableStateOf(ReadingAppearance.DEFAULT_LINE_HEIGHT)
    private var readingTheme by mutableStateOf(ReadingAppearance.DEFAULT_THEME)
    private var pageAnimation by mutableStateOf(ReadingAppearance.DEFAULT_ANIMATION)
    private var formattingDisplay = false
    private var displayAnchor = 0
    private var restoringReader = false
    private var pageTurnSerial by mutableIntStateOf(0)
    private var pageTurnForward by mutableStateOf(true)
    private var lastAnimatedPage = -1
    private var currentReader: ReaderView? = null
    private var syncJob: Job? = null

    private val snapshotStore = FanqieSnapshotStore(host)
    private var snapshotScopeKey by mutableStateOf("")
    private var snapshotSavedAt by mutableStateOf(0L)
    private var snapshotLoaded by mutableStateOf(false)
    private var previewById by mutableStateOf<Map<String, List<BookInfo>>>(emptyMap())
    private var coverUrls by mutableStateOf<Map<String, String>>(emptyMap())
    private var started by mutableStateOf(false)
    private var bootstrapped = false
    private var awaitingBootstrap = false
    private val groupCache = LinkedHashMap<String, GroupCache>()
    private var loadingGroupId = ""
    private val previewLoading = HashSet<String>()
    private var persistJob: Job? = null

    private val pluginLauncher = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (awaitingBootstrap) {
            awaitingBootstrap = false
            connecting = false
            bootstrapped = result.resultCode == Activity.RESULT_OK
            if (bootstrapped) connectAndLoad() else fatal = "插件连接未完成，请重试"
        } else if (result.resultCode == Activity.RESULT_OK) {
            connectAndLoad()
        } else notice = "未修改插件账号"
    }

    init {
        prefs = host.getSharedPreferences(PhoneLibrary.PREFS, Context.MODE_PRIVATE)
        readAppearancePreferences()
        downloadThreads = prefs.getInt("downloadThreads", 3).coerceIn(1, 4)
        session = FanqieSession(host)
    }

    private data class GroupCache(
        val books: List<BookInfo>,
        val next: String,
        val more: Boolean,
        val fetchedAt: Long,
    )

    @Composable
    internal fun FanqieContent(nav: MochiNavigator, onGoHome: () -> Unit) {
        val toast = rememberMochiToastState()
        ReadingSystemBars(nav.current == RouteReader && !controlsVisible)
        LaunchedEffect(Unit) { ensureStarted() }
        LaunchedEffect(nav.current) {
            currentRoute = nav.current
            if (nav.current != RouteFanqieDisplay) endDisplayFormatting()
            // 从阅读/详情返回书架只刷新本机进度，不发起网络书架请求。
            if (nav.current == RouteShelf && loggedIn) refreshShelfProgress()
        }
        androidx.activity.compose.BackHandler(nav.current == RouteReader && controlsVisible) { controlsVisible = false }

        LaunchedEffect(pendingRoute) {
            val target = pendingRoute ?: return@LaunchedEffect
            pendingRoute = null
            val source = pendingSource
            pendingSource = null
            if (target == RouteToc) {
                showToc = true
            } else if (nav.current != target) {
                nav.push(target, from = source)
            }
        }
        LaunchedEffect(notice) {
            if (notice.isNotEmpty()) {
                toast.show(notice)
                notice = ""
            }
        }

        MochiNavHost(nav, Modifier.fillMaxSize()) { route ->
            when {
                route == RouteShelf -> GroupsPage(nav)
                route.startsWith(RouteGroupPrefix) -> GroupPage(nav, route.removePrefix(RouteGroupPrefix))
                route == RouteSearch -> SearchPage(nav)
                route == RouteBook -> BookPage(nav)
                route == RouteToc -> TocPage(nav)
                route == RouteReader -> ReaderRoute(nav)
                route == RouteParagraphs -> ParagraphPage(nav)
                route == RouteComments -> CommentsPage(nav)
                route == RouteCompose -> ComposePage(nav)
                route == RouteCover -> CoverPage(nav)
                route == RouteDownload -> DownloadPage(nav)
                route == RouteAccount -> AccountPage(nav, onGoHome)
                route == RouteFanqieDisplay -> DisplayPage(nav)
                route == RouteFanqieSeek -> SeekPage(nav)
                else -> GroupsPage(nav)
            }
        }
        MochiToastHost(toast)
        ReaderChapterSheet(showToc, selected?.title.orEmpty(), chapters.map { it.title }, chapterIndex,
            onPick = { openChapter(it) }, onDismiss = { showToc = false })
    }

    // ---------------- 书架 / 搜索 ----------------

    private suspend fun refreshShelfProgress() {
        if (!loggedIn) return
        val ids = buildList {
            addAll(shelf.map { it.id })
            groupCache.values.forEach { cache -> addAll(cache.books.map { it.id }) }
            previewById.values.forEach { books -> addAll(books.map { it.id }) }
        }.distinct()
        if (ids.isEmpty()) return
        val revision = sessionRevision
        val fresh = session.progressMap(ids)
        if (revision != sessionRevision) return
        progressById = (progressById - ids.toSet()) + fresh
    }

    private fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            if (!snapshotLoaded) {
                val snapshot = withContext(Dispatchers.IO) { snapshotStore.loadLast() }
                if (snapshot != null) applySnapshot(snapshot) else snapshotLoaded = true
            }
            connectAndLoad()
        }
    }

    @Composable
    private fun GroupsPage(nav: MochiNavigator) {
        val gridState = rememberLazyGridState()
        val realGroups = remember(groups) { groups.filter { it.id.isNotBlank() } }
        val total = remember(realGroups) { realGroups.sumOf { maxOf(0, it.count) } }
        val subtitle = when {
            connecting -> "正在连接插件…"
            fatal != null -> "连接失败"
            !loggedIn -> "未连接账号"
            else -> "${realGroups.size} 组 · $total 本"
        }
        ShelfGridScreen(
            title = "番茄",
            subtitle = subtitle,
            actionLabel = "搜索",
            actionNavId = SearchNav,
            onAction = { nav.push(RouteSearch) },
            reserveBottomBar = true,
            state = gridState,
        ) {
            if (!snapshotLoaded) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-snapshot-loading") {
                    MochiListSection { MochiListRow("正在读取本地书架快照…", loading = true) }
                }
            }
            if (connecting) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-connecting") {
                    MochiListSection { MochiListRow("正在连接番茄插件…", loading = true) }
                }
            }
            if (fatal != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-error") {
                    MochiListSection(header = "连接失败", footer = fatal) {
                        MochiListRow("无法读取番茄数据", subtitle = fatal, destructive = true)
                        MochiListButton("重试", onClick = { retryConnection() })
                    }
                }
            }
            if (!connecting && fatal == null && !loggedIn) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-account") {
                    MochiListSection(
                        header = "账号",
                        footer = "账号只保存在插件里；主程序不读取 Cookie。",
                    ) {
                        MochiListRow("尚未导入番茄账号", subtitle = "先在插件设置里导入 Cookie")
                        MochiListButton("打开插件账号设置", onClick = { openPluginSetup() })
                    }
                }
            }
            if (groups.isNotEmpty() || (loggedIn && fatal == null)) {
                if (realGroups.isEmpty() && !busy) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-groups-empty") {
                        MochiListSection(
                            header = "分组",
                            footer = "插件没有返回可用分组；没有数据时不伪造分类。",
                        ) {
                            MochiListRow("暂无分组", subtitle = "点下方「刷新分组」重新读取")
                        }
                    }
                }
                groupsGrid(realGroups, nav)
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-footer") {
                    MochiListSection(
                        header = "账号与刷新",
                        footer = if (busy) "正在刷新…" else "书架会自动保存；需要更新时可手动刷新。",
                    ) {
                        MochiListRow(
                            title = "番茄账号",
                            subtitle = if (loggedIn) "已连接 · 账号由插件保管" else "未连接 · 打开插件设置",
                            chevron = true,
                            onClick = { nav.push(RouteAccount) },
                        )
                        MochiListRow(
                            title = "刷新分组",
                            subtitle = "更新分组与数量，不清空已加载书",
                            chevron = true,
                            onClick = { refreshGroups() },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun GroupPage(nav: MochiNavigator, id: String) {
        val currentGroup = groups.firstOrNull { it.id == id }
        val gridState = rememberLazyGridState()
        val shelfItems = remember(shelf, progressById) {
            shelf.map { book -> FanqieShelfItem(book, progressById[book.id]) }
        }
        val displayed = remember(shelfItems, shelfFilter, shelfSort) {
            ShelfRules.fanqieVisible(shelfItems, shelfFilter, shelfSort)
        }
        var showFilter by remember { mutableStateOf(false) }
        var showSort by remember { mutableStateOf(false) }
        LaunchedEffect(id, snapshotLoaded) {
            if (snapshotLoaded) activateGroup(id)
        }
        val subtitle = buildString {
            if (currentGroup != null && currentGroup.count >= 0) append("共 ").append(currentGroup.count).append(" 本")
            if (shelfMore) {
                if (isNotEmpty()) append(" · ")
                append("已加载 ").append(shelf.size)
            }
        }.ifEmpty { "分组" }

        ShelfGridScreen(
            title = currentGroup?.name ?: "分组",
            subtitle = subtitle,
            onBack = { nav.pop() },
            actionLabel = "筛选",
            actionNavId = "fanqie.group.filter",
            onAction = { showFilter = true },
            reserveBottomBar = false,
            state = gridState,
        ) {
            if (currentGroup == null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-group-missing") {
                    MochiListSection(header = "分组", footer = "快照里没有这个分组，返回分组列表刷新。") {
                        MochiListRow("分组不存在")
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-group-filter") {
                ShelfFilterSortCard(
                    filter = shelfFilter,
                    sort = shelfSort,
                    onFilterClick = { showFilter = true },
                    onSortClick = { showSort = true },
                )
            }
            if (fatal != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-group-error") {
                    MochiListSection(header = "读取失败", footer = fatal) {
                        MochiListRow("无法加载该分组", subtitle = fatal, destructive = true)
                        MochiListButton("重试", onClick = { scope.launch { loadGroup(id, reset = true) } })
                    }
                }
            }
            if (busy && shelf.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-group-loading") {
                    MochiListSection { MochiListRow("正在加载该分组…", loading = true) }
                }
            }
            if (!busy && fatal == null && shelf.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-books-empty") {
                    MochiListSection(header = "该分组") {
                        MochiListRow("这个分组还没有书", subtitle = "返回分组或刷新重试")
                    }
                }
            }
            if (shelf.isNotEmpty() && displayed.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-no-match") {
                    MochiListSection(header = "筛选结果", footer = "把阅读状态改回「全部」可看全部已加载书。") {
                        MochiListRow("没有符合条件的书", subtitle = "当前共 ${shelf.size} 本")
                    }
                }
            }
            bookGrid(displayed) { book, source -> openBook(book, readNow = false, sourceKey = source) }
            if (shelfMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "fanqie-more") {
                    MochiButton(
                        onClick = { scope.launch { loadGroup(id, reset = false) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(if (busy) "加载中…" else "加载更多", style = MochiTheme.textStyles.bodyLarge)
                    }
                }
            }
        }
        ShelfChoiceSheet(
            visible = showFilter,
            title = "阅读状态",
            options = ShelfReadFilter.entries.map { it.label },
            selected = ShelfReadFilter.entries.indexOf(shelfFilter),
            onSelect = { shelfFilter = ShelfReadFilter.entries[it] },
            onDismiss = { showFilter = false },
        )
        ShelfChoiceSheet(
            visible = showSort,
            title = "排序",
            options = ShelfSort.entries.map { it.label },
            selected = ShelfSort.entries.indexOf(shelfSort),
            onSelect = { shelfSort = ShelfSort.entries[it] },
            onDismiss = { showSort = false },
        )
    }

    private fun LazyGridScope.groupsGrid(groups: List<ShelfGroup>, nav: MochiNavigator) {
        items(groups, key = { "fanqie.group.${it.id}" }) { group ->
            val source = "fanqie.group.${group.id}"
            ShelfGroupCard(
                group = group,
                session = session,
                preview = previewById[group.id].orEmpty(),
                coverUrlFor = { book -> coverUrls[book.id] },
                revision = sessionRevision,
                sourceKey = source,
                onClick = { nav.push(RouteGroupPrefix + group.id, from = source) },
                onVisible = { ensurePreview(group.id) },
                onCoverUrl = { bookId, url -> rememberCover(bookId, url) },
            )
        }
    }

    private fun LazyGridScope.bookGrid(
        entries: List<FanqieShelfItem>,
        onOpen: (BookInfo, String) -> Unit,
    ) {
        items(entries, key = { it.book.id }) { item ->
            val source = "fanqie.book.${item.book.id}"
            ShelfBookCard(
                title = item.book.title,
                info = ShelfRules.fanqieInfoText(item),
                sourceKey = source,
                onClick = { onOpen(item.book, source) },
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(ShelfRules.CoverAspect)) {
                    FanqieCoverImage(
                        session = session,
                        book = item.book,
                        revision = sessionRevision,
                        modifier = Modifier.fillMaxSize(),
                        knownUrl = coverUrls[item.book.id],
                        shape = MochiTheme.shapes.medium,
                        onUrl = { url -> rememberCover(item.book.id, url) },
                    )
                }
            }
        }
    }

    private fun activateGroup(id: String) {
        if (groupId != id) {
            groupId = id
            val cached = groupCache[id]
            shelf = cached?.books.orEmpty()
            shelfNext = cached?.next.orEmpty()
            shelfMore = cached?.more ?: false
        }
        val cached = groupCache[id]
        if (cached == null || System.currentTimeMillis() - cached.fetchedAt > GroupTtlMs) {
            scope.launch { loadGroup(id, reset = true) }
        }
    }

    private suspend fun loadGroup(id: String, reset: Boolean) {
        if (!loggedIn) return
        if (loadingGroupId == id) return
        loadingGroupId = id
        val revision = sessionRevision
        busy = true
        if (reset) fatal = null
        val old = groupCache[id]
        try {
            val cursor = if (reset) "" else old?.next.orEmpty()
            val page = session.shelf(id, cursor)
            if (revision != sessionRevision) return
            val merged = if (reset) {
                ShelfMerge.mergeBooks(old?.books.orEmpty(), page.items)
            } else {
                ShelfMerge.appendBooks(old?.books.orEmpty(), page.items)
            }
            val fetchedAt = System.currentTimeMillis()
            groupCache[id] = GroupCache(merged, page.next, page.more, fetchedAt)
            if (groupId == id) {
                shelf = merged
                shelfNext = page.next
                shelfMore = page.more
            }
            if (previewById[id].isNullOrEmpty() && merged.isNotEmpty()) {
                previewById = previewById + (id to merged.take(4))
            }
            cacheShelfProgress(merged)
            persistSnapshot()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (groupId == id) fatal = FanqieSession.errorText(t) else notice = FanqieSession.errorText(t)
        } finally {
            if (loadingGroupId == id) loadingGroupId = ""
            busy = false
        }
    }

    private fun ensurePreview(groupId: String) {
        if (!loggedIn) return
        if (previewById.containsKey(groupId) || !previewLoading.add(groupId)) return
        val cached = groupCache[groupId]?.books?.take(4).orEmpty()
        if (cached.isNotEmpty()) {
            previewById = previewById + (groupId to cached)
            previewLoading.remove(groupId)
            persistSnapshot()
            return
        }
        val revision = sessionRevision
        scope.launch {
            try {
                val books = session.preview(groupId)
                if (revision == sessionRevision && groups.any { it.id == groupId }) {
                    previewById = previewById + (groupId to books)
                    persistSnapshot()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            } finally {
                previewLoading.remove(groupId)
            }
        }
    }

    private fun refreshGroups() {
        scope.launch {
            if (!loggedIn) {
                connectAndLoad()
                return@launch
            }
            busy = true
            fatal = null
            try {
                refreshGroupsNow()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                fatal = FanqieSession.errorText(t)
            } finally {
                busy = false
            }
        }
    }

    private fun applySnapshot(snapshot: ShelfSnapshot) {
        snapshotScopeKey = snapshot.scopeKey
        snapshotSavedAt = snapshot.savedAt
        groups = snapshot.groups.map { ShelfGroup(it.id, it.name, it.count) }
        groupCache.clear()
        snapshot.groupBooks.forEach { (id, books) ->
            groupCache[id] = GroupCache(
                books = books.map { BookInfo(it.id, it.title, it.author) },
                next = snapshot.groupNext[id].orEmpty(),
                more = snapshot.groupMore[id] ?: false,
                fetchedAt = snapshot.groupFetchedAt[id] ?: 0L,
            )
        }
        previewById = snapshot.previews.mapValues { (_, books) ->
            books.map { BookInfo(it.id, it.title, it.author) }
        }
        coverUrls = snapshot.coverUrls
        snapshot.coverUrls.forEach { (id, url) -> session.rememberCoverUrl(id, url) }
        snapshotLoaded = true
    }

    private fun persistSnapshot(markGroups: Boolean = false) {
        if (snapshotScopeKey.isEmpty()) return
        if (markGroups) snapshotSavedAt = System.currentTimeMillis()
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            val snapshot = buildSnapshot() ?: return@launch
            withContext(Dispatchers.IO) { snapshotStore.save(snapshot) }
        }
    }

    private fun buildSnapshot(): ShelfSnapshot? {
        if (snapshotScopeKey.isEmpty()) return null
        fun cached(books: List<BookInfo>): List<CachedBook> = books.map { book ->
            CachedBook(book.id, book.title, book.author, coverUrls[book.id] ?: session.coverUrl(book.id).orEmpty())
        }
        return ShelfSnapshot(
            scopeKey = snapshotScopeKey,
            savedAt = snapshotSavedAt,
            groups = groups.map { CachedGroup(it.id, it.name, it.count) },
            groupBooks = groupCache.mapValues { (_, cache) -> cached(cache.books) },
            groupNext = groupCache.mapValues { (_, cache) -> cache.next },
            groupMore = groupCache.mapValues { (_, cache) -> cache.more },
            groupFetchedAt = groupCache.mapValues { (_, cache) -> cache.fetchedAt },
            previews = previewById.mapValues { (_, books) -> cached(books) },
            coverUrls = coverUrls.toMap(),
        )
    }

    private fun rememberCover(bookId: String, url: String) {
        if (url.isEmpty() || coverUrls[bookId] == url) return
        coverUrls = coverUrls + (bookId to url)
        session.rememberCoverUrl(bookId, url)
        persistSnapshot()
    }

    private fun clearShelfData() {
        groups = emptyList()
        groupCache.clear()
        previewLoading.clear()
        loadingGroupId = ""
        previewById = emptyMap()
        coverUrls = emptyMap()
        shelf = emptyList()
        shelfNext = ""
        shelfMore = false
        groupId = ""
        snapshotScopeKey = ""
        snapshotSavedAt = 0L
    }

    @Composable
    private fun SearchPage(nav: MochiNavigator) {
        MochiCompactTitleScaffold(
            title = "搜索番茄小说",
            subtitle = if (searched) "“$keyword” · ${searchResults.size} 本" else "书名 / 作者",
            navigationIcon = { MochiBackButton(history = { "番茄书架" }, onClick = { nav.pop() }) },
            searchBar = {
                MochiSearchBar(
                    query = keyword,
                    onQueryChange = { keyword = it },
                    placeholder = "书名 / 作者",
                )
            },
            actions = {
                MochiBarButton("搜索", onClick = { runSearch(reset = true) }, navId = SearchNav)
            },
        ) {
            if (!searched) {
                MochiListSection(header = "提示", footer = "搜索走插件接口，不需要在主程序登录。") {
                    MochiListRow("输入关键词后点右上角搜索")
                }
            } else if (searchResults.isEmpty() && !busy) {
                MochiListSection(header = "结果", footer = "换个关键词试试") {
                    MochiListRow("没有找到相关书籍")
                }
            } else {
                MochiListSection(header = "搜索结果", footer = if (searchMore) "还有更多结果" else null) {
                    searchResults.forEach { book ->
                        MochiListRow(
                            title = book.title,
                            subtitle = book.author.takeIf { it.isNotBlank() },
                            chevron = true,
                            onClick = { openBook(book, readNow = false) },
                        )
                    }
                    if (searchMore) {
                        MochiListButton("加载更多", onClick = { runSearch(reset = false) })
                    }
                }
            }
        }
    }

    // ---------------- 书籍 / 目录 ----------------

    @Composable
    private fun BookPage(nav: MochiNavigator) {
        val book = selected
        MochiCompactTitleScaffold(
            title = book?.title ?: "书籍",
            subtitle = book?.author?.takeIf { it.isNotBlank() },
            navigationIcon = { MochiBackButton(history = { "番茄书架" }, onClick = { nav.pop() }) },
            actions = {
                MochiBarButton("封面", onClick = { nav.push(RouteCover) }, navId = "fanqie.cover")
            },
        ) {
            if (book == null) {
                MochiListSection { MochiListRow("没有选中的书") }
                return@MochiCompactTitleScaffold
            }
            if (busy) {
                MochiListSection { MochiListRow("正在读取目录与进度…", loading = true) }
            }
            if (fatal != null) {
                MochiListSection(header = "读取失败", footer = fatal) {
                    MochiListRow("无法打开这本书", subtitle = fatal, destructive = true)
                    MochiListButton("重试", onClick = { openBook(book, readNow = false) })
                }
            }
            val detail = details
            if (detail != null) {
                MochiListSection(
                    header = "书籍详情",
                    footer = detail.description.takeIf { it.isNotBlank() } ?: "暂无简介",
                ) {
                    if (detail.chapters > 0) MochiListRow("章节", value = "${detail.chapters} 章")
                    if (detail.latestChapter.isNotBlank()) {
                        MochiListRow("最新", subtitle = detail.latestChapter)
                    }
                    MochiListRow("阅读进度", value = syncStatus)
                }
            }
            MochiListSection(header = "阅读") {
                MochiListRow("开始 / 继续阅读", chevron = true, onClick = { openBook(book, readNow = true) })
                MochiListRow("章节目录", chevron = true, onClick = { showToc = true })
                MochiListRow("同步进度", value = syncStatus, onClick = { syncNow() })
            }
            MochiListSection(header = "离线") {
                MochiListRow(
                    title = "下载到本地",
                    subtitle = "完整下载后加入本机书库，可离线阅读并同步进度",
                    chevron = true,
                    onClick = { nav.push(RouteDownload) },
                )
            }
            MochiListSection(header = "来源") {
                MochiListRow("返回番茄书架", chevron = true, onClick = { nav.pop() })
            }
        }
    }

    @Composable
    private fun TocPage(nav: MochiNavigator) {
        var limit by remember { mutableIntStateOf(100) }
        val shown = minOf(limit, chapters.size)
        MochiCompactTitleScaffold(
            title = "章节目录",
            subtitle = selected?.title ?: "",
            navigationIcon = { MochiBackButton(history = { "书籍" }, onClick = { nav.pop() }) },
        ) {
            MochiListSection(
                header = "共 ${chapters.size} 章",
                footer = if (shown < chapters.size) "已显示 $shown 章" else syncStatus,
            ) {
                if (chapters.isEmpty() && !busy) {
                    MochiListRow("这本书暂时没有可读章节")
                }
                for (index in 0 until shown) {
                    MochiListRow(
                        title = chapters[index].title,
                        subtitle = "第 ${index + 1} 章",
                        value = if (index == chapterIndex) "在读" else null,
                        onClick = { openChapter(index) },
                    )
                }
                if (shown < chapters.size) {
                    MochiListButton("显示更多章节", onClick = { limit += 100 })
                }
            }
        }
    }

    // ---------------- 阅读 ----------------

    @Composable
    private fun ReaderRoute(nav: MochiNavigator) {
        val content = chapter
        val book = chapterBook
        if (content == null || book == null) {
            MochiCompactTitleScaffold(
                title = "阅读",
                navigationIcon = { MochiBackButton(history = { "目录" }, onClick = { nav.pop() }) },
            ) {
                MochiListSection {
                    MochiListRow(if (chapterLoading) "正在加载章节…" else "没有可显示的章节", loading = chapterLoading)
                }
            }
            return
        }
        val listener = readerListener(content)
        val dark = MochiTheme.colorScheme.isDark
        val palette = remember(readingTheme, dark) { ReadingAppearance.resolve(readingTheme, dark) }
        val desiredFontSize = fontSize
        val desiredFontFamily = fontFamily
        val desiredLineSpacing = lineSpacing
        ReaderPage(
            title = selected?.title ?: content.title,
            controlsVisible = controlsVisible,
            passiveTitle = if (chapterStart) selected?.title ?: content.title else content.title,
            readingProgress = if (chapters.isEmpty()) 0 else
                ((chapterIndex + readerPercent / 100f) * 100f / chapters.size).roundToInt(),
            readingPalette = palette,
            subtitle = "${selected?.title.orEmpty()} · 第 ${chapterIndex + 1}/${chapters.size} 章 · $readerPercent%",
            onBack = { nav.pop() },
            onToc = { showToc = true },
            onDisplay = { beginDisplayFormatting(); nav.push(RouteFanqieDisplay) },
            onSeek = { nav.push(RouteFanqieSeek) },
            onPrevPage = { currentReader?.turnPage(false, true) },
            onNextPage = { currentReader?.turnPage(true, true) },
            readerContent = {
                key(content.itemId) {
                    ReaderPageTurn(pageTurnSerial, pageTurnForward, pageAnimation,
                        enabled = nav.current == RouteReader) {
                    AndroidView(
                        factory = { viewContext ->
                            ReaderView(viewContext).apply {
                                setPhoneLayout(true)
                                setChromeVisible(false)
                                setChapterLayout(true)
                                setStandaloneChapterTitle(content.title)
                                setImmersiveTap(true)
                                setPhoneContentInsets(78f, 30f)
                                setReadingColors(palette.background, palette.body, palette.muted, palette.accent)
                                // apply 的 Receiver 也有 fontSize/fontFamily 属性，必须用显式捕获值。
                                setFontSize(desiredFontSize)
                                setFontFamily(desiredFontFamily)
                                setLineHeightMultiplier(desiredLineSpacing)
                                currentReader = this
                                restoringReader = true
                                restoreAnchor = readerOffset
                                setBook(book)
                                setListener(listener)
                                restoreTo(ReadingRules.clampOffset(readerOffset, book.charCount()))
                                currentReader = this
                            }
                        },
                        // uiMode/主题变化时只同步配色：不重设 book、不重排、不重新分页
                        update = { view ->
                            view.setReadingColors(palette.background, palette.body, palette.muted, palette.accent)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                }
            },
            // 段评与字号/进度共用一行，窄屏时由 FlowRow 自然换行。
            bottomExtraActions = {
                MochiBarButton("段评", onClick = { nav.push(RouteParagraphs) }, navId = "fanqie.comments")
            },
            overlay = {
                if (chapterLoading) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MochiDefaults.groupedBackground())
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MochiListSection { MochiListRow("正在切换章节…", loading = true) }
                    }
                }
            },
        )
    }

    private fun readerListener(content: ChapterContent): ReaderView.Listener = object : ReaderView.Listener {
        override fun onProgressChanged(charOffset: Int, percent: Float) {
            if (destroyed) return
            readerPercent = percent.roundToInt()
            chapterStart = currentReader?.isChapterStartPage ?: (charOffset == 0)
            if (formattingDisplay) return
            if (restoringReader) {
                if (ReadingRules.isRestorePlaceholder(restoreAnchor, charOffset)) return
                // 页面可能向前取整；恢复首帧不覆盖精确的已保存偏移。
                readerOffset = restoreAnchor
                restoringReader = false
                restoreAnchor = 0
                return
            }
            restoreAnchor = 0
            readerOffset = charOffset
            val count = chapters.size
            val index = chapterIndex
            scope.launch {
                lastTimestamp = session.savePosition(content, index, count, charOffset, lastTimestamp)
                scheduleFlush()
            }
        }

        override fun onPageChanged(pageIndex: Int, pageCount: Int) {
            if (pageIndex != lastAnimatedPage) {
                if (lastAnimatedPage >= 0) pageTurnForward = pageIndex > lastAnimatedPage
                lastAnimatedPage = pageIndex
                pageTurnSerial++
            }
        }

        override fun onChromeToggleRequested() { controlsVisible = !controlsVisible }

        override fun onSeekRequested() {
            pendingRoute = RouteFanqieSeek
        }

        override fun onTocRequested() {
            pendingRoute = RouteToc
        }

        override fun onChapterBoundary(forward: Boolean) {
            adjacentChapter(forward)
        }
    }

    @Composable
    private fun DisplayPage(nav: MochiNavigator) {
        ReaderDisplayPage(
            title = selected?.title.orEmpty(),
            fontSize = fontSize,
            fontFamily = fontFamily,
            onFontSize = { requested ->
                val size = ReadingRules.clampFontSize(requested)
                if (size != fontSize) {
                    fontSize = size
                    currentReader?.setFontSize(size)
                    prefs.edit().putInt(PhoneLibrary.FONT_SIZE, size).apply()
                }
            },
            onFontFamily = { family ->
                if (family != fontFamily) {
                    fontFamily = family
                    currentReader?.setFontFamily(family)
                    prefs.edit().putString("fontFamily", family).apply()
                }
            },
            lineSpacing = lineSpacing,
            readingTheme = readingTheme,
            pageAnimation = pageAnimation,
            onLineSpacing = { requested ->
                lineSpacing = ReadingAppearance.clampLineHeight(requested)
                currentReader?.setLineHeightMultiplier(lineSpacing)
                prefs.edit().putFloat(ReadingAppearance.KEY_LINE_HEIGHT, lineSpacing).apply()
            },
            onReadingTheme = { requested ->
                readingTheme = ReadingAppearance.normalizeTheme(requested)
                prefs.edit().putString(ReadingAppearance.KEY_THEME, readingTheme).apply()
            },
            onPageAnimation = { requested ->
                pageAnimation = ReadingAppearance.normalizeAnimation(requested)
                prefs.edit().putString(ReadingAppearance.KEY_ANIMATION, pageAnimation).apply()
            },
            onBack = { nav.pop() },
        )
    }

    @Composable
    private fun SeekPage(nav: MochiNavigator) {
        ReaderSeekPage(
            title = chapter?.title ?: "本章",
            percent = readerPercent,
            onSeek = { value ->
                val total = currentReader?.charCount ?: 0
                if (total > 0) currentReader?.seekTo((total.toLong() * value / 100L).toInt())
                nav.pop()
            },
            onBack = { nav.pop() },
        )
    }

    // ---------------- 段评 ----------------

    @Composable
    private fun ParagraphPage(nav: MochiNavigator) {
        val content = chapter
        var limit by remember { mutableIntStateOf(50) }
        val total = content?.paragraphs?.size ?: 0
        val shown = minOf(limit, total)
        MochiCompactTitleScaffold(
            title = "选择段落",
            subtitle = content?.title ?: "",
            navigationIcon = { MochiBackButton(history = { "阅读" }, onClick = { nav.pop() }) },
        ) {
            MochiListSection(
                header = "共 $total 段",
                footer = if (shown < total) "已显示 $shown 段" else null,
            ) {
                if (content == null) {
                    MochiListRow("请先打开一章")
                }
                for (index in 0 until shown) {
                    val item = content!!.paragraphs[index]
                    MochiListRow(
                        title = "第 ${item.index + 1} 段",
                        subtitle = item.text.take(48),
                        chevron = true,
                        onClick = {
                            paragraph = item
                            comments = emptyList()
                            nav.push(RouteComments)
                        },
                    )
                }
                if (shown < total) {
                    MochiListButton("更多段落", onClick = { limit += 50 })
                }
            }
        }
    }

    @Composable
    private fun CommentsPage(nav: MochiNavigator) {
        val target = paragraph
        val content = chapter
        MochiCompactTitleScaffold(
            title = "段评",
            subtitle = target?.let { "第 ${it.index + 1} 段" },
            navigationIcon = { MochiBackButton(history = { "段落" }, onClick = { nav.pop() }) },
            actions = {
                MochiBarButton("写段评", onClick = {
                    draft = ""
                    confirming = false
                    publishError = null
                    nav.push(RouteCompose)
                })
            },
        ) {
            if (target != null) {
                MochiListSection(header = "原文") {
                    MochiListRow(target.text)
                }
            }
            MochiListSection(header = "段评", footer = if (commentsMore) "还有更多段评" else null) {
                if (comments.isEmpty() && !busy) {
                    MochiListRow("暂无段评", subtitle = "写下第一条")
                }
                comments.forEach { comment ->
                    MochiListRow(
                        title = comment.text,
                        subtitle = comment.author.takeIf { it.isNotBlank() } ?: "匿名",
                    )
                }
                if (commentsMore) {
                    MochiListButton("更多段评", onClick = {
                        val chapterContent = content
                        val para = target
                        if (chapterContent != null && para != null) {
                            scope.launch { loadComments(chapterContent, para, append = true) }
                        }
                    })
                }
            }
        }
    }

    @Composable
    private fun ComposePage(nav: MochiNavigator) {
        val target = paragraph
        val content = chapter
        MochiCompactTitleScaffold(
            title = "写段评",
            subtitle = target?.let { "第 ${it.index + 1} 段" },
            navigationIcon = { MochiBackButton(history = { "段评" }, onClick = { nav.pop() }) },
        ) {
            MochiListSection(header = "内容", footer = "发表后使用当前账号公开可见。") {
                MochiTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "输入段评（1–500 字）",
                    singleLine = false,
                    maxLines = 5,
                    maxLength = 500,
                    supportingText = "${draft.length}/500",
                )
            }
            if (!confirming) {
                MochiButton(
                    onClick = {
                        if (draft.trim().isEmpty()) {
                            publishError = "请输入 1–500 字"
                        } else {
                            publishError = null
                            confirming = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("预览并确认", style = MochiTheme.textStyles.bodyLarge)
                }
            } else {
                MochiListSection(
                    header = "确认发表",
                    footer = "确认后将使用当前账号公开发表，请勿重复发送。",
                ) {
                    MochiListRow("原文", subtitle = target?.text)
                    MochiListRow("段评", subtitle = draft.trim())
                }
                if (publishError != null) {
                    MochiListSection(header = "发送失败", footer = publishError) {
                        MochiListRow("结果未确认", subtitle = publishError, destructive = true)
                        MochiListButton("刷新段评确认", onClick = {
                            if (content != null && target != null) {
                                scope.launch { loadComments(content, target, append = false) }
                            }
                        })
                    }
                }
                MochiButton(
                    onClick = {
                        if (content != null && target != null) publish(content, target)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !publishing,
                ) {
                    Text(if (publishing) "发送中…" else "确认公开发表", style = MochiTheme.textStyles.bodyLarge)
                }
                MochiListSection {
                    MochiListButton("返回修改", onClick = { confirming = false })
                }
            }
            if (publishError != null && !confirming) {
                MochiListSection(header = "发送失败", footer = publishError) {
                    MochiListRow("段评未发表", subtitle = publishError, destructive = true)
                }
            }
        }
    }

    // ---------------- 封面 / 下载 / 账号 ----------------

    @Composable
    private fun CoverPage(nav: MochiNavigator) {
        val book = selected
        LaunchedEffect(book?.id) {
            val target = book ?: return@LaunchedEffect
            cover = null
            coverError = null
            try {
                cover = session.cover(target)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                coverError = FanqieSession.errorText(t)
            }
        }
        MochiCompactTitleScaffold(
            title = "封面",
            subtitle = book?.title,
            navigationIcon = { MochiBackButton(history = { "书籍" }, onClick = { nav.pop() }) },
        ) {
            val image = cover
            when {
                image != null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "${book?.title.orEmpty()}封面",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                    )
                }

                coverError != null -> MochiListSection(header = "封面", footer = coverError) {
                    MochiListRow("封面暂时无法加载", subtitle = coverError, destructive = true)
                }

                else -> MochiListSection {
                    MochiListRow("正在加载封面…", loading = true)
                }
            }
        }
    }

    @Composable
    private fun DownloadPage(nav: MochiNavigator) {
        val book = selected
        MochiCompactTitleScaffold(
            title = "下载到本地",
            subtitle = book?.title,
            navigationIcon = { MochiBackButton(history = { "书籍" }, onClick = { nav.pop() }) },
        ) {
            MochiListSection(
                header = "下载",
                footer = "复用既有分章断点下载：中断后从已完成章节继续，完成后进入本机书库。",
            ) {
                MochiListRow("章节", value = "${chapters.size} 章")
                if (downloading) {
                    MochiListRow(
                        "正在下载",
                        subtitle = "$downloadDone / $downloadTotal 章",
                        value = if (downloadTotal > 0) "${downloadDone * 100 / downloadTotal}%" else null,
                    )
                }
                downloadResult?.let { file ->
                    MochiListRow("已完成", subtitle = file.name)
                }
                downloadError?.let { message ->
                    MochiListRow("下载中断", subtitle = message, destructive = true)
                }
            }
            MochiListSection(header = "下载线程", footer = "线程越多占用带宽越高；最多 4 条。") {
                for (threads in 1..4) {
                    MochiListRow(
                        title = "$threads 线程",
                        checked = threads == downloadThreads,
                        onClick = {
                            downloadThreads = threads
                            prefs.edit().putInt("downloadThreads", threads).apply()
                        },
                    )
                }
            }
            if (downloading) {
                MochiButton(
                    onClick = { cancelDownload.set(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("暂停并保留进度", style = MochiTheme.textStyles.bodyLarge)
                }
            } else {
                MochiButton(
                    onClick = { if (book != null) startDownload(book) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = book != null && chapters.isNotEmpty(),
                ) {
                    Text(
                        if (downloadResult == null) "开始 / 继续下载" else "重新下载",
                        style = MochiTheme.textStyles.bodyLarge,
                    )
                }
            }
            downloadResult?.let { file ->
                MochiListSection {
                    MochiListButton("离线阅读这本书", onClick = {
                        startActivity(
                            Intent(host, PhoneReaderActivity::class.java)
                                .putExtra(PhoneReaderActivity.EXTRA_PATH, file.absolutePath),
                        )
                    })
                }
            }
        }
    }

    @Composable
    private fun AccountPage(nav: MochiNavigator, onGoHome: () -> Unit) {
        MochiCompactTitleScaffold(
            title = "番茄账号",
            subtitle = if (loggedIn) "已连接 · 账号由插件保管" else "未连接",
            navigationIcon = { MochiBackButton(history = { "番茄书架" }, onClick = { nav.pop() }) },
        ) {
            MochiListSection(
                header = "插件",
                footer = "Cookie 只在插件内加密保存；主程序通过证书校验后的 ContentProvider 读取书架与进度。",
            ) {
                MochiListRow("连接状态", value = if (loggedIn) "已连接" else "未连接")
                MochiListRow("账号作用域", value = if (loggedIn) "已绑定当前账号" else "无")
                MochiListButton("插件账号设置", onClick = { openPluginSetup() })
                MochiListButton("重新连接", onClick = { connectAndLoad() })
            }
            MochiListSection(header = "本机") {
                MochiListRow(
                    title = "本地 TXT 书库",
                    subtitle = "回到手机书库导入、筛选与阅读",
                    chevron = true,
                    onClick = {
                        nav.popTo(0)
                        onGoHome()
                    },
                )
            }
        }
    }

    // ---------------- 业务动作 ----------------

    private fun retryConnection() {
        session.close()
        session = FanqieSession(host)
        sessionRevision++
        bootstrapped = false
        connectAndLoad()
    }

    private fun connectAndLoad() {
        if (destroyed || connecting) return
        if (!bootstrapped) {
            connecting = true
            awaitingBootstrap = true
            try {
                pluginLauncher.launch(session.client.bootstrapIntent())
            } catch (t: Throwable) {
                awaitingBootstrap = false
                connecting = false
                fatal = FanqieSession.errorText(t)
            }
            return
        }
        connecting = true
        scope.launch {
            fatal = null
            try {
                val beforeScope = session.scope
                val beforeLoggedIn = session.loggedIn
                val ok = session.connect()
                if (beforeScope != session.scope || beforeLoggedIn != ok) {
                    clearShelfData()
                    progressById = emptyMap()
                    sessionRevision++
                }
                loggedIn = ok
                if (!ok) {
                    clearShelfData()
                    snapshotLoaded = true
                    return@launch
                }
                val key = fanqieScopeKey(session.scope)
                if (key != snapshotScopeKey) {
                    val snapshot = withContext(Dispatchers.IO) { snapshotStore.load(key) }
                    if (snapshot != null) {
                        applySnapshot(snapshot)
                    } else {
                        clearShelfData()
                        snapshotScopeKey = key
                        snapshotLoaded = true
                    }
                }
                snapshotLoaded = true
                refreshShelfProgress()
                val staleGroups = groups.isEmpty() ||
                    System.currentTimeMillis() - snapshotSavedAt > GroupTtlMs
                if (staleGroups) {
                    busy = true
                    try {
                        refreshGroupsNow()
                    } finally {
                        busy = false
                    }
                }
                val active = groupCache[groupId]
                if (groupId.isNotEmpty() &&
                    (active == null || System.currentTimeMillis() - active.fetchedAt > GroupTtlMs)
                ) {
                    loadGroup(groupId, reset = true)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                fatal = FanqieSession.errorText(t)
            } finally {
                connecting = false
            }
        }
    }

    private suspend fun refreshGroupsNow() {
        val revision = sessionRevision
        val fresh = session.groups()
        if (revision != sessionRevision) return
        val oldCounts = groups.associate { it.id to it.count }
        val changed = fresh.filter { oldCounts[it.id] != it.count }.map { it.id }.toSet()
        changed.forEach { groupCache.remove(it) }
        if (changed.isNotEmpty()) {
            previewById = previewById.filterKeys { it !in changed }
            session.clearPreviews()
        }
        groups = ShelfMerge.mergeGroups(groups, fresh)
        val ids = groups.map { it.id }.toSet()
        groupCache.keys.retainAll(ids)
        previewById = previewById.filterKeys { it in ids }
        persistSnapshot(markGroups = true)
    }

    private suspend fun cacheShelfProgress(books: List<BookInfo>) {
        if (books.isEmpty()) return
        val revision = sessionRevision
        val ids = books.map { it.id }
        val fresh = session.progressMap(ids)
        if (revision != sessionRevision) return
        progressById = (progressById - ids.toSet()) + fresh
    }

    private fun runSearch(reset: Boolean) {
        val query = keyword.trim()
        if (query.isEmpty()) {
            notice = "请输入关键词"
            return
        }
        scope.launch {
            busy = true
            try {
                val page = session.search(query, if (reset) "" else searchNext)
                searchResults = if (reset) page.items else searchResults + page.items
                searchNext = page.next
                searchMore = page.more
                searched = true
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                notice = FanqieSession.errorText(t)
            } finally {
                busy = false
            }
        }
    }

    private fun openBook(book: BookInfo, readNow: Boolean, sourceKey: String? = null) {
        scope.launch {
            busy = true
            fatal = null
            pendingSource = if (readNow) null else sourceKey
            try {
                selected = book
                details = null
                details = runCatching { session.details(book) }.getOrNull()
                chapters = session.directory(book)
                val (resume, status) = session.progress(book)
                syncStatus = status
                resumeProgress = resume
                lastTimestamp = resume?.timestamp ?: 0L
                val byItem = ReadingRules.chapterIndexOf(chapters, resume?.itemId)
                chapterIndex = ReadingRules.clampChapterIndex(if (byItem >= 0) byItem else 0, chapters.size)
                if (chapters.isEmpty()) {
                    fatal = "这本书暂时没有可读章节"
                    if (!readNow) pendingRoute = RouteBook
                } else if (readNow) {
                    loadChapter(chapterIndex, push = true)
                } else {
                    pendingRoute = RouteBook
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                fatal = FanqieSession.errorText(t)
                pendingRoute = RouteBook
            } finally {
                busy = false
            }
        }
    }

    private fun openChapter(index: Int) {
        loadChapter(index, push = currentRoute != RouteReader)
    }

    private fun loadChapter(index: Int, push: Boolean) {
        readAppearancePreferences()
        val book = selected ?: return
        val target = chapters.getOrNull(index) ?: return
        scope.launch {
            chapterLoading = true
            fatal = null
            try {
                val content = session.chapter(book, target)
                val built = buildChapterBook(content)
                val restore = resumeProgress
                    ?.takeIf { it.itemId == content.itemId }
                    ?.let { ChapterPosition.offset(content, it) }
                    ?: 0
                chapter = content
                chapterBook = built
                chapterIndex = index
                readerOffset = ReadingRules.clampOffset(restore, built.charCount())
                restoreAnchor = readerOffset
                restoringReader = true
                lastAnimatedPage = -1
                readerPercent = if (built.charCount() > 0) (readerOffset * 100 / built.charCount()) else 0
                resumeProgress = null
                if (push) pendingRoute = RouteReader
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                fatal = FanqieSession.errorText(t)
            } finally {
                chapterLoading = false
            }
        }
    }

    private fun adjacentChapter(forward: Boolean) {
        if (chapterLoading) return
        val target = chapterIndex + if (forward) 1 else -1
        if (target < 0 || target >= chapters.size) {
            notice = if (forward) "已到最后一章" else "已到第一章"
            return
        }
        loadChapter(target, push = false)
    }

    private fun buildChapterBook(content: ChapterContent): Book {
        val text = StringBuilder()
        val lines = ArrayList<IntArray>()
        for (item in content.paragraphs) {
            val start = text.length
            text.append("　　").append(item.text.replace('\n', ' '))
            lines.add(intArrayOf(start, text.length))
            text.append('\n')
        }
        val file = File(host.cacheDir, content.itemId + ".txt")
        return Book(
            file,
            text.length.toLong(),
            1L,
            content.title,
            "",
            "UTF-8",
            text.toString(),
            lines.toTypedArray(),
            0L,
        )
    }

    private fun scheduleFlush() {
        val book = selected ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(2000)
            syncStatus = session.flush(book.id)
        }
    }

    private fun syncNow() {
        val book = selected ?: return
        scope.launch {
            syncStatus = "正在同步…"
            syncStatus = session.flush(book.id)
            val (resume, status) = session.progress(book)
            resumeProgress = resume
            syncStatus = status
            val index = ReadingRules.chapterIndexOf(chapters, resume?.itemId)
            if (resume != null && index >= 0) {
                chapterIndex = index
                loadChapter(index, push = currentRoute != RouteReader)
            }
        }
    }

    private fun loadComments(content: ChapterContent, target: Paragraph, append: Boolean) {
        scope.launch {
            busy = true
            try {
                val page = session.comments(content, target, if (append) commentsNext else "")
                comments = if (append) comments + page.items else page.items
                commentsNext = page.next
                commentsMore = page.more
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                notice = FanqieSession.errorText(t)
            } finally {
                busy = false
            }
        }
    }

    private fun publish(content: ChapterContent, target: Paragraph) {
        if (publishing) return
        scope.launch {
            publishing = true
            publishError = null
            try {
                session.publish(content, target, draft.trim())
                confirming = false
                notice = "已发表"
                loadComments(content, target, append = false)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                publishError = "发送结果未确认：${FanqieSession.errorText(t)}。请先刷新段评，勿重复发送。"
            } finally {
                publishing = false
            }
        }
    }

    private fun startDownload(book: BookInfo) {
        if (downloading) return
        val list = chapters
        if (list.isEmpty()) {
            downloadError = "没有可下载的章节"
            return
        }
        cancelDownload.set(false)
        downloadError = null
        downloadResult = null
        downloadDone = 0
        downloadTotal = list.size
        downloading = true
        scope.launch {
            try {
                val result = session.download(book, list, downloadThreads, cancelDownload) { done, total ->
                    downloadDone = done
                    downloadTotal = total
                }
                if (result == null) {
                    downloadError = "已暂停，已完成章节保留"
                } else {
                    downloadResult = result
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                downloadError = FanqieSession.errorText(t)
            } finally {
                downloading = false
            }
        }
    }

    private fun openPluginSetup() {
        try {
            pluginLauncher.launch(session.client.configureIntent().putExtra(ReadingAppearance.KEY_THEME, readingTheme))
        } catch (t: Throwable) {
            fatal = FanqieSession.errorText(t)
        }
    }

    private fun readAppearancePreferences() {
        fontSize = ReadingRules.clampFontSize(prefs.getInt(PhoneLibrary.FONT_SIZE, ReadingRules.DEFAULT_PHONE_FONT_SIZE))
        fontFamily = prefs.getString("fontFamily", "sans-serif") ?: "sans-serif"
        lineSpacing = ReadingAppearance.clampLineHeight(prefs.getFloat(ReadingAppearance.KEY_LINE_HEIGHT, ReadingAppearance.DEFAULT_LINE_HEIGHT))
        readingTheme = ReadingAppearance.normalizeTheme(prefs.getString(ReadingAppearance.KEY_THEME, ReadingAppearance.DEFAULT_THEME))
        pageAnimation = ReadingAppearance.normalizeAnimation(prefs.getString(ReadingAppearance.KEY_ANIMATION, ReadingAppearance.DEFAULT_ANIMATION))
    }

    private fun beginDisplayFormatting() {
        if (formattingDisplay) return
        displayAnchor = readerOffset.coerceAtLeast(0)
        formattingDisplay = true
    }

    private fun endDisplayFormatting() {
        if (!formattingDisplay) return
        currentReader?.seekTo(displayAnchor)
        readerOffset = displayAnchor
        formattingDisplay = false
    }

    fun onHostResume() {
        readAppearancePreferences()
        // 从独立阅读/账号页面回来时只刷新本机进度显示，不主动拉云端或下载。
        if (started && loggedIn) scope.launch { refreshShelfProgress() }
    }

    fun onHostPause() {
        syncJob?.cancel()
        val book = selected
        if (book != null && loggedIn) {
            scope.launch { syncStatus = session.flush(book.id) }
        }
    }

    fun close() {
        destroyed = true
        cancelDownload.set(true)
        syncJob?.cancel()
        persistJob?.cancel()
        session.close()
        scope.cancel()
    }
}
