package com.watchreader;

import android.text.TextPaint;

import java.util.HashMap;
import java.util.Map;

/**
 * 精确页码映射（页码 ↔ 起始字符偏移）。
 *
 * 为什么用字符偏移而不是行号：
 *   早期版本用"行号"当页面主键，而 {@link Book#index} 的一行是一个**段落**；
 *   长段落会被拆成多个视觉行，于是"本页消耗了多少视觉行"与"本页消耗了多少逻辑行"
 *   并不相等。结果是一页正好在段落中间填满时，下一页会从"下一段"开始，
 *   中间那段剩余文字**永远不渲染**（用户反馈："某页最后一行段落超过一行后，
 *   第一行之后的内容会被截断"）。
 *
 *   改成字符偏移后：[startChar, endChar) 逐页首尾相接，
 *   下一页的起点就是上一页的终点，**结构上不可能漏字或重叠**。
 *
 * 设计（相比旧版简单很多）：
 *   · pageToStart / startToPage 都是精确映射，翻页 ±1 只是取相邻的起止字符；
 *   · 不需要"平均每页行数"来估算页码，也不需要锚点纠正与邻页收敛 ——
 *     那套救援逻辑存在的原因正是行号边界不可靠，现在根因没了；
 *   · 查找未访问页（目录/百分比跳转）时，从已知起点或 0 开始顺序推进，最多滚到目标页。
 */
final class PageMap {

    private final Book book;
    private final TextPaint paint;
    private final int width;
    private final int height;
    private final float spacingAdd;
    private final int charCount;

    /** 页码 → 起始字符偏移 */
    private final Map<Integer, Integer> pageToStart = new HashMap<>();
    /** 起始字符偏移 → 页码 */
    private final Map<Integer, Integer> startToPage = new HashMap<>();
    /** 页码 → 结束字符偏移（= 下一页起点），register 时顺便记下，翻页 O(1) */
    private final Map<Integer, Integer> pageToEnd = new HashMap<>();

    PageMap(Book book, TextPaint paint, int width, int height, float spacingAdd) {
        this.book = book;
        this.paint = paint;
        this.width = width;
        this.height = height;
        this.spacingAdd = spacingAdd;
        this.charCount = Math.max(1, book.charCount());
    }

    /** 结果：起始字符偏移 + 页码，两者一定自洽 */
    static final class Loc {
        final int startChar;
        final int page;

        Loc(int startChar, int page) {
            this.startChar = startChar;
            this.page = page;
        }
    }

    // ---------------- 查询 ----------------

    /** 已知页码 → 该页起始字符（未访问过的页会顺序推进解析出来） */
    int startOfPage(int pageIndex) {
        if (pageIndex <= 0) {
            pageToStart.put(0, 0);
            return 0;
        }
        Integer cached = pageToStart.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        return resolvePage(pageIndex).startChar;
    }

    /** 已知页码 → 该页结束字符（= 下一页起点） */
    int endOfPage(int pageIndex) {
        Integer cached = pageToEnd.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        int start = startOfPage(pageIndex);
        return Paginator.measure(book, paint, start, width, height, spacingAdd).endChar;
    }

    /**
     * 已知字符偏移 → 它落在第几页。
     * 做法：从缓存里找"起点 ≤ target 且最大"的已知页作为推进起点，再顺序推进到 target。
     * 正常阅读路径（翻页/恢复进度）都有缓存命中，所以循环几乎不转。
     */
    Loc locateChar(int charOffset) {
        int target = clampChar(charOffset);

        int bestPage = 0;
        int bestStart = 0;
        int bestStartSeen = -1;
        for (Map.Entry<Integer, Integer> e : pageToStart.entrySet()) {
            int start = e.getValue();
            if (start <= target && start > bestStartSeen) {
                bestStartSeen = start;
                bestStart = start;
                bestPage = e.getKey();
            }
        }
        if (bestStartSeen < 0) {
            register(0, 0);
        }

        return advanceTo(bestPage, bestStart, target);
    }

    /** 从 (page, cursor) 顺序推进，直到覆盖 target；沿途把每页都登记下来 */
    private Loc advanceTo(int page, int cursor, int target) {
        int guard = 0;
        while (guard++ < 100000) {
            Paginator.Measure m = Paginator.measure(book, paint, cursor, width, height, spacingAdd);
            register(page, cursor);
            if (m.endChar <= cursor || m.endChar > target) {
                return new Loc(cursor, page);
            }
            cursor = m.endChar;
            page++;
        }
        return new Loc(cursor, page);
    }

    /** 翻到下一页：页码 +1，起点 = 当前页终点（精确，无需估算） */
    Loc nextPage(int currentPage, int currentEndChar) {
        int next = currentPage + 1;
        int start = clampChar(currentEndChar);
        register(next, start);
        return new Loc(start, next);
    }

    /**
     * 翻到上一页：页码 −1。
     * 往回推需要知道上一页的起点；缓存里没有时从 0 顺序推进到 prev（页数多时是 O(页)，
     * 但只在"没访问过的页往回翻"时发生，正常阅读路径都有缓存）。
     */
    Loc prevPage(int currentPage, int currentStartChar) {
        int prev = Math.max(0, currentPage - 1);
        Integer cached = pageToStart.get(prev);
        if (cached != null && cached < currentStartChar) {
            return new Loc(cached, prev);
        }
        if (prev == 0) {
            register(0, 0);
            return new Loc(0, 0);
        }
        Loc loc = resolvePage(prev);
        if (loc.startChar >= currentStartChar) {
            // 兜底：解析结果不合法（不该发生），退到全文开头
            register(0, 0);
            return new Loc(0, 0);
        }
        return loc;
    }

    // ---------------- 登记与解析 ----------------

    /** 登记一页：记下起点与终点（终点即下一页起点） */
    void register(int pageIndex, int startChar) {
        if (pageIndex < 0) {
            return;
        }
        int start = clampChar(startChar);
        pageToStart.put(pageIndex, start);
        startToPage.put(start, pageIndex);
        Paginator.Measure m = Paginator.measure(book, paint, start, width, height, spacingAdd);
        int end = Math.max(m.endChar, start + 1);
        pageToEnd.put(pageIndex, Math.min(end, charCount));
    }

    /** 从 0 开始顺序推进到目标页 */
    private Loc resolvePage(int target) {
        if (target <= 0) {
            register(0, 0);
            return new Loc(0, 0);
        }
        int page = 0;
        int cursor = 0;
        int guard = 0;
        while (page < target && guard++ < 100000) {
            Paginator.Measure m = Paginator.measure(book, paint, cursor, width, height, spacingAdd);
            register(page, cursor);
            if (m.endChar <= cursor) {
                // 已到末尾，页码夹到最后一页
                return new Loc(cursor, page);
            }
            cursor = m.endChar;
            page++;
        }
        register(page, cursor);
        return new Loc(cursor, page);
    }

    private int clampChar(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, charCount - 1);
    }

    void clear() {
        pageToStart.clear();
        startToPage.clear();
        pageToEnd.clear();
    }
}
