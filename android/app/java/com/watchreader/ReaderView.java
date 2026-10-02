package com.watchreader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 阅读视图：自绘。
 *
 * 两个关键性能决策（都是实测踩出来的）：
 *  1. 不用 StaticLayout —— Builder.obtain 会分析 start..end 整段，传"到全书末尾"会直接 ANR；
 *     改成用 Book 的行索引逐行排版 + canvas.drawText，一页只处理页容量那么多字。
 *  2. 字体先测一个中文字宽，再对每行做 textScaleX 微调，
 *     这样"自定义折行的结果"和"实际绘制宽度"始终一致，不会右边被裁。
 *
 * 圆屏安全区：正文宽度取 0.78×W（圆的弦长），四角不会切到字。
 */
public class ReaderView extends View {

    public interface Listener {
        void onProgressChanged(int charOffset, float percent);

        void onPageChanged(int pageIndex, int pageCount);

        void onSeekRequested();

        void onTocRequested();
    }

    private static final int BG = 0xFF0B0F14;
    private static final int BODY = 0xFFE6EDF5;
    private static final int BAR_TEXT = 0xFF8394AB;
    private static final int FOOTER_TEXT = 0xFF4C8DFF;
    private static final String LOG_TAG = "ActivityManager";

    private static final float TOP_BAR_PX = 30f;
    private static final float BOTTOM_BAR_PX = 34f;
    private static final float SIDE_INSET_PX = 8f;

    /** 行高倍数：中文小说 1.3 左右既省版面又不挤 */
    private static final float LINE_HEIGHT_RATIO = 1.3f;

    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 方向定标诊断：记录"原始滚动值 → 判定方向 → 实际翻页"。
     * 用途：从日志反推"某个方向的旋转到底对应正值还是负值"，不再靠猜。
     * 定标完成后把 logCrownDiag 置 false 即可（只影响日志，不影响功能）。
     */
    public boolean logCrownDiag = false;
    private final StringBuilder crownDiag = new StringBuilder();
    private int crownDiagFlips = 0;
    private int crownDiagLastDir = 0;
    private float crownDiagLastDelta = 0f;

    public String drainCrownDiag() {
        String out = crownDiag.toString();
        crownDiag.setLength(0);
        return out;
    }

    /** 页码连续性诊断（临时，验证"快速翻页页码不乱"） */
    public boolean logPageDiag = false;
    private final StringBuilder pageDiag = new StringBuilder();
    private int pageDiagLoads = 0;
    private Runnable pageDiagFlusher;

    /** 每装载一页回调一次，供 Activity 立即落盘（不必等 onPause） */
    public void setPageDiagFlusher(Runnable flusher) {
        this.pageDiagFlusher = flusher;
    }

    public String drainPageDiag() {
        String out = pageDiag.toString();
        pageDiag.setLength(0);
        return out;
    }

    /**
     * 表冠原始轨迹记录（临时）：每条事件都记 原始值 + 增量 + 判定方向。
     * 用来验证"固件累积量是否真的在每次旋转后复位" —— 方向随机会不会就是这里来的。
     */
    private final StringBuilder crownTrace = new StringBuilder();
    private Runnable crownTraceFlusher;
    private int crownTraceCount = 0;

    public void setCrownTraceFlusher(Runnable flusher) {
        this.crownTraceFlusher = flusher;
    }

    public String drainCrownTrace() {
        String out = crownTrace.toString();
        crownTrace.setLength(0);
        return out;
    }

    private Book book;
    private Listener listener;

    private int fontSize = 13;
    private float lineSpacingAdd = 6f;
    private String fontFamily = "sans-serif";

    private float textLeft;
    private float textTop;
    private float textWidth;
    private float textHeight;
    private float headerBaseline;
    private float footerBaseline;

    private Paginator.Page current;
    /** 精确页码映射：页码 ↔ 行号，替代"按平均行数估算页码"（后者快速翻页会累积误差） */
    private PageMap pageMap;
    private List<Chapter> chapters;
    private int estimatedPages = 1;
    private int pageIndex = 0;
    private int lastReportedOffset = -1;
    private boolean ready;

    private static final class Chapter {
        final String title;
        final int offset;

        Chapter(String title, int offset) {
            this.title = title;
            this.offset = offset;
        }
    }

    public ReaderView(Context context) {
        super(context);
        setBackgroundColor(BG);
        bodyPaint.setColor(BODY);
        bodyPaint.setTypeface(android.graphics.Typeface.create(fontFamily, android.graphics.Typeface.NORMAL));
        barPaint.setColor(BAR_TEXT);
        barPaint.setTextSize(20f);
        footerPaint.setColor(FOOTER_TEXT);
        footerPaint.setTextSize(20f);
        applyFontMetrics();

        // 旋转表冠（物理滚轮）会作为"通用运动事件"派发给当前有焦点的 View，
        // 因此这个 View 必须可聚焦、且吃掉触摸焦点，否则表冠事件收不到。
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBook(Book book) {
        this.book = book;
        this.ready = false;
        this.chapters = null;
        this.pageIndex = 0;
        this.current = null;
        this.pageMap = null;
        this.lastReportedOffset = -1;
        requestLayout();
        invalidate();
    }

    /** 分页参数变了（字号/字体/尺寸），页码映射必须重建 */
    private void resetPageMap() {
        pageMap = null;
    }

    private PageMap pageMap() {
        if (pageMap == null && book != null && textWidth > 0 && textHeight > 0) {
            pageMap = new PageMap(book, bodyPaint, (int) textWidth, (int) textHeight, lineSpacingAdd);
        }
        return pageMap;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int size) {
        int clamped = Math.max(12, Math.min(30, size));
        if (clamped == fontSize) {
            return;
        }
        fontSize = clamped;
        applyFontMetrics();
        resetPageMap();      // 字号变了，分页全变，页码映射必须重建
        reflowFromCurrent();
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String family) {
        String next = family == null ? "sans-serif" : family;
        if (next.equals(fontFamily)) {
            return;
        }
        fontFamily = next;
        bodyPaint.setTypeface(android.graphics.Typeface.create(fontFamily, android.graphics.Typeface.NORMAL));
        resetPageMap();
        reflowFromCurrent();
    }

    public int getCharOffset() {
        return current == null ? 0 : current.startChar;
    }

    public float getPercent() {
        if (book == null || book.charCount() == 0 || current == null) {
            return 0f;
        }
        return Math.min(100f, current.endChar * 100f / book.charCount());
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getPageCount() {
        return estimatedPages;
    }

    public int getCharCount() {
        return book == null ? 0 : book.charCount();
    }

    /** 打开时恢复阅读位置（字符偏移是权威值，换字号也不会丢） */
    public void restoreTo(int charOffset) {
        if (book == null) {
            return;
        }
        ensureMetrics(getWidth(), getHeight());
        if (getWidth() <= 0 || getHeight() <= 0) {
            pendingStart = charOffset;
            return;
        }
        loadPage(charOffset);
    }

    /** 显式跳转（目录/百分比） */
    public void seekTo(int charOffset) {
        if (book == null) {
            return;
        }
        ensureMetrics(getWidth(), getHeight());
        loadPage(charOffset);
    }

    private int pendingStart = -1;

    private void reflowFromCurrent() {
        if (book == null) {
            return;
        }
        int anchor = current == null ? 0 : current.startChar;
        ensureMetrics(getWidth(), getHeight());
        loadPage(anchor);
    }

    // ---------------- 几何 / 字体 ----------------

    private void applyFontMetrics() {
        bodyPaint.setTextSize(fontSize * getResources().getDisplayMetrics().scaledDensity);
        lineSpacingAdd = Math.max(1f, fontSize * LINE_HEIGHT_RATIO - fontSize);
    }

    /**
     * 圆屏在某个 y 处的可用弦长（左右对称）。
     * 顶栏/底栏越靠近上下端，弦越短，所以它们不能按正文宽度铺满，否则箭头会贴到弧线上。
     */
    private float chordWidthAt(float y) {
        float diameter = Math.min(getWidth(), getHeight());
        if (diameter <= 0) {
            return 0f;
        }
        float radius = diameter / 2f;
        float dy = Math.abs(y - getHeight() / 2f);
        if (dy >= radius) {
            return 0f;
        }
        return 2f * (float) Math.sqrt(Math.max(0d, radius * radius - dy * dy));
    }

    /**
     * 计算圆屏安全区。
     *
     * 圆屏（displayInfo 带 FLAG_ROUND）四角是圆弧：如果正文宽度直接按屏幕宽度给比例，
     * 顶端/底端那几行就会伸进圆弧外被切掉（用户实测反馈"四个角部分的字被截断"）。
     *
     * 正解是按弦长算：距圆心垂直距离 y 处，圆的可用弦长 = 2·√(r² − y²)。
     * 取正文区第一行与最后一行里**更差的那个 y** 算弦长当作正文宽度，
     * 这样整页任何一行都不会越界。用行中心而不是行上边缘，略微放宽。
     */
    private void ensureMetrics(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }

        float diameter = Math.min(w, h);
        float radius = diameter / 2f;
        float centerY = h / 2f;

        textTop = TOP_BAR_PX + 6f;
        float footerTop = h - BOTTOM_BAR_PX;
        textHeight = Math.max(40f, footerTop - textTop - 4f);

        float lineHeight = Math.max(1f, Paginator.lineHeightOf(bodyPaint, lineSpacingAdd));
        float firstLineOffset = Math.min(lineHeight, textHeight) / 2f;
        float yTop = Math.abs(centerY - (textTop + firstLineOffset));
        float yBottom = Math.abs((textTop + textHeight - firstLineOffset) - centerY);
        float yWorst = Math.max(yTop, yBottom);

        float chord = 2f * (float) Math.sqrt(Math.max(0d, radius * radius - yWorst * yWorst));
        if (chord <= 0f || chord > diameter) {
            chord = diameter * 0.62f;
        }

        // 只留 2% 余量。之前留 6% 又叠加了保守的取法，导致正文窄得没必要
        // （用户反馈"文字范围太小"）。弦长本身已经保证了圆弧边界，不需要再大幅缩。
        textWidth = Math.max(80f, chord * 0.98f);
        textLeft = (w - textWidth) / 2f;

        headerBaseline = TOP_BAR_PX - 8f;
        footerBaseline = h - 11f;
    }

    // ---------------- 页面装载 ----------------

    /** 用已知起始字符装载（页码由 PageMap 反查） */
    private void loadPage(int startChar) {
        loadPage(startChar, -1);
    }

    /**
     * 装载指定起始行的一页。
     *
     * @param knownPageIndex 已知页码时传进来（翻页的 ±1 路径），避免再反查；
     *                       传 -1 表示"未知页码"，交给 PageMap 精确求解。
     */
    private void loadPage(int startChar, int knownPageIndex) {
        ensureMetrics(getWidth(), getHeight());
        if (book == null || textWidth <= 0 || textHeight <= 0) {
            android.util.Log.e(LOG_TAG, "WatchReader loadPage skip: book=" + (book != null)
                    + " w=" + textWidth + " h=" + textHeight + " view=" + getWidth() + "x" + getHeight());
            return;
        }

        try {
            if (chapters == null) {
                chapters = findChapters(book.text);
            }

            Paginator.Page page = Paginator.paginateAt(book, bodyPaint, startChar,
                    (int) textWidth, (int) textHeight, lineSpacingAdd);
            current = page;
            estimatedPages = Paginator.estimatePageCount(book, bodyPaint,
                    (int) textWidth, (int) textHeight, lineSpacingAdd);

            PageMap map = pageMap();
            if (map != null) {
                if (knownPageIndex >= 0) {
                    pageIndex = knownPageIndex;
                    map.register(pageIndex, page.startChar);
                } else {
                    PageMap.Loc loc = map.locateChar(page.startChar);
                    pageIndex = loc.page;
                    map.register(pageIndex, page.startChar);
                }
            } else {
                pageIndex = 0;
            }

            ready = true;
            if (logPageDiag && pageDiag.length() < 6000) {
                pageDiag.append("page=").append(pageIndex).append(" load#").append(++pageDiagLoads).append(" via=").append(knownPageIndex >= 0 ? "flip" : "seek")
                        .append(" lines=").append(page.startLine).append('-').append(page.endLine)
                        .append(" chars=").append(page.startChar).append('-').append(page.endChar)
                        .append(" n=").append(page.lineCount())
                        .append('\n');
                if (pageDiagFlusher != null) {
                    pageDiagFlusher.run();
                }
            }
            reportProgress();
            invalidate();
        } catch (Throwable t) {
            android.util.Log.e(LOG_TAG, "WatchReader loadPage crashed: " + t, t);
        }
    }

    private void flip(boolean forward) {
        if (book == null || current == null || pageMap() == null) {
            return;
        }

        // 输入合并：极快的连翻（狂点屏幕/猛拧表冠）会让绘制队列堆积，
        // 表现为"点了却没跟上/页码乱跳"。限制最快约 11 页/秒。
        long now = System.currentTimeMillis();
        if (now - lastFlipAt < FLIP_MIN_INTERVAL_MS) {
            return;
        }
        lastFlipAt = now;

        PageMap map = pageMap();
        if (forward) {
            // 本页终点已经是全文末尾 = 最后一页
            if (current.endChar >= book.charCount()) {
                return;
            }
            // 页码 +1，起点 = 本页终点（字符精确衔接，不会漏字）
            PageMap.Loc next = map.nextPage(pageIndex, current.endChar);
            if (next.startChar <= current.startChar) {
                return;
            }
            loadPage(next.startChar, next.page);
        } else {
            if (current.startChar <= 0) {
                return;
            }
            PageMap.Loc prev = map.prevPage(pageIndex, current.startChar);
            loadPage(prev.startChar, prev.page);
        }
    }

    private void reportProgress() {
        if (listener == null || current == null) {
            return;
        }
        if (current.startChar == lastReportedOffset) {
            return;
        }
        lastReportedOffset = current.startChar;
        listener.onProgressChanged(current.startChar, getPercent());
        listener.onPageChanged(pageIndex, estimatedPages);
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ensureMetrics(w, h);
        if (book == null) {
            return;
        }
        // 尺寸变了 → 每页容量变了 → 页码映射整体作废
        resetPageMap();
        int anchor = pendingStart >= 0 ? pendingStart : (current == null ? 0 : current.startChar);
        pendingStart = -1;
        loadPage(anchor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (book == null) {
            return;
        }
        if (!ready) {
            loadPage(0);
        }
        if (current == null) {
            if (menuOpen) {
                drawMenu(canvas);
            }
            return;
        }

        // 顶栏：章节名（优先）/书名 + 百分比
        String header = currentChapterTitle();
        if (TextUtils.isEmpty(header)) {
            header = book.title;
        }
        // 顶栏也要服从圆形边界：该高度处的弦长才是真正可用的横向空间
        float headerSafe = Math.max(60f, chordWidthAt(headerBaseline) - 12f);
        float headerLeft = (getWidth() - headerSafe) / 2f;
        String percent = Math.round(getPercent()) + "%";
        float percentWidth = barPaint.measureText(percent);
        canvas.drawText(ellipsize(header, barPaint, headerSafe - percentWidth - 10f),
                headerLeft, headerBaseline, barPaint);
        canvas.drawText(percent, getWidth() - headerLeft - percentWidth, headerBaseline, barPaint);

        // 正文：逐行绘制
        float lineHeight = Paginator.lineHeightOf(bodyPaint, lineSpacingAdd);
        Paint.FontMetrics fm = bodyPaint.getFontMetrics();
        float baseline = textTop - fm.top;
        String[] texts = current.lineTexts;
        for (int i = 0; i < texts.length; i++) {
            String line = texts[i];
            if (line.length() > 0) {
                // 微调字距，保证"折行时的宽度估算"和"实际绘制宽度"严格一致，右边不会被裁
                float measured = bodyPaint.measureText(line);
                bodyPaint.setTextScaleX(measured > textWidth && measured > 0
                        ? Math.max(0.85f, textWidth / measured) : 1f);
                canvas.drawText(line, textLeft, baseline + i * lineHeight, bodyPaint);
                bodyPaint.setTextScaleX(1f);
            }
        }

        // 底栏：翻页提示 + 页码
        float footerSafe = Math.max(60f, chordWidthAt(footerBaseline) - 12f);
        float footerLeft = (getWidth() - footerSafe) / 2f;
        canvas.drawText("‹", footerLeft + SIDE_INSET_PX, footerBaseline, footerPaint);
        canvas.drawText("›", getWidth() - footerLeft - footerPaint.measureText("›") - SIDE_INSET_PX,
                footerBaseline, footerPaint);
        String footer = (pageIndex + 1) + "/" + estimatedPages;
        canvas.drawText(footer, (getWidth() - footerPaint.measureText(footer)) / 2f, footerBaseline, footerPaint);

        // 菜单浮层画在最上面
        if (menuOpen) {
            drawMenu(canvas);
        }
    }

    private String ellipsize(String text, Paint paint, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        int fit = paint.breakText(text, true, maxWidth, null);
        return fit <= 0 ? "" : text.substring(0, Math.max(0, fit - 1)) + "…";
    }

    // ---------------- 交互 ----------------

    // ===== 旋转表冠翻页 =====
    //
    // 实测（HUAWEI GLL-AL09 / HarmonyOS 4.0.0.408）：
    //   表冠是独立输入设备 /dev/input/event5 "rotary_crown"，上报 REL_WHEEL；
    //   Android 侧表现为 ACTION_SCROLL(8) + source=0x400000(SOURCE_ROTARY_ENCODER) + AXIS_SCROLL。
    //
    // ⚠️ 这个数值**不是**"累积转动量"，而是**表冠当前的位置/速度偏移**。
    //   实测轨迹（一次连续旋转）：
    //     -0.065 -0.143 -0.143 -0.156 -0.143 -0.091 -0.065 -0.065 -0.039 -0.013
    //   它在一次旋转内部围绕零点来回摆动、符号反复翻转 —— 这是表冠在卡位之间的机械回弹。
    //   因此：
    //     · 用「相邻事件差值」判方向必然随机（差值符号在一次旋转内部就来回变）；
    //     · 用「单次原始值符号」判方向也不稳（回弹会短暂穿到另一侧）。
    //   可靠判据是「滑动窗口多数投票 + 换向滞后」：
    //     · 只有 |raw| ≥ CROWN_MIN_MAG 的样本参与投票，滤掉零点附近回弹；
    //     · 同向样本累积能量，攒够 CROWN_PAGE_ACCUM 才翻一页；
    //     · 反向样本只扣能量、不立刻清零；反向能量超过 CROWN_REVERSE_MARGIN 才算换向；
    //     · 翻页后保留一部分能量，避免"一格翻两页"。

    /** 低于此绝对值不参与投票（回弹噪声多在 0.013 以下） */
    private static final float CROWN_MIN_MAG = 0.02f;

    /** 翻一页所需的同向累计能量 */
    private static final float CROWN_PAGE_ACCUM = 0.25f;

    /** 反向能量超过此值才算"用户换向"（滞后，抗回弹） */
    private static final float CROWN_REVERSE_MARGIN = 0.12f;

    /** 翻页后保留的能量比例，避免连续翻页过快 */
    private static final float CROWN_HOLD_RATIO = 0.3f;

    /** 单次样本贡献的能量上限 */
    private static final float CROWN_MAX_STEP = 0.18f;

    /** 两次翻页最小间隔 */
    private static final long CROWN_COOLDOWN_MS = 260L;

    /** 触摸过后这段时间内忽略表冠（点屏幕时手腕转动很常见） */
    private static final long CROWN_TOUCH_GUARD_MS = 400L;

    /** 超过这么久没有表冠事件就认为这次旋转结束（能量清零） */
    private static final long CROWN_IDLE_RESET_MS = 600L;

    /** 两次翻页的最小间隔：合并极快的连翻，避免绘制队列堆积导致页码错乱 */
    private static final long FLIP_MIN_INTERVAL_MS = 90L;

    private long lastFlipAt = 0L;

    /**
     * 方向映射。实测"往上拧"时原始值为负，按用户要求「往上拧 = 往后读」，故为 false。
     * 想改成"往下拧 = 往后读"，把这里改成 true 即可。
     */
    private static final boolean CROWN_UP_MEANS_FORWARD = false;

    private float crownForwardAccum = 0f;   // 朝"往后翻"方向累积的能量
    private float crownBackwardAccum = 0f;  // 朝"往前翻"方向累积的能量
    private long crownLastFlipAt = 0L;
    private long crownLastEventAt = 0L;
    private long lastTouchAt = 0L;
    private int crownEventCount = 0;
    private int crownFlipCount = 0;

    /** 判定单个样本指向哪个方向：+1 = 往后翻，-1 = 往前翻 */
    private int crownDirOf(float raw) {
        return CROWN_UP_MEANS_FORWARD ? (raw > 0f ? 1 : -1) : (raw < 0f ? 1 : -1);
    }

    /**
     * 表冠事件入口。父级（Window/Activity）会把通用运动事件派发给有焦点的 View。
     * 返回 true 表示已消费。
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // ACTION_SCROLL = 8，是表冠/滚轮的标准动作
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (scroll != 0f) {
                crownEventCount++;
                handleCrown(scroll);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    /** 滑动窗口多数投票 + 换向滞后 */
    private void handleCrown(float raw) {
        // 菜单打开时，表冠用来选项而不是翻页
        if (menuOpen) {
            handleMenuCrown(raw);
            return;
        }
        long now = System.currentTimeMillis();

        // 原始轨迹记录（诊断用）：raw + 本次判定方向，一条不落
        if (logCrownDiag && crownTrace.length() < 20000) {
            crownTraceCount++;
            crownTrace.append('#').append(crownTraceCount)
                    .append(" t=").append(now % 1000000)
                    .append(" d=").append(deltaText(raw))
                    .append(" raw=").append(String.format(java.util.Locale.US, "%+.5f", raw))
                    .append(" dir=").append(raw >= 0f ? "+" : "-")
                    .append('\n');
            if (crownTraceFlusher != null && crownTraceCount % 25 == 0) {
                crownTraceFlusher.run();
            }
        }

        // 刚摸过屏幕就别理表冠
        if (now - lastTouchAt < CROWN_TOUCH_GUARD_MS) {
            clearCrownAccum();
            crownLastEventAt = now;
            return;
        }

        // 长时间没有表冠事件 = 上一次旋转结束，能量清零，避免跨旋转累积
        if (crownLastEventAt != 0L && now - crownLastEventAt > CROWN_IDLE_RESET_MS) {
            clearCrownAccum();
        }
        crownLastEventAt = now;

        // 1) 回弹噪声：绝对值太小不投票
        if (Math.abs(raw) < CROWN_MIN_MAG) {
            return;
        }

        // 2) 投票：按符号把能量加到对应方向
        // 真实旋转优先：既然已经收到有效样本，就不该继续被"触摸守卫"压着
        lastTouchAt = 0L;
        float weight = Math.min(CROWN_MAX_STEP, Math.abs(raw));
        int dir = crownDirOf(raw);
        if (dir > 0) {
            crownForwardAccum += weight;
        } else {
            crownBackwardAccum += weight;
        }

        // 3) 换向滞后：反向能量足够大才把另一侧清零，小回弹只抵消
        if (dir > 0 && crownBackwardAccum >= CROWN_REVERSE_MARGIN) {
            crownBackwardAccum = 0f;
        } else if (dir < 0 && crownForwardAccum >= CROWN_REVERSE_MARGIN) {
            crownForwardAccum = 0f;
        }

        // 4) 冷却期内只攒能量不翻页
        if (now - crownLastFlipAt < CROWN_COOLDOWN_MS) {
            return;
        }

        // 5) 哪一侧能量先攒够就往哪边翻
        boolean forwardFlip = crownForwardAccum >= CROWN_PAGE_ACCUM;
        boolean backwardFlip = !forwardFlip && crownBackwardAccum >= CROWN_PAGE_ACCUM;

        if (forwardFlip || backwardFlip) {
            int pageBefore = pageIndex;
            int offsetBefore = getCharOffset();
            flip(forwardFlip);
            int pageAfter = pageIndex;
            int offsetAfter = getCharOffset();

            // 翻页动作落盘：把"触发值 + 能量 + 页面是否真的动了"一起记下来，
            // 避免再出现"以为翻了其实没翻"的诊断盲区。
            if (logCrownDiag && crownTrace.length() < 24000) {
                crownTrace.append("edge t=").append(now % 1000000)
                        .append(" raw=").append(String.format(java.util.Locale.US, "%+.5f", raw))
                        .append(" fwdEnergy=").append(String.format(java.util.Locale.US, "%.4f", crownForwardAccum))
                        .append(" bwdEnergy=").append(String.format(java.util.Locale.US, "%.4f", crownBackwardAccum))
                        .append(" want=").append(forwardFlip ? "后翻" : "前翻")
                        .append(" page ").append(pageBefore).append("→").append(pageAfter)
                        .append(" offset ").append(offsetBefore).append("→").append(offsetAfter)
                        .append(" moved=").append(offsetAfter != offsetBefore)
                        .append('\n');
                if (crownTraceFlusher != null) {
                    crownTraceFlusher.run();
                }
            }

            if (forwardFlip) {
                crownForwardAccum *= CROWN_HOLD_RATIO;
                crownBackwardAccum = 0f;
            } else {
                crownBackwardAccum *= CROWN_HOLD_RATIO;
                crownForwardAccum = 0f;
            }
            crownLastFlipAt = now;
            crownFlipCount++;
            haptic();
        }
    }

    private void clearCrownAccum() {
        crownForwardAccum = 0f;
        crownBackwardAccum = 0f;
    }

    /** 仅用于诊断日志：与上一个样本的差值 */
    private float crownTraceLastRaw = 0f;

    private String deltaText(float raw) {
        float d = raw - crownTraceLastRaw;
        crownTraceLastRaw = raw;
        return String.format(java.util.Locale.US, "%+.5f", d);
    }

    private void haptic() {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** 供 Activity 诊断用 */
    public String crownDebugInfo() {
        return "收到事件=" + crownEventCount + " 已翻页=" + crownFlipCount
                + " 后翻能量=" + String.format(java.util.Locale.US, "%.4f", crownForwardAccum)
                + " 前翻能量=" + String.format(java.util.Locale.US, "%.4f", crownBackwardAccum);
    }

    // ===== 表冠可操作的菜单 =====
    //
    // 系统 AlertDialog 收不到表冠事件（它有自己的窗口，通用运动事件不会派发到我们的 View），
    // 所以设置菜单改成"在当前 View 里自绘一个浮层"：
    //   · 表冠转到哪一项，哪一项高亮；
    //   · 点屏幕执行高亮项；
    //   · 点浮层外面关闭。
    // 这样正文、菜单、目录三处的表冠行为就统一了。

    /** 菜单项 */
    public static final class MenuItem {
        public final String label;
        public final Runnable action;

        public MenuItem(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private final java.util.List<MenuItem> menuItems = new java.util.ArrayList<>();
    private boolean menuOpen = false;
    private int menuSelected = 0;
    private int menuScrollTop = 0;
    private String menuTitle = "";
    private Runnable menuDismissAction;
    private float crownMenuAccum = 0f;

    /** 菜单里滚动一格需要的能量（比翻页小，选菜单要跟手） */
    private static final float CROWN_MENU_STEP = 0.12f;

    /** 菜单是否打开（Activity 用它决定返回键行为） */
    public boolean isMenuOpen() {
        return menuOpen;
    }

    public void openMenu(String title, java.util.List<MenuItem> items, Runnable onDismiss) {
        menuItems.clear();
        if (items != null) {
            menuItems.addAll(items);
        }
        menuTitle = title == null ? "" : title;
        menuSelected = 0;
        menuScrollTop = 0;
        menuOpen = !menuItems.isEmpty();
        menuDismissAction = onDismiss;
        crownMenuAccum = 0f;
        clearCrownAccum();
        if (logCrownDiag && crownTrace.length() < 24000) {
            crownTrace.append("openMenu items=").append(menuItems.size())
                    .append(" open=").append(menuOpen)
                    .append(" titleLen=").append(menuTitle == null ? -1 : menuTitle.length())
                    .append('\n');
            if (crownTraceFlusher != null) {
                crownTraceFlusher.run();
            }
        }
        invalidate();
    }

    public void closeMenu() {
        if (!menuOpen) {
            return;
        }
        menuOpen = false;
        menuItems.clear();
        Runnable action = menuDismissAction;
        menuDismissAction = null;
        if (action != null) {
            action.run();
        }
        invalidate();
    }

    /**
     * 菜单几何：返回 [left, top, right, bottom]。
     *
     * 宽度受圆形屏的硬约束：**面板顶边所在高度的弦长就是宽度上限**。
     * 面板越高 → 顶边越靠上 → 弦越短 → 越窄，所以不能简单设成"屏宽的 85%"。
     * 这里取几何允许的最大值（弦长减 4px 余量），并把行高按可见行数收敛，
     * 让顶行尽量靠近圆心一侧，从而拿到更宽的可用宽度。
     */
    private float[] menuBounds() {
        float w = getWidth();
        float h = getHeight();
        float diameter = Math.min(w, h);

        float rowH = menuRowHeight();
        int rows = Math.min(menuItems.size(), MENU_MAX_ROWS);
        // +1 行给标题
        float panelH = Math.min(h * 0.90f, rowH * (rows + 1.15f));

        // 关键：顶边越低（越靠近圆心），弦越长、面板越宽。
        // 所以先保证"顶边不高于 MENU_TOP_MIN_Y"，再把面板高度压到能放进这个范围。
        float top = Math.max(MENU_TOP_MIN_Y, (h - panelH) / 2f);
        if (top + panelH > h - MENU_BOTTOM_MIN_PAD) {
            panelH = Math.max(rowH * 2f, h - MENU_BOTTOM_MIN_PAD - top);
        }

        float chordAtTop = chordWidthAt(top + 2f);
        float panelW = chordAtTop > 0f ? chordAtTop - 4f : diameter * 0.62f;
        panelW = Math.max(Math.min(120f, w), Math.min(panelW, w));

        float left = (w - panelW) / 2f;
        return new float[] { left, top, left + panelW, top + panelH };
    }

    /**
     * 菜单面板顶边的最小 y。
     * 圆屏上"越靠上弦越短"，顶边如果贴到 y≈23，弦长只剩 254px（屏宽 54%），
     * 菜单就窄得没法看。把顶边压到 y≥70 后弦长可达 333px（71%），宽出来的部分足够多放几个字。
     */
    private static final float MENU_TOP_MIN_Y = 70f;

    /** 面板底部离屏幕下沿的最小留白（同样是为了不贴到弧线上） */
    private static final float MENU_BOTTOM_MIN_PAD = 46f;

    /** 菜单一屏最多显示几行（多了会顶到弧线上，反而更窄） */
    private static final int MENU_MAX_ROWS = 10;

    /** 底部"确定"按钮占的高度 */
    private float menuFooterHeight() {
        return Math.max(32f, Math.min(46f, getHeight() * 0.10f));
    }

    /** 菜单列表区域（列表用，不含标题与底部按钮） */
    private float[] menuListBounds() {
        float[] b = menuBounds();
        float titleH = Math.max(24f, menuRowHeight() * 0.75f);
        return new float[] { b[0], b[1] + titleH, b[2], b[3] - menuFooterHeight() };
    }

    private float menuRowHeight() {
        float h = getHeight();
        return Math.max(30f, Math.min(44f, h * 0.078f));
    }

    private void drawMenu(Canvas canvas) {
        float[] b = menuBounds();
        float rowH = menuRowHeight();

        canvas.drawColor(0xCC000000);

        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(0xFF161C25);
        canvas.drawRoundRect(b[0], b[1], b[2], b[3], 12f, 12f, panel);

        float titleH = Math.max(24f, rowH * 0.75f);
        float footerH = menuFooterHeight();

        // 标题行：左边标题，右边"第几项/共几项"
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(0xFF8394AB);
        titlePaint.setTextSize(17f);
        titlePaint.setTextAlign(Paint.Align.LEFT);
        Paint counterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        counterPaint.setColor(0xFF8394AB);
        counterPaint.setTextSize(15f);
        counterPaint.setTextAlign(Paint.Align.RIGHT);

        String counter = (menuSelected + 1) + "/" + menuItems.size();
        float counterW = counterPaint.measureText(counter);
        canvas.drawText(ellipsize(menuTitle, titlePaint, b[2] - b[0] - counterW - 34f),
                b[0] + 14f, b[1] + titleH * 0.78f, titlePaint);
        canvas.drawText(counter, b[2] - 14f, b[1] + titleH * 0.78f, counterPaint);

        float listTop = b[1] + titleH;
        float listBottom = b[3] - footerH;
        int visibleRows = Math.max(1, (int) ((listBottom - listTop) / rowH));
        if (menuSelected < menuScrollTop) {
            menuScrollTop = menuSelected;
        } else if (menuSelected >= menuScrollTop + visibleRows) {
            menuScrollTop = menuSelected - visibleRows + 1;
        }

        Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selPaint.setColor(0xFF1D2C47);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(20f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        canvas.save();
        canvas.clipRect(b[0], listTop, b[2], listBottom);
        for (int i = menuScrollTop; i < menuItems.size(); i++) {
            float rowTop = listTop + (i - menuScrollTop) * rowH;
            if (rowTop > listBottom) {
                break;
            }
            if (i == menuSelected) {
                canvas.drawRoundRect(b[0] + 6f, rowTop + 2f, b[2] - 6f, rowTop + rowH - 2f,
                        8f, 8f, selPaint);
            }
            textPaint.setColor(i == menuSelected ? 0xFF4C8DFF : 0xFFE6EDF5);
            canvas.drawText(ellipsize(menuItems.get(i).label, textPaint, b[2] - b[0] - 40f),
                    (b[0] + b[2]) / 2f, rowTop + rowH * 0.68f, textPaint);
        }
        canvas.restore();

        drawMenuScrollbar(canvas, b, listTop, listBottom, visibleRows);

        // 确认按钮：表冠只能"选"，需要一个明确的"执行"入口
        Paint btnBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        btnBg.setColor(0xFF1D2C47);
        float btnLeft = b[0] + 12f;
        float btnRight = b[2] - 12f;
        float btnTop = b[3] - footerH + 5f;
        float btnBottom = b[3] - 6f;
        canvas.drawRoundRect(btnLeft, btnTop, btnRight, btnBottom, 9f, 9f, btnBg);

        // 菜单里有"确定/确认"这类项时，按钮以它为准，避免语义重叠
        int confirmIndex = findConfirmItemIndex();
        String confirmLabel = confirmIndex >= 0
                ? menuItems.get(confirmIndex).label
                : "确定";
        Paint btnText = new Paint(Paint.ANTI_ALIAS_FLAG);
        btnText.setColor(0xFF4C8DFF);
        btnText.setTextSize(19f);
        btnText.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(ellipsize(confirmLabel, btnText, btnRight - btnLeft - 16f),
                (btnLeft + btnRight) / 2f, btnTop + (btnBottom - btnTop) * 0.72f, btnText);
    }

    /** 列表右侧的滚动条：让"还有多少内容、现在在哪"一眼可见 */
    private void drawMenuScrollbar(Canvas canvas, float[] b, float listTop, float listBottom,
                                   int visibleRows) {
        if (menuItems.size() <= visibleRows || listBottom <= listTop) {
            return;
        }
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(0x33FFFFFF);
        Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumb.setColor(0xFF4C8DFF);

        float x = b[2] - 5f;
        canvas.drawRoundRect(x - 1.5f, listTop + 2f, x + 1.5f, listBottom - 2f, 2f, 2f, track);

        float total = menuItems.size();
        float ratio = visibleRows / total;
        float thumbH = Math.max(14f, (listBottom - listTop - 4f) * ratio);
        float maxScroll = Math.max(1f, total - visibleRows);
        float pos = (menuScrollTop / maxScroll) * ((listBottom - listTop - 4f) - thumbH);
        canvas.drawRoundRect(x - 1.5f, listTop + 2f + pos, x + 1.5f, listTop + 2f + pos + thumbH,
                2f, 2f, thumb);
    }

    /** 菜单里是否已有"确定/确认/跳转"这类项（有的话确认按钮就用它） */
    private int findConfirmItemIndex() {
        for (int i = 0; i < menuItems.size(); i++) {
            String label = menuItems.get(i).label;
            if (label.startsWith("确定") || label.startsWith("确认") || label.startsWith("跳转")) {
                return i;
            }
        }
        return -1;
    }

    /** 执行高亮项（表冠选中后由按钮/按键触发） */
    public void confirmMenuSelection() {
        if (!menuOpen || menuSelected < 0 || menuSelected >= menuItems.size()) {
            return;
        }
        MenuItem item = menuItems.get(menuSelected);
        closeMenu();
        if (item.action != null) {
            item.action.run();
        }
    }

    /** 菜单打开时的表冠处理：只移动高亮，不翻页 */
    private void handleMenuCrown(float raw) {
        if (logCrownDiag && crownTrace.length() < 24000) {
            crownTrace.append("menu raw=").append(String.format(java.util.Locale.US, "%+.5f", raw))
                    .append(" items=").append(menuItems.size())
                    .append(" sel=").append(menuSelected)
                    .append(" accum=").append(String.format(java.util.Locale.US, "%.4f", crownMenuAccum))
                    .append('\n');
        }
        if (Math.abs(raw) < CROWN_MIN_MAG) {
            return;
        }
        crownMenuAccum += Math.min(CROWN_MAX_STEP, Math.abs(raw));
        if (crownMenuAccum < CROWN_MENU_STEP) {
            return;
        }
        crownMenuAccum = 0f;
        // 菜单方向与正文保持一致（"上拧 = 往后/往下走"）。
        // 注意这里是**取反**的：正文里 crownDirOf>0 表示往后翻页，
        // 而菜单里"往后"= 高亮往下移动，用户实测原来的方向是反的。
        int next = menuSelected + (crownDirOf(raw) > 0 ? -1 : 1);
        if (next < 0) {
            next = 0;
        }
        if (next > menuItems.size() - 1) {
            next = menuItems.size() - 1;
        }
        if (next != menuSelected) {
            menuSelected = next;
            haptic();
            invalidate();
        }
    }

    /**
     * 菜单打开时的触摸：
     *   · 点条目 → 直接执行；
     *   · 上下滑动 → 移动选中项（补回"触摸滑不动"的问题）；
     *   · 点底部按钮 → 执行高亮项；
     *   · 点面板外面 → 关闭。
     */
    private boolean handleMenuTouch(MotionEvent event) {
        float[] b = menuBounds();
        float[] list = menuListBounds();
        float rowH = menuRowHeight();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                menuDragY = event.getY();
                menuDragging = true;
                menuDragMoved = 0f;
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!menuDragging) {
                    return true;
                }
                float y = event.getY();
                float step = y - menuDragY;   // 手指下滑为正
                if (Math.abs(step) < 14f) {
                    return true;
                }
                int delta = (int) (step < 0 ? 1 : -1) * Math.max(1,
                        (int) (Math.abs(step) / Math.max(1f, rowH)));
                int next = clampMenuIndex(menuSelected + delta);
                if (next != menuSelected) {
                    menuSelected = next;
                    haptic();
                    invalidate();
                }
                menuDragMoved += Math.abs(step);
                menuDragY = y;
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                menuDragging = false;
                return true;

            case MotionEvent.ACTION_UP: {
                menuDragging = false;
                float x = event.getX();
                float y = event.getY();

                // 点底部按钮 = 执行高亮项
                if (y >= b[3] - menuFooterHeight()) {
                    if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) {
                        confirmMenuSelection();
                    }
                    return true;
                }

                // 滑动过就不当成点击，避免"滚动完手指抬起误触发"
                if (menuDragMoved > 18f) {
                    return true;
                }

                if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) {
                    closeMenu();
                    return true;
                }
                if (y < list[1]) {
                    return true;   // 点在标题上
                }

                int row = menuScrollTop + (int) ((y - list[1]) / rowH);
                if (logCrownDiag && crownTrace.length() < 24000) {
                    crownTrace.append("menuTap x=").append((int) x).append(" y=").append((int) y)
                            .append(" panel=[").append((int) b[0]).append(',').append((int) b[1])
                            .append(',').append((int) b[2]).append(',').append((int) b[3]).append(']')
                            .append(" listTop=").append((int) list[1])
                            .append(" rowH=").append((int) rowH)
                            .append(" scrollTop=").append(menuScrollTop)
                            .append(" row=").append(row)
                            .append(" items=").append(menuItems.size())
                            .append('\n');
                    if (crownTraceFlusher != null) {
                        crownTraceFlusher.run();
                    }
                }
                if (row < 0 || row >= menuItems.size()) {
                    return true;
                }
                menuSelected = row;
                MenuItem item = menuItems.get(row);
                if (logCrownDiag && crownTrace.length() < 24000) {
                    crownTrace.append("menuPick row=").append(row)
                            .append(" label=").append(item.label).append('\n');
                    if (crownTraceFlusher != null) {
                        crownTraceFlusher.run();
                    }
                }
                closeMenu();
                if (item.action != null) {
                    item.action.run();
                }
                return true;
            }

            default:
                return true;
        }
    }

    private int clampMenuIndex(int index) {
        if (index < 0) {
            return 0;
        }
        return Math.min(index, Math.max(0, menuItems.size() - 1));
    }

    /** 菜单拖拽状态 */
    private boolean menuDragging = false;
    private float menuDragY = 0f;
    private float menuDragMoved = 0f;

    /**
     * 把高亮定位到指定项（打开目录时定位到"当前章节"用）。
     * 不改变菜单是否打开的状态。
     */
    public void setMenuSelection(int index) {
        menuSelected = clampMenuIndex(index);
        crownMenuAccum = 0f;
        invalidate();
    }

    /** 供 Activity 判断"当前高亮项是不是某一项"（用于目录定位） */
    public int getMenuSelection() {
        return menuSelected;
    }

    private float downX;
    private float downY;
    private long downTime;
    private boolean longPressFired;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressFired = true;
            if (listener != null) {
                listener.onTocRequested();
            }
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 菜单打开时，触摸用来选项/关闭
        if (menuOpen) {
            return handleMenuTouch(event);
        }
        // 只在"抬手"时刷新触摸时间。
        // 之前放在方法最外层（每个触摸事件都刷新）会让"点屏幕打开书后立刻拧表冠"的前 400ms
        // 全被守卫吞掉 —— 实测表现为"第一次拧没反应，要拧第二次"。
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lastTouchAt = System.currentTimeMillis();
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                longPressFired = false;
                clearCrownAccum();      // 触摸开始就清掉表冠能量，避免"点一下屏幕再拧"叠加
                crownLastEventAt = 0L;
                postDelayed(longPressRunnable, 450);
                if (!isFocused()) {
                    requestFocus();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > 30f || Math.abs(event.getY() - downY) > 30f) {
                    removeCallbacks(longPressRunnable);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (longPressFired) {
                    return true;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long dt = System.currentTimeMillis() - downTime;

                if (Math.abs(dx) > 60f && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    flip(dx < 0);
                    return true;
                }

                if (dt < 350 && Math.abs(dx) < 24f && Math.abs(dy) < 24f) {
                    if (event.getY() > getHeight() - 90f) {
                        if (listener != null) {
                            listener.onSeekRequested();
                        }
                        return true;
                    }
                    if (event.getX() < getWidth() / 3f) {
                        flip(false);
                    } else {
                        flip(true);
                    }
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    // ---------------- 章节 ----------------

    private static final Pattern[] CHAPTER_PATTERNS = new Pattern[] {
            Pattern.compile("^第\\s*[0-9零一二三四五六七八九十百千万两]{1,12}\\s*[章节節回卷幕篇集话話]"),
            Pattern.compile("^第\\s*[0-9]{1,6}\\s*[章节節回卷幕篇集话話]"),
            Pattern.compile("^(Chapter|CHAPTER)\\s*[0-9IVXLC]{1,6}"),
            Pattern.compile("^(序章|序言|楔子|前言|引子|后记|後記|尾声|尾聲|番外|正文|终章|終章)"),
    };

    private List<Chapter> findChapters(String text) {
        List<Chapter> result = new ArrayList<>();
        int start = 0;
        int guard = 0;
        while (start <= text.length() && guard++ < 200000) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            int len = end - start;
            if (len > 0 && len <= 64) {
                String line = text.substring(start, end).trim();
                line = line.replace("\u3000", " ").trim();
                if (isChapterHead(line)) {
                    result.add(new Chapter(line, start));
                    if (result.size() >= 3000) {
                        break;
                    }
                }
            }
            if (end >= text.length()) {
                break;
            }
            start = end + 1;
        }
        return result;
    }

    private static boolean isChapterCandidate(int len) {
        return len > 0 && len <= 64;
    }

    private static boolean isChapterHead(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (Pattern p : CHAPTER_PATTERNS) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private String currentChapterTitle() {
        if (chapters == null || chapters.isEmpty() || current == null) {
            return null;
        }
        String title = null;
        for (Chapter c : chapters) {
            if (c.offset <= current.startChar) {
                title = c.title;
            } else {
                break;
            }
        }
        return title;
    }

    public List<String> chapterTitles() {
        List<String> titles = new ArrayList<>();
        if (chapters == null) {
            return titles;
        }
        for (Chapter c : chapters) {
            titles.add(c.title);
        }
        return titles;
    }

    public List<Integer> chapterOffsets() {
        List<Integer> offsets = new ArrayList<>();
        if (chapters == null) {
            return offsets;
        }
        for (Chapter c : chapters) {
            offsets.add(c.offset);
        }
        return offsets;
    }
}
