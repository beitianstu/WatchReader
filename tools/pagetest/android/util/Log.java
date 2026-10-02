package android.util;

/** Log stub：桌面测试时把日志打到 stdout，便于观察 */
public final class Log {
    private Log() { }

    public static int e(String tag, String msg) {
        System.out.println("[E/" + tag + "] " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        System.out.println("[E/" + tag + "] " + msg + " :: " + t);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("[W/" + tag + "] " + msg);
        return 0;
    }

    public static int i(String tag, String msg) {
        System.out.println("[I/" + tag + "] " + msg);
        return 0;
    }
}