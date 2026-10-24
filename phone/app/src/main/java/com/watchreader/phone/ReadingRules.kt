package com.watchreader.phone

import com.watchreader.fanqie.ReaderPlugin.ChapterInfo

/**
 * 阅读位置/来源切换的纯规则，供手机阅读页与番茄阅读页共用。
 * 不碰 Android、不复制分页或进度引擎，只做边界判断，便于 JVM 单测。
 */
internal object ReadingRules {

    const val MIN_FONT_SIZE = 12
    const val MAX_FONT_SIZE = 30
    const val DEFAULT_PHONE_FONT_SIZE = 18

    /** 与旧 ReaderView.setFontSize 的 clamp 保持一致，避免设置页显示 30 实际被截断。 */
    fun clampFontSize(size: Int): Int = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    /** 恢复位置必须落在正文范围内；空书统一回到 0。 */
    fun clampOffset(offset: Int, charCount: Int): Int =
        if (charCount <= 0) 0 else offset.coerceIn(0, charCount - 1)

    /**
     * setBook 之后、restoreTo 生效之前，ReaderView 会先回调一次初始页（偏移 0）。
     * 恢复目标不是 0 时这次回调只是占位，不能落盘覆盖已保存的进度。
     */
    fun isRestorePlaceholder(restoreAnchor: Int, charOffset: Int): Boolean =
        restoreAnchor > 0 && charOffset == 0

    /** 云端进度只在严格更新时覆盖本地，避免时钟相同的重复拉取打断阅读。 */
    fun remoteIsNewer(localTimestamp: Long, remoteTimestamp: Long): Boolean =
        remoteTimestamp > localTimestamp

    /** 来源章节 id → 目录下标；找不到返回 -1，调用方保留本地保存的章节。 */
    fun chapterIndexOf(chapters: List<ChapterInfo>, itemId: String?): Int {
        if (itemId.isNullOrEmpty() || chapters.isEmpty()) return -1
        return chapters.indexOfFirst { it.id == itemId }
    }

    /** 目录可能比上次保存时变短，保存的下标要重新夹到有效范围。 */
    fun clampChapterIndex(index: Int, chapterCount: Int): Int =
        if (chapterCount <= 0) 0 else index.coerceIn(0, chapterCount - 1)
}
