package com.watchreader;

import android.text.TextPaint;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * F3 回归：页边界落在上一逻辑行行尾时的行为。
 *
 * 旧缺陷：measure()/paginateAt() 会把"已经渲染完的非空行"当成一个空行占掉一行，
 * 并且 paginateAt() 把本页起点记成那一行的 lineStart（回退一行）。表现为
 * "页首多一行空白、保存的进度偏小一行、重开书重复上一页最后一行"。
 *
 * 本测试断言的不变量（都与 measure/paginateAt 的一致性和 F3 直接相关）：
 *   1. measure(t).endChar == paginateAt(t).endChar，行数也一致；
 *   2. 页首必须是本页第一行的真实起点：等于请求位置，或（请求位置正好是换行符时）
 *      前移一位；绝不回退；
 *   3. 页首不得落在换行符上；首行不得是空行，除非那本来就是空行；
 *   4. 逐页推进（下一页起点 = 上一页 endChar，与 ReaderView.flip 一致）时，
 *      区间之间的空隙只能是换行符；所有页的行文本拼接 == 全文去掉换行（不丢字/不重复）；
 *   5. 全局"空行行数"必须等于正文里真正的空行数（不额外占空行）；
 *   6. 页码/起点在"前进 → 回翻 → 再前进 → 恢复"后完全一致（不累积偏移）。
 *
 * 用法：tools\pagetest\run.ps1（与 PaginateCheck 一起跑）
 */
public final class BoundaryCheck {

    static final int WIDTH = 300;
    static final int HEIGHT = 400;
    static final float SPACING = 2f;
    static final float FONT = 13f;

    static int failures = 0;

    public static void main(String[] args) {
        // 1) 每段正好占一整行：页边界必然落在行尾 —— F3 的触发条件
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            exact.append("AAAAAAAAAAAAAAAAAAAA").append('\n');
        }
        checkBoundaryCase("行尾边界（每段正好一行）", exact.toString());

        // 2) 长短段落 + 空行交替（检验"真空行仍占一行"）
        StringBuilder mixed = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            mixed.append("　　第").append(i).append("段，短句。\n\n");
            mixed.append("　　长一点的段落，含标点。，、；：？！\n");
        }
        checkBoundaryCase("长短段落 + 空行交替", mixed.toString());

        // 3) 超长无换行段落（页边界落在行中间，不该受影响）
        StringBuilder longPara = new StringBuilder("超长段落：");
        for (int i = 0; i < 2000; i++) {
            longPara.append("没有任何换行的正文内容，用来把一页切在行中间。");
        }
        longPara.append("\n结束。\n");
        checkBoundaryCase("超长无换行段落", longPara.toString());

        // 4) 精确页首：从每一逻辑行行首取页，起点必须等于请求值
        checkPageStartAtEveryLineStart(exact.toString());
        checkPageStartAtEveryLineStart(mixed.toString());

        // 5) 前进 → 回翻 → 再前进 → 恢复
        checkFlipsAndRestore();

        System.out.println();
        if (failures == 0) {
            System.out.println("=== BoundaryCheck 全部通过（页首/行尾边界、前后翻、恢复）===");
        } else {
            System.out.println("=== BoundaryCheck 有 " + failures + " 项失败 ===");
            System.exit(1);
        }
    }

    // ---------------- 逐页边界 ----------------

    static void checkBoundaryCase(String name, String normalized) {
        Book book = makeBook(normalized);
        TextPaint paint = paint();
        String layout = book.text;
        int total = book.charCount();

        int cursor = 0;
        int pages = 0;
        int emptyRows = 0;
        int prevEnd = -1;
        StringBuilder joined = new StringBuilder();

        while (cursor < total && pages < 500000) {
            Paginator.Page page = Paginator.paginateAt(book, paint, cursor, WIDTH, HEIGHT, SPACING);
            Paginator.Measure m = Paginator.measure(book, paint, cursor, WIDTH, HEIGHT, SPACING);

            if (page.lineCount() == 0) {
                fail(name, "第 " + pages + " 页为空页（cursor=" + cursor + "）");
                return;
            }
            // 不变量 1：measure 与 paginateAt 必须一致
            if (m.endChar != page.endChar) {
                fail(name, "第 " + pages + " 页 endChar 不一致：measure=" + m.endChar
                        + " paginateAt=" + page.endChar + "（cursor=" + cursor + "）");
            }
            if (Math.max(1, m.rowCount) != page.lineCount()) {
                fail(name, "第 " + pages + " 页行数不一致：measure=" + m.rowCount
                        + " paginateAt=" + page.lineCount() + "（cursor=" + cursor + "）");
            }
            // 不变量 2：页首不得回退，最多因"请求位置是换行符"前移一位
            if (page.startChar < cursor) {
                fail(name, "第 " + pages + " 页起点回退：" + cursor + " -> " + page.startChar);
            }
            if (page.startChar > cursor && (cursor >= total || layout.charAt(cursor) != '\n')) {
                fail(name, "第 " + pages + " 页起点越过了非换行字符：" + cursor + " -> " + page.startChar);
            }
            // 不变量 3：页首不得落在换行符上 —— 唯一例外是"真空行"，
            // 它的字符区间是零长度，位置本身就跟随后面的换行符；首行非空，
            // 除非那本来就是空行
            boolean emptyLineStart = isEmptyLineAt(book, page.startChar);
            if (page.startChar < total && layout.charAt(page.startChar) == '\n' && !emptyLineStart) {
                fail(name, "第 " + pages + " 页页首落在换行符上（" + page.startChar + "）");
            }
            if (page.lineTexts[0].isEmpty() && !emptyLineStart) {
                fail(name, "第 " + pages + " 页首行是白占的空行（startChar=" + page.startChar + "）");
            }
            // 不变量 4：区间之间只能隔着换行符
            if (prevEnd >= 0) {
                int gap = page.startChar - prevEnd;
                if (gap < 0) {
                    fail(name, "第 " + pages + " 页与上一页重叠（gap=" + gap + "）");
                } else if (gap > 1) {
                    fail(name, "第 " + pages + " 页与上一页之间漏了 " + gap + " 个字符");
                } else if (gap == 1 && layout.charAt(prevEnd) != '\n') {
                    fail(name, "第 " + pages + " 页与上一页之间的空隙不是换行符");
                }
            }
            for (String s : page.lineTexts) {
                if (s.indexOf('\n') >= 0) {
                    fail(name, "第 " + pages + " 页的行文本里含换行符");
                }
                if (s.isEmpty()) {
                    emptyRows++;
                }
                joined.append(s);
            }
            if (page.endChar <= cursor) {
                fail(name, "第 " + pages + " 页无法推进（cursor=" + cursor + "）");
                return;
            }
            prevEnd = page.endChar;
            cursor = page.endChar;
            pages++;
        }

        // 不变量 4（全局）：所有页行文本拼接 == 全文去掉换行
        String expected = layout.replace("\n", "");
        if (!joined.toString().equals(expected)) {
            fail(name, "逐页行文本拼接 != 全文（长度 " + joined.length() + " vs " + expected.length() + "）");
        }
        // 不变量 5：空行行数 == 正文里真正的空行数（不多占、不少占）
        int realEmptyLines = countEmptyLines(layout);
        if (emptyRows != realEmptyLines) {
            fail(name, "空行行数不符：实际 " + emptyRows + "，正文真空行 " + realEmptyLines);
        }
        System.out.println("  [" + name + "] " + pages + " 页覆盖 " + total
                + " 字符，空行 " + emptyRows + "/" + realEmptyLines + "  PASS");
    }

    // ---------------- 精确页首 ----------------

    static void checkPageStartAtEveryLineStart(String normalized) {
        Book book = makeBook(normalized);
        TextPaint paint = paint();
        String name = "行首取页（" + shortName(normalized) + "）";
        int bad = 0;
        for (int i = 0; i < book.index.length; i++) {
            int lineStart = book.index[i][0];
            Paginator.Page page = Paginator.paginateAt(book, paint, lineStart, WIDTH, HEIGHT, SPACING);
            if (page.lineCount() == 0) {
                continue;   // 空书/末尾空行，忽略
            }
            if (page.startChar != lineStart) {
                bad++;
                if (bad <= 3) {
                    fail(name, "行 " + i + "（起点 " + lineStart + "）取页后 startChar=" + page.startChar);
                }
            }
            if (page.lineTexts[0].isEmpty() && !isEmptyLineAt(book, lineStart)) {
                fail(name, "行 " + i + " 首行是白占的空行");
            }
            Paginator.Measure m = Paginator.measure(book, paint, lineStart, WIDTH, HEIGHT, SPACING);
            if (m.endChar != page.endChar) {
                fail(name, "行 " + i + " 的 measure/paginateAt endChar 不一致");
            }
        }
        if (bad == 0) {
            System.out.println("  [" + name + "] " + book.index.length + " 个行首全部精确  PASS");
        }
    }

    // ---------------- 前后翻 / 恢复 ----------------

    static void checkFlipsAndRestore() {
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            n.append("AAAAAAAAAAAAAAAAAAAA").append('\n');
        }
        Book book = makeBook(n.toString());
        TextPaint paint = paint();
        String name = "前进/回翻/再前进/恢复";
        PageMap map = new PageMap(book, paint, WIDTH, HEIGHT, SPACING);

        // 前进 7 页（完全照 ReaderView.loadPage + flip(true) 的调用方式）
        List<int[]> forward = new ArrayList<>();
        Paginator.Page cur = Paginator.paginateAt(book, paint, 0, WIDTH, HEIGHT, SPACING);
        int page = 0;
        map.register(page, cur.startChar);
        forward.add(new int[] { page, cur.startChar, cur.endChar });
        for (int i = 0; i < 6 && cur.endChar < book.charCount(); i++) {
            PageMap.Loc next = map.nextPage(page, cur.endChar);
            if (next.startChar <= cur.startChar) {
                fail(name, "前进未推进");
                break;
            }
            cur = Paginator.paginateAt(book, paint, next.startChar, WIDTH, HEIGHT, SPACING);
            page = next.page;
            map.register(page, cur.startChar);
            forward.add(new int[] { page, cur.startChar, cur.endChar });
        }

        // 页起点必须严格递增（prevPage 的缓存命中依赖这一点）
        for (int i = 1; i < forward.size(); i++) {
            if (forward.get(i)[1] <= forward.get(i - 1)[1]) {
                fail(name, "第 " + i + " 页起点未递增：" + forward.get(i - 1)[1] + " -> " + forward.get(i)[1]);
            }
        }

        // 回翻：每一页都要回到第一次记录的 (page, startChar)
        for (int i = forward.size() - 1; i > 0; i--) {
            int[] rec = forward.get(i);
            int[] want = forward.get(i - 1);
            PageMap.Loc prev = map.prevPage(rec[0], rec[1]);
            if (prev.page != want[0] || prev.startChar != want[1]) {
                fail(name, "回翻不一致：期望 page=" + want[0] + " start=" + want[1]
                        + "，实际 page=" + prev.page + " start=" + prev.startChar);
                break;
            }
        }

        // 再前进：页码/起点/终点必须与第一次完全一致（不累积偏移）
        Paginator.Page again = Paginator.paginateAt(book, paint, forward.get(0)[1], WIDTH, HEIGHT, SPACING);
        int againPage = 0;
        map.register(againPage, again.startChar);
        for (int i = 1; i < forward.size() && again.endChar < book.charCount(); i++) {
            PageMap.Loc next = map.nextPage(againPage, again.endChar);
            again = Paginator.paginateAt(book, paint, next.startChar, WIDTH, HEIGHT, SPACING);
            againPage = next.page;
            map.register(againPage, again.startChar);
            int[] want = forward.get(i);
            if (againPage != want[0] || again.startChar != want[1] || again.endChar != want[2]) {
                fail(name, "第二次前进与第一次不一致：page " + want[0] + "/" + againPage
                        + " start " + want[1] + "/" + again.startChar
                        + " end " + want[2] + "/" + again.endChar);
                break;
            }
        }

        // 恢复：用保存的起点走 locateChar，必须回到同一页、同一页首、同一页终点
        int[] last = forward.get(forward.size() - 1);
        Paginator.Page restored = Paginator.paginateAt(book, paint, last[1], WIDTH, HEIGHT, SPACING);
        PageMap.Loc loc = map.locateChar(restored.startChar);
        if (restored.startChar != last[1]) {
            fail(name, "恢复起点 != 保存起点：" + last[1] + " -> " + restored.startChar);
        }
        if (loc.page != last[0]) {
            fail(name, "恢复页码不一致：保存 " + last[0] + "，locateChar 得到 " + loc.page);
        }
        if (restored.endChar != last[2]) {
            fail(name, "恢复页终点不一致：" + last[2] + " -> " + restored.endChar);
        }
        if (failures == 0) {
            System.out.println("  [" + name + "] " + forward.size() + " 页往返一致（保存起点 "
                    + last[1] + " → 恢复 page=" + loc.page + "）  PASS");
        }
    }

    // ---------------- 工具 ----------------

    static TextPaint paint() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(FONT);
        return paint;
    }

    static Book makeBook(String normalized) {
        String layout = Book.toLayoutText(normalized);
        int[][] index = Book.buildLayoutIndex(layout);
        return new Book(new File("dummy.txt"), layout.length(), 0L, "测试", "作者", "utf-8", layout, index, 0L);
    }

    static boolean isEmptyLineAt(Book book, int pos) {
        for (int[] range : book.index) {
            if (range[0] == pos) {
                return range[1] == range[0];
            }
            if (range[0] > pos) {
                break;
            }
        }
        return false;
    }

    static int countEmptyLines(String layout) {
        int empty = 0;
        int start = 0;
        for (int i = 0; i < layout.length(); i++) {
            if (layout.charAt(i) == '\n') {
                if (i == start) {
                    empty++;
                }
                start = i + 1;
            }
        }
        if (start == layout.length()) {
            empty++;   // 末尾换行产生的最后一个空行（buildLayoutIndex 也会建这一行）
        }
        return empty;
    }

    static String shortName(String normalized) {
        return normalized.length() + " 字符";
    }

    static void fail(String name, String detail) {
        failures++;
        System.out.println("  [" + name + "] FAIL: " + detail);
    }
}
