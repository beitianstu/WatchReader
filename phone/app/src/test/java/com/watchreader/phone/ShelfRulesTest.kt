package com.watchreader.phone

import com.watchreader.fanqie.ReaderPlugin.BookInfo
import com.watchreader.fanqie.ReaderPlugin.ReadingProgress
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校验三列/两列和筛选排序只依赖真实字段；不启动 Compose、不联网、不读真实书。 */
class ShelfRulesTest {

    private fun book(
        file: File,
        title: String,
        author: String,
        percent: Float,
        modified: Long,
    ): PhoneBook {
        file.writeText("x")
        file.setLastModified(modified)
        return PhoneBook(file, title, author, 1024L, percent)
    }

    private fun progress(
        book: String,
        rate: Double,
        timestamp: Long,
        chapter: Int = 0,
    ): ReadingProgress = ReadingProgress(
        book,
        "item-$book",
        chapter,
        0,
        0,
        0,
        timestamp,
        rate,
        rate,
        "v1",
        false,
    )

    @Test
    fun gridUsesThreeColumnsUntilSmallScreenOrLargeFont() {
        assertEquals(3, ShelfRules.gridColumns(360, 1f))
        assertEquals(3, ShelfRules.gridColumns(411, 1.2f))
        assertEquals(2, ShelfRules.gridColumns(320, 1f))
        assertEquals(2, ShelfRules.gridColumns(411, 1.3f))
    }

    @Test
    fun localFilterSortAndSearchUseRealFields() {
        val dir = Files.createTempDirectory("shelf-rules-local").toFile()
        try {
            val now = System.currentTimeMillis()
            val unread = book(File(dir, "a.txt"), "Beta", "张三", 0f, now - 1_000L)
            val reading = book(File(dir, "b.txt"), "Alpha", "李四", 50f, now - 10_000L)
            val finished = book(File(dir, "c.txt"), "Gamma", "王五", 100f, now - 5_000L)
            val books = listOf(unread, reading, finished)

            assertEquals(
                listOf("Beta", "Gamma", "Alpha"),
                ShelfRules.localVisible(books, "", ShelfReadFilter.All, ShelfSort.Default).map { it.title },
            )
            assertEquals(
                listOf("Beta"),
                ShelfRules.localVisible(books, "张", ShelfReadFilter.All, ShelfSort.Default).map { it.title },
            )
            assertEquals(
                listOf("Beta"),
                ShelfRules.localVisible(books, "", ShelfReadFilter.Unread, ShelfSort.Title).map { it.title },
            )
            assertEquals(
                listOf("Alpha"),
                ShelfRules.localVisible(books, "", ShelfReadFilter.Reading, ShelfSort.Title).map { it.title },
            )
            assertEquals(
                listOf("Gamma"),
                ShelfRules.localVisible(books, "", ShelfReadFilter.Finished, ShelfSort.Title).map { it.title },
            )
            assertEquals(
                listOf("Alpha", "Beta", "Gamma"),
                ShelfRules.localVisible(books, "", ShelfReadFilter.All, ShelfSort.Title).map { it.title },
            )
            assertEquals(
                listOf("Gamma", "Alpha", "Beta"),
                ShelfRules.localVisible(books, "", ShelfReadFilter.All, ShelfSort.Progress).map { it.title },
            )
            assertTrue(ShelfRules.localInfoText(reading).contains("50%"))
            assertEquals("未读", LibraryFiles.progressText(0f))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fanqieFilterSortUseLocalProgressOnly() {
        val noProgress = FanqieShelfItem(BookInfo("1", "Beta", "Zed"), null)
        val reading = FanqieShelfItem(
            BookInfo("2", "Alpha", "Amy"),
            progress("2", rate = 0.25, timestamp = 200L, chapter = 3),
        )
        val finished = FanqieShelfItem(
            BookInfo("3", "Gamma", "Mia"),
            progress("3", rate = 1.0, timestamp = 100L),
        )
        val items = listOf(noProgress, reading, finished)

        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            ShelfRules.fanqieVisible(items, ShelfReadFilter.All, ShelfSort.Title).map { it.book.title },
        )
        assertEquals(
            listOf("Beta"),
            ShelfRules.fanqieVisible(items, ShelfReadFilter.Unread, ShelfSort.Default).map { it.book.title },
        )
        assertEquals(
            listOf("Alpha"),
            ShelfRules.fanqieVisible(items, ShelfReadFilter.Reading, ShelfSort.Default).map { it.book.title },
        )
        assertEquals(
            listOf("Gamma"),
            ShelfRules.fanqieVisible(items, ShelfReadFilter.Finished, ShelfSort.Default).map { it.book.title },
        )
        assertEquals(
            listOf("Alpha", "Gamma", "Beta"),
            ShelfRules.fanqieVisible(items, ShelfReadFilter.All, ShelfSort.Progress).map { it.book.title },
        )
        assertEquals(
            listOf("Alpha", "Gamma", "Beta"),
            ShelfRules.fanqieVisible(items, ShelfReadFilter.All, ShelfSort.Author).map { it.book.title },
        )
        assertTrue(ShelfRules.fanqieInfoText(reading).contains("25%"))
        assertEquals("未开始", ShelfRules.fanqieProgressText(null))
        assertEquals("读完", ShelfRules.fanqieProgressText(progress("4", 1.0, 1L)))
        assertEquals("第 4 章", ShelfRules.fanqieProgressText(progress("5", 0.0, 1L, chapter = 3)))
    }

    @Test
    fun previewTakesAtMostFirstFourBooks() {
        val books = (1..9).map { BookInfo("$it", "书$it", "作者$it") }
        val preview = ShelfRules.previewOf(books)
        assertEquals(4, preview.size)
        assertEquals(listOf("书1", "书2", "书3", "书4"), preview.map { it.title })
    }
}
