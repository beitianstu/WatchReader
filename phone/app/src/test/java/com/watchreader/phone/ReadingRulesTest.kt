package com.watchreader.phone

import com.watchreader.fanqie.ReaderPlugin.ChapterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读位置恢复与来源切换的边界：不镜像 ReaderView/ProgressSync 的实现。 */
class ReadingRulesTest {

    private fun chapters(vararg ids: String): List<ChapterInfo> =
        ids.map { ChapterInfo(it, "第 $it 章", "v1") }

    @Test
    fun restoreOffsetStaysInsideBook() {
        assertEquals(0, ReadingRules.clampOffset(-5, 1000))
        assertEquals(999, ReadingRules.clampOffset(5000, 1000))
        assertEquals(42, ReadingRules.clampOffset(42, 1000))
        assertEquals(0, ReadingRules.clampOffset(30, 0))
    }

    @Test
    fun remoteProgressOnlyWinsWhenStrictlyNewer() {
        assertTrue(ReadingRules.remoteIsNewer(1000L, 1001L))
        assertFalse(ReadingRules.remoteIsNewer(1000L, 1000L))
        assertFalse(ReadingRules.remoteIsNewer(1000L, 999L))
    }

    @Test
    fun sourceChapterSwitchesOnlyWhenItemStillExists() {
        val list = chapters("10", "11", "12")
        assertEquals(2, ReadingRules.chapterIndexOf(list, "12"))
        assertEquals(-1, ReadingRules.chapterIndexOf(list, "99"))
        assertEquals(-1, ReadingRules.chapterIndexOf(list, null))
        assertEquals(-1, ReadingRules.chapterIndexOf(emptyList(), "10"))
    }

    @Test
    fun savedChapterSurvivesShorterDirectory() {
        assertEquals(2, ReadingRules.clampChapterIndex(8, 3))
        assertEquals(0, ReadingRules.clampChapterIndex(4, 0))
    }

    @Test
    fun fontRangeMatchesReaderClamp() {
        assertEquals(12, ReadingRules.clampFontSize(1))
        assertEquals(30, ReadingRules.clampFontSize(99))
        assertEquals(18, ReadingRules.clampFontSize(18))
    }

    @Test
    fun initialZeroOffsetDoesNotOverwriteSavedProgress() {
        // 有旧进度时，setBook 的首次 0 偏移回调只是占位，不能落盘
        assertTrue(ReadingRules.isRestorePlaceholder(restoreAnchor = 4200, charOffset = 0))
        // 旧进度本来就是 0：这次回调就是真实位置，允许保存
        assertFalse(ReadingRules.isRestorePlaceholder(restoreAnchor = 0, charOffset = 0))
        // 恢复后真正装载到的页（偏移可能略小于保存值）不是占位
        assertFalse(ReadingRules.isRestorePlaceholder(restoreAnchor = 4200, charOffset = 4180))
    }
}
