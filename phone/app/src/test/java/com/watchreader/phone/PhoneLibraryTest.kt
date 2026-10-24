package com.watchreader.phone

import com.watchreader.Book
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 覆盖手机书库的关键业务链路：导入落盘 → 旧引擎扫描 → 索引缓存重开；
 * 同名并发导入不互相覆盖；读取失败不破坏原有书籍、不留下半成品。
 */
class PhoneLibraryTest {

    private fun body(prefix: String): String = buildString {
        append(prefix).append('\n')
        repeat(80) { index ->
            append("用于验证导入与重新打开的正文，第 ").append(index).append(" 行。\n")
        }
    }

    @Test
    fun importedTxtIsScannedAndReopenedFromIndexCache() {
        val root = Files.createTempDirectory("watchreader-phone").toFile()
        try {
            val booksDir = File(root, "books").apply { mkdirs() }
            val cacheDir = File(root, "index").apply { mkdirs() }

            val name = LibraryFiles.safeName("测试书(作者).txt")
            assertEquals("测试书(作者).txt", name)
            val imported = LibraryFiles.importFrom(
                ByteArrayInputStream(body("第一章 开始").toByteArray(Charsets.UTF_8)),
                booksDir,
                name,
            )
            assertEquals(name, imported)

            val scanned = Book.scan(booksDir, true)
            assertEquals(1, scanned.size)
            assertEquals(imported, scanned[0].file.name)

            val parsed = Book.parseName(scanned[0].file.name)
            assertEquals("测试书", parsed[0])
            assertEquals("作者", parsed[1])

            val first = Book.open(scanned[0].file, cacheDir)
            assertTrue(first.text.contains("第一章 开始"))
            assertTrue(first.charCount() > 512)
            assertTrue(Book.cachedCharCount(cacheDir, scanned[0].file) > 0)

            val reopened = Book.open(scanned[0].file, cacheDir)
            assertEquals(first.text, reopened.text)
            assertEquals(0L, reopened.buildMs)

            val books = listOf(PhoneBook(scanned[0].file, "测试书", "作者", scanned[0].size, 0f))
            assertEquals(1, LibraryFiles.filter(books, "测试").size)
            assertEquals(1, LibraryFiles.filter(books, "作者").size)
            assertEquals(0, LibraryFiles.filter(books, "不存在").size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentSameNameImportsKeepBothContents() {
        val dir = Files.createTempDirectory("watchreader-phone-race").toFile()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val bodies = listOf(body("甲"), body("乙"))
            val results = arrayOfNulls<String>(2)
            val errors = arrayOfNulls<Throwable>(2)
            for (index in 0..1) {
                pool.execute {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        results[index] = LibraryFiles.importFrom(
                            ByteArrayInputStream(bodies[index].toByteArray(Charsets.UTF_8)),
                            dir,
                            "书.txt",
                        )
                    } catch (t: Throwable) {
                        errors[index] = t
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS))
            assertNull(errors[0])
            assertNull(errors[1])
            assertEquals(setOf("书.txt", "书 (2).txt"), results.filterNotNull().toSet())

            val stored = dir.listFiles { file -> file.name.endsWith(".txt") }
                .orEmpty()
                .map { it.readText() }
                .toSet()
            assertEquals(bodies.toSet(), stored)
            assertTrue(dir.listFiles { file -> file.name.endsWith(".part") }.orEmpty().isEmpty())
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedReadKeepsExistingBookAndLeavesNoPartialFile() {
        val dir = Files.createTempDirectory("watchreader-phone-fail").toFile()
        try {
            val existing = File(dir, "书.txt")
            existing.writeText("原有内容")

            val failing = object : InputStream() {
                private var sent = 0
                override fun read(): Int {
                    if (sent++ < 600) return 'x'.code
                    throw IOException("模拟读取中断")
                }
            }

            try {
                LibraryFiles.importFrom(failing, dir, "书.txt")
                fail("应当抛出读取异常")
            } catch (expected: IOException) {
                assertEquals("模拟读取中断", expected.message)
            }

            assertEquals("原有内容", existing.readText())
            assertEquals(listOf("书.txt"), dir.list().orEmpty().sorted())
        } finally {
            dir.deleteRecursively()
        }
    }
}
