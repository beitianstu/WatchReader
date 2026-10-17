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

    /**
     * 连续页码前缀：prefix[0..prefixLen) 就是第 0..prefixLen-1 页的起始字符。
     *
     * 来源有两个：{@link PageIndexCache} 上次落盘的页码缓存（构造时传入 seed），
     * 以及本次阅读过程中顺序量出来的页。只有**顺序、连续**登记的页才能进这里 ——
     * 这样"最大 p 使 prefix[p] ≤ target"就一定等于"target 落在第 p 页"，可以直接二分命中，
     * 不必再从第 0 页顺序重量。
     */
    private int[] prefix;
    private int prefixLen;

    /** 兼容旧签名（不带页码缓存） */
    PageMap(Book book, TextPaint paint, int width, int height, float spacingAdd) {
        this(book, paint, width, height, spacingAdd, null);
    }

    PageMap(Book book, TextPaint paint, int width, int height, float spacingAdd, int[] seed) {
        this.book = book;
        this.paint = paint;
        this.width = width;
        this.height = height;
        this.spacingAdd = spacingAdd;
        this.charCount = Math.max(1, book.charCount());
        this.prefix = new int[64];
        this.prefixLen = 0;
        if (seed != null && seed.length > 0 && seed[0] == 0
                && seed[seed.length - 1] < charCount
                && PageIndexCache.isSane(seed, charCount)) {
            ensurePrefixCapacity(seed.length);
            System.arraycopy(seed, 0, prefix, 0, seed.length);
            prefixLen = seed.length;
        }
    }

    /** 已连续量出的页起始（下标=页码），供落盘复用 */
    int[] contiguousPageStarts() {
        int[] copy = new int[prefixLen];
        System.arraycopy(prefix, 0, copy, 0, prefixLen);
        return copy;
    }

    private void ensurePrefixCapacity(int needed) {
        if (needed <= prefix.length) {
            return;
        }
        int cap = prefix.length;
        while (cap < needed) {
            cap = cap * 2;
        }
        int[] bigger = new int[cap];
        System.arraycopy(prefix, 0, bigger, 0, prefixLen);
        prefix = bigger;
    }

    /** 第 page 页的已知起点；未知返回 null */
    private Integer knownStart(int page) {
        if (page >= 0 && page < prefixLen) {
            return prefix[page];
        }
        return pageToStart.get(page);
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
            register(0, 0);
            return 0;
        }
        Integer cached = knownStart(pageIndex);
        if (cached != null) {
            return cached;
        }
        return resolvePage(pageIndex).startChar;
    }

    /**
     * 已知页码 → 该页结束字符（= 下一页起点）。
     * 按需测量，不再常驻缓存：register 里那次额外测量没有任何读取者（F6），
     * 却让恢复进度/跳转的定位成本翻倍。
     */
    int endOfPage(int pageIndex) {
        int start = startOfPage(pageIndex);
        return Paginator.measure(book, paint, start, width, height, spacingAdd).endChar;
    }

    /**
     * 已知字符偏移 → 它落在第几页。
     *
     * 顺序：
     *  1. 先在"连续页码前缀"里二分（页缓存命中时这一步就出结果，O(log 页数)、零测量）；
     *  2. 前缀没覆盖时，退回到已登记页里找起点 ≤ target 且最大的一页；
     *  3. 都没有才从第 0 页开始顺序推进。
     *
     * 关键性质：前缀是**逐页连续**登记的，所以"最大 p 使 prefix[p] ≤ target"必然就是
     * target 所在的那一页 —— 只要它的下一页起点也已知（或正好是前缀末尾，量一次即可确认），
     * 就不需要把前面所有页重新量一遍。
     */
    Loc locateChar(int charOffset) {
        int target = clampChar(charOffset);

        int bestPage = -1;
        int bestStart = 0;
        int bestStartSeen = -1;

        // 1) 连续前缀二分
        if (prefixLen > 0) {
            int lo = 0;
            int hi = prefixLen - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (prefix[mid] <= target) {
                    bestPage = mid;
                    bestStart = prefix[mid];
                    bestStartSeen = bestStart;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
        }

        // 2) 前缀未命中（或 target 比前缀首项还靠前）：看零散登记的页
        if (bestStartSeen < 0) {
            for (Map.Entry<Integer, Integer> e : pageToStart.entrySet()) {
                int start = e.getValue();
                if (start <= target && start > bestStartSeen) {
                    bestStartSeen = start;
                    bestStart = start;
                    bestPage = e.getKey();
                }
            }
        }

        if (bestStartSeen < 0) {
            register(0, 0);
            bestPage = 0;
            bestStart = 0;
        }

        // 3) 快路径：下一页起点已知且已越过 target → target 就在 bestPage 这一页
        if (bestPage >= 0 && bestPage + 1 < prefixLen && prefix[bestPage + 1] > target) {
            return new Loc(bestStart, bestPage);
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
        Integer cached = knownStart(prev);
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

    /**
     * 登记一页的起点（页码 ↔ 起始字符的精确映射）。
     *
     * 这里**刻意不再自己调用 Paginator.measure**（F6）：调用方
     * {@link #advanceTo}/{@link #resolvePage} 刚刚才量过这一页，再量一次纯属浪费；
     * 需要"页终点"时用 {@link #endOfPage} 按需计算。
     */
    void register(int pageIndex, int startChar) {
        if (pageIndex < 0) {
            return;
        }
        int start = clampChar(startChar);
        pageToStart.put(pageIndex, start);
        startToPage.put(start, pageIndex);
        // 只有"紧接着前缀末尾、且起点严格递增"的页才加入连续前缀（快路径与落盘都依赖它连续）
        if (pageIndex == prefixLen && (prefixLen == 0 ? start == 0 : start > prefix[prefixLen - 1])) {
            ensurePrefixCapacity(prefixLen + 1);
            prefix[prefixLen] = start;
            prefixLen++;
            return;
        }
        // 同一页被更精确地重新登记：从"上一页终点（可能是换行位）"改成"真正的页首"。
        // 前缀必须跟着前移，否则 prevPage/locateChar 会拿到差一个字符的旧值
        //（BoundaryCheck 的"回翻一致性"就是这么抓出来的）。只允许前移、且不得越过下一页起点。
        if (pageIndex < prefixLen && start > prefix[pageIndex]
                && (pageIndex + 1 >= prefixLen || start < prefix[pageIndex + 1])) {
            prefix[pageIndex] = start;
        }
    }

    /** 从 0 开始顺序推进到目标页 */
    private Loc resolvePage(int target) {
        if (target <= 0) {
            register(0, 0);
            return new Loc(0, 0);
        }
        // 前缀已经量到第 target 页：直接命中，不用再走一遍
        if (target < prefixLen) {
            register(target, prefix[target]);
            return new Loc(prefix[target], target);
        }
        // 否则从"前缀里最靠后、且不超过 target"的那一页接着往前量
        int page = 0;
        int cursor = 0;
        if (prefixLen > 0) {
            page = Math.min(target, prefixLen - 1);
            cursor = prefix[page];
        }
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
        prefixLen = 0;
    }
}
