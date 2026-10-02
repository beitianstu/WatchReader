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
     *
     * 注意语义已经改为**字符偏移驱动**：内部先算出本页结束的字符位置，
     * 再映射回行号。早期版本直接返回 `起始行 + 视觉行数`，而视觉行数（长段落会被
     * 拆成多行）与逻辑行数并不相等，于是长段落被切开时会把"下一行"整段跳过
     * —— 用户反馈的"某页最后一行段落超过一行后，后面的内容被截断"就是这个。
     */
    public static int pageEndLine(Book book, TextPaint paint, int lineIndex,
                                  int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0 || width <= 0 || height <= 0) {
            return Math.max(0, lineIndex);
        }
        int line = Math.max(0, Math.min(lineIndex, book.index.length - 1));
        int startChar = book.index[line][0];
        Measure m = measure(book, paint, startChar, width, height, lineSpacingAdd);
        if (m.endChar <= startChar) {
            return line + 1;
        }
        // 结束字符所处（或其后一条）逻辑行
        int endLine = lineWithChar(book, m.endChar);
        return Math.max(line + 1, endLine);
    }

    /** 分页测量的结果：本页覆盖 [startChar, endChar) */
    public static final class Measure {
        public final int startChar;
        public final int endChar;
        public final int rowCount;

        Measure(int startChar, int endChar, int rowCount) {
            this.startChar = startChar;
            this.endChar = endChar;
            this.rowCount = rowCount;
        }
    }

    /** 包含 character 的逻辑行号（character 越界时返回最后一行） */
    public static int lineWithChar(Book book, int character) {
        if (book == null || book.index.length == 0) {
            return 0;
        }
        int lo = 0;
        int hi = book.index.length - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (book.index[mid][0] <= character) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /**
     * 从任意字符位置开始，量出"能放进 width × height 的一段"。
     * 这是分页的唯一真相来源：返回的 endChar 就是下一页的 startChar，
     * 因此页面之间**结构上不可能漏字或重叠**。
     */
    public static Measure measure(Book book, TextPaint paint, int startChar,
                                  int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0 || width <= 0 || height <= 0) {
            return new Measure(startChar, startChar, 0);
        }
        int charCount = book.charCount();
        int from = Math.max(0, Math.min(startChar, Math.max(0, charCount - 1)));

        float lineHeight = lineHeightOf(paint, lineSpacingAdd);
        if (lineHeight <= 0) {
            lineHeight = 1f;
        }
        int maxRows = Math.max(1, (int) Math.floor(height / lineHeight));

        int line = lineWithChar(book, from);
        int cursor = from;
        int rows = 0;
        int guard = 0;

        while (rows < maxRows && line < book.index.length && guard++ < maxRows * 4 + 64) {
            int[] range = book.index[line];
            int lineStart = range[0];
            int lineEnd = range[1];

            // 起点可能落在本行中间（上一页把这一行切开了）
            int offset = Math.max(cursor, lineStart);
            if (offset >= lineEnd) {
                // 空行：占一行
                cursor = lineEnd;
                rows++;
                line++;
                continue;
            }

            String source = book.text;
            int produced = 0;
            while (offset < lineEnd && rows < maxRows) {
                int fit = fitChars(paint, source, offset, lineEnd, width);
                if (fit <= 0) {
                    fit = 1;
                }
                int end = Math.min(offset + fit, lineEnd);
                // 避头尾：断点落在"不能放行首"的标点上时回退一格
                if (end < lineEnd && end > offset + 1 && NO_LINE_START.indexOf(source.charAt(end)) >= 0) {
                    end--;
                }
                cursor = end;
                rows++;
                produced++;
                offset = end;
            }

            if (produced == 0 && rows < maxRows) {
                // 兜底：本行一个字符都放不下也不能死循环
                cursor = lineEnd;
                rows++;
            }
            line++;
        }

        return new Measure(from, cursor, rows);
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
    /**
     * 取"从某一逻辑行开始"的一页（行号入口，内部仍按字符位置推进）。
     * 需要精确续页时用 {@link #paginateAt}，它接受任意字符位置。
     */
    public static Page paginate(Book book, TextPaint paint, int lineIndex,
                                int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0 || width <= 0 || height <= 0) {
            return EMPTY;
        }
        int line = Math.max(0, Math.min(lineIndex, book.index.length - 1));
        return paginateAt(book, paint, book.index[line][0], width, height, lineSpacingAdd);
    }

    /**
     * 取"从字符位置 startChar 开始"的一页。
     *
     * 这是分页的主入口：页面以**字符区间**为界，[startChar, endChar)。
     * 下一页直接用上一页的 endChar 当 startChar，因此长段落被切开时
     * 剩下的部分一定会在下一页渲染，不会丢（这是"段落超一行后内容被截断"的根治点）。
     */
    public static Page paginateAt(Book book, TextPaint paint, int startChar,
                                  int width, int height, float lineSpacingAdd) {
        if (book == null || book.index.length == 0 || width <= 0 || height <= 0) {
            return EMPTY;
        }

        Measure m = measure(book, paint, startChar, width, height, lineSpacingAdd);
        if (m.endChar <= m.startChar) {
            return EMPTY;
        }

        int count = Math.max(1, m.rowCount);
        int[] starts = new int[count];
        String[] texts = new String[count];

        // 按视觉行逐行切出文本（与 measure 用同一套 fitChars，保证边界一致）
        int line = lineWithChar(book, m.startChar);
        int cursor = m.startChar;
        int row = 0;
        int guard = 0;
        int lastEnd = m.startChar;

        while (row < count && cursor < m.endChar && line < book.index.length
                && guard++ < count * 4 + 64) {
            int[] range = book.index[line];
            int lineStart = range[0];
            int lineEnd = Math.min(range[1], m.endChar);

            int offset = Math.max(cursor, lineStart);
            if (offset >= lineEnd) {
                // 空行
                starts[row] = lineStart;
                texts[row] = "";
                row++;
                cursor = Math.max(cursor, lineStart);
                line++;
                continue;
            }
            while (offset < lineEnd && row < count) {
                int fit = fitChars(paint, book.text, offset, lineEnd, width);
                if (fit <= 0) {
                    fit = 1;
                }
                int end = Math.min(offset + fit, lineEnd);
                if (end < lineEnd && end > offset + 1 && NO_LINE_START.indexOf(book.text.charAt(end)) >= 0) {
                    end--;
                }
                starts[row] = offset;
                texts[row] = book.text.substring(offset, end);
                row++;
                cursor = end;
                lastEnd = Math.max(lastEnd, end);
                offset = end;
            }
            line++;
        }

        if (row == 0) {
            return EMPTY;
        }

        int[] finalStarts = new int[row];
        String[] finalTexts = new String[row];
        System.arraycopy(starts, 0, finalStarts, 0, row);
        System.arraycopy(texts, 0, finalTexts, 0, row);

        int pageStart = finalStarts[0];
        // 结束位置以 measure 为准（它决定了下一页从哪里开始），文本可能因避头尾回退而略短，
        // 回退掉的那些字会在下一页开头出现，不会丢。
        int pageEnd = Math.max(m.endChar, lastEnd);

        return new Page(pageStart, pageEnd, pageStart, pageEnd, finalStarts, finalTexts);
    }

    /** 当前字号下的行高（含行间距） */
    public static float lineHeightOf(Paint paint, float lineSpacingAdd) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (fm.bottom - fm.top) + lineSpacingAdd;
    }

    /**
     * 从 offset 开始、最多到 end，一行能放下多少字符（返回字符数）。
     * 直接用整段正文 + 区间测量，避免为每一行都 substring 出一份拷贝。
     */
    private static int fitChars(TextPaint paint, CharSequence text, int offset, int end, int width) {
        int remaining = end - offset;
        if (remaining <= 0) {
            return 0;
        }

        int fit = paint.breakText(text, offset, end, true, width, null);
        if (fit <= 0) {
            fit = 1;
        }

        // breakText 对超长行可能低估（返回 0 或很小），兜底用平均字宽估算
        if (fit == 1 && remaining > 1) {
            int probe = Math.min(8, remaining);
            float avg = paint.measureText(text, offset, offset + probe) / probe;
            if (avg > 0) {
                int byAvg = (int) (width / avg);
                if (byAvg > fit) {
                    fit = byAvg;
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
