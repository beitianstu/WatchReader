package com.watchreader;

import java.util.Arrays;
import java.util.List;

/**
 * 手机章节排版装饰：章标题独占页首，标题下方留一条分隔。
 *
 * 为什么单独一个类：分页测量（{@link Paginator}）、页码映射（{@link PageMap}）、
 * 页数统计（ReaderView.countPages）和实际绘制必须用**同一套规则**，
 * 否则会出现"这一页画不下但按行数算得下"的覆盖/丢字。把规则收在这里，
 * 四处都只是读同一份 {@link #topRowsAt(int)}。
 *
 * 规则：
 *   · 一页正好从章首开始时，顶部预留 {@link #titleRows()} 个正文行高，
 *     用来画放大的章标题 + 分隔线；
 *   · 章标题那一行本身不再作为正文行参与排版（{@link #bodyStartAfterTitle}），
 *     避免同一个标题既被放大画一次、又被正文再画一次；
 *   · 其余页（从章节中间开始）完全不预留，行为与旧版一致。
 *
 * 纯逻辑、不依赖 Android，可直接在 JVM 单测里验证边界。
 */
public final class ChapterDecor {

    /** 默认预留行数：2 行给放大标题，1 行给分隔与留白。 */
    public static final int DEFAULT_TITLE_ROWS = 3;

    /** 章首字符偏移，升序。 */
    private final int[] starts;
    private final int titleRows;
    private int[] reservedRows;
    private boolean skipTitle = true;

    public ChapterDecor(int[] starts, int titleRows) {
        int[] sorted = starts == null ? new int[0] : starts.clone();
        Arrays.sort(sorted);
        this.starts = sorted;
        this.titleRows = Math.max(0, titleRows);
    }

    /** 已测量的逐章标题高度；偏移必须按章节顺序升序。 */
    public ChapterDecor(int[] starts, int[] rows) {
        this(starts, DEFAULT_TITLE_ROWS);
        if (rows == null || rows.length != this.starts.length || !Arrays.equals(starts, this.starts)) {
            throw new IllegalArgumentException("Chapter offsets and heights must be aligned");
        }
        reservedRows = rows.clone();
        for (int i = 0; i < reservedRows.length; i++) reservedRows[i] = Math.max(1, reservedRows[i]);
    }

    public ChapterDecor(int[] starts, int[] rows, boolean skipTitle) {
        this(starts, rows);
        this.skipTitle = skipTitle;
    }

    public int bodyStart(CharSequence text, int from) {
        return skipTitle ? bodyStartAfterTitle(text, from) : from;
    }

    /** 章节列表 → 装饰；没有章节时返回 null（等于不装饰，保持旧排版）。 */
    public static ChapterDecor of(List<Chapters.Chapter> chapters, int titleRows) {
        if (chapters == null || chapters.isEmpty()) {
            return null;
        }
        int[] offsets = new int[chapters.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = chapters.get(i).offset;
        }
        return new ChapterDecor(offsets, titleRows);
    }

    public int titleRows() {
        return titleRows;
    }

    public int size() {
        return starts.length;
    }

    public int[] starts() {
        return starts.clone();
    }

    /** 正好从章首开始的一页，顶部预留多少行；其余位置返回 0。 */
    public int topRowsAt(int startChar) {
        int index = indexOf(startChar);
        return index < 0 ? 0 : reservedRows == null ? titleRows : reservedRows[index];
    }

    public boolean isStart(int charOffset) {
        return indexOf(charOffset) >= 0;
    }

    /** 严格大于 charOffset 的下一个章首；没有则返回 -1。 */
    public int nextStartAfter(int charOffset) {
        int lo = 0;
        int hi = starts.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] > charOffset) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found < 0 ? -1 : starts[found];
    }

    /**
     * 章标题行之后的正文起点：跳过标题行本身（以及它的换行符）。
     * 标题就是全文最后一行时返回文本长度（这一页只剩标题区）。
     */
    public static int bodyStartAfterTitle(CharSequence text, int from) {
        if (text == null) {
            return Math.max(0, from);
        }
        int start = Math.max(0, from);
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return text.length();
    }

    private int indexOf(int value) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] == value) {
                return mid;
            }
            if (starts[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }
}
