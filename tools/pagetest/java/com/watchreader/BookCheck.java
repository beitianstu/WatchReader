package com.watchreader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * F7 / A1 / M1 回归（纯桌面，不需要手表）：
 *   M1  Book.normalize()：CRLF / CR 的连续空行压缩必须与 LF 完全一致；
 *   F7  合法的高行数索引缓存必须能被 readLoadCache 读出来（第二次打开 buildMs==0），
 *       旧实现被写死的 40 万行上限挡掉，导致这类书每次打开都整本重建；
 *   A1  writeCache 必须先关流再替换（否则 Windows 上 rename 失败、缓存永远写不出来）；
 *       这里靠"缓存文件确实生成 + 第二次命中"间接验证。
 *   损坏校验仍然有效：把 charCount 改坏后必须判过期并重建。
 *
 * 用法：tools\pagetest\run.ps1
 */
public final class BookCheck {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        checkNormalize();
        checkLargeLineCountCache();
        System.out.println();
        if (failures == 0) {
            System.out.println("=== BookCheck 全部通过（LF/CRLF 归一、合法大行数缓存、损坏校验）===");
        } else {
            System.out.println("=== BookCheck 有 " + failures + " 项失败 ===");
            System.exit(1);
        }
    }

    // ---------------- M1 ----------------

    static void checkNormalize() {
        System.out.println("== M1: Book.normalize 换行归一 ==");
        String lf = Book.normalize("a\n\n\n\nb");
        check("LF 连续空行压缩为 2", "a\n\nb", lf);
        check("CRLF 与 LF 结果一致", lf, Book.normalize("a\r\n\r\n\r\n\r\nb"));
        check("CR 与 LF 结果一致", lf, Book.normalize("a\r\r\r\rb"));
        check("普通 CRLF 段落不被破坏", "第一段\n第二段\n", Book.normalize("第一段\r\n第二段\r\n"));
        System.out.println("  [normalize] LF/CRLF/CR 结果一致  " + (failures == 0 ? "PASS" : "见上"));
    }

    // ---------------- F7 / A1 ----------------

    static void checkLargeLineCountCache() throws Exception {
        System.out.println("== F7/A1: 合法大行数索引缓存 ==");
        File tmp = File.createTempFile("wr-bookcheck", ".txt");
        File cacheDir = new File(tmp.getParentFile(), "wr-bookcheck-cache-" + System.nanoTime());
        cacheDir.mkdirs();
        File cache = new File(cacheDir, Book.cacheId(tmp) + ".idx");
        try {
            int lines = 210000;
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (int i = 0; i < lines; i++) {
                    w.write("abcd\n");
                }
            }

            Book first = Book.open(tmp, cacheDir);
            check("首次打开拿到正文", true, first.charCount() > 0);
            check("缓存文件已生成（A1：流关闭后再替换）", true, cache.isFile());
            int cacheLines = countNewlines(cache);
            check("缓存行数 > 40 万（旧实现的硬上限）", true, cacheLines > 400000);

            Book second = Book.open(tmp, cacheDir);
            check("合法大行数缓存第二次命中 buildMs==0（F7）", 0L, second.buildMs);
            check("两次打开正文长度一致", first.charCount(), second.charCount());
            check("两次打开索引条数一致", first.index.length, second.index.length);

            // 损坏校验：把 charCount 行改一个数字 -> 必须判过期并重建，然后重新可命中
            byte[] raw = Files.readAllBytes(cache.toPath());
            int nl1 = indexOf(raw, (byte) '\n', 0);
            int nl2 = indexOf(raw, (byte) '\n', nl1 + 1);
            if (nl1 > 0 && nl2 > nl1 + 1) {
                int p = nl1 + 1;
                raw[p] = (raw[p] == (byte) '9') ? (byte) '8' : (byte) (raw[p] + 1);
                Files.write(cache.toPath(), raw);
                Book third = Book.open(tmp, cacheDir);
                check("被改坏 charCount 的缓存判过期并重建（F7 保留校验）", true, third.buildMs > 0);
                Book fourth = Book.open(tmp, cacheDir);
                check("重建后的缓存再次命中 buildMs==0", 0L, fourth.buildMs);
            } else {
                fail("缓存头部结构异常，无法做损坏校验");
            }

            System.out.println("  [大行数缓存] 源行数=" + lines + " 缓存行数=" + cacheLines
                    + " 正文长度=" + first.charCount() + " buildMs: 首次=" + first.buildMs
                    + " 命中=" + second.buildMs + "  PASS");
        } finally {
            deleteRecursively(cacheDir);
            tmp.delete();
        }
    }

    // ---------------- 工具 ----------------

    static int indexOf(byte[] raw, byte value, int from) {
        for (int i = Math.max(0, from); i < raw.length; i++) {
            if (raw[i] == value) {
                return i;
            }
        }
        return -1;
    }

    static int countNewlines(File file) throws Exception {
        byte[] raw = Files.readAllBytes(file.toPath());
        int n = 0;
        for (byte b : raw) {
            if (b == '\n') {
                n++;
            }
        }
        return n;
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    static void check(String name, Object expected, Object actual) {
        String e = String.valueOf(expected);
        String a = String.valueOf(actual);
        if (!e.equals(a)) {
            fail(name + "：期望 " + e + "，实际 " + a);
        } else {
            System.out.println("  [OK] " + name);
        }
    }

    static void fail(String detail) {
        failures++;
        System.out.println("  [FAIL] " + detail);
    }
}
