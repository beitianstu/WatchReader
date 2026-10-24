package com.watchreader.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfSnapshotCodecTest {
    private val scope = "0123456789abcdef".repeat(4)

    private fun snapshot(scopeKey: String = scope) = ShelfSnapshot(
        scopeKey = scopeKey,
        savedAt = 123L,
        groups = listOf(CachedGroup("g", "分组", 1)),
        groupBooks = mapOf("g" to listOf(CachedBook("b", "书名", "作者", "https://cover"))),
        groupNext = mapOf("g" to "cursor"),
        groupMore = mapOf("g" to true),
        groupFetchedAt = mapOf("g" to 456L),
        previews = mapOf("g" to listOf(CachedBook("b", "书名", "作者", "https://cover"))),
        coverUrls = mapOf("b" to "https://cover"),
    )

    @Test
    fun roundTripsValidSnapshot() {
        assertEquals(snapshot(), ShelfSnapshotCodec.decode(ShelfSnapshotCodec.encode(snapshot())))
    }

    @Test
    fun rejectsInvalidScopeKey() {
        assertNull(ShelfSnapshotCodec.decode(ShelfSnapshotCodec.encode(snapshot("../unsafe"))))
    }

    @Test
    fun rejectsHugeNegativeAndSparseIndexesWithoutFillingGaps() {
        val encoded = ShelfSnapshotCodec.encode(snapshot())
        val book = encoded.lineSequence().first { it.startsWith("book\t") }
        assertNull(ShelfSnapshotCodec.decode(encoded.replace(book, book.replace("\t0\t", "\t2147483647\t"))))
        assertNull(ShelfSnapshotCodec.decode(encoded.replace(book, book.replace("\t0\t", "\t-1\t"))))
        assertNull(ShelfSnapshotCodec.decode(encoded.replace(book, book.replace("\t0\t", "\t1\t"))))
    }

    @Test
    fun rejectsSparsePreviewIndexes() {
        val encoded = ShelfSnapshotCodec.encode(snapshot())
        val preview = encoded.lineSequence().first { it.startsWith("preview\t") }
        assertNull(ShelfSnapshotCodec.decode(encoded.replace(preview, preview.replace("\t0\t", "\t1000000000\t"))))
    }
}
