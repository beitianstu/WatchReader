package com.watchreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.IntentFilter;
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
     * 调试用广播：adb 可以直接触发"息屏保持 Wi-Fi"开关，绕开菜单点击坐标，
     * 便于确定性验证锁是否真的生效。
     *   adb shell am broadcast -a com.watchreader.SET_KEEPWIFI --ez on true
     */
    public static final String ACTION_SET_KEEPWIFI = "com.watchreader.SET_KEEPWIFI";

    private final android.content.BroadcastReceiver keepWifiReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if (!ACTION_SET_KEEPWIFI.equals(intent.getAction())) {
                        return;
                    }
                    boolean on = intent.getBooleanExtra("on", !KeepAwake.isEnabled(context));
                    KeepAwake.setEnabled(context, on);
                    KeepAwake.sync(context);
                    Toast.makeText(context, on ? "keepWifi ON" : "keepWifi OFF", Toast.LENGTH_SHORT).show();
                    android.util.Log.e(LOG_TAG, "WR keepWifi=" + (on ? 1 : 0)
                            + " held=" + (KeepAwake.isHeld() ? 1 : 0));
                }
            };

    /** 广播只做调试通道，用明显的前缀便于 logcat 过滤（中文日志在华为手表上会被丢弃） */
    public static final String TAG_WR = "ActivityManager";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // 只接收我们自己发的、且需要显式带包名的广播，避免被外部随意触发
        IntentFilter keepWifiFilter = new IntentFilter(ACTION_SET_KEEPWIFI);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(keepWifiReceiver, keepWifiFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(keepWifiReceiver, keepWifiFilter);
        }
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

        // 若用户开了"息屏保持 Wi-Fi"，这里就把锁拿起来。
        // 注意：**不在 onPause 释放** —— 否则一回到表盘就失效，这个开关就没意义了；
        // 只在用户手动关掉开关时释放（进程被杀时系统会自动回收）。
        KeepAwake.sync(this);

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveProgress();
        writeCrownLog();
        try {
            unregisterReceiver(keepWifiReceiver);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 打开 ----------------

    private void loadBookAsync() {
        final TextView loading = new TextView(this);
        loading.setText("正在建立索引…");
        loading.setTextColor(0xFF8394AB);
        loading.setTextSize(12f);
        loading.setGravity(Gravity.CENTER);
        addContentView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        new Thread(() -> {
            try {
                File indexDir = new File(getFilesDir(), "index");
                if (!indexDir.isDirectory() && !indexDir.mkdirs()) {
                    throw new IllegalStateException("无法创建索引目录");
                }
                final Book book = Book.open(bookFile, indexDir);
                // 立刻记一笔"打开成功"，方便 adb 侧取证（也能验证索引耗时）
                prefs.edit().putString("lastOpen", book.title + "|" + book.charCount()
                        + "|" + TextDecoder.describe(book.encoding) + "|" + book.buildMs).commit();
                runOnUiThread(() -> {
                    loading.setVisibility(android.view.View.GONE);
                    readerView.setBook(book);
                    int offset = prefs.getInt(progressOffsetKey(), 0);
                    readerView.restoreTo(offset);
                    String msg = String.format(java.util.Locale.US,
                            "%s · %s · %d 字 · 索引 %dms", book.title,
                            TextDecoder.describe(book.encoding), book.charCount(), book.buildMs);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            } catch (final Throwable e) {
                // 用系统 tag 打日志：华为手表会丢弃未知 tag 的日志，自定义 tag 抓不到
                android.util.Log.e("ActivityManager", "WatchReader open failed: " + e, e);
                prefs.edit().putString("lastError", String.valueOf(e)).commit();
                runOnUiThread(() -> {
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
        lastOffset = charOffset;
        lastPercent = percent;
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
        // 息屏保持 Wi-Fi 开关（普通 APK 改不了系统策略，改用 WifiLock + CPU 锁实现）
        boolean keepWifi = KeepAwake.isEnabled(this);
        items.add(new ReaderView.MenuItem(
                "息屏保持 Wi-Fi：" + (keepWifi ? "开" : "关"),
                () -> toggleKeepWifi()));
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
    /** 切换"息屏保持 Wi-Fi"，并立刻把锁同步到新状态 */
    private void toggleKeepWifi() {
        android.util.Log.e(LOG_TAG, "WatchReader toggleKeepWifi 被调用，当前=" + KeepAwake.isEnabled(this));
        boolean next = !KeepAwake.isEnabled(this);
        KeepAwake.setEnabled(this, next);
        KeepAwake.sync(this);
        android.util.Log.e(LOG_TAG, "WatchReader toggleKeepWifi 结果：keepWifi=" + next
                + " 锁持有=" + KeepAwake.isHeld());
        String msg = next
                ? "已开启：息屏后 Wi-Fi 保持（会更费电）"
                : "已关闭：恢复系统休眠策略";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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
