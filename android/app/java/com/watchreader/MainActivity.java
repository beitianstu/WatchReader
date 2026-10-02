package com.watchreader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书库页：扫描手表上的 TXT，显示"读到百分之几"，点开继续读。
 *
 * 存储策略（Android 10 是旧存储模型，这里走最省事的一条路）：
 *   · 主线：从 /sdcard/Download、/sdcard/Books 等目录直接读取（需要读存储权限）；
 *   · 备线：把 TXT 推到 App 私有目录 files/books，**不需要任何权限**（adb push 即可）。
 *
 * 进度与行索引都放在 App 私有目录/preferences 里，卸载才会清掉。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1001;

    private static final String[] SCAN_DIRS = new String[] {
            "/sdcard/Download",
            "/sdcard/Books",
            "/sdcard/books",
            "/sdcard/novel",
            "/sdcard/Novel",
            "/sdcard/Documents",
            "/sdcard",
    };

    private ListView listView;
    private TextView statusView;

    // ===== 书库界面的表冠支持 =====
    //
    // 表冠事件是"通用运动事件"，系统只会派发给**当前有焦点的 View**。
    // 书库里焦点可能落在"重扫/字号"按钮上，ListView 就收不到了 —— 所以直接在 Activity 层拦截，
    // 自己控制列表滚动，这样焦点在哪都不影响。
    private float crownScrollAccum = 0f;

    /** 转多少（归一化滚动量）滚动一个条目 */
    private static final float CROWN_LIST_STEP = 0.10f;

    /** 单次样本低于此值算噪声（与阅读页同一套实测标定） */
    private static final float CROWN_LIST_DEADZONE = 0.015f;

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (event != null && event.getActionMasked() == android.view.MotionEvent.ACTION_SCROLL) {
            float scroll = event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL);
            if (scroll != 0f) {
                handleCrownScroll(scroll);
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** 书库列表的表冠滚动：攒够一格就滚一个条目 */
    private void handleCrownScroll(float scroll) {
        if (listView == null) {
            return;
        }
        if (Math.abs(scroll) < CROWN_LIST_DEADZONE) {
            return;
        }
        crownScrollAccum += Math.min(0.5f, Math.abs(scroll));
        if (crownScrollAccum < CROWN_LIST_STEP) {
            return;
        }
        crownScrollAccum = 0f;
        // 实测"往上拧 = 负值"；列表里往上拧应当往下看（与系统滚动一致）
        int delta = scroll < 0f ? 1 : -1;
        int target = listView.getFirstVisiblePosition() + delta;
        int max = Math.max(0, listView.getCount() - 1);
        listView.setSelection(Math.max(0, Math.min(max, target)));
    }
    private final List<Book.Candidate> candidates = new ArrayList<>();
    private ShelfAdapter adapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("watchreader", MODE_PRIVATE);
        buildUi();
        askPermissionThenScan();

        // 调试通道：adb 可直接开关"息屏保持 Wi-Fi"，便于确定性验证
        keepWifiReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (!ReaderActivity.ACTION_SET_KEEPWIFI.equals(intent.getAction())) {
                    return;
                }
                boolean on = intent.getBooleanExtra("on", !KeepAwake.isEnabled(context));
                KeepAwake.setEnabled(context, on);
                KeepAwake.sync(context);
                android.util.Log.e("ActivityManager", "WR keepWifi=" + (on ? 1 : 0)
                        + " held=" + (KeepAwake.isHeld() ? 1 : 0));
            }
        };
        IntentFilter f = new IntentFilter(ReaderActivity.ACTION_SET_KEEPWIFI);
        registerReceiver(keepWifiReceiver, f);
    }

    private android.content.BroadcastReceiver keepWifiReceiver;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(keepWifiReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从阅读页回来要刷新百分比
        scan();
    }

    private File privateBooksDir() {
        File dir = new File(getFilesDir(), "books");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(this, "创建目录失败", Toast.LENGTH_SHORT).show();
        }
        return dir;
    }

    private File indexDir() {
        File dir = new File(getFilesDir(), "index");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(this, "创建索引目录失败", Toast.LENGTH_SHORT).show();
        }
        return dir;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(0, 0, 0, 0);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 10, 10, 6);

        TextView title = new TextView(this);
        title.setText("本地书库");
        title.setTextColor(0xFF4C8DFF);
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button rescan = new Button(this);
        rescan.setText("重扫");
        rescan.setTextSize(12f);
        rescan.setAllCaps(false);
        rescan.setOnClickListener(v -> askPermissionThenScan());
        bar.addView(rescan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button fonts = new Button(this);
        fonts.setText("字号");
        fonts.setTextSize(12f);
        fonts.setAllCaps(false);
        fonts.setOnClickListener(v -> showFontDialog());
        bar.addView(fonts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bar);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setPadding(10, 0, 10, 0);
        adapter = new ShelfAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openBook(candidates.get(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showBookMenu(candidates.get(position));
            return true;
        });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setTextColor(0xFF8394AB);
        statusView.setTextSize(10f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(12, 4, 12, 10);
        root.addView(statusView);

        setContentView(root);
    }

    // ---------------- 扫描 ----------------

    private void askPermissionThenScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_PERM);
            return;
        }
        scan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scan();
            } else {
                // 拒绝授权也不是死路：App 私有目录里的书照样能读
                scan();
                Toast.makeText(this, "未授权：只能读取 App 私有目录里的书", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void scan() {
        candidates.clear();
        Map<String, Book.Candidate> unique = new LinkedHashMap<>();

        // 1) 私有目录优先（不需要权限）
        File priv = privateBooksDir();
        for (Book.Candidate c : Book.scan(priv, true)) {
            unique.put(c.file.getAbsolutePath(), c);
        }

        // 2) 公共目录（有权限才读得到）
        boolean canReadPublic = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (canReadPublic) {
            for (String path : SCAN_DIRS) {
                File dir = new File(path);
                if (!dir.isDirectory()) {
                    continue;
                }
                // /sdcard 根目录只扫一层，避免把微信/QQ 的缓存目录全翻一遍
                boolean recursive = !"/sdcard".equals(path);
                for (Book.Candidate c : Book.scan(dir, recursive)) {
                    unique.putIfAbsent(c.file.getAbsolutePath(), c);
                }
            }
        }

        candidates.addAll(unique.values());
        adapter.notifyDataSetChanged();

        if (candidates.isEmpty()) {
            statusView.setText("没找到 TXT。\n把小说放到 " + Environment.getExternalStorageDirectory()
                    + "/Download，或用 adb push 推到\n" + priv.getAbsolutePath());
        } else {
            statusView.setText("共 " + candidates.size() + " 本 · " + priv.getAbsolutePath());
        }
    }

    private void showBookMenu(Book.Candidate candidate) {
        Book book = null;
        try {
            book = Book.open(candidate.file, indexDir());
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        final Book opened = book;
        String[] items = new String[] {
                "开始阅读",
                "从第一页开始",
                "删除本地进度（书还在）",
                "删除这本书的文件",
        };
        new AlertDialog.Builder(this)
                .setTitle(opened.title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            openBook(candidate);
                            break;
                        case 1:
                            prefs.edit().remove(progressKey(candidate.file)).apply();
                            openBook(candidate);
                            break;
                        case 2:
                            prefs.edit().remove(progressKey(candidate.file)).apply();
                            scan();
                            Toast.makeText(this, "已清除进度", Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            if (candidate.file.delete()) {
                                deleteIndex(candidate.file);
                                prefs.edit().remove(progressKey(candidate.file)).apply();
                                scan();
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "删不掉（可能在只读目录）", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void deleteIndex(File file) {
        File idx = new File(indexDir(), Book.cacheId(file) + ".idx");
        if (idx.isFile()) {
            idx.delete();
        }
    }

    private void openBook(Book.Candidate candidate) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_PATH, candidate.file.getAbsolutePath());
        startActivity(intent);
    }

    static String progressKey(File file) {
        return "progress." + Book.cacheId(file);
    }

    // ---------------- 字号（也放在书库页，方便先调好再读） ----------------

    private void showFontDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(prefs.getInt("fontSize", 17)));
        new AlertDialog.Builder(this)
                .setTitle("正文字号（12~30）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        int size = Integer.parseInt(input.getText().toString().trim());
                        prefs.edit().putInt("fontSize", Math.max(12, Math.min(30, size))).apply();
                        Toast.makeText(this, "已保存，进书生效", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 列表 ----------------

    private final class ShelfAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return candidates.size();
        }

        @Override
        public Object getItem(int position) {
            return candidates.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(14, 12, 14, 12);

                TextView name = new TextView(MainActivity.this);
                name.setId(android.R.id.text1);
                name.setTextColor(0xFFE6EDF5);
                name.setTextSize(15f);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

                TextView meta = new TextView(MainActivity.this);
                meta.setId(android.R.id.text2);
                meta.setTextColor(0xFF8394AB);
                meta.setTextSize(10f);
                meta.setSingleLine(true);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                texts.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView pct = new TextView(MainActivity.this);
                pct.setId(android.R.id.text1 + 100);
                pct.setTextColor(0xFF4C8DFF);
                pct.setTextSize(12f);

                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(pct, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            Book.Candidate candidate = candidates.get(position);
            String[] parsed = Book.parseName(candidate.file.getName());

            TextView name = row.findViewById(android.R.id.text1);
            TextView meta = row.findViewById(android.R.id.text2);
            TextView pct = row.findViewById(android.R.id.text1 + 100);

            name.setText(parsed[0]);
            String sizeText = candidate.size >= 1024 * 1024
                    ? String.format(java.util.Locale.US, "%.2fMB", candidate.size / 1024f / 1024f)
                    : String.format(java.util.Locale.US, "%.0fKB", candidate.size / 1024f);
            meta.setText((parsed[1].isEmpty() ? "" : parsed[1] + " · ") + sizeText + " · "
                    + candidate.file.getParentFile().getName());

            float percent = progressPercent(candidate.file);
            pct.setText(percent <= 0f ? "未读" : (percent >= 99.5f ? "读完" : Math.round(percent) + "%"));

            row.setBackgroundColor(position % 2 == 0 ? 0xFF131A23 : Color.TRANSPARENT);
            return row;
        }
    }

    private float progressPercent(File file) {
        String raw = prefs.getString(progressKey(file), null);
        if (raw == null) {
            return 0f;
        }
        try {
            String[] parts = raw.split("\\|");
            // 格式：charOffset|pageIndex|percent。percent 存的是 0~100 的百分比
            return parts.length >= 3 ? Float.parseFloat(parts[2]) * 100f : 0f;
        } catch (Exception e) {
            return 0f;
        }
    }
}
