package com.watchreader.phone

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.watchreader.fanqie.CoverArt
import com.watchreader.fanqie.LocalBookDownloader
import com.watchreader.fanqie.ProgressStore
import com.watchreader.fanqie.ProgressSync
import com.watchreader.fanqie.ReaderPlugin
import com.watchreader.fanqie.ReaderPlugin.BookDetails
import com.watchreader.fanqie.ReaderPlugin.BookInfo
import com.watchreader.fanqie.ReaderPlugin.ChapterContent
import com.watchreader.fanqie.ReaderPlugin.ChapterInfo
import com.watchreader.fanqie.ReaderPlugin.Comment
import com.watchreader.fanqie.ReaderPlugin.Page
import com.watchreader.fanqie.ReaderPlugin.Paragraph
import com.watchreader.fanqie.ReaderPlugin.ReadingProgress
import com.watchreader.fanqie.ReaderPlugin.ShelfGroup
import com.watchreader.fanqie.RemotePluginClient
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 手机番茄 UI 的业务层：只经 [RemotePluginClient] 走既有 ContentProvider 接口，
 * 不读取 Cookie、不实现网络/签名/解密，也不复制分页与进度引擎。
 * 所有方法都在 IO 线程执行；调用方负责生命周期取消与 UI 状态。
 */
internal class FanqieSession(context: Context) : AutoCloseable {

    private val app = context.applicationContext
    val client = RemotePluginClient(app)

    /**
     * 插件页/详情页/书架预览可能在不同 IO 协程里同时读写这些缓存；
     * LinkedHashMap 不是线程安全的，所有访问都经过同一把锁。
     */
    private val cacheLock = Any()
    /** 换账号时自增：旧请求回来时不许写进新账号的缓存。 */
    @Volatile
    private var cacheGeneration = 0
    /** 手动刷新分组时自增：旧的分组第一页请求不许覆盖新缓存。 */
    @Volatile
    private var previewGeneration = 0
    @Volatile
    private var sync: ProgressSync? = null
    private val detailsCache = object : LinkedHashMap<String, BookDetails>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookDetails>?): Boolean = size > 24
    }
    private val chapterCache = object : LinkedHashMap<String, ChapterContent>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterContent>?): Boolean = size > 8
    }

    /** 分组预览只保存每组第一页的前 4 本，最多留 16 组，不拉完整 368 本目录。 */
    private val previewCache = object : LinkedHashMap<String, List<BookInfo>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<BookInfo>>?): Boolean = size > 16
    }

    /** 网格缩略图上限 3MiB；只存缩放后的小图，详情页仍走 CoverArt 大图。 */
    private val thumbCache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val coverStore = FanqieCoverStore(app)
    private val coverUrlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
    }
    private val thumbMutexLock = Any()
    private class ThumbMutexEntry(val mutex: Mutex, var users: Int = 0)
    private val thumbMutexes = HashMap<String, ThumbMutexEntry>()

    /** 当前账号 scope 的 SHA-256；只用于隔离磁盘缩略图，不是账号 ID。 */
    @Volatile
    private var scopeKey = ""

    /** 缩略图最多同时下载 2 张，避免整屏网格一起打 CDN。 */
    private val thumbPermits = Semaphore(2)

    /** 分组预览最多同时读 2 组的第一页。 */
    private val previewPermits = Semaphore(2)

    @Volatile
    var loggedIn = false
        private set

    @Volatile
    var scope = "unconnected"
        private set

    @Volatile
    private var closed = false

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** 连接插件并把插件侧的旧进度并入当前账号的本地进度库（与手表 connectPlugin 一致）。 */
    suspend fun connect(): Boolean = io {
        client.refresh()
        val nextScope = client.accountScope()
        val nextLoggedIn = client.loggedIn()
        // 换账号/掉登录时先清掉详情、章节、分组预览和封面，避免跨账号残留；sync 同步复位。
        if (nextScope != scope || nextLoggedIn != loggedIn) clearCaches()
        scope = nextScope
        scopeKey = fanqieScopeKey(nextScope)
        loggedIn = nextLoggedIn
        sync = null
        if (nextLoggedIn) {
            val storage = ProgressStore(app, scope)
            for (legacy in client.legacyProgress()) {
                val local = storage.get(legacy.bookId)
                if (local == null || local.timestamp < legacy.timestamp) storage.put(legacy)
            }
            sync = ProgressSync(client, storage)
        }
        nextLoggedIn
    }

    suspend fun groups(): List<ShelfGroup> = io { client.shelfGroups() }

    suspend fun shelf(group: String, cursor: String): Page<BookInfo> =
        io { client.bookshelf(group, cursor) }

    /**
     * 分组拼贴预览：只读该组第一页，并只保留前 4 本元信息。
     * 不读目录/正文、不拉进度、不触发下载。
     */
    suspend fun preview(group: String): List<BookInfo> = io {
        synchronized(cacheLock) { previewCache[group] }?.let { return@io it }
        previewPermits.withPermit {
            synchronized(cacheLock) { previewCache[group] }?.let { return@withPermit it }
            val generation = cacheGeneration
            val previewMark = previewGeneration
            val first = ShelfRules.previewOf(client.bookshelf(group, "").items)
            synchronized(cacheLock) {
                if (cacheGeneration == generation && previewGeneration == previewMark) {
                    previewCache[group] = first
                }
            }
            first
        }
    }

    /** 手动刷新书架时清掉分组拼贴的首页缓存，不丢详情/封面/章节。 */
    fun clearPreviews() {
        synchronized(cacheLock) {
            previewGeneration++
            previewCache.clear()
        }
    }

    suspend fun search(keyword: String, cursor: String): Page<BookInfo> =
        io { client.search(keyword, cursor) }

    suspend fun details(book: BookInfo): BookDetails = io {
        synchronized(cacheLock) { detailsCache[book.id] } ?: run {
            val generation = cacheGeneration
            val fresh = client.details(book)
            synchronized(cacheLock) {
                if (cacheGeneration == generation) {
                    detailsCache[book.id] = fresh
                    if (fresh.coverUrl.isNotEmpty()) coverUrlCache[book.id] = fresh.coverUrl
                }
            }
            fresh
        }
    }

    /** 已解析的封面 URL；网格可把它写进快照，下次不再为了封面走详情 RPC。 */
    fun coverUrl(bookId: String): String? = synchronized(cacheLock) { coverUrlCache[bookId] }

    fun rememberCoverUrl(bookId: String, url: String) {
        if (bookId.isEmpty() || url.isEmpty()) return
        synchronized(cacheLock) { coverUrlCache[bookId] = url }
    }

    suspend fun directory(book: BookInfo): List<ChapterInfo> = io { client.directory(book.id) }

    suspend fun chapter(book: BookInfo, info: ChapterInfo): ChapterContent = io {
        val key = "${book.id}/${info.id}/${info.version}"
        synchronized(cacheLock) { chapterCache[key] } ?: run {
            val generation = cacheGeneration
            val content = client.chapter(book.id, info)
            if (content == null || content.bookId != book.id || content.itemId != info.id || content.paragraphs.isEmpty()) {
                throw IOException("本章内容暂不可读")
            }
            synchronized(cacheLock) {
                if (cacheGeneration == generation) chapterCache[key] = content
            }
            content
        }
    }

    /** 本机已保存的进度，只读 ProgressStore；不发起云端同步。 */
    suspend fun progressMap(bookIds: Collection<String>): Map<String, ReadingProgress> = io {
        val engine = sync ?: return@io emptyMap()
        val result = LinkedHashMap<String, ReadingProgress>()
        for (id in bookIds) {
            val progress = runCatching { engine.store.get(id) }.getOrNull()
            if (progress != null) result[id] = progress
        }
        result
    }

    /** 拉取云端进度；云端不可用时退回本地，和手表 openBook 的降级一致。 */
    suspend fun progress(book: BookInfo): Pair<ReadingProgress?, String> = io {
        val engine = sync ?: return@io null to "尚未连接账号"
        try {
            val pulled = engine.pull(book.id)
            pulled to when {
                pulled == null -> "暂无云端进度"
                pulled.dirty -> "有本地待同步进度"
                else -> "已读取云端进度"
            }
        } catch (t: Throwable) {
            engine.store.get(book.id) to "云端暂不可用，使用本地进度"
        }
    }

    /** 记录当前位置：时间戳单调递增，避免同毫秒覆盖。 */
    suspend fun savePosition(
        chapter: ChapterContent,
        chapterIndex: Int,
        chapterCount: Int,
        offset: Int,
        previousTimestamp: Long,
    ): Long = io {
        val engine = sync ?: return@io 0L
        val timestamp = maxOf(System.currentTimeMillis(), previousTimestamp + 1)
        val progress = com.watchreader.fanqie.ChapterPosition.capture(
            chapter, chapterIndex, chapterCount, offset, timestamp,
        )
        if (progress.paragraph >= 0) engine.store.put(progress)
        timestamp
    }

    suspend fun flush(bookId: String): String = io {
        val engine = sync ?: return@io "尚未连接账号"
        try {
            when (engine.flush(bookId)) {
                ProgressSync.Result.SENT -> "已与番茄同步"
                ProgressSync.Result.REMOTE_NEWER -> "云端有较新进度，可立即同步到此处"
                ProgressSync.Result.DEFERRED -> "本地进度待上传"
                else -> "进度已是最新"
            }
        } catch (t: Throwable) {
            "同步失败，本地进度待上传"
        }
    }

    suspend fun comments(chapter: ChapterContent, paragraph: Paragraph, cursor: String): Page<Comment> =
        io { client.comments(chapter, paragraph, cursor) }

    suspend fun publish(chapter: ChapterContent, paragraph: Paragraph, text: String): String =
        io { client.publish(chapter, paragraph, text) }

    suspend fun cover(book: BookInfo): Bitmap = io {
        val url = details(book).coverUrl
        if (url.isEmpty()) throw IOException("这本书暂无封面")
        CoverArt.load(url)
    }

    /**
     * 网格缩略图：只经详情缓存拿封面地址，再走既有 CoverArt（HTTPS/域名/大小限制不变）；
     * 解码后缩到最长边约 180px 再进 3MiB 缓存，最多 2 张并发。
     * 失败返回 null，由 UI 显示诚实的书名占位。
     */
    suspend fun thumb(book: BookInfo, knownUrl: String? = null): Bitmap? = io {
        val key = book.id
        val generation = cacheGeneration
        val diskKey = scopeKey
        if (!knownUrl.isNullOrEmpty()) {
            synchronized(cacheLock) {
                if (cacheGeneration == generation) coverUrlCache[key] = knownUrl
            }
        }
        thumbCache.get(key)?.let { return@io it }
        if (diskKey.isNotEmpty()) {
            coverStore.load(diskKey, key)?.let {
                synchronized(cacheLock) {
                    if (cacheGeneration == generation && scopeKey == diskKey) thumbCache.put(key, it)
                }
                return@io it
            }
        }
        thumbPermits.withPermit {
            withThumbLock(key) {
                thumbCache.get(key)?.let { return@withThumbLock it }
                if (diskKey.isNotEmpty()) {
                    coverStore.load(diskKey, key)?.let {
                        synchronized(cacheLock) {
                            if (cacheGeneration == generation && scopeKey == diskKey) thumbCache.put(key, it)
                        }
                        return@withThumbLock it
                    }
                }
                val url = knownUrl?.takeIf { it.isNotEmpty() }
                    ?: coverUrl(key)
                    ?: runCatching { details(book).coverUrl }.getOrNull().orEmpty()
                if (url.isEmpty()) return@withThumbLock null
                synchronized(cacheLock) {
                    if (cacheGeneration == generation) coverUrlCache[key] = url
                }
                val loaded = runCatching { CoverArt.load(url) }.getOrNull() ?: return@withThumbLock null
                val scaled = scaleThumb(loaded)
                val belongsToCurrentScope = synchronized(cacheLock) {
                    if (cacheGeneration == generation && scopeKey == diskKey) {
                        thumbCache.put(key, scaled)
                        true
                    } else {
                        false
                    }
                }
                if (belongsToCurrentScope && diskKey.isNotEmpty()) coverStore.save(diskKey, key, scaled)
                scaled
            }
        }
    }

    private suspend fun <T> withThumbLock(bookId: String, block: suspend () -> T): T {
        val entry = synchronized(thumbMutexLock) {
            thumbMutexes.getOrPut(bookId) { ThumbMutexEntry(Mutex()) }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(thumbMutexLock) {
                entry.users--
                if (entry.users == 0 && thumbMutexes[bookId] === entry) thumbMutexes.remove(bookId)
            }
        }
    }

    private fun scaleThumb(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val longest = maxOf(width, height)
        if (longest <= ThumbMaxPx || width <= 0 || height <= 0) return source
        val ratio = ThumbMaxPx.toFloat() / longest
        val targetWidth = (width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun clearCaches() {
        synchronized(cacheLock) {
            cacheGeneration++
            previewGeneration++
            detailsCache.clear()
            chapterCache.clear()
            previewCache.clear()
            coverUrlCache.clear()
        }
        thumbCache.evictAll()
        scopeKey = ""
        sync = null
    }

    /**
     * 下载到私有书库：复用 LocalBookDownloader 的分章断点/校验逻辑。
     * @return 完成的文件；null 表示用户暂停（已完成章节保留）
     */
    suspend fun download(
        book: BookInfo,
        chapters: List<ChapterInfo>,
        threads: Int,
        cancel: AtomicBoolean,
        onProgress: (Int, Int) -> Unit,
    ): File? = io {
        val downloader = LocalBookDownloader(
            File(app.filesDir, "books"),
            File(app.filesDir, "fanqie-downloads"),
            threads,
        )
        downloader.download(
            scope,
            book,
            chapters,
            client,
            { cancel.get() || closed },
        ) { done, total -> onProgress(done, total) }
    }

    override fun close() {
        if (closed) return
        closed = true
        clearCaches()
        runCatching { client.close() }
    }

    companion object {
        /** 网格缩略图最长边。详情封面仍用 CoverArt 的大图。 */
        private const val ThumbMaxPx = 180

        /** 把插件/网络异常转成可读提示；保留既有业务错误原文。 */
        fun errorText(t: Throwable): String {
            if (t is java.net.SocketTimeoutException) return "网络超时，请检查连接后重试"
            if (t is java.net.UnknownHostException || t is javax.net.ssl.SSLException) {
                return "无法连接番茄服务器，请检查网络"
            }
            val message = t.message
            val prefixes = listOf(
                "番茄", "登录", "请先", "接口", "本章", "这本", "设备", "网络",
                "服务端", "段评", "封面", "下载", "请求过快",
            )
            if (message != null && prefixes.any { message.startsWith(it) }) return message
            return "操作未完成，请稍后重试"
        }
    }
}
