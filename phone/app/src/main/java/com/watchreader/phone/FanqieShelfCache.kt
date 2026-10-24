package com.watchreader.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import com.watchreader.fanqie.ReaderPlugin.BookInfo
import com.watchreader.fanqie.ReaderPlugin.ShelfGroup
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * 番茄书架的内存/磁盘快照。只保存公开书架元信息（组、书 id/标题/作者/封面 URL、顺序、游标），
 * 不保存 Cookie、token、实际账号 ID 或密钥；文件按插件 scope 的 SHA-256 隔离。
 */
internal data class CachedBook(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String = "",
)

internal data class CachedGroup(
    val id: String,
    val name: String,
    val count: Int,
)

internal data class ShelfSnapshot(
    val scopeKey: String,
    val savedAt: Long,
    val groups: List<CachedGroup>,
    val groupBooks: Map<String, List<CachedBook>>,
    val groupNext: Map<String, String>,
    val groupMore: Map<String, Boolean>,
    val groupFetchedAt: Map<String, Long>,
    val previews: Map<String, List<CachedBook>>,
    val coverUrls: Map<String, String>,
)

/** 只保存 scope 的 SHA-256（不是账号 ID）。 */
internal fun fanqieScopeKey(scope: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** 无 Android 依赖的文本快照格式，便于 JVM 单测；字段用 URL-safe Base64 转义。 */
internal object ShelfSnapshotCodec {
    private const val Header = "WRSHELF1"
    private const val MaxChars = 4 * 1024 * 1024
    private const val MaxLines = 40_000
    private val scopePattern = Regex("[0-9a-f]{64}")

    fun encode(snapshot: ShelfSnapshot): String {
        val text = StringBuilder(256 + snapshot.groupBooks.values.sumOf { it.size } * 96)
        text.append(Header).append('\n')
        text.append("scope\t").append(enc(snapshot.scopeKey)).append('\t').append(snapshot.savedAt).append('\n')
        snapshot.groups.forEach { group ->
            text.append("group\t").append(enc(group.id)).append('\t')
                .append(enc(group.name)).append('\t').append(group.count).append('\n')
        }
        snapshot.groupBooks.forEach { (groupId, books) ->
            books.forEachIndexed { index, book ->
                text.append("book\t").append(enc(groupId)).append('\t').append(index).append('\t')
                    .append(enc(book.id)).append('\t').append(enc(book.title)).append('\t')
                    .append(enc(book.author)).append('\t').append(enc(book.coverUrl)).append('\n')
            }
        }
        snapshot.groupNext.forEach { (groupId, next) ->
            text.append("next\t").append(enc(groupId)).append('\t').append(enc(next)).append('\n')
        }
        snapshot.groupMore.forEach { (groupId, more) ->
            text.append("more\t").append(enc(groupId)).append('\t').append(if (more) 1 else 0).append('\n')
        }
        snapshot.groupFetchedAt.forEach { (groupId, at) ->
            text.append("fetched\t").append(enc(groupId)).append('\t').append(at).append('\n')
        }
        snapshot.previews.forEach { (groupId, books) ->
            books.forEachIndexed { index, book ->
                text.append("preview\t").append(enc(groupId)).append('\t').append(index).append('\t')
                    .append(enc(book.id)).append('\t').append(enc(book.title)).append('\t')
                    .append(enc(book.author)).append('\t').append(enc(book.coverUrl)).append('\n')
            }
        }
        snapshot.coverUrls.forEach { (bookId, url) ->
            text.append("cover\t").append(enc(bookId)).append('\t').append(enc(url)).append('\n')
        }
        return text.toString()
    }

    fun decode(raw: String): ShelfSnapshot? {
        if (raw.length > MaxChars) return null
        return runCatching {
            val lines = raw.lineSequence().filter { it.isNotEmpty() }.toList()
            if (lines.size > MaxLines || lines.firstOrNull() != Header) return null
            var scopeKey = ""
            var savedAt = 0L
            val groups = ArrayList<CachedGroup>()
            val books = LinkedHashMap<String, MutableList<CachedBook>>()
            val next = LinkedHashMap<String, String>()
            val more = LinkedHashMap<String, Boolean>()
            val fetched = LinkedHashMap<String, Long>()
            val previews = LinkedHashMap<String, MutableList<CachedBook>>()
            val covers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val parts = line.split('\t')
                when (parts[0]) {
                    "scope" -> {
                        if (parts.size != 3) return null
                        scopeKey = dec(parts[1])
                        savedAt = parts[2].toLong()
                    }
                    "group" -> {
                        if (parts.size != 4) return null
                        groups += CachedGroup(dec(parts[1]), dec(parts[2]), parts[3].toInt())
                    }
                    "book" -> {
                        if (parts.size != 7) return null
                        val groupId = dec(parts[1])
                        val index = parts[2].toInt()
                        if (index < 0) return null
                        val list = books.getOrPut(groupId) { ArrayList() }
                        if (index != list.size) return null
                        list.add(CachedBook(dec(parts[3]), dec(parts[4]), dec(parts[5]), dec(parts[6])))
                    }
                    "next" -> {
                        if (parts.size != 3) return null
                        next[dec(parts[1])] = dec(parts[2])
                    }
                    "more" -> {
                        if (parts.size != 3) return null
                        more[dec(parts[1])] = parts[2] == "1"
                    }
                    "fetched" -> {
                        if (parts.size != 3) return null
                        fetched[dec(parts[1])] = parts[2].toLong()
                    }
                    "preview" -> {
                        if (parts.size != 7) return null
                        val groupId = dec(parts[1])
                        val index = parts[2].toInt()
                        if (index < 0) return null
                        val list = previews.getOrPut(groupId) { ArrayList() }
                        if (index != list.size) return null
                        list.add(CachedBook(dec(parts[3]), dec(parts[4]), dec(parts[5]), dec(parts[6])))
                    }
                    "cover" -> {
                        if (parts.size != 3) return null
                        covers[dec(parts[1])] = dec(parts[2])
                    }
                    else -> return null
                }
            }
            if (!scopePattern.matches(scopeKey)) return null
            ShelfSnapshot(
                scopeKey = scopeKey,
                savedAt = savedAt,
                groups = groups,
                groupBooks = books.mapValues { it.value.filter { book -> book.id.isNotEmpty() } },
                groupNext = next,
                groupMore = more,
                groupFetchedAt = fetched,
                previews = previews,
                coverUrls = covers,
            )
        }.getOrNull()
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}

/** 复用未变化的对象的合并；返回同一 List 实例表示内容完全未变。 */
internal object ShelfMerge {
    fun sameGroup(a: ShelfGroup, b: ShelfGroup): Boolean =
        a.id == b.id && a.name == b.name && a.count == b.count

    fun mergeGroups(old: List<ShelfGroup>, fresh: List<ShelfGroup>): List<ShelfGroup> {
        if (old.size == fresh.size && old.indices.all { sameGroup(old[it], fresh[it]) }) return old
        val byId = old.associateBy { it.id }
        return fresh.map { next ->
            byId[next.id]?.takeIf { sameGroup(it, next) } ?: next
        }
    }

    fun sameBook(a: BookInfo, b: BookInfo): Boolean =
        a.id == b.id && a.title == b.title && a.author == b.author

    fun mergeBooks(old: List<BookInfo>, fresh: List<BookInfo>): List<BookInfo> {
        if (old.size == fresh.size && old.indices.all { sameBook(old[it], fresh[it]) }) return old
        val byId = old.associateBy { it.id }
        return fresh.map { next ->
            byId[next.id]?.takeIf { sameBook(it, next) } ?: next
        }
    }

    /** 增量合并：旧书保持在原顺序，新增书追加；返回同一 List 实例表示没有变化。 */
    fun appendBooks(old: List<BookInfo>, fresh: List<BookInfo>): List<BookInfo> {
        if (fresh.isEmpty()) return old
        val byId = old.associateBy { it.id }
        val result = ArrayList<BookInfo>(old.size + fresh.size)
        result += old
        for (book in fresh) {
            val existing = byId[book.id]
            if (existing == null) result += book
        }
        if (result.size == old.size && result.indices.all { sameBook(result[it], old[it]) }) return old
        return result
    }
}

/**
 * 书架快照落盘。文件很小（元信息），按 scope 哈希隔离；写入走临时文件 + rename，失败不破坏旧快照。
 * 不做像素截图，不保存正文/目录/Cookie/账号 ID。
 */
internal class FanqieSnapshotStore(context: Context) {
    private val dir = File(context.filesDir, "fanqie-shelf").also { it.mkdirs() }
    private val pointer = File(dir, "last")
    private val maxFiles = 6

    fun scopeKey(scope: String): String = fanqieScopeKey(scope)

    fun loadLast(): ShelfSnapshot? = runCatching {
        val key = pointer.readText().trim()
        if (key.isEmpty()) null else load(key)
    }.getOrNull()

    fun load(scopeKey: String): ShelfSnapshot? = runCatching {
        if (!isScopeKey(scopeKey)) return null
        val file = File(dir, "$scopeKey.snap")
        if (!file.isFile || file.length() > 4L * 1024 * 1024) null
        else ShelfSnapshotCodec.decode(file.readText())?.takeIf { it.scopeKey == scopeKey }
    }.getOrNull()

    fun save(snapshot: ShelfSnapshot) {
        if (!isScopeKey(snapshot.scopeKey)) return
        synchronized(storeLock) {
            runCatching {
                val encoded = ShelfSnapshotCodec.encode(snapshot)
                if (encoded.length > 4 * 1024 * 1024) return
                atomicWrite(File(dir, "${snapshot.scopeKey}.snap"), encoded)
                atomicWrite(pointer, snapshot.scopeKey)
                pruneLocked()
            }
        }
    }

    private fun pruneLocked() {
        val files = dir.listFiles { file -> file.name.endsWith(".snap") }.orEmpty()
            .sortedByDescending { it.lastModified() }
        files.drop(maxFiles).forEach { runCatching { it.delete() } }
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File.createTempFile("${target.name.take(24)}-", ".tmp", dir)
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private val storeLock = Any()
        private fun isScopeKey(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
    }
}

/**
 * 有界磁盘缩略图缓存（20MiB）。文件名是 scope+bookId 的哈希，不把账号标识写进文件名；
 * 只保存已经缩到网格尺寸的 JPEG，详情大图仍走 CoverArt。
 */
internal class FanqieCoverStore(context: Context) {
    private val dir = File(context.cacheDir, "fanqie-covers").also { it.mkdirs() }
    private val maxBytes = 20L * 1024 * 1024

    fun load(scopeKey: String, bookId: String): Bitmap? = runCatching {
        if (!validScopeKey(scopeKey) || bookId.isEmpty()) return null
        val file = fileFor(scopeKey, bookId)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    fun save(scopeKey: String, bookId: String, bitmap: Bitmap) {
        if (!validScopeKey(scopeKey) || bookId.isEmpty()) return
        synchronized(storeLock) {
            runCatching {
                val file = fileFor(scopeKey, bookId)
                val dirKey = dir.canonicalPath
                val before = cachedBytes?.takeIf { cachedBytesDir == dirKey } ?: currentBytesLocked().also {
                    cachedBytesDir = dirKey
                    cachedBytes = it
                }
                val oldLength = file.length()
                val temp = File.createTempFile("cover-", ".tmp", dir)
                try {
                    java.io.FileOutputStream(temp).use { out ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out))
                        out.fd.sync()
                    }
                    Os.rename(temp.absolutePath, file.absolutePath)
                } finally {
                    if (temp.exists()) temp.delete()
                }
                val afterWrite = (before - oldLength + file.length()).coerceAtLeast(0L)
                if (afterWrite > maxBytes) {
                    cachedBytes = trimLocked()
                    cachedBytesDir = dirKey
                } else {
                    cachedBytes = afterWrite
                    cachedBytesDir = dirKey
                }
            }
        }
    }

    fun clearMemoryOnly() = Unit

    private fun fileFor(scopeKey: String, bookId: String): File =
        File(dir, sha256Hex("$scopeKey:$bookId") + ".jpg")

    private fun currentBytesLocked(): Long = dir.listFiles { file -> file.isFile }
        .orEmpty().sumOf { it.length() }

    private fun trimLocked(): Long {
        val files = dir.listFiles { file -> file.isFile }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return total
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= length
        }
        return total
    }

    companion object {
        private val storeLock = Any()
        private var cachedBytesDir: String? = null
        private var cachedBytes: Long? = null
        private fun validScopeKey(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
    }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
