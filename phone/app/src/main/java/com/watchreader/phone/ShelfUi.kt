package com.watchreader.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchreader.fanqie.ReaderPlugin.BookInfo
import com.watchreader.fanqie.ReaderPlugin.ReadingProgress
import com.watchreader.fanqie.ReaderPlugin.ShelfGroup
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiBarButton
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.MochiSegmentedDefaults
import ui.feice.mochi.basic.MochiSheet
import ui.feice.mochi.basic.MochiSheetContent
import ui.feice.mochi.basic.MochiSheetDetent
import ui.feice.mochi.basic.MochiSheetHeader
import ui.feice.mochi.basic.MochiTabBar
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.rememberMochiSheetState
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.effect.overscroll.MochiScrollDefaults
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiScrollEdgeDefaults
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.navigation.MochiTitleBarDefaults
import ui.feice.mochi.navigation.mochiHideWhenCovered
import ui.feice.mochi.navigation.mochiNavSource
import ui.feice.mochi.shape.MochiConcentric
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** 根底栏标签：主页（本机书库）/ 番茄书架 / 设置。 */
internal const val RootTabHome = 0
internal const val RootTabFanqie = 1
internal const val RootTabSettings = 2

/** 跨 Activity 选择根页：只传语义，不传任何书或账号数据。 */
internal const val ExtraRootTab = "com.watchreader.phone.extra.ROOT_TAB"
internal const val RootTabHomeValue = "home"
internal const val RootTabFanqieValue = "fanqie"
internal const val RootTabSettingsValue = "settings"

/** 只声明 Intent，读取逻辑与 root 判定都在另一个执行者新增的 OfficialCacheActivity 内。 */
internal fun openOfficialCache(context: Context): Boolean = runCatching {
    val intent = Intent().setClassName(
        context.packageName,
        "com.watchreader.phone.OfficialCacheActivity",
    )
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}.isSuccess

internal enum class ShelfSort(val label: String) {
    Default("默认"),
    Title("书名"),
    Author("作者"),
    Progress("进度"),
}

internal enum class ShelfReadFilter(val label: String) {
    All("全部"),
    Unread("未读"),
    Reading("在读"),
    Finished("读完"),
}

/** 网格展示用的一本番茄书：书籍元信息 + 本机进度；进度为 null 表示未开始。 */
internal data class FanqieShelfItem(
    val book: BookInfo,
    val progress: ReadingProgress?,
)

/**
 * 纯 JVM 的网格/筛选规则，便于单测；不依赖 Compose、Android 或插件。
 * 所有排序/筛选只作用于调用方已给的书，不自行拉目录、正文或云端进度。
 */
internal object ShelfRules {

    /** 竖封面宽高比：约 0.7（官方书架封面比例）。 */
    const val CoverAspect: Float = 0.7f

    /** 3 列；窄屏或系统大字号降为 2 列，不缩字、不裁封面。 */
    fun gridColumns(widthDp: Int, fontScale: Float): Int =
        if (widthDp < 360 || fontScale >= 1.3f) 2 else 3

    fun localVisible(
        books: List<PhoneBook>,
        query: String,
        filter: ShelfReadFilter,
        sort: ShelfSort,
    ): List<PhoneBook> {
        val keyword = query.trim()
        val matched = books.filter { book ->
            keyword.isEmpty() ||
                book.title.contains(keyword, ignoreCase = true) ||
                book.author.contains(keyword, ignoreCase = true) ||
                book.file.name.contains(keyword, ignoreCase = true)
        }.filter { filter.matches(it.percent) }
        return when (sort) {
            ShelfSort.Default -> matched.sortedWith(
                compareByDescending<PhoneBook> { it.file.lastModified() }.thenBy { it.title },
            )
            ShelfSort.Title -> matched.sortedBy { it.title }
            ShelfSort.Author -> matched.sortedWith(
                compareBy<PhoneBook> { it.author.ifBlank { "\uFFFF" } }.thenBy { it.title },
            )
            ShelfSort.Progress -> matched.sortedWith(
                compareByDescending<PhoneBook> { it.percent }.thenBy { it.title },
            )
        }
    }

    fun fanqieVisible(
        books: List<FanqieShelfItem>,
        filter: ShelfReadFilter,
        sort: ShelfSort,
    ): List<FanqieShelfItem> {
        val matched = books.filter { filter.matchesFanqie(it.progress) }
        return when (sort) {
            ShelfSort.Default -> matched
            ShelfSort.Title -> matched.sortedBy { it.book.title }
            ShelfSort.Author -> matched.sortedWith(
                compareBy<FanqieShelfItem> { it.book.author.ifBlank { "\uFFFF" } }
                    .thenBy { it.book.title },
            )
            ShelfSort.Progress -> matched.sortedWith(
                compareByDescending<FanqieShelfItem> { it.progress?.timestamp ?: Long.MIN_VALUE }
                    .thenBy { it.book.title },
            )
        }
    }

    /** 分组预览最多取第一页前 4 本；不展开整组目录。 */
    fun previewOf(books: List<BookInfo>): List<BookInfo> = books.take(4)

    fun fanqieProgressText(progress: ReadingProgress?): String {
        if (progress == null) return "未开始"
        val rate = progress.bookRate
        return when {
            rate >= 0.995 -> "读完"
            rate > 0.0 -> "${(rate * 100).roundToInt()}%"
            progress.chapter > 0 -> "第 ${progress.chapter + 1} 章"
            progress.charOffset > 0 -> "有本地进度"
            else -> "未开始"
        }
    }

    fun localInfoText(book: PhoneBook): String =
        listOfNotNull(
            book.author.takeIf { it.isNotBlank() },
            LibraryFiles.progressText(book.percent),
        ).joinToString(" · ")

    fun fanqieInfoText(item: FanqieShelfItem): String =
        listOfNotNull(
            item.book.author.takeIf { it.isNotBlank() },
            fanqieProgressText(item.progress),
        ).joinToString(" · ")

    private fun ShelfReadFilter.matches(percent: Float): Boolean = when (this) {
        ShelfReadFilter.All -> true
        ShelfReadFilter.Unread -> percent <= 0f
        ShelfReadFilter.Reading -> percent > 0f && percent < 99.5f
        ShelfReadFilter.Finished -> percent >= 99.5f
    }

    private fun ShelfReadFilter.matchesFanqie(progress: ReadingProgress?): Boolean = when (this) {
        ShelfReadFilter.All -> true
        ShelfReadFilter.Unread -> !progress.started()
        ShelfReadFilter.Reading -> progress.started() && !progress.finished()
        ShelfReadFilter.Finished -> progress.finished()
    }

    private fun ReadingProgress?.started(): Boolean =
        this != null && (bookRate > 0.0 || chapter > 0 || charOffset > 0)

    private fun ReadingProgress?.finished(): Boolean = this != null && bookRate >= 0.995
}

@Composable
internal fun rememberShelfColumns(): Int {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    return remember(configuration.screenWidthDp, fontScale) {
        ShelfRules.gridColumns(configuration.screenWidthDp, fontScale)
    }
}

/**
 * 根页骨架：固定 MochiTitleBar + 自管 LazyVerticalGrid + 真实 MochiTabBar。
 * 网格是页面唯一的竖向滚动容器，不嵌进任何 Column/Scaffold 的无界高度里。
 * 顶部与底栏的空间用 contentPadding 预留，栏本身浮在内容上。
 */
@Composable
internal fun ShelfGridScreen(
    title: String,
    subtitle: String?,
    actionLabel: String? = null,
    actionNavId: String? = null,
    onAction: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    reserveBottomBar: Boolean = false,
    @Suppress("UNUSED_PARAMETER") selectedTab: Int? = null,
    @Suppress("UNUSED_PARAMETER") onSelectTab: (Int) -> Unit = {},
    state: LazyGridState = rememberLazyGridState(),
    columns: Int = rememberShelfColumns(),
    content: LazyGridScope.() -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabSpace = if (reserveBottomBar) 64.dp + navigationBottom + 12.dp else navigationBottom + 12.dp
    val action = onAction
    val label = actionLabel
    val actions: (@Composable RowScope.() -> Unit)? =
        if (label != null && action != null) {
            { MochiBarButton(label, onClick = action, navId = actionNavId) }
        } else {
            null
        }
    val navigationIcon: (@Composable () -> Unit)? =
        onBack?.let { back -> { MochiBackButton(onClick = back) } }

    Box(Modifier.fillMaxSize().background(MochiDefaults.groupedBackground())) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = statusTop + MochiTitleBarDefaults.Height + 12.dp,
                bottom = tabSpace + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            flingBehavior = MochiScrollDefaults.flingBehavior(),
            content = content,
        )
        MochiTitleBar(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.align(Alignment.TopCenter),
            navigationIcon = navigationIcon,
            actions = actions,
            backgroundProgress = MochiScrollEdgeDefaults.progress(state),
        )
    }
}

/** 三个根页共用的真实 MochiTabBar；items 必须是 ImmutableList。 */
@Composable
internal fun RootTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember {
        persistentListOf(
            MochiTabItem(label = "主页", icon = { tint -> HomeGlyph(tint) }),
            MochiTabItem(label = "番茄", icon = { tint -> FanqieGlyph(tint) }),
            MochiTabItem(label = "设置", icon = { tint -> SettingsGlyph(tint) }),
        )
    }
    MochiTabBar(
        selectedIndex = selected.coerceIn(0, items.lastIndex),
        onSelect = onSelect,
        items = items,
        modifier = modifier.mochiHideWhenCovered(),
    )
}

@Composable
internal fun ShelfModeBar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    MochiSegmentedControl(
        selectedIndex = selected,
        onSelect = onSelect,
        items = persistentListOf(*labels.toTypedArray()),
        modifier = modifier.fillMaxWidth(),
        behavior = MochiSegmentedDefaults.behavior(height = 44.dp),
    )
}

/**
 * 筛选/排序入口：两行都是标准 MochiListRow，行高一致；选项放进 Medium 面板，
 * 面板里同样只用 MochiListRow，避免行高混排。内容只驱动已加载的书，不触发网络。
 */
@Composable
internal fun ShelfFilterSortCard(
    filter: ShelfReadFilter,
    sort: ShelfSort,
    onFilterClick: () -> Unit,
    onSortClick: () -> Unit,
) {
    MochiListSection {
        MochiListRow(
            "阅读状态",
            value = filter.label,
            chevron = true,
            onClick = onFilterClick,
        )
        MochiListRow(
            "排序",
            value = sort.label,
            chevron = true,
            onClick = onSortClick,
        )
    }
}

/** 旧页面兼容入口；新界面使用 [ShelfFilterSortCard] + [ShelfChoiceSheet]。 */
@Composable
internal fun ShelfFilterCard(
    filter: ShelfReadFilter,
    sort: ShelfSort,
    onFilter: (ShelfReadFilter) -> Unit,
    onSort: (ShelfSort) -> Unit,
    footer: String?,
    search: (@Composable () -> Unit)? = null,
) {
    var filterSheet by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }
    MochiListSection(header = "筛选与排序", footer = footer) {
        if (search != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { search() }
        MochiListRow("阅读状态", value = filter.label, chevron = true, onClick = { filterSheet = true })
        MochiListRow("排序", value = sort.label, chevron = true, onClick = { sortSheet = true })
    }
    ShelfChoiceSheet(
        visible = filterSheet,
        title = "阅读状态",
        options = ShelfReadFilter.entries.map { it.label },
        selected = ShelfReadFilter.entries.indexOf(filter),
        onSelect = { onFilter(ShelfReadFilter.entries[it]) },
        onDismiss = { filterSheet = false },
    )
    ShelfChoiceSheet(
        visible = sortSheet,
        title = "排序",
        options = ShelfSort.entries.map { it.label },
        selected = ShelfSort.entries.indexOf(sort),
        onSelect = { onSort(ShelfSort.entries[it]) },
        onDismiss = { sortSheet = false },
    )
}

@Composable
internal fun ShelfChoiceSheet(
    visible: Boolean,
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberMochiSheetState(listOf(MochiSheetDetent.Medium))
    LaunchedEffect(visible) {
        if (visible) state.show() else state.hide()
    }
    MochiSheet(state = state, onDismiss = onDismiss) {
        MochiSheetHeader(
            title,
            actions = { MochiBarButton("关闭", onClick = onDismiss) },
        )
        MochiSheetContent {
            MochiListSection {
                options.forEachIndexed { index, label ->
                    MochiListRow(
                        label,
                        checked = index == selected,
                        onClick = {
                            onSelect(index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 单书卡片：竖封面 + 两行书名 + 一行真实信息。整卡可点（远大于 44dp），
 * 封面按 0.7 比例；标题最少保留两行高度，短书名与长书名卡片自然对齐。
 */
@Composable
internal fun ShelfBookCard(
    title: String,
    info: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceKey: String? = null,
    cover: @Composable () -> Unit,
) {
    val source = if (sourceKey != null) {
        Modifier.mochiNavSource(sourceKey, 12.dp, MochiDefaults.groupedBackground())
    } else {
        Modifier
    }
    val titleHeight = with(LocalDensity.current) {
        (MochiTheme.textStyles.bodyMedium.lineHeight.value * 2f).sp.toDp()
    }
    Column(
        modifier
            .fillMaxWidth()
            .mochiPressDeform()
            .then(source)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title，$info" },
    ) {
        cover()
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MochiTheme.textStyles.bodyMedium,
            color = MochiTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = titleHeight),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            info,
            style = MochiTheme.textStyles.footnote,
            color = MochiTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分组卡片：0.7 竖向 2x2 封面拼贴 + 组名 + 真实本数。
 * 外圆角与内封面圆角由 [MochiConcentric.inner] 按真实 inset 推导，不做缩放。
 * 预览只读每组第一页最多 4 本，且只有组合可见时才请求。
 */
@Composable
internal fun ShelfGroupCard(
    group: ShelfGroup,
    session: FanqieSession,
    preview: List<BookInfo>,
    coverUrlFor: (BookInfo) -> String?,
    revision: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceKey: String? = null,
    onVisible: () -> Unit,
    onCoverUrl: (String, String) -> Unit = { _, _ -> },
) {
    LaunchedEffect(group.id, group.count, revision, preview.isEmpty()) { onVisible() }
    val source = if (sourceKey != null) {
        Modifier.mochiNavSource(sourceKey, GroupCorner, MochiDefaults.groupedBackground())
    } else {
        Modifier
    }
    val cellShape = remember(GroupCorner, GroupInset) {
        MochiRoundedRectangle(MochiConcentric.inner(GroupCorner, GroupInset))
    }
    val outerShape = remember(GroupCorner) { MochiRoundedRectangle(GroupCorner) }
    Column(
        modifier
            .fillMaxWidth()
            .mochiPressDeform()
            .then(source)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (group.count >= 0) {
                    "${group.name}，共 ${group.count} 本"
                } else {
                    group.name
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ShelfRules.CoverAspect)
                .clip(outerShape)
                .background(MochiDefaults.groupedContainer()),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(GroupInset),
                verticalArrangement = Arrangement.spacedBy(GroupInset),
            ) {
                for (row in 0 until 2) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(GroupInset),
                    ) {
                        for (column in 0 until 2) {
                            val book = preview.getOrNull(row * 2 + column)
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .weight(1f)
                                    .clip(cellShape),
                            ) {
                                if (book != null) {
                                    FanqieCoverImage(
                                        session = session,
                                        book = book,
                                        revision = revision,
                                        knownUrl = coverUrlFor(book),
                                        modifier = Modifier.fillMaxSize(),
                                        shape = cellShape,
                                        onUrl = { url -> onCoverUrl(book.id, url) },
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(MochiTheme.colorScheme.surfaceContainerHigh),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            group.name,
            style = MochiTheme.textStyles.bodyMedium,
            color = MochiTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (group.count >= 0) "共 ${group.count} 本" else "分组",
            style = MochiTheme.textStyles.footnote,
            color = MochiTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private val GroupCorner = 14.dp
private val GroupInset = 2.dp

/** 旧页面兼容入口；新分组页直接传 preview/coverUrlFor/onVisible。 */
@Composable
internal fun ShelfGroupCard(
    group: ShelfGroup,
    session: FanqieSession,
    revision: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ShelfGroupCard(
        group = group,
        session = session,
        preview = emptyList(),
        coverUrlFor = { null },
        revision = revision,
        onClick = onClick,
        modifier = modifier,
        sourceKey = null,
        onVisible = {},
    )
}

/**
 * 封面缩略图：只走 FanqieSession 的 thumb（其内部复用 CoverArt/CoverLoader）；
 * 组合进入可见区域才发请求。没封面/失败时显示诚实的主题书名占位，不造图。
 */
@Composable
internal fun FanqieCoverImage(
    session: FanqieSession,
    book: BookInfo,
    revision: Int,
    modifier: Modifier = Modifier,
    knownUrl: String? = null,
    shape: androidx.compose.ui.graphics.Shape,
    onUrl: (String) -> Unit = {},
) {
    var bitmap by remember(book.id, revision) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(book.id, revision, knownUrl) {
        bitmap = try {
            session.thumb(book, knownUrl)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
        session.coverUrl(book.id)?.let(onUrl)
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "${book.title}封面",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        CoverPlaceholder(book.title, modifier, shape)
    }
}

/** 无封面占位：主题容器色 + 书名；不读 TXT 全文，不生成封面。 */
@Composable
internal fun CoverPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MochiTheme.shapes.medium,
) {
    val scheme = MochiTheme.colorScheme
    val palette = remember(scheme) {
        listOf(
            scheme.secondaryContainer to scheme.onSecondaryContainer,
            scheme.tertiaryContainer to scheme.onTertiaryContainer,
            scheme.surfaceContainerHigh to scheme.onSurfaceVariant,
            scheme.primaryContainer to scheme.onPrimaryContainer,
        )
    }
    val pair = palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
        modifier
            .clip(shape)
            .background(pair.first)
            .padding(8.dp)
            .semantics { contentDescription = "$title，暂无封面" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.ifBlank { "无封面" },
            style = MochiTheme.textStyles.bodyMedium,
            color = pair.second,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------- 根底栏图标（Canvas 手绘，lib 未提供图标集） ----------------

@Composable
private fun HomeGlyph(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.46f)
            lineTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.46f)
            moveTo(w * 0.24f, h * 0.44f)
            lineTo(w * 0.24f, h * 0.84f)
            lineTo(w * 0.76f, h * 0.84f)
            lineTo(w * 0.76f, h * 0.44f)
            moveTo(w * 0.44f, h * 0.84f)
            lineTo(w * 0.44f, h * 0.62f)
            lineTo(w * 0.56f, h * 0.62f)
            lineTo(w * 0.56f, h * 0.84f)
        }
        drawPath(path, tint, style = stroke)
    }
}

@Composable
private fun FanqieGlyph(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = minOf(w, h) * 0.29f,
            center = Offset(w * 0.5f, h * 0.58f),
            style = stroke,
        )
        val leaf = Path().apply {
            moveTo(w * 0.5f, h * 0.28f)
            lineTo(w * 0.37f, h * 0.34f)
            moveTo(w * 0.5f, h * 0.28f)
            lineTo(w * 0.63f, h * 0.34f)
            moveTo(w * 0.5f, h * 0.24f)
            lineTo(w * 0.5f, h * 0.34f)
        }
        drawPath(leaf, tint, style = stroke)
    }
}

@Composable
private fun SettingsGlyph(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val lines = listOf(0.3f to 0.5f, 0.5f to 0.35f, 0.7f to 0.65f)
        for ((y, x) in lines) {
            drawLine(
                tint,
                Offset(w * 0.16f, h * y),
                Offset(w * 0.84f, h * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(tint, radius = 2.4.dp.toPx(), center = Offset(w * x, h * y))
        }
    }
}
