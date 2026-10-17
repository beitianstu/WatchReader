package com.watchreader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一本书：文件路径 + 元信息 + 行索引。
 *
 * 行索引 index = [ [lineStartChar, lineEndChar], ... ]（不含换行符本身），
 * 存成紧凑文本文件放在 App 私有目录，重启后直接复用，不用重扫全文。
 */
public final class Book {

    /** 索引文件版本，格式变了就 +1 让旧缓存自动失效（v2：索引改在"排版文本"上，含段首缩进） */
    private static final int INDEX_VERSION = 2;

    public final File file;
    public final long size;
    public final long mtime;
    public final String title;
    public final String author;
    public final String encoding;
    /** 排版文本（可能含段首缩进），所有偏移都以它为准 */
    public final String text;
    /** 每行 [start, end) */
    public final int[][] index;
    public final long buildMs;

    public Book(File file, long size, long mtime, String title, String author,
                String encoding, String text, int[][] index, long buildMs) {
        this.file = file;
        this.size = size;
        this.mtime = mtime;
        this.title = title;
        this.author = author;
        this.encoding = encoding;
        this.text = text;
        this.index = index;
        this.buildMs = buildMs;
    }

    public int lineCount() {
        return index.length;
    }

    public int charCount() {
        return text.length();
    }

    /** 从文件名推书名/作者：网文常见 "书名(作者).txt" */
    public static String[] parseName(String fileName) {
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        int open = Math.max(stem.lastIndexOf('('), stem.lastIndexOf('（'));
        int close = Math.max(stem.lastIndexOf(')'), stem.lastIndexOf('）'));
        if (open > 0 && close > open) {
            return new String[] { stem.substring(0, open).trim(), stem.substring(open + 1, close).trim() };
        }
        return new String[] { stem.trim(), "" };
    }

    /** 缓存文件名：路径的 SHA-1 前 16 位，避免非法字符 */
    public static String cacheId(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(file.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) {
                sb.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(file.getAbsolutePath().hashCode());
        }
    }

    private static File cacheFile(File cacheDir, File book) {
        return new File(cacheDir, cacheId(book) + ".idx");
    }

    /**
     * 只读索引缓存的头部，拿到正文长度（charCount），不加载整个索引。
     *
     * 书库列表要显示"读到百分之几"，最可靠的做法是 offset / charCount；
     * 但在列表里为每本书调用 open() 会把大书的索引全读进内存（一本 2MB 小说的索引约 3MB），
     * 明显不划算。缓存头部第二行就是 charCount，读几十字节即可。
     *
     * 缓存格式（见 writeCache）：
     *   第 1 行  version|fileSize|mtime|encoding
     *   第 2 行  charCount
     *   第 3 行  indexCount
     *
     * @return 正文长度；缓存不存在、版本不符或文件已被替换时返回 -1
     */
    public static int cachedCharCount(File cacheDir, File book) {
        int count = readCachedCharCount(cacheDir, book);
        return count > 0 ? count : -1;
    }

    /**
     * 只读缓存头部，快速判断"这次打开能不能复用已建索引"。
     * 用于加载提示如实显示阶段（复用索引 vs 首次建索引），不读索引与正文。
     */
    public static boolean hasFreshCache(File cacheDir, File book) {
        return readCachedCharCount(cacheDir, book) >= 0;
    }

    /** @return 头部里的正文长度；缓存不存在/版本不符/文件已变/损坏时返回 -1 */
    private static int readCachedCharCount(File cacheDir, File book) {
        if (cacheDir == null || book == null) {
            return -1;
        }
        File cache = cacheFile(cacheDir, book);
        if (!cache.isFile()) {
            return -1;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(cache), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                return -1;
            }
            String[] parts = header.split("\\|");
            if (parts.length < 3 || Integer.parseInt(parts[0]) != INDEX_VERSION) {
                return -1;
            }
            // 头部里的 size/mtime 必须与当前文件一致，否则说明缓存已过期
            if (Long.parseLong(parts[1]) != book.length()
                    || Long.parseLong(parts[2]) != book.lastModified()) {
                return -1;
            }
            String countLine = reader.readLine();
            if (countLine == null) {
                return -1;
            }
            return Integer.parseInt(countLine.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** {@link #open(File, File, LoadListener)} 回调的阶段 */
    public enum LoadPhase {
        /** 缓存头部有效，本次会复用已建索引（通常很快） */
        FROM_CACHE,
        /** 需要解码 + 重建索引（首次打开、书变了、缓存坏了） */
        BUILD_INDEX
    }

    /** 加载阶段回调：让界面显示真实阶段，而不是永远写"正在建立索引" */
    public interface LoadListener {
        void onPhase(LoadPhase phase);
    }

    /**
     * 打开一本书：能命中缓存就秒开，否则解码 + 建行索引并落盘。
     * 时间戳/大小对不上（文件被换过）会自动重建。
     */
    public static Book open(File file, File cacheDir) throws IOException {
        return open(file, cacheDir, null);
    }

    /**
     * 带阶段回调的打开。
     *
     * 回调会先根据**缓存头部**给出预期阶段（复用/重建）；若头部看着有效、真正解析时
     * 才发现损坏，会再补一次 BUILD_INDEX —— 界面提示因此始终与实际做的工作一致。
     */
    public static Book open(File file, File cacheDir, LoadListener listener) throws IOException {
        long size = file.length();
        long mtime = file.lastModified();
        File cache = cacheFile(cacheDir, file);

        boolean freshHeader = hasFreshCache(cacheDir, file);
        if (listener != null) {
            listener.onPhase(freshHeader ? LoadPhase.FROM_CACHE : LoadPhase.BUILD_INDEX);
        }

        Book cached = tryLoadCache(file, cache, size, mtime);
        if (cached != null) {
            return cached;
        }
        if (listener != null && freshHeader) {
            listener.onPhase(LoadPhase.BUILD_INDEX);
        }

        long started = System.currentTimeMillis();
        TextDecoder.Decoded decoded = TextDecoder.decode(file);
        String normalized = normalize(decoded.text);
        // 排版文本：段首插两个全角缩进。索引和取页都基于它，缩进即"字宽"的一部分
        String layout = toLayoutText(normalized);
        int[][] index = buildLayoutIndex(layout);
        long buildMs = System.currentTimeMillis() - started;

        String[] name = parseName(file.getName());
        Book book = new Book(file, size, mtime, name[0], name[1], decoded.encoding, layout, index, buildMs);
        writeCache(cache, book);
        return book;
    }

    /**
     * 读索引缓存。
     *
     * 实现要点（都是实测踩出来的）：
     *  1. **一次性读入内存再单遍解析** —— 原来用 RandomAccessFile.readLine() 逐行解析 4 万条索引，
     *     在手表上很慢；缓存的意义就是"秒开"，不能被解析本身拖垮。
     *  2. 任何一个环节不满足都直接重建，并**记下具体原因**（之前这里是静默 return null，
     *     导致"明明有缓存却每次重建"查不出原因）。
     */
    private static Book tryLoadCache(File file, File cache, long size, long mtime) {
        if (!cache.isFile()) {
            logCacheMiss("索引缓存不存在");
            return null;
        }

        try {
            byte[] raw = java.nio.file.Files.readAllBytes(cache.toPath());
            if (raw.length < 16) {
                logCacheMiss("缓存文件过小(" + raw.length + "B)");
                return null;
            }

            // 第 1 遍：数出 '\n' 个数，按**实际行数**分配行起点表（F7）。
            // 旧实现写死 40 万行上限：段落行数多的书（约 >20 万行）缓存永远读不出来，
            // 每次打开都整本重建。行数天然上界是 raw.length（每行至少 1 字节），
            // 所以既不按"文件大小"乱估，也不会因为文件大就误判。
            int newlines = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == '\n') {
                    newlines++;
                }
            }
            int lineCountTotal = newlines + 1;          // 末尾可能还有半行
            if (lineCountTotal > raw.length) {          // 只可能来自损坏的缓存
                logCacheMiss("缓存行数异常(" + lineCountTotal + " > " + raw.length + "B)");
                return null;
            }

            // 第 2 遍：单遍扫出每行起始（比逐行 readLine 快得多）
            int[] starts = new int[lineCountTotal + 1];  // +1 给哨兵
            starts[0] = 0;
            int lineNo = 1;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == '\n') {
                    starts[lineNo++] = i + 1;
                }
            }
            // 末尾补一个哨兵，方便统一用 (starts[k], starts[k+1]-1) 取第 k 行
            starts[lineCountTotal] = raw.length;

            // 第 0 行：版本|大小|时间戳|编码
            String header = new String(raw, starts[0], starts[1] - starts[0] - 1, StandardCharsets.UTF_8);
            String[] parts = header.split("\\|");
            if (parts.length < 4) {
                logCacheMiss("头部格式异常");
                return null;
            }
            int version = Integer.parseInt(parts[0].trim());
            long cachedSize = Long.parseLong(parts[1].trim());
            long cachedMtime = Long.parseLong(parts[2].trim());
            if (version != INDEX_VERSION) {
                logCacheMiss("索引版本变化 " + version + "→" + INDEX_VERSION);
                return null;
            }
            if (cachedSize != size) {
                logCacheMiss("文件大小变化 " + cachedSize + "→" + size);
                return null;
            }
            if (cachedMtime != mtime) {
                logCacheMiss("文件修改时间变化 " + cachedMtime + "→" + mtime);
                return null;
            }
            String encoding = parts[3].trim();

            int charCount = Integer.parseInt(
                    new String(raw, starts[1], starts[2] - starts[1] - 1, StandardCharsets.UTF_8).trim());
            int indexCount = Integer.parseInt(
                    new String(raw, starts[2], starts[3] - starts[2] - 1, StandardCharsets.UTF_8).trim());

            // 索引行紧随其后；再往后是 ###TEXT### 标记与正文
            // 行号：0=头部 1=字符数 2=条数 3..3+条数-1=索引 3+条数=标记 4+条数=正文起始
            if (indexCount <= 0 || 4 + indexCount > lineCountTotal) {
                logCacheMiss("索引条数与文件行数不匹配 (" + indexCount + " 条 / " + lineCountTotal + " 行)");
                return null;
            }

            String marker = new String(raw, starts[3 + indexCount],
                    starts[4 + indexCount] - starts[3 + indexCount] - 1, StandardCharsets.UTF_8);
            if (!marker.startsWith("###TEXT###")) {
                logCacheMiss("缺少正文标记，实际为「" + marker + "」");
                return null;
            }

            int[][] index = new int[indexCount][];
            for (int i = 0; i < indexCount; i++) {
                int from = starts[3 + i];
                int to = starts[4 + i] - 1;   // 去掉换行
                int tab = -1;
                for (int p = from; p < to; p++) {
                    if (raw[p] == '\t') {
                        tab = p;
                        break;
                    }
                }
                if (tab < 0 || to <= from) {
                    logCacheMiss("第 " + i + " 条索引格式异常");
                    return null;
                }
                index[i] = new int[] {
                        parseInt(raw, from, tab),
                        parseInt(raw, tab + 1, to),
                };
            }

            int textStart = starts[4 + indexCount];
            String text = new String(raw, textStart, raw.length - textStart, StandardCharsets.UTF_8);

            if (text.length() != charCount) {
                logCacheMiss("正文长度不符 " + text.length() + "≠" + charCount);
                return null;
            }
            if (!indexIsConsistent(text, index)) {
                logCacheMiss("索引与正文不自洽 (lines=" + indexCount + " chars=" + text.length() + ")");
                return null;
            }

            String[] name = parseName(file.getName());
            return new Book(file, size, mtime, name[0], name[1], encoding, text, index, 0);
        } catch (Throwable e) {
            logCacheMiss("解析异常: " + e);
            return null;
        }
    }

    private static int parseInt(byte[] raw, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            int c = raw[i] - '0';
            if (c < 0 || c > 9) {
                continue;
            }
            value = value * 10 + c;
        }
        return value;
    }

    private static void logCacheMiss(String reason) {
        android.util.Log.e("ActivityManager", "WatchReader 缓存未命中: " + reason);
    }

    private static void writeCache(File cache, Book book) {
        File tmp = new File(cache.getAbsolutePath() + ".tmp");
        try {
            // 先写临时文件并**关闭流**：Windows 上文件流未关闭时 rename/delete 会失败
            // （旧的实现把 rename 放在 try-with-resources 里面，于是桌面环境永远写不出缓存，
            //   而且先删旧缓存、失败后连旧缓存也没了 —— F12/A1）。
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                StringBuilder sb = new StringBuilder(book.text.length() / 4 + 1024);
                sb.append(INDEX_VERSION).append('|').append(book.size).append('|').append(book.mtime)
                  .append('|').append(book.encoding).append('\n');
                sb.append(book.text.length()).append('\n');
                sb.append(book.index.length).append('\n');
                for (int[] line : book.index) {
                    sb.append(line[0]).append('\t').append(line[1]).append('\n');
                }
                sb.append("###TEXT###\n").append(book.text);
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            // 原子替换：REPLACE_EXISTING 直接覆盖目标，不先删旧缓存，
            // 于是"新缓存没装上、旧缓存也没了"的窗口不存在。
            java.nio.file.Files.move(tmp.toPath(), cache.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            tmp.delete();
        }
    }

    /** 索引自洽性校验：行区间必须递增、不越界、且与正文长度吻合 */
    private static boolean indexIsConsistent(String text, int[][] index) {
        if (index.length == 0) {
            return text.length() == 0;
        }
        int previousStart = -1;
        for (int i = 0; i < index.length; i++) {
            int s = index[i][0];
            int e = index[i][1];
            if (s < 0 || e < s || e > text.length()) {
                return false;
            }
            if (s < previousStart) {
                return false;
            }
            previousStart = s;
        }
        // 末行必须落在正文末尾附近（排版文本末尾一定有一个换行或直接到 EOF）
        int lastEnd = index[index.length - 1][1];
        return text.length() - lastEnd <= 4;
    }

    /** 规范化：换行统一 \n（LF/CRLF 同样处理）、去 BOM/NUL，连续 3 个以上空行压成 2 个 */
    public static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int blankStreak = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean newline = (c == '\n');
            if (c == '\r') {
                // CRLF 与单个 CR 都当换行，并且要和 '\n' 走同一套空行压缩计数（M1）：
                // 旧实现在这里把 blankStreak 清零，于是 CRLF 文件的连续空行完全不被压缩。
                newline = true;
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    i++;
                }
            }
            if (newline) {
                blankStreak++;
                if (blankStreak <= 2) {
                    sb.append('\n');
                }
                continue;
            }
            blankStreak = 0;
            if (c == '\uFEFF' || c == '\0') {
                continue;
            }
            if (c < 0x20 && c != '\t') {
                continue;
            }
            if (c == '\u00A0') {
                sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 每行存 [start, end)；段落之间插空行会把 offset 一起处理掉 */
    private static int[][] buildLineIndex(String text) {
        List<int[]> lines = new ArrayList<>(Math.max(64, text.length() / 24));
        boolean atParagraphStart = true;
        int lineStart = 0;
        int i = 0;
        int n = text.length();

        while (i <= n) {
            if (i == n || text.charAt(i) == '\n') {
                lines.add(new int[] { lineStart, i });
                if (i == n) {
                    break;
                }
                i++;
                lineStart = i;
                atParagraphStart = true;
                continue;
            }
            if (atParagraphStart && text.charAt(i) != '\n') {
                atParagraphStart = false;
            }
            i++;
        }

        return lines.toArray(new int[0][]);
    }

    /** 按布局生成带段首缩进的排版文本（缩进计入行宽，取行时只切片） */
    public static String toLayoutText(String normalized) {
        StringBuilder sb = new StringBuilder(normalized.length() + normalized.length() / 40 + 8);
        boolean newParagraph = true;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\n') {
                sb.append(c);
                newParagraph = true;
                continue;
            }
            if (newParagraph) {
                sb.append('\u3000').append('\u3000');
                newParagraph = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 在排版文本上重建行索引（缩进后索引必须重算） */
    public static int[][] buildLayoutIndex(String layoutText) {
        List<int[]> lines = new ArrayList<>(Math.max(64, layoutText.length() / 24));
        int lineStart = 0;
        for (int i = 0; i <= layoutText.length(); i++) {
            if (i == layoutText.length() || layoutText.charAt(i) == '\n') {
                lines.add(new int[] { lineStart, i });
                lineStart = i + 1;
            }
        }
        return lines.toArray(new int[0][]);
    }

    /** 扫描出的候选书（还没解码，只读目录元信息） */
    public static final class Candidate implements Comparable<Candidate> {
        public final File file;
        public final long size;

        Candidate(File file) {
            this.file = file;
            this.size = file.length();
        }

        @Override
        public int compareTo(Candidate other) {
            return Long.compare(other.file.lastModified(), this.file.lastModified());
        }
    }

    /** 扫描目录下的 .txt（可递归），按修改时间倒序 */
    public static List<Candidate> scan(File dir, boolean recursive) {
        List<Candidate> result = new ArrayList<>();
        scanInto(dir, recursive, result);
        Collections.sort(result);
        return result;
    }

    private static void scanInto(File dir, boolean recursive, List<Candidate> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (recursive && !f.getName().startsWith(".")) {
                    scanInto(f, recursive, out);
                }
                continue;
            }
            String name = f.getName().toLowerCase(Locale.US);
            if ((name.endsWith(".txt") || name.endsWith(".text")) && f.length() > 512) {
                out.add(new Candidate(f));
            }
        }
    }

    static Charset utf8() {
        return StandardCharsets.UTF_8;
    }
}
