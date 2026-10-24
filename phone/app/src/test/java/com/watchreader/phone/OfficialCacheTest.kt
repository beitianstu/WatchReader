package com.watchreader.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 业务测试：切头/JSON 边界、中文序号、TXT 章节拼接、身份去重与文件名白名单。
 * 不接触 Android、真实缓存、密钥或设备；AES/gzip/HTML 由真机验证覆盖。
 */
class OfficialCacheTest {

    @Test
    fun jsonStartsAtFirstBraceWithinFirst64Bytes() {
        val raw = "1791037148-86400000{\"name\":\"第1章 标题\"}".toByteArray(Charsets.UTF_8)
        val start = OfficialCache.findJsonStart(raw)
        assertEquals("1791037148-86400000".length, start)
        assertEquals("{\"name\":\"第1章 标题\"}", OfficialCache.extractJsonText(raw, start))
    }

    @Test
    fun braceOutsideFirst64BytesIsRejected() {
        val raw = ByteArray(80) { 'a'.code.toByte() }
        raw[65] = '{'.code.toByte()
        assertEquals(-1, OfficialCache.findJsonStart(raw))
    }

    @Test
    fun trailingSerializationTailIsTrimmed() {
        val head = "1791037148-86400000{\"name\":\"第1章\"}".toByteArray(Charsets.UTF_8)
        val tail = byteArrayOf(0, 1, 2, 0x7f)
        val raw = head + tail
        val start = OfficialCache.findJsonStart(raw)
        assertEquals("{\"name\":\"第1章\"}", OfficialCache.extractJsonText(raw, start))
    }

    @Test
    fun titleOrderUsesArabicAndChineseNumbers() {
        assertEquals(12, OfficialCache.chapterOrderFromTitle("第12章 标题"))
        assertEquals(12, OfficialCache.chapterOrderFromTitle("第十二章 标题"))
        assertEquals(3, OfficialCache.chapterOrderFromTitle("第 三 节 标题"))
        assertEquals(103, OfficialCache.chapterOrderFromTitle("第一百零三章"))
        assertEquals(10003, OfficialCache.chapterOrderFromTitle("第一万零三章"))
        assertNull(OfficialCache.chapterOrderFromTitle("番外 一"))
        assertNull(OfficialCache.chapterOrderFromTitle(null))
    }

    @Test
    fun chineseNumberParserCoversCommonForms() {
        assertEquals(10L, OfficialCache.parseChineseNumber("十"))
        assertEquals(13L, OfficialCache.parseChineseNumber("十三"))
        assertEquals(23L, OfficialCache.parseChineseNumber("二十三"))
        assertEquals(103L, OfficialCache.parseChineseNumber("一百零三"))
        assertEquals(200L, OfficialCache.parseChineseNumber("两百"))
        assertEquals(1001L, OfficialCache.parseChineseNumber("一千零一"))
        assertEquals(10003L, OfficialCache.parseChineseNumber("一万零三"))
    }

    @Test
    fun chapterBlockDoesNotRepeatExistingHeading() {
        val out = StringBuilder()
        OfficialCache.appendChapter(out, "第1章 标题", "第1章 标题\n\n正文第一段")
        assertEquals("第1章 标题\n\n正文第一段", out.toString())
    }

    @Test
    fun chapterBlockAddsHeadingWhenBodyLacksIt() {
        val out = StringBuilder()
        OfficialCache.appendChapter(out, "第2章 标题", "正文")
        assertEquals("第2章 标题\n\n正文", out.toString())
    }

    @Test
    fun normalizeBodyCollapsesBlankLinesAndWindowsNewlines() {
        assertEquals("甲\n\n乙", OfficialCache.normalizeBody(" 甲 \r\n\r\n\r\n 乙 "))
    }

    @Test
    fun appendChapterSeparatesChapters() {
        val out = StringBuilder()
        OfficialCache.appendChapter(out, "第1章", "甲")
        OfficialCache.appendChapter(out, "第2章", "乙")
        assertEquals("第1章\n\n甲\n\n第2章\n\n乙", out.toString())
    }

    @Test
    fun cacheFileNameRequiresSignedDigits() {
        assertTrue(OfficialCache.isCacheFileName("123456789"))
        assertTrue(OfficialCache.isCacheFileName("-123"))
        assertFalse(OfficialCache.isCacheFileName("abc"))
        assertFalse(OfficialCache.isCacheFileName("12a"))
        assertFalse(OfficialCache.isCacheFileName("../12"))
    }

    @Test
    fun dedupeKeyUsesBookAndChapterIdentity() {
        assertEquals("book:42", OfficialCache.dedupeKey("book", 42))
        assertEquals("book:0", OfficialCache.dedupeKey("book", 0))
    }
}
