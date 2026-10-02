package com.watchreader;

import android.graphics.Paint;
import android.text.TextPaint;

/**
 * 分页引擎（行索引版）。
 *
 * 为什么不用 StaticLayout：
 *   StaticLayout.Builder.obtain(source, start, end, ...) 会对 **start..end 整段** 建段落结构。
 *   我们原本传的是"从本页起点到全书末尾"，一本 110 万字的小说直接让主线程卡死（实测 ANR：
 *   Input dispatching timed out, Wait queue length: 52）。
 *
 * 现在的做法：
 *   1. 用 Book 预先算好的行索引（[行首, 行尾)，不含换行）逐行排版；
 *   2. 每行只做一次 Paint.breakText 测量，超出列宽就回退到最近的断点；
 *   3. 一页只处理"页容量那么多字"，复杂度 O(页) 而不是 O(全书)。
 *
 * 代价：断行是自己实现的启发式（空格/标点优先），不享受系统标点避头尾；
 *       对中文小说足够，若首字是收尾标点会回退一格。
 */
public final class Paginator {

    private Paginator() {
    }

    /** 一页的排版结果 */
    public static final class Page {
        /** 本页第一行的行号 */
        public final int startLine;
        /** 本页最后一行的行号（不含） */
        public final int endLine;
        /** 本页对应的字符区间（用于保存进度、算百分比） */
        public final int startChar;
        public final int endChar;
        /** 每行在全书中的起始字符下标，长度 = 行数 */
        public final int[] lineStarts;
        /** 每行文本 */
        public final String[] lineTexts;

        Page(int startLine, int endLine, int startChar, int endChar, int[] lineStarts, String[] lineTexts) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.startChar = startChar;
            this.endChar = endChar;
            this.lineStarts = lineStarts;
            this.lineTexts = lineTexts;
        }

        public int lineCount() {
            return lineTexts.length;
        }
    }

    /**
     * 只算"这一页到哪里结束"（返回结束行号，不含）。
     * 与 {@link #paginate} 用同一套几何，供 PageMap 精确页码映射复用，
     * 避免两处各写一份"一页能放几行"导致页码与内容对不上。
     */
    public static int pageEndLine(Book book, TextPaint paint, int lineIndex,
                                  int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0 || width <= 0 || height <= 0) {
            return Math.max(0, lineIndex);
        }

        float lineHeight = lineHeightOf(paint, lineSpacingAdd);
        if (lineHeight <= 0) {
            lineHeight = 1f;
        }
        int maxLines = (int) Math.floor(height / lineHeight);
        if (maxLines < 1) {
            maxLines = 1;
        }

        int from = Math.max(0, Math.min(lineIndex, book.index.length - 1));
        int count = 0;

        while (count < maxLines && from + count < book.index.length) {
            int idx = from + count;
            int[] range = book.index[idx];
            String source = book.text.substring(range[0], range[1]);

            int offset = 0;
            boolean produced = false;
            while (offset < source.length() && count < maxLines) {
                int fit = fitChars(paint, source, offset, width);
                if (fit <= 0) {
                    fit = 1;
                }
                int end = offset + fit;
                if (end < source.length() && end > offset + 1
                        && NO_LINE_START.indexOf(source.charAt(end)) >= 0) {
                    end--;
                }
                count++;
                produced = true;
                offset = end;
            }

            if (!produced && count < maxLines) {
                count++;
            }
        }

        return from + count;
    }

    private static final Page EMPTY = new Page(0, 0, 0, 0, new int[0], new String[0]);

    /** 不允许出现在行首的标点（简单版避头尾） */
    private static final String NO_LINE_START = "，。、；：？！）】》」』”’…—·,.;:?!)]}>\"";

    public static Page empty() {
        return EMPTY;
    }

    /**
     * 取从 lineIndex 开始、能放进 height 的一页。
     *
     * @param book      书（含排版文本与行索引）
     * @param paint     正文画笔
     * @param lineIndex 起始行号
     * @param width     可用宽度 px
     * @param height    可用高度 px
     */
    public static Page paginate(Book book, TextPaint paint, int lineIndex,
                                int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0 || width <= 0 || height <= 0) {
            return EMPTY;
        }

        float lineHeight = lineHeightOf(paint, lineSpacingAdd);
        if (lineHeight <= 0) {
            lineHeight = 1f;
        }
        int maxLines = (int) Math.floor(height / lineHeight);
        if (maxLines < 1) {
            maxLines = 1;
        }

        int from = Math.max(0, Math.min(lineIndex, book.index.length - 1));
        int[] starts = new int[maxLines];
        String[] texts = new String[maxLines];
        int count = 0;
        int lastIndex = from;

        while (count < maxLines && from + count < book.index.length) {
            int idx = from + count;
            int[] range = book.index[idx];
            String source = book.text.substring(range[0], range[1]);

            // 多行（长段落）：行内会拆成若干视觉行
            int offset = 0;
            boolean produced = false;
            while (offset < source.length() && count < maxLines) {
                int fit = fitChars(paint, source, offset, width);
                if (fit <= 0) {
                    fit = 1;
                }
                int end = offset + fit;

                // 避头尾：如果断点落在"不能放行首"的标点上，回退一格
                if (end < source.length() && end > offset + 1 && NO_LINE_START.indexOf(source.charAt(end)) >= 0) {
                    end--;
                }

                starts[count] = range[0] + offset;
                texts[count] = source.substring(offset, end);
                count++;
                lastIndex = idx;
                produced = true;
                offset = end;
            }

            if (!produced && count < maxLines) {
                // 空行：占一行
                starts[count] = range[0];
                texts[count] = "";
                count++;
                lastIndex = idx;
            }
        }

        if (count == 0) {
            return EMPTY;
        }

        int[] finalStarts = new int[count];
        String[] finalTexts = new String[count];
        System.arraycopy(starts, 0, finalStarts, 0, count);
        System.arraycopy(texts, 0, finalTexts, 0, count);

        int startChar = finalStarts[0];
        int endChar = lastIndex + 1 < book.index.length ? book.index[lastIndex + 1][0] : book.charCount();

        return new Page(from, lastIndex + 1, startChar, endChar, finalStarts, finalTexts);
    }

    /** 当前字号下的行高（含行间距） */
    public static float lineHeightOf(Paint paint, float lineSpacingAdd) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (fm.bottom - fm.top) + lineSpacingAdd;
    }

    /**
     * 一行能放下多少字符：优先用 breakText 精确测量，
     * 标签类字符（`<p>`）按 0 宽处理，避免空白虚高。
     */
    private static int fitChars(TextPaint paint, String line, int offset, int width) {
        int remaining = line.length() - offset;
        if (remaining <= 0) {
            return 0;
        }

        int fit = paint.breakText(line, offset, line.length(), true, width, null);
        if (fit <= 0) {
            fit = 1;
        }

        // breakText 对超长行可能低估（返回 0 或很小），兜底用平均字宽估算
        if (fit == 1 && remaining > 1) {
            float avg = paint.measureText(line, offset, Math.min(offset + 8, line.length())) / Math.min(8, remaining);
            if (avg > 0) {
                int byAvg = (int) (width / avg);
                if (byAvg > fit) {
                    fit = Math.min(byAvg, remaining);
                }
            }
        }

        return Math.min(fit, remaining);
    }

    /** 估算总页数：抽样 8 页取平均行数，再除总行数 */
    public static int estimatePageCount(Book book, TextPaint paint, int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0) {
            return 1;
        }

        float lineHeight = lineHeightOf(paint, lineSpacingAdd);
        int maxLines = Math.max(1, (int) Math.floor(height / Math.max(1f, lineHeight)));

        // 一页最多覆盖多少"源行"（长段落会被拆行，所以用抽样实测）
        long linesPerPage = 0;
        int samples = 0;
        int cursor = 0;
        int step = Math.max(1, book.index.length / 8);
        for (int i = 0; i < 8 && cursor < book.index.length; i++) {
            Page page = paginate(book, paint, cursor, width, height, lineSpacingAdd);
            if (page.lineCount() == 0) {
                break;
            }
            linesPerPage += page.endLine - page.startLine;
            samples++;
            cursor = page.endLine;
            if (cursor >= book.index.length) {
                break;
            }
            cursor = Math.min(book.index.length - 1, cursor + step);
        }

        if (samples == 0 || linesPerPage == 0) {
            return Math.max(1, (int) Math.ceil((double) book.index.length / maxLines));
        }

        double avgLinesPerPage = (double) linesPerPage / samples;
        return Math.max(1, (int) Math.ceil(book.index.length / avgLinesPerPage));
    }

    /**
     * 精确定位：字符偏移 → 行号。
     * 用行索引二分，O(log n)，不再逐页累计。
     */
    public static int lineOfChar(Book book, int charOffset) {
        if (book == null || book.index.length == 0) {
            return 0;
        }
        int target = Math.max(0, Math.min(charOffset, Math.max(0, book.charCount() - 1)));
        int lo = 0;
        int hi = book.index.length - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (book.index[mid][0] <= target) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /** 字符偏移 → 页码（按抽样得到的"每页平均行数"换算，用于显示与跳转） */
    public static int pageOfChar(Book book, TextPaint paint, int charOffset,
                                 int width, int height, float lineSpacingAdd) {
        int line = lineOfChar(book, charOffset);

        // 精确：从 0 开始按页走太慢；用"每页平均行数"估算后做局部校正
        float lineHeight = lineHeightOf(paint, lineSpacingAdd);
        int maxLines = Math.max(1, (int) Math.floor(height / Math.max(1f, lineHeight)));

        // 抽样：从当前位置前后各量几页，估算每页覆盖的源行数
        int probe = Math.max(0, Math.min(line, book.index.length - 1));
        Page page = paginate(book, paint, probe, width, height, lineSpacingAdd);
        int linesPerPage = Math.max(1, page.endLine - page.startLine);
        if (linesPerPage < maxLines / 2) {
            linesPerPage = Math.max(1, maxLines);
        }
        return Math.max(0, line / linesPerPage);
    }
}
