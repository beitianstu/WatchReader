package com.watchreader;

import android.text.TextPaint;

import java.util.HashMap;
import java.util.Map;

/**
 * 精确页码映射（替代"用平均每页行数估算页码"的写法）。
 *
 * 为什么需要它：
 *   每页覆盖的**源行数并不固定** —— 一个超长段落会在一页里被拆成十几行，而空行密集处
 *   一页也能放十几行。早先用 `pageIndex = 起始行 / 平均每页行数` 估算，快速连翻时
 *   误差会累积，表现就是"页码乱跳、跟内容对不上"。
 *
 * 实现要点（第一版在这里踩过坑，注释保留下来防止再犯）：
 *   · 页边界是**确定性**的：本页结束行 = 下一页起始行。
 *   · 向前/向后翻页时页码是明确的 ±1，直接从当前页推，不做任何估算。
 *   · 只有"跳到没访问过的页"（目录/百分比）才需要救援定位：
 *     先用锚点纠正估算，再邻页收敛。
 *   · **查"行→页码"必须用行区间比对，不能复用"页码→行"的结果** ——
 *     第一版就是拿 resolve(估算页) 返回的起始行当答案，导致第 8~18 行也被判成第 0 页。
 */
final class PageMap {

    private final Book book;
    private final TextPaint paint;
    private final int width;
    private final int height;
    private final float spacingAdd;

    /** 页码 → 起始行号 */
    private final Map<Integer, Integer> pageToLine = new HashMap<>();
    /** 行号 → 页码（已确定的行） */
    private final Map<Integer, Integer> lineToPage = new HashMap<>();
    /**
     * 段落锚点：估算页号 → (真实页号 - 估算页号)。
     * 相邻页的估算误差几乎相同，用最近锚点纠正命中率很高。
     */
    private final Map<Integer, Integer> anchors = new HashMap<>();

    private final int totalLines;
    /** 每页平均覆盖的源行数，仅用于给"救援定位"一个初值 */
    private final int avgLinesPerPage;

    PageMap(Book book, TextPaint paint, int width, int height, float spacingAdd) {
        this.book = book;
        this.paint = paint;
        this.width = width;
        this.height = height;
        this.spacingAdd = spacingAdd;
        this.totalLines = book.index.length;
        this.avgLinesPerPage = sampleLinesPerPage();
    }

    /** 结果：行号 + 页码，两者一定自洽 */
    static final class Loc {
        final int line;
        final int page;

        Loc(int line, int page) {
            this.line = line;
            this.page = page;
        }
    }

    private int sampleLinesPerPage() {
        int cursor = 0;
        long sum = 0;
        int samples = 0;
        for (int i = 0; i < 8 && cursor < totalLines; i++) {
            int end = Paginator.pageEndLine(book, paint, cursor, width, height, spacingAdd);
            if (end <= cursor) {
                break;
            }
            sum += end - cursor;
            samples++;
            cursor = end;
        }
        if (samples == 0) {
            return Math.max(1, (int) Math.floor(height / Math.max(1f,
                    Paginator.lineHeightOf(paint, spacingAdd))));
        }
        return Math.max(1, (int) (sum / samples));
    }

    int getAvgLinesPerPage() {
        return avgLinesPerPage;
    }

    /** 已知页码时取起始行（翻页主路径，命中缓存即 O(1)） */
    int lineOfPage(int pageIndex) {
        Integer cached = pageToLine.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        if (pageIndex <= 0) {
            pageToLine.put(0, 0);
            return 0;
        }
        return resolvePage(pageIndex).line;
    }

    /** 已知行号时取所属页（用行区间比对，绝不复用 page→line 的返回值） */
    int pageOfLineKnown(int lineIndex) {
        int line = clampLine(lineIndex);

        Integer known = lineToPage.get(line);
        if (known != null) {
            return known;
        }

        return resolvePage(estimatePage(line)).page;
    }

    /**
     * 定位到指定行：返回 (该行所属页的页码, 该页起始行)。
     * 用于"恢复进度/跳转"这类需要同时知道页码和起点的场景。
     */
    Loc locateLine(int lineIndex) {
        int line = clampLine(lineIndex);

        Integer known = lineToPage.get(line);
        if (known != null) {
            return new Loc(lineOfPage(known), known);
        }

        return resolvePage(estimatePage(line));
    }

    /** 往前/往后翻一页：页码是确定的 ±1，不需要估算 */
    Loc nextPage(int currentPage, int currentEndLine) {
        int nextPage = currentPage + 1;
        int start = clampLine(currentEndLine);
        register(nextPage, start);
        return new Loc(start, nextPage);
    }

    Loc prevPage(int currentPage, int currentStartLine) {
        int prev = Math.max(0, currentPage - 1);
        Integer cached = pageToLine.get(prev);
        int start = cached != null ? cached : (prev == 0 ? 0 : resolvePage(prev).line);
        if (start >= currentStartLine) {
            start = Math.max(0, currentStartLine - 1);
        }
        register(prev, start);
        return new Loc(start, prev);
    }

    /** 登记一页：把该页覆盖的行都登记成同一页码（长段落跨页时后写入的会覆盖成更靠后的页） */
    void register(int pageIndex, int startLine) {
        int start = clampLine(startLine);
        pageToLine.put(pageIndex, start);
        lineToPage.put(start, pageIndex);

        int end = Paginator.pageEndLine(book, paint, start, width, height, spacingAdd);
        for (int l = start; l < end && l < totalLines; l++) {
            lineToPage.put(l, pageIndex);
        }

        int estimated = start / avgLinesPerPage;
        anchors.put(estimated, pageIndex - estimated);
    }

    /** 返回的是"起始行落在该页"的那一页；同时把该页所有行登记好 */
    private Loc resolvePage(int pageIndex) {
        int target = Math.max(0, pageIndex);

        Integer direct = pageToLine.get(target);
        if (direct != null) {
            return new Loc(direct, target);
        }

        int page = corrected(target);
        // 只取缓存里的起点，取不到就从 0 开始向后滚 —— 绝不能在这里再调 resolvePage，
        // 否则 resolvePage ↔ startOfKnownOrZero 会互相递归直接栈溢出（真踩过）。
        int cursor = safeStartOf(page);

        int guard = 0;
        while (guard++ < 4096) {
            if (page == target) {
                register(page, cursor);
                return new Loc(cursor, page);
            }
            if (page < target) {
                int end = Paginator.pageEndLine(book, paint, cursor, width, height, spacingAdd);
                register(page, cursor);
                if (end <= cursor) {
                    // 已到全文末尾：把页码夹到最后一页，避免无限循环
                    return new Loc(cursor, page);
                }
                cursor = end;
                page++;
            } else {
                int back = Math.max(0, cursor - avgLinesPerPage);
                int bGuard = 0;
                while (back < cursor && bGuard++ < 128) {
                    int end = Paginator.pageEndLine(book, paint, back, width, height, spacingAdd);
                    if (end >= cursor) {
                        break;
                    }
                    back = end;
                }
                if (back >= cursor) {
                    return new Loc(cursor, page);
                }
                page--;
                cursor = back;
                register(page, cursor);
            }
        }
        return new Loc(cursor, Math.max(0, page));
    }

    /** 纯粹的缓存查询：查不到就返回 0，绝不触发解析（防递归） */
    private int safeStartOf(int pageIndex) {
        if (pageIndex <= 0) {
            return 0;
        }
        Integer cached = pageToLine.get(pageIndex);
        return cached != null ? cached : 0;
    }

    private int corrected(int pageIndex) {
        int bestEstimate = -1;
        int bestDelta = 0;
        for (Map.Entry<Integer, Integer> e : anchors.entrySet()) {
            int estimate = e.getKey();
            if (estimate <= pageIndex && estimate > bestEstimate) {
                bestEstimate = estimate;
                bestDelta = e.getValue();
            }
        }
        if (bestEstimate < 0) {
            return Math.max(0, pageIndex);
        }
        return Math.max(0, pageIndex + bestDelta);
    }

    private int estimatePage(int line) {
        return Math.max(0, line / avgLinesPerPage);
    }

    private int clampLine(int line) {
        if (line < 0) {
            return 0;
        }
        return Math.min(line, Math.max(0, totalLines - 1));
    }

    void clear() {
        pageToLine.clear();
        anchors.clear();
        lineToPage.clear();
    }
}
