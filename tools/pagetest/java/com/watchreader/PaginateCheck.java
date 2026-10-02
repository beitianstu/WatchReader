package com.watchreader;

import android.text.TextPaint;

import java.io.File;

/**
 * 分页无缝性测试（桌面上跑，不需要手表/模拟器）。
 *
 * 核心不变量：从字符 0 开始逐页推进，页面区间 [startChar, endChar) 必须
 * **首尾相接、无缝覆盖**全文。
 *
 * 这正是用户反馈的 bug："如果某一页的最后一行的段落中文字长度超过一行，
 * 第一行之后的内容会被截断" —— 旧实现把"起始行 + 视觉行数"当作下一页的起点，
 * 而长段落会被拆成多个视觉行，于是被切开的那一段剩余文字永远不会渲染。
 *
 * 用法：见 tools/pagetest/run.ps1
 */
public final class PaginateCheck {

    private static final int WIDTH = 300;
    private static final int HEIGHT = 400;
    private static final float SPACING = 2f;
    private static final float FONT_SIZE = 13f;

    public static void main(String[] args) {
        int failures = 0;

        // 用例 1：一个超长段落（无换行）—— 必然被切成很多视觉行，最易触发旧 bug
        StringBuilder huge = new StringBuilder("第一章 超长段落\n");
        for (int i = 0; i < 4000; i++) {
            huge.append("这是一段没有任何换行的超长正文，用来逼出长段落被切成多个视觉行的情况。");
        }
        huge.append("\n结尾一行。\n");
        failures += check("超长单段落（无换行）", huge.toString());

        // 用例 2：正好在段落中间翻页（长度刻意设计成非整页）
        StringBuilder mid = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            mid.append("第").append(i).append("段：")
               .append("内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容").append("\n");
        }
        failures += check("多段等长文本", mid.toString());

        // 用例 3：短段落 + 空行交替（考验空行占位）
        StringBuilder mixed = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            mixed.append("　　第").append(i).append("段，短句。\n\n");
            mixed.append("　　长一点的段落，含标点。，、；：？！\n");
        }
        failures += check("短段落与空行交替", mixed.toString());

        // 用例 4：标点密集（考验避头尾回退不会漏字）
        StringBuilder punct = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            punct.append("啊，。、；：？！哦）】》」』”’…—·,.;:?!)]}>\"").append(i).append("尾巴\n");
        }
        failures += check("标点密集（避头尾）", punct.toString());

        // 用例 5：超窄列（每行只放得下 1~2 字，考验 fit<=0 的兜底不死循环）
        failures += checkNarrow("超窄列宽", "测试内容内容内容内容内容内容内容内容内容内容\n第二段也来一些字\n", 20);

        System.out.println();
        if (failures == 0) {
            System.out.println("=== 全部用例通过：分页无缝覆盖全文，无跳字/重叠 ===");
        } else {
            System.out.println("=== 有 " + failures + " 个用例失败 ===");
            System.exit(1);
        }

        // 对照实验：证明旧算法（"起始行 + 视觉行数"）确实会跳字
        System.out.println();
        System.out.println("--- 对照实验：旧算法 vs 新算法 ---");
        proveLegacySkips("超长单段落（无换行）", huge.toString());
    }

    /**
     * 复现旧算法：pageEndLine 返回 `起始行 + 视觉行数`，nextPage 直接用 endLine 当起始行。
     * 长段落会被拆成多个视觉行，于是"剩余部分"所在的行被跳过。
     */
    private static void proveLegacySkips(String name, String normalized) {
        Book book = makeBook(normalized);
        TextPaint paint = new TextPaint();
        paint.setTextSize(FONT_SIZE);

        int total = book.charCount();
        int line = 0;
        int visited = 0;          // 实际检查到的字符数（按行区间累加）
        int pages = 0;
        int firstSkipPage = -1;
        int skippedChars = 0;

        while (line < book.index.length && pages < 500000) {
            int endLine = legacyPageEndLine(book, paint, line, WIDTH, HEIGHT, SPACING);
            if (endLine <= line) {
                break;
            }
            // 旧算法"本页覆盖"的字符 = 行 line..endLine 的区间。
            // 若本页在段落中间结束，被切开那段的剩余字符既不在本页、也不在下一页（下一页从 endLine 行开始）。
            int nextStart = endLine < book.index.length ? book.index[endLine][0] : total;
            int lastRowEnd = legacyLastRowEndChar(book, paint, line, WIDTH, HEIGHT, SPACING);
            if (nextStart > lastRowEnd) {
                if (firstSkipPage < 0) {
                    firstSkipPage = pages;
                }
                skippedChars += nextStart - lastRowEnd;
            }
            visited++;
            pages++;
            line = endLine;
        }

        System.out.printf("  [%s] 旧算法走完 %d 页，发现 %d 处跳字，累计跳过 %d 字符（首次在第 %d 页）%n",
                name, pages, firstSkipPage < 0 ? 0 : 1, skippedChars, firstSkipPage);
        if (skippedChars > 0) {
            System.out.println("  → 证实旧实现会把长段落剩余部分整段跳过（正是用户看到的截断现象）");
        } else {
            System.out.println("  → 本用例未触发跳字（该用例段落较短）");
        }
    }

    /** 旧版 pageEndLine 的原始算法 */
    private static int legacyPageEndLine(Book book, TextPaint paint, int lineIndex,
                                         int width, int height, float spacing) {
        float lineHeight = Paginator.lineHeightOf(paint, spacing);
        int maxLines = Math.max(1, (int) Math.floor(height / Math.max(1f, lineHeight)));
        int from = Math.max(0, Math.min(lineIndex, book.index.length - 1));
        int count = 0;
        while (count < maxLines && from + count < book.index.length) {
            int idx = from + count;
            int[] range = book.index[idx];
            String source = book.text.substring(range[0], range[1]);
            int offset = 0;
            boolean produced = false;
            while (offset < source.length() && count < maxLines) {
                int fit = legacyFitChars(paint, source, offset, width);
                if (fit <= 0) { fit = 1; }
                int end = offset + fit;
                if (end < source.length() && end > offset + 1
                        && "，。、；：？！）】》」』”’…—·,.;:?!)]}>\"" .indexOf(source.charAt(end)) >= 0) {
                    end--;
                }
                count++;
                produced = true;
                offset = end;
            }
            if (!produced && count < maxLines) { count++; }
        }
        return from + count;
    }

    /** 旧算法实际渲染到的最后一个字符（用于与"下一页起点"比较，暴露空隙） */
    private static int legacyLastRowEndChar(Book book, TextPaint paint, int lineIndex,
                                            int width, int height, float spacing) {
        float lineHeight = Paginator.lineHeightOf(paint, spacing);
        int maxLines = Math.max(1, (int) Math.floor(height / Math.max(1f, lineHeight)));
        int from = Math.max(0, Math.min(lineIndex, book.index.length - 1));
        int count = 0;
        int lastEnd = book.index[from][0];
        while (count < maxLines && from + count < book.index.length) {
            int idx = from + count;
            int[] range = book.index[idx];
            String source = book.text.substring(range[0], range[1]);
            int offset = 0;
            boolean produced = false;
            while (offset < source.length() && count < maxLines) {
                int fit = legacyFitChars(paint, source, offset, width);
                if (fit <= 0) { fit = 1; }
                int end = offset + fit;
                if (end < source.length() && end > offset + 1
                        && "，。、；：？！）】》」』”’…—·,.;:?!)]}>\"" .indexOf(source.charAt(end)) >= 0) {
                    end--;
                }
                lastEnd = range[0] + end;
                count++;
                produced = true;
                offset = end;
            }
            if (!produced && count < maxLines) { count++; }
        }
        return lastEnd;
    }

    private static int legacyFitChars(TextPaint paint, String line, int offset, int width) {
        int remaining = line.length() - offset;
        if (remaining <= 0) { return 0; }
        int fit = paint.breakText(line, offset, line.length(), true, width, null);
        if (fit <= 0) { fit = 1; }
        if (fit == 1 && remaining > 1) {
            float avg = paint.measureText(line, offset, Math.min(offset + 8, line.length()))
                    / Math.min(8, remaining);
            if (avg > 0) {
                int byAvg = (int) (width / avg);
                if (byAvg > fit) { fit = Math.min(byAvg, remaining); }
            }
        }
        return Math.min(fit, remaining);
    }

    private static int check(String name, String normalized) {
        return checkNarrow(name, normalized, WIDTH);
    }

    private static int checkNarrow(String name, String normalized, int width) {
        Book book = makeBook(normalized);
        TextPaint paint = new TextPaint();
        paint.setTextSize(FONT_SIZE);

        int total = book.charCount();
        int cursor = 0;
        int pages = 0;
        float lineHeight = Paginator.lineHeightOf(paint, SPACING);
        int maxRows = Math.max(1, (int) Math.floor(HEIGHT / lineHeight));

        while (cursor < total && pages < 500000) {
            Paginator.Measure m = Paginator.measure(book, paint, cursor, width, HEIGHT, SPACING);

            if (m.endChar <= cursor) {
                System.out.println("  [" + name + "] FAIL 第 " + pages + " 页无法推进（cursor=" + cursor + "）");
                return 1;
            }
            if (m.startChar != cursor) {
                System.out.println("  [" + name + "] FAIL 第 " + pages + " 页起点 " + m.startChar
                        + " != 上一页终点 " + cursor + "（出现跳字或重叠）");
                return 1;
            }
            if (m.rowCount < 1 || m.rowCount > maxRows) {
                System.out.println("  [" + name + "] FAIL 第 " + pages + " 页行数 " + m.rowCount
                        + " 超出页容量 " + maxRows);
                return 1;
            }
            cursor = m.endChar;
            pages++;
        }

        boolean covered = cursor >= total;

        // 再用 paginateAt 走一遍：页面文本拼接不能超出区间，且必须能推进
        int cursor2 = 0;
        int pages2 = 0;
        while (cursor2 < total && pages2 < 500000) {
            Paginator.Page page = Paginator.paginateAt(book, paint, cursor2, width, HEIGHT, SPACING);
            if (page.lineCount() == 0) {
                System.out.println("  [" + name + "] FAIL paginateAt 在 cursor=" + cursor2 + " 返回空页");
                return 1;
            }
            int joined = 0;
            for (String s : page.lineTexts) {
                joined += s.length();
            }
            if (joined > page.endChar - page.startChar) {
                System.out.println("  [" + name + "] FAIL 第 " + pages2 + " 页文本长度 " + joined
                        + " 超过区间 " + (page.endChar - page.startChar));
                return 1;
            }
            if (page.endChar <= cursor2) {
                System.out.println("  [" + name + "] FAIL paginateAt 第 " + pages2 + " 页无法推进");
                return 1;
            }
            cursor2 = page.endChar;
            pages2++;
        }
        boolean covered2 = cursor2 >= total;

        boolean ok = covered && covered2;
        System.out.printf("  [%s] %d 页覆盖到 %d/%d 字符（paginateAt %d 页到 %d）  %s%n",
                name, pages, cursor, total, pages2, cursor2, ok ? "PASS" : "FAIL");
        return ok ? 0 : 1;
    }

    private static Book makeBook(String normalized) {
        String layout = Book.toLayoutText(normalized);
        int[][] index = Book.buildLayoutIndex(layout);
        File dummy = new File("dummy.txt");
        return new Book(dummy, layout.length(), 0L, "测试", "作者", "utf-8", layout, index, 0L);
    }
}
