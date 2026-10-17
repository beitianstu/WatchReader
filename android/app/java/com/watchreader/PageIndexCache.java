package com.watchreader;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 页码索引缓存：把"某个版面下每一页的起始字符"存起来，下次打开直接复用。
 *
 * 为什么需要它：
 *   行索引（{@link Book}）解决的是"解码 + 断行"，但"第 N 页从哪个字开始"是按版面算出来的。
 *   打开一本读到 75% 的书时，恢复进度要先从第 0 页顺序量到目标页（实测 2MB 书 = 964 页、
 *   约 2.1 万次 breakText），这是缓存命中后**唯一**的大块排版开销，而且发生在 UI 线程。
 *   把量过的页码起点存下来，下次就是二分查找 —— 版面没变就不用再量第二遍。
 *
 * 安全边界（任何一条不满足就当作没有缓存，退回顺序分页，绝不使用可疑页码）：
 *   · 页码格式版本一致（分页算法改了要 +1）；
 *   · 传入的 {@link Book} 快照与当前文件一致（fileSize / lastModified / charCount）；
 *     —— 盖章与校验都只用 Book 自己的快照，不用"调用当下"的文件元信息，
 *        这样阅读期间源被替换时，旧书的页码不会挂到新书头上；
 *   · 版面 key 一致（字号/字体/实测字号/可用宽高/行距，见 ReaderView.layoutKey）；
 *   · 数组本身自洽：起点递增、不越界、第 0 页起点为 0；
 *   · 文件体积 < 8MiB（先看长度再读，坏掉的大缓存不会被整块读进内存）。
 *
 * 纯逻辑 + 简单文本格式，桌面测试可直接跑（tools/loadtest）。
 */
final class PageIndexCache {

    /** 页码缓存格式版本：分页/版面语义变了就 +1，让旧文件自动失效 */
    private static final int VERSION = 1;

    /** 页码缓存的体积上限：超过就当作坏文件（正常 10MiB 书约 1.3 万页 ≈ 100KB） */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    static final String EXT = ".pages";

    private PageIndexCache() {
    }

    static File fileFor(File cacheDir, File book) {
        return new File(cacheDir, Book.cacheId(book) + EXT);
    }

    /**
     * 源文件是否已经和这份 {@link Book} 的快照（打开那一刻的 size/mtime）不一致。
     *
     * 页码起点是**由 Book.text 推导**出来的，只能盖 Book 自己的快照章；
     * 用"当前 File 的 length/lastModified"盖章会在阅读期间源被替换时，
     * 把旧书的页码挂到新书头上（同字数新文件尤其危险）。
     */
    private static boolean sourceChanged(Book book) {
        return book.file.length() != book.size || book.file.lastModified() != book.mtime;
    }

    /**
     * 读页码缓存。
     *
     * 必须传入**打开时的 Book**：头部校验用的是它的 size/mtime/charCount 快照。
     * 源文件已经被替换（快照与当前文件不一致）时拒绝加载，返回 null。
     *
     * @return 长度 = 已缓存页数的"页起始字符"数组（下标即页码）；不可用时返回 null
     */
    static int[] load(File cacheDir, Book book, String layoutKey) {
        if (cacheDir == null || book == null || layoutKey == null
                || book.charCount() <= 0 || sourceChanged(book)) {
            return null;
        }
        int charCount = book.charCount();
        File cache = fileFor(cacheDir, book.file);
        if (!cache.isFile()) {
            return null;
        }
        try {
            // 先看文件长度再读：损坏的超大缓存不该先被整块分配进内存
            long len = cache.length();
            if (len < 32 || len > MAX_BYTES) {
                return null;
            }
            byte[] raw = Files.readAllBytes(cache.toPath());
            String[] lines = new String(raw, StandardCharsets.UTF_8).split("\n");
            if (lines.length < 2) {
                return null;
            }
            // 头部用制表符分隔并限制成 5 段：layoutKey 里可能有 '|'（见 ReaderView.layoutKey），
            // 用 '|' 会被拆散、校验永远失败（这是踩过的坑）。
            String[] header = lines[0].split("\t", 5);
            if (header.length < 5) {
                return null;
            }
            if (Integer.parseInt(header[0].trim()) != VERSION
                    || Long.parseLong(header[1].trim()) != book.size
                    || Long.parseLong(header[2].trim()) != book.mtime
                    || Integer.parseInt(header[3].trim()) != charCount
                    || !layoutKey.equals(header[4])) {
                return null;
            }
            int count = Integer.parseInt(lines[1].trim());
            // 用 lines.length - 2 而不是 2 + count：count 被写成 Integer.MAX_VALUE 时
            // "2 + count" 会溢出成负数，旧写法会放过它去 new int[count]（OOM）。
            // count > charCount 也不可能：起点必须严格递增且都小于 charCount。
            if (count <= 0 || count > lines.length - 2 || count > charCount) {
                return null;
            }
            int[] starts = new int[count];
            for (int i = 0; i < count; i++) {
                starts[i] = Integer.parseInt(lines[2 + i].trim());
            }
            if (!isSane(starts, charCount)) {
                return null;
            }
            return starts;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 起点必须从 0 开始、严格递增、且落在正文范围内 */
    static boolean isSane(int[] starts, int charCount) {
        return isSane(starts, charCount, starts.length);
    }

    /**
     * 原子写入（先写 .tmp、关流、再 move 覆盖），失败只留下临时文件，不破坏旧缓存。
     *
     * 头部盖的是传入 {@link Book} 的**打开快照**；若源文件已经变了（快照与当前文件不一致），
     * 直接拒绝保存 —— 这些页码对"当前的文件"没有意义，不能盖章。
     */
    static void save(File cacheDir, Book book, String layoutKey, int[] starts, int count) {
        if (cacheDir == null || book == null || layoutKey == null || starts == null
                || book.charCount() <= 0 || sourceChanged(book)
                || count <= 1 || count > starts.length
                || !isSane(starts, book.charCount(), count)) {
            return;
        }
        int charCount = book.charCount();
        File cache = fileFor(cacheDir, book.file);
        File tmp = new File(cache.getAbsolutePath() + ".tmp");
        try {
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                return;
            }
            StringBuilder sb = new StringBuilder(count * 8 + 128);
            sb.append(VERSION).append('\t').append(book.size).append('\t').append(book.mtime)
              .append('\t').append(charCount).append('\t').append(layoutKey).append('\n');
            sb.append(count).append('\n');
            for (int i = 0; i < count; i++) {
                sb.append(starts[i]).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable e) {
            tmp.delete();
        }
    }

    /** 前缀自洽校验：只检查前 count 项（保存时数组可能比 count 长） */
    private static boolean isSane(int[] starts, int charCount, int count) {
        if (count <= 0 || count > starts.length || starts[0] != 0) {
            return false;
        }
        for (int i = 1; i < count; i++) {
            if (starts[i] <= starts[i - 1] || starts[i] >= charCount) {
                return false;
            }
        }
        return true;
    }

    /** 删掉某本书的页码缓存（书被删除/进度被清时调用；删不掉也不影响功能） */
    static void delete(File cacheDir, File book) {
        if (cacheDir == null || book == null) {
            return;
        }
        File cache = fileFor(cacheDir, book);
        if (cache.isFile()) {
            cache.delete();
        }
        new File(cache.getAbsolutePath() + ".tmp").delete();
    }
}
