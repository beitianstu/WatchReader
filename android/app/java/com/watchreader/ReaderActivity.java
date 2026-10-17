package com.watchreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 阅读页：全屏自绘正文 + 本地进度 + 旋转表冠翻页。
 *
 * 交互（圆屏 + 一块表冠，尽量少而稳）：
 *   · 旋转表冠 → 翻页（死区/累加/冷却三道防抖，轻微转动不翻页）
 *   · 点右侧 2/3 → 下一页；点左侧 1/3 → 上一页
 *   · 左右滑动 → 翻页
 *   · 长按 → 目录
 *   · 点底部中央（页码处）→ 阅读设置（字号/字体/跳转/返回）
 *
 * 进度：字符偏移为权威值（字号变了页码就失效，偏移不会），同时存页码与百分比。
 */
public class ReaderActivity extends Activity implements ReaderView.Listener {

    public static final String EXTRA_PATH = "path";

    private static final String PREFS = "watchreader";
    private static final String LOG_TAG = "ActivityManager";

    /** 表冠诊断：把原始事件写进 App 私有目录，方便用 adb 取出确认轴号与量级 */
    private static final boolean CROWN_DIAG = false;
    private static final int CROWN_DIAG_MAX_LINES = 200;

    private ReaderView readerView;
    private SharedPreferences prefs;
    private File bookFile;
    private int lastOffset;
    private float lastPercent;
    private int lastFontSize;
    private StringBuilder crownLog;
    private int crownRawEvents;
    /**
     * F4：只有真正装载过一页（restoreTo/seekTo 成功走完 loadPage → onProgressChanged）
     * 之后才允许落盘。否则"加载中/加载失败时 onPause/onDestroy"会把初始值 0 写进
     * progress.<id>.offset，把用户原有进度直接冲掉。
     */
    private boolean progressLoaded;
    /**
     * F4：onDestroy 之后，后台建索引线程可能才把 runOnUiThread 回调排到队列里；
     * 那个回调若继续 setBook/restoreTo，就会替一本已经关掉的书写进度，
     * 有可能覆盖新会话刚写的值。destroyed 之后一切后台回调都变成空操作。
     */
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (CROWN_DIAG) {
            crownLog = new StringBuilder();
        }

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            Toast.makeText(this, "缺少书籍路径", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        bookFile = new File(path);

        readerView = new ReaderView(this);
        readerView.setListener(this);
        // 每翻一页立刻把"页码连续性"日志落盘，方便 adb 侧实时取证
        readerView.setPageDiagFlusher(this::flushPageDiag);
        readerView.setCrownTraceFlusher(this::flushCrownTrace);
        lastFontSize = prefs.getInt("fontSize", 13);
        readerView.setFontSize(lastFontSize);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        root.addView(readerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 阅读时保持亮屏：正拧表冠时熄屏会很别扭
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        readerView.requestFocus();

        loadBookAsync();
    }

    /**
     * 兜底与诊断：某些固件把表冠事件直接发给 Activity（而不是子 View）。
     * 这里先把原始事件记进内存（用于确认轴号与量级），再交给系统正常派发。
     */
    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (CROWN_DIAG && event != null) {
            int action = event.getActionMasked();
            boolean looksLikeCrown = action == android.view.MotionEvent.ACTION_SCROLL
                    || event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL) != 0f;
            if (looksLikeCrown && crownRawEvents < CROWN_DIAG_MAX_LINES) {
                crownRawEvents++;
                crownLog.append("action=").append(action)
                        .append(" source=0x").append(Integer.toHexString(event.getSource()))
                        .append(" scroll=").append(event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL))
                        .append(" relX=").append(event.getAxisValue(0x00000015))
                        .append(" relY=").append(event.getAxisValue(0x00000016))
                        .append(" ax30=").append(event.getAxisValue(0x00000030))
                        .append('\n');
                if (crownRawEvents % 20 == 0) {
                    writeCrownLog();
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** 每翻一页立即落盘页码连续性日志（诊断用，见 ReaderView.logPageDiag） */
    private void flushPageDiag() {
        if (readerView == null) {
            return;
        }
        String pages = readerView.drainPageDiag();
        if (pages.length() == 0) {
            return;
        }
        try {
            File out = new File(getFilesDir(), "page-diag.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, true);
            fos.write(pages.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "WatchReader page diag write failed: " + e);
        }
    }

    /** 表冠原始轨迹落盘（诊断用） */
    private void flushCrownTrace() {
        if (readerView == null) {
            return;
        }
        String trace = readerView.drainCrownTrace();
        if (trace.length() == 0) {
            return;
        }
        try {
            File out = new File(getFilesDir(), "crown-trace.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, true);
            fos.write(trace.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "WatchReader crown trace write failed: " + e);
        }
    }

    private void writeCrownLog() {        if (!CROWN_DIAG || crownLog == null) {
            return;
        }
        try {
            StringBuilder all = new StringBuilder();
            // 先写"方向定标"结果：每条都记录了 原始滚动值 → 判定方向 → 实际往哪翻
            if (readerView != null) {
                String flips = readerView.drainCrownDiag();
                if (flips.length() > 0) {
                    all.append("=== 翻页定标 ===\n").append(flips).append('\n');
                }
                String pages = readerView.drainPageDiag();
                if (pages.length() > 0) {
                    all.append("=== 页码连续性 ===\n").append(pages).append('\n');
                }
            }
            all.append("=== 原始滚动事件 ===\n").append(crownLog);

            File out = new File(getFilesDir(), "crown-events.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false);
            fos.write(all.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            android.util.Log.e(LOG_TAG, "WatchReader crown log write failed: " + e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        writeCrownLog();
        flushPageIndex();
    }

    @Override
    protected void onDestroy() {
        saveProgress();      // 已装载过才写（见 progressLoaded），正常每页落盘不受影响
        writeCrownLog();
        flushPageIndex();    // 把本次量出的页码起点落盘，下次打开不用重量
        destroyed = true;    // 之后的后台回调一律不生效，避免替新会话写进度
        super.onDestroy();
    }

    /** 落盘页码缓存；失败只是下次多量一遍，不影响阅读 */
    private void flushPageIndex() {
        if (readerView == null) {
            return;
        }
        try {
            readerView.flushPageIndex();
        } catch (Throwable t) {
            android.util.Log.e(LOG_TAG, "WatchReader flush page index failed: " + t);
        }
    }

    /** 单调时钟的毫秒差（System.nanoTime 不受系统时间调整影响，只用于诊断） */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ---------------- 打开 ----------------

    /** 书库/阅读页共用的私有缓存目录（索引缓存 + 页码缓存） */
    private File indexDir() {
        File dir = new File(getFilesDir(), "index");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建索引目录");
        }
        return dir;
    }

    private void loadBookAsync() {
        final TextView loading = new TextView(this);
        // 真实阶段由 Book 回调刷新：只有真的要重建索引才说"正在建立索引"
        // （"重建"不只是首次打开：源文件更新、缓存坏了同样会重建，所以不写"首次"）
        loading.setText("正在打开…");
        loading.setTextColor(0xFF8394AB);
        loading.setTextSize(12f);
        loading.setGravity(Gravity.CENTER);
        addContentView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 单调时钟：从点击打开算起，到首屏就绪为止（诊断用，不改变任何行为）
        final long openStartNanos = System.nanoTime();

        new Thread(() -> {
            try {
                final File cacheDir = indexDir();
                // 本次装载是否真的重建了索引（缓存命中/内存复用都不算）
                final boolean[] rebuilt = { false };
                final Book.LoadListener phase = p -> {
                    if (p == Book.LoadPhase.BUILD_INDEX) {
                        rebuilt[0] = true;
                    }
                    runOnUiThread(() -> {
                        if (!destroyed) {
                            loading.setText(p == Book.LoadPhase.BUILD_INDEX
                                    ? "正在建立索引…"
                                    : "正在加载…");
                        }
                    });
                };
                final long bookStartNanos = System.nanoTime();
                final Book book = BookCache.open(bookFile, cacheDir, phase);
                final long bookLoadMs = elapsedMs(bookStartNanos);
                // lastOpen：第 4 段是**本次**索引耗时（复用就是 0），不再是"第一次构建的耗时"
                prefs.edit().putString("lastOpen", book.title + "|" + book.charCount()
                        + "|" + TextDecoder.describe(book.encoding) + "|"
                        + (rebuilt[0] ? book.buildMs : 0L)).commit();
                runOnUiThread(() -> {
                    if (destroyed) {
                        // 页面已经销毁：不要 setBook/restoreTo，更不要替它落盘（F4）
                        return;
                    }
                    loading.setVisibility(android.view.View.GONE);
                    readerView.setPageCacheDir(cacheDir);
                    readerView.setBook(book);
                    int offset = prefs.getInt(progressOffsetKey(), 0);
                    // 即使 saveProgress 在 restoreTo 之前被调用（例如恰好 onPause），
                    // 写回的也是原值而不是 0
                    lastOffset = offset;
                    long restoreStartNanos = System.nanoTime();
                    readerView.restoreTo(offset);
                    long restoreMs = elapsedMs(restoreStartNanos);
                    long totalMs = elapsedMs(openStartNanos);
                    // 诊断日志（无书名/正文/路径）：本次 Book 加载、恢复定位、到首屏就绪的总耗时、索引阶段
                    android.util.Log.i(LOG_TAG, "WR load book=" + bookLoadMs + "ms restore=" + restoreMs
                            + "ms total=" + totalMs + "ms stage="
                            + (rebuilt[0] ? ("build" + book.buildMs + "ms") : "reuse"));
                    final long firstFrameStart = openStartNanos;
                    readerView.postOnAnimation(() -> {
                        if (!destroyed) {
                            android.util.Log.i(LOG_TAG, "WR first-frame total=" + elapsedMs(firstFrameStart) + "ms");
                        }
                    });
                    String indexInfo = rebuilt[0]
                            ? ("索引 " + book.buildMs + "ms")
                            : "已复用索引";
                    String msg = String.format(java.util.Locale.US,
                            "%s · %s · %d 字 · %s", book.title,
                            TextDecoder.describe(book.encoding), book.charCount(), indexInfo);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            } catch (final Throwable e) {
                // 用系统 tag 打日志：华为手表会丢弃未知 tag 的日志，自定义 tag 抓不到
                android.util.Log.e("ActivityManager", "WatchReader open failed: " + e, e);
                prefs.edit().putString("lastError", String.valueOf(e)).commit();
                runOnUiThread(() -> {
                    if (destroyed) {
                        return;
                    }
                    Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "book-open").start();
    }

    // ---------------- 进度 ----------------

    private String progressOffsetKey() {
        return MainActivity.progressKey(bookFile) + ".offset";
    }

    private String progressKey() {
        return MainActivity.progressKey(bookFile);
    }

    private void saveProgress() {
        if (bookFile == null || readerView == null) {
            return;
        }
        // F4：还没成功装载过任何一页时（正在建索引 / 打开失败 / 只走到 onPause），
        // lastOffset 仍是初始值 0，此时落盘等于把旧进度冲成 0。宁可不写。
        if (!progressLoaded) {
            return;
        }
        // 用 commit() 而不是 apply()：apply 是**异步**落盘，手表随时可能被系统杀掉，
        // 那次写就可能还没写到磁盘就丢了（用户反馈"有时无法保存进度"）。
        // 每页多花几毫秒换"一定落盘"，这个交易在手表上值得。
        boolean ok = prefs.edit()
                .putInt(progressOffsetKey(), lastOffset)
                // 兼容书库列表：offset|page|percent
                .putString(progressKey(), lastOffset + "|" + readerView.getPageIndex() + "|" + lastPercent)
                .commit();
        if (!ok) {
            // 用系统 tag：华为手表会丢弃未知 tag 的日志
            android.util.Log.e("ActivityManager", "WatchReader saveProgress failed offset=" + lastOffset);
        }
    }

    @Override
    public void onProgressChanged(int charOffset, float percent) {
        if (destroyed) {
            return;
        }
        lastOffset = charOffset;
        lastPercent = percent;
        progressLoaded = true;   // 从现在起才允许落盘（F4）
        // 每翻一页就落盘：手表可能随时被系统杀掉，不能等 onPause
        saveProgress();
    }

    @Override
    public void onPageChanged(int pageIndex, int pageCount) {
        // 页码只用于底栏显示，ReaderView 自己维护
    }

    @Override
    public void onSeekRequested() {
        saveProgress();
        showActionMenu();
    }

    @Override
    public void onTocRequested() {
        // 长按正文 → 打开自绘目录菜单（表冠可选项）。
        // 旧版这里走的是系统 AlertDialog，表冠用不了，已经删掉。
        showTocMenu();
    }

    // ---------------- 菜单 ----------------

    /**
     * 阅读设置菜单。
     *
     * 用 ReaderView 自绘的浮层，而不是系统 AlertDialog —— 因为系统对话框拿不到表冠事件
     * （它在自己的窗口里，通用运动事件不会派发到我们的 View），菜单里就转不动。
     * 现在：表冠上下选项，点屏幕确认。
     */
    private void showActionMenu() {
        java.util.List<ReaderView.MenuItem> items = new java.util.ArrayList<>();
        items.add(new ReaderView.MenuItem(
                "调大字号 (" + lastFontSize + " → " + Math.min(30, lastFontSize + 1) + ")",
                () -> applyFont(lastFontSize + 1)));
        items.add(new ReaderView.MenuItem(
                "调小字号 (" + lastFontSize + " → " + Math.max(12, lastFontSize - 1) + ")",
                () -> applyFont(lastFontSize - 1)));
        items.add(new ReaderView.MenuItem("切换字体（黑体/等宽/衬线）", this::cycleFontFamily));
        items.add(new ReaderView.MenuItem("目录", this::showTocMenu));
        items.add(new ReaderView.MenuItem("按百分比跳转", this::showSeekDialog));
        if (CROWN_DIAG) {
            items.add(new ReaderView.MenuItem("查看表冠诊断", this::showCrownDiag));
            items.add(new ReaderView.MenuItem("查看页码诊断", this::showPageDiag));
        }
        // 只保留一个"返回书库"，避免重复项
        items.add(new ReaderView.MenuItem("返回书库", this::finish));

        readerView.openMenu("阅读设置", items, null);
    }

    /** 目录也用自绘菜单，这样表冠能翻目录 */
    private void showTocMenu() {
        java.util.List<String> titles = readerView.chapterTitles();
        if (titles.isEmpty()) {
            Toast.makeText(this, "这本书没识别出章节", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<Integer> offsets = readerView.chapterOffsets();
        java.util.List<ReaderView.MenuItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            items.add(new ReaderView.MenuItem(titles.get(i), () -> {
                if (index < offsets.size()) {
                    readerView.seekTo(offsets.get(index));
                }
            }));
        }
        readerView.openMenu("目录", items, null);

        // 打开目录时把高亮定位到"当前正在读的那一章"，否则 340 章要从头翻
        int current = readerView.getCharOffset();
        int matched = 0;
        for (int i = 0; i < offsets.size(); i++) {
            if (offsets.get(i) <= current) {
                matched = i;
            } else {
                break;
            }
        }
        readerView.setMenuSelection(matched);
    }

    /** 表冠诊断 */
    private void showCrownDiag() {
        String text = "已捕获表冠事件：" + readerView.crownDebugInfo() + "\n\n"
                + "原始事件（前 " + CROWN_DIAG_MAX_LINES + " 条，同时写入 files/crown-events.log）：\n\n"
                + (crownLog == null ? "" : crownLog.toString());
        new AlertDialog.Builder(this)
                .setTitle("表冠诊断")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 页码连续性诊断 */
    private void showPageDiag() {
        String text = "页码诊断已写入 files/page-diag.log\n\n"
                + "如需抓取，请先从菜单退出阅读页（触发落盘），再 adb 取文件。";
        new AlertDialog.Builder(this)
                .setTitle("页码诊断")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * 侧键确认。
     * 这块表没有独立的"确认/Home"物理键：hisi_func_key 报的是华为私有的 KEY_F26，
     * 其余只有 KEY_POWER。所以把 F26 与 DPAD_CENTER / ENTER 都接上；
     * 菜单里另有一个可点的"确定"按钮保证只用触摸也能操作。
     */
    private static final int KEY_F26 = 186;

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (readerView != null && readerView.isMenuOpen()) {
            if (keyCode == KEY_F26
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                readerView.confirmMenuSelection();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 菜单打开时，返回键先关菜单，而不是退出阅读页
        if (readerView != null && readerView.isMenuOpen()) {
            readerView.closeMenu();
            return;
        }
        super.onBackPressed();
    }

    private void applyFont(int size) {
        int clamped = Math.max(12, Math.min(30, size));
        lastFontSize = clamped;
        prefs.edit().putInt("fontSize", clamped).apply();
        readerView.setFontSize(clamped);
        Toast.makeText(this, "字号 " + clamped, Toast.LENGTH_SHORT).show();
    }

    private void cycleFontFamily() {
        String current = readerView.getFontFamily();
        String next;
        if ("sans-serif".equals(current)) {
            next = "monospace";
        } else if ("monospace".equals(current)) {
            next = "serif";
        } else {
            next = "sans-serif";
        }
        readerView.setFontFamily(next);
        Toast.makeText(this, "字体：" + next, Toast.LENGTH_SHORT).show();
    }

    private void showSeekDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Math.round(lastPercent)));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("跳到百分之几（0~100）")
                .setView(input)
                .setPositiveButton("跳转", (d, w) -> {
                    try {
                        int percent = Integer.parseInt(input.getText().toString().trim());
                        percent = Math.max(0, Math.min(100, percent));
                        readerView.seekTo(percentOffset(percent));
                    } catch (Exception e) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按百分比换算字符偏移（占位实现，实际用书长比例） */
    private int percentOffset(int percent) {
        int total = readerView.getCharCount();
        return (int) ((long) total * percent / 100L);
    }

}
