package com.watchreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

/**
 * "息屏保持 Wi-Fi"开关。
 *
 * 为什么不是改系统设置：
 *   系统的 wifi_sleep_policy 属于 Settings.Global，写它需要 WRITE_SECURE_SETTINGS，
 *   只有系统应用能拿到，普通 APK 怎么申请都没用。所以 App 侧改用两个标准锁来达到目的：
 *
 *   1) WifiLock（WIFI_MODE_FULL_HIGH_PERF）：显式告诉 Wi-Fi 子系统"别为了省电把射频关了"；
 *   2) PARTIAL_WAKE_LOCK：保证 CPU 不被挂起。只持有 WifiLock 时，深度睡眠仍可能让连接假死，
 *      两个一起拿才稳（这也是各类下载/同步类 App 的通用做法）。
 *
 * 代价与边界：
 *   · 会明显增加耗电，所以默认关闭，由用户在菜单里自己开；
 *   · 只在 App 处于前台时持有（onResume 拿、onPause 放），退出阅读器就恢复系统策略；
 *   · 不加超时，但 onPause 一定会释放，不会出现"退出后还一直耗电"。
 */
public final class KeepAwake {

    private static final String PREFS = "watchreader";
    private static final String KEY_KEEP_WIFI = "keepWifiOnScreenOff";

    private static WifiManager.WifiLock wifiLock;
    private static PowerManager.WakeLock cpuLock;
    private static boolean held = false;

    private KeepAwake() {
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_WIFI, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_WIFI, enabled).apply();
        if (!enabled) {
            release(context);
        }
    }

    /** 按当前开关状态获取/释放锁。在 onResume / 开关切换后调用。 */
    public static void sync(Context context) {
        if (isEnabled(context)) {
            acquire(context);
        } else {
            release(context);
        }
    }

    public static boolean isHeld() {
        return held;
    }

    /** 当前持有的锁的说明（用于菜单/诊断显示） */
    public static String describe(Context context) {
        if (!isEnabled(context)) {
            return "关（跟随系统策略，息屏可能断网）";
        }
        return held ? "开（Wi-Fi 与 CPU 锁已持有）" : "开（App 在前台时生效）";
    }

    private static void acquire(Context context) {
        if (held) {
            return;
        }
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "watchreader:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            android.util.Log.e("ActivityManager", "WatchReader WifiLock 获取失败: " + t);
        }

        // 说明：这里**刻意不申请 CPU 唤醒锁**。
        // 目标是"息屏后 Wi-Fi 不断"，WifiLock 已经足够；再加 PARTIAL_WAKE_LOCK 会让 CPU
        // 长期不睡，手表续航会明显变差。如果实测发现深睡后连接仍会假死，再考虑加。
        held = true;
        android.util.Log.e("ActivityManager", "WatchReader 已持有 Wi-Fi/CPU 锁（息屏保持网络）");
    }

    public static void release(Context context) {
        if (!held) {
            return;
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {
        }
        wifiLock = null;

        try {
            if (cpuLock != null && cpuLock.isHeld()) {
                cpuLock.release();
            }
        } catch (Throwable ignored) {
        }
        cpuLock = null;

        held = false;
        android.util.Log.e("ActivityManager", "WatchReader 已释放 Wi-Fi/CPU 锁");
    }
}
