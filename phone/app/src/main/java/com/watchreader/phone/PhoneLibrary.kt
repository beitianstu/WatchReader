package com.watchreader.phone

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.watchreader.Book
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Locale

/** 书库里的一本书：只保留列表需要的元信息，不加载正文与索引。 */
data class PhoneBook(
    val file: File,
    val title: String,
    val author: String,
    val size: Long,
    val percent: Float,
)

/** 纯 JVM 可测的导入/筛选规则，不依赖 Android。 */
internal object LibraryFiles {

    /** 只串行化“选名 + 改名”这一小段；复制本身仍可并行，避免并发导入抢同一个名字。 */
    private val commitLock = Any()

    /** 与旧书库相同的公共扫描目录；/sdcard 根目录不扫，避免翻遍其它 App 的缓存。 */
    val PUBLIC_DIRS = listOf("/sdcard/Download", "/sdcard/Books", "/sdcard/books", "/sdcard/Documents")

    /** SAF 返回的 display name 可能是空串或带路径；统一成可落盘的 .txt 文件名。 */
    fun safeName(raw: String?): String {
        val base = (raw ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filter { it.code >= 0x20 && it != '\u007f' }
            .trim()
            .ifEmpty { "导入的书" }
        val withExt = if (base.endsWith(".txt", true) || base.endsWith(".text", true)) base else "$base.txt"
        return if (withExt.length <= 96) withExt else withExt.take(92) + ".txt"
    }

    /**
     * 导入一个流：唯一临时文件 + 原子改名。提交时目标已存在就换下一个名字，
     * 绝不覆盖其它导入或原有书籍；失败只清理本次的临时文件。
     */
    fun importFrom(input: InputStream, dir: File, base: String): String {
        val temp = File.createTempFile("import-", ".part", dir)
        try {
            copyInto(temp, input)
            if (temp.length() <= 512L) {
                throw IOException("文件为空或过小（书库只收录大于 512 字节的 TXT）")
            }
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("导入已取消")
            }
            return commitImport(temp, dir, base)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** 分块复制并检查线程中断，避免取消后还在后台长时间写文件。 */
    fun copyInto(temp: File, input: InputStream) {
        temp.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("导入已取消")
                }
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.flush()
        }
    }

    /**
     * 把临时文件提交为最终文件名。选名与改名在进程内互斥，且 `Files.move`
     * 不带 REPLACE_EXISTING：目标已存在就换下一个后缀，绝不覆盖其它导入或原有书籍。
     */
    fun commitImport(temp: File, dir: File, base: String): String = synchronized(commitLock) {
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.', "")
        for (index in 0..99) {
            val name = when {
                index == 0 -> base
                ext.isEmpty() -> "$stem (${index + 1})"
                else -> "$stem (${index + 1}).$ext"
            }
            val target = File(dir, name)
            if (target.exists()) continue
            try {
                Files.move(temp.toPath(), target.toPath())
                return@synchronized target.name
            } catch (e: FileAlreadyExistsException) {
                // 外部进程恰好抢先创建：继续试下一个，不删除也不覆盖
            }
        }
        throw IOException("同名文件过多，无法保存")
    }

    fun filter(books: List<PhoneBook>, query: String): List<PhoneBook> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return books
        return books.filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.author.contains(keyword, ignoreCase = true) ||
                it.file.name.contains(keyword, ignoreCase = true)
        }
    }

    fun sizeText(bytes: Long): String =
        if (bytes >= 1024 * 1024) {
            String.format(Locale.US, "%.1f MB", bytes / 1048576f)
        } else {
            String.format(Locale.US, "%.0f KB", bytes / 1024f)
        }

    fun progressText(percent: Float): String = when {
        percent <= 0f -> "未读"
        percent >= 99.5f -> "读完"
        else -> "${percent.toInt()}%"
    }
}

/** 手机书库的 Android 侧入口：私有目录、SAF 导入、旧引擎扫描、旧进度键。 */
internal object PhoneLibrary {

    const val PREFS = "watchreader"
    const val FONT_SIZE = "fontSize"
    const val PHONE_FONT_SIZE = 18

    /** 旧阅读页没有字号时默认 13sp；手机首次进入写入 18sp，已有值不覆盖。 */
    fun ensurePhoneFontSize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(FONT_SIZE)) prefs.edit().putInt(FONT_SIZE, PHONE_FONT_SIZE).apply()
    }

    fun booksDir(context: Context): File =
        File(context.filesDir, "books").also { if (!it.isDirectory) it.mkdirs() }

    fun indexDir(context: Context): File =
        File(context.filesDir, "index").also { if (!it.isDirectory) it.mkdirs() }

    fun hasPublicAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** 只读目录元信息，不解码正文；调用方负责放到 IO 线程。 */
    fun scan(context: Context): List<PhoneBook> {
        val dirs = ArrayList<File>()
        dirs += booksDir(context)
        if (hasPublicAccess(context)) {
            LibraryFiles.PUBLIC_DIRS.map(::File).filter { it.isDirectory }.forEach { dirs += it }
        }

        val unique = LinkedHashMap<String, Book.Candidate>()
        for (dir in dirs) {
            for (candidate in Book.scan(dir, true)) {
                unique.putIfAbsent(candidate.file.absolutePath, candidate)
            }
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cacheDir = indexDir(context)
        return unique.values
            .sortedByDescending { it.file.lastModified() }
            .map { candidate ->
                val parsed = Book.parseName(candidate.file.name)
                PhoneBook(
                    file = candidate.file,
                    title = parsed[0].ifEmpty { candidate.file.name },
                    author = parsed.getOrNull(1).orEmpty(),
                    size = candidate.size,
                    percent = percent(prefs, cacheDir, candidate.file),
                )
            }
    }

    /**
     * 把 SAF 选中的 TXT 复制进私有 files/books。临时文件唯一、提交不覆盖；
     * 失败只删本次临时文件，原有书籍不受影响；返回最终文件名。
     */
    fun importBook(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val base = LibraryFiles.safeName(displayName)
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        return input.use { LibraryFiles.importFrom(it, booksDir(context), base) }
    }

    /** 与旧 ReaderActivity/MainActivity 完全相同的进度键：progress.<缓存 id>。 */
    private fun percent(prefs: SharedPreferences, indexDir: File, file: File): Float {
        val raw = prefs.getString("progress." + Book.cacheId(file), null) ?: return 0f
        return try {
            val parts = raw.split('|')
            val stored = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val offset = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val total = Book.cachedCharCount(indexDir, file)
            val value = if (total > 0) offset * 100f / total else stored
            value.coerceIn(0f, 100f)
        } catch (t: Throwable) {
            0f
        }
    }
}
