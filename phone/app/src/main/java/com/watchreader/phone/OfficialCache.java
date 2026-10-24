package com.watchreader.phone;

import android.content.Context;
import android.text.Html;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 只读导入番茄官方 App 已经下载到本机的章节缓存。
 *
 * <p>缓存格式（v5.5.3.99 实测）：{@code 短 ASCII 头 + JSON}；JSON 的 {@code content}
 * 是 base64(16 字节 IV + AES-128-CBC 密文)，解密后按需 gzip，得到 XHTML。
 * 元数据（书名/章节名/key_version）是明文，只有正文需要密钥。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只读 {@code /data/user/0/com.dragon.read/files} 下严格数字命名的缓存目录，
 *       以及同一数字目录对应的 {@code mmkv/prefix_public_crypt_key_kv_<uid>}。</li>
 *   <li>不写、不删官方 App 目录；不访问 Cookie、账号资料、其它 mmkv 或凭据文件。</li>
 *   <li>密钥只在本进程内存中使用；导入结束（含失败/取消）后清零 byte[]，不落盘、不打印。</li>
 *   <li>root 命令是固定模板，路径的每一段都先按数字标识白名单校验；不拼接用户命令。</li>
 *   <li>单文件 8 MiB、单章解压 4 MiB、整本 64 MiB/10000 章；每条 root 命令 10 秒超时。</li>
 * </ul>
 *
 * <p>本类不负责把结果放进书库；调用方拿到临时 TXT 后应使用
 * {@link LibraryFiles#importFrom} 做原子、不覆盖的导入，并删除临时文件。
 */
public final class OfficialCache {

    /** 官方 App 私有缓存根目录；只在这里做固定模板的只读操作。 */
    private static final String FILES_ROOT = "/data/user/0/com.dragon.read/files";
    private static final int MAX_JSON_START = 64;

    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CHAPTER_TEXT = 4 * 1024 * 1024;
    public static final long MAX_BOOK_BYTES = 64L * 1024 * 1024;
    public static final int MAX_CHAPTERS = 10000;
    public static final long ROOT_TIMEOUT_MS = 10_000L;

    private static final int MAX_KEY_FILE_BYTES = 64 * 1024;
    private static final int MAX_LS_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 8 * 1024;
    private static final byte[] CHAPTER_SEPARATOR = "\n\n".getBytes(StandardCharsets.UTF_8);

    private static final Pattern UID_DIR = Pattern.compile("\\d{1,20}");
    private static final Pattern BOOK_DIR = Pattern.compile("prefix_public_(\\d{1,20})");
    private static final Pattern CACHE_FILE = Pattern.compile("-?\\d{1,32}");
    private static final Pattern KEY_FILE = Pattern.compile("prefix_public_crypt_key_kv_(\\d{1,20})");
    private static final Pattern KEY_PAIR =
            Pattern.compile("key_(\\d+)!\\s*([0-9A-Fa-f]{32})");
    private static final Pattern TITLE_ORDER = Pattern.compile(
            "第\\s*([0-9]{1,9}|[零〇○一二两三四五六七八九十百千万]{1,12})\\s*[章节回卷]");

    /** 只有明确表示“第几章/第几节”的字段才参与排序；chapter_id/item_id 只作身份。 */
    private static final String[] ORDER_FIELDS = {
            "chapter_index", "chapterIndex", "serial_number", "serialNumber", "serial_no", "serialNo",
    };

    private OfficialCache() {
    }

    /** 进度回调；在后台线程调用，回调内容不含用户 ID、密钥或正文。 */
    public interface Progress {
        void onProgress(String text);
    }

    /** 可以安全展示给用户的失败原因；不包装原始 su 输出或文件路径。 */
    public static final class CacheException extends Exception {
        public CacheException(String message) {
            super(message);
        }
    }

    /** 扫描结果。rootAvailable=false 时 books 为空，界面应显示“不可用”并允许退出。 */
    public static final class ScanResult {
        public final boolean rootAvailable;
        public final List<CacheBook> books;
        public final String message;

        ScanResult(boolean rootAvailable, List<CacheBook> books, String message) {
            this.rootAvailable = rootAvailable;
            this.books = books;
            this.message = message;
        }
    }

    /** 一本书的缓存目录；uid 只用于继续读取同一账号的密钥，绝不展示或输出。 */
    public static final class CacheBook {
        final String uid;
        public final String bookId;
        public final String name;
        public final int fileCount;

        CacheBook(String uid, String bookId, String name, int fileCount) {
            this.uid = uid;
            this.bookId = bookId;
            this.name = name;
            this.fileCount = fileCount;
        }
    }

    /** 导入统计。readable 是实际列出的缓存文件数，不冒充全书总章数。 */
    public static final class ImportResult {
        public final File textFile;
        public final int readable;
        public final int ok;
        public final int failed;
        public final int missingKey;
        public final int corrupt;
        public final int duplicates;
        public final boolean orderReliable;

        ImportResult(File textFile, int readable, int ok, int failed, int missingKey,
                     int corrupt, int duplicates, boolean orderReliable) {
            this.textFile = textFile;
            this.readable = readable;
            this.ok = ok;
            this.failed = failed;
            this.missingKey = missingKey;
            this.corrupt = corrupt;
            this.duplicates = duplicates;
            this.orderReliable = orderReliable;
        }
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /** 扫描官方缓存目录。全部为阻塞 IO，调用方必须放到后台线程。 */
    public static ScanResult scan(Progress progress) {
        if (!Root.hasRoot()) {
            return new ScanResult(false, Collections.<CacheBook>emptyList(), null);
        }

        List<String> rootEntries = Root.ls(FILES_ROOT);
        if (rootEntries == null) {
            return new ScanResult(true, Collections.<CacheBook>emptyList(),
                    "读取官方缓存目录失败，请确认 root 权限可用");
        }

        List<CacheBook> books = new ArrayList<>();
        try {
            for (String uid : rootEntries) {
                checkCancelled();
                if (!UID_DIR.matcher(uid).matches()) continue;

                List<String> children = Root.ls(FILES_ROOT + "/" + uid);
                if (children == null) continue;

                for (String child : children) {
                    checkCancelled();
                    Matcher bookMatcher = BOOK_DIR.matcher(child);
                    if (!bookMatcher.matches()) continue;

                    String bookId = bookMatcher.group(1);
                    String dir = FILES_ROOT + "/" + uid + "/" + child;
                    List<String> names = Root.ls(dir);
                    if (names == null) continue;

                    List<String> files = new ArrayList<>();
                    for (String name : names) {
                        if (CACHE_FILE.matcher(name).matches()) files.add(name);
                    }
                    if (files.isEmpty()) continue;

                    if (progress != null) {
                        progress.onProgress("正在读取缓存书名…（已发现 " + books.size() + " 本）");
                    }
                    String name = readBookName(dir, files);
                    books.add(new CacheBook(uid, bookId,
                            name.isEmpty() ? "未命名缓存书" : name, files.size()));
                }
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException error) {
            return new ScanResult(true, Collections.<CacheBook>emptyList(), "扫描官方缓存失败");
        }

        Collections.sort(books, new Comparator<CacheBook>() {
            @Override
            public int compare(CacheBook left, CacheBook right) {
                return left.name.compareTo(right.name);
            }
        });
        return new ScanResult(true, books, null);
    }

    /** 最多试 3 个文件，取第一个带 book_name 的有效 JSON；避免为扫一本书读整目录。 */
    private static String readBookName(String dir, List<String> files) {
        int tried = 0;
        for (String file : files) {
            if (tried >= 3) break;
            checkCancelled();
            byte[] raw = Root.cat(dir + "/" + file, MAX_FILE_BYTES);
            tried++;
            if (raw == null) continue;
            try {
                JSONObject object = parseObject(raw);
                if (object == null) continue;
                String name = cleanDisplay(object.optString("book_name", ""));
                if (!name.isEmpty()) return name;
            } catch (Exception ignored) {
                // 单个文件损坏不应中断整本书/整个列表；继续试下一个。
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 把一本书的已下载章节解码成一个临时 UTF-8 TXT。成功时调用方负责用
     * {@link LibraryFiles#importFrom} 导入并删除 {@link ImportResult#textFile}。
     */
    public static ImportResult importBook(Context context, CacheBook book, Progress progress)
            throws CacheException {
        checkCancelled();

        List<String> names = Root.ls(FILES_ROOT + "/" + book.uid + "/prefix_public_" + book.bookId);
        if (names == null) throw new CacheException("无法列出这本书的缓存文件");

        List<String> files = new ArrayList<>();
        for (String name : names) {
            if (CACHE_FILE.matcher(name).matches()) files.add(name);
        }
        if (files.isEmpty()) throw new CacheException("这本书没有可读的缓存文件");
        if (files.size() > MAX_CHAPTERS) {
            throw new CacheException("缓存章数超过 " + MAX_CHAPTERS + "，已拒绝整本导入");
        }

        Map<String, byte[]> keys = readKeys(book.uid);
        try {
            return decodeBook(context, book, files, keys, progress);
        } finally {
            clearKeys(keys);
        }
    }

    private static ImportResult decodeBook(Context context, CacheBook book, List<String> files,
                                           Map<String, byte[]> keys, Progress progress)
            throws CacheException {
        List<ChapterMeta> chapters = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        int missingKey = 0;
        int corrupt = 0;
        long firstPassBytes = 0;
        String dir = FILES_ROOT + "/" + book.uid + "/prefix_public_" + book.bookId;

        // 第一遍只读元数据：确定去重、缺密钥和排序，不保留正文，避免整本正文驻留内存。
        for (int index = 0; index < files.size(); index++) {
            checkCancelled();
            if (progress != null) {
                progress.onProgress("读取元数据 " + (index + 1) + "/" + files.size());
            }
            String file = files.get(index);
            byte[] raw = Root.cat(dir + "/" + file, MAX_FILE_BYTES);
            if (raw == null) {
                corrupt++;
                continue;
            }
            firstPassBytes += raw.length;
            if (firstPassBytes > MAX_BOOK_BYTES) {
                throw new CacheException("缓存总大小超过 64 MiB，已拒绝整本导入");
            }

            ChapterMeta meta;
            try {
                meta = readMeta(raw, book.bookId, file);
            } catch (Exception error) {
                meta = null;
            }
            if (meta == null) {
                corrupt++;
                continue;
            }
            if (!keys.containsKey(meta.keyVersion)) {
                missingKey++;
                continue;
            }
            if (meta.chapterId > 0) {
                String identity = dedupeKey(meta.bookId, meta.chapterId);
                if (!seen.add(identity)) {
                    duplicates++;
                    continue;
                }
            }
            chapters.add(meta);
        }

        if (chapters.isEmpty()) {
            throw new CacheException(failureText(missingKey, corrupt));
        }

        Collections.sort(chapters, new Comparator<ChapterMeta>() {
            @Override
            public int compare(ChapterMeta left, ChapterMeta right) {
                if (left.order != null && right.order != null) {
                    return Integer.compare(left.order, right.order);
                }
                if (left.order != null) return -1;
                if (right.order != null) return 1;
                // 没有真实序号时保持目录读取顺序；此时 orderReliable=false，界面明确提示不可靠。
                return 0;
            }
        });

        boolean orderReliable = true;
        Set<Integer> usedOrders = new HashSet<>();
        for (ChapterMeta meta : chapters) {
            if (meta.order == null || !usedOrders.add(meta.order)) {
                orderReliable = false;
                break;
            }
        }

        File temp = null;
        boolean keep = false;
        int ok = 0;
        try {
            temp = File.createTempFile("fanqie-cache-", ".txt", context.getCacheDir());
            long secondPassBytes = 0;
            try (OutputStream output = new FileOutputStream(temp)) {
                for (int index = 0; index < chapters.size(); index++) {
                    checkCancelled();
                    ChapterMeta meta = chapters.get(index);
                    if (progress != null) {
                        progress.onProgress("解密正文 " + (index + 1) + "/" + chapters.size());
                    }
                    byte[] raw = Root.cat(dir + "/" + meta.fileName, MAX_FILE_BYTES);
                    if (raw == null) {
                        corrupt++;
                        continue;
                    }
                    secondPassBytes += raw.length;
                    if (secondPassBytes > MAX_BOOK_BYTES) {
                        throw new CacheException("缓存总大小超过 64 MiB，已拒绝整本导入");
                    }

                    String body;
                    try {
                        JSONObject object = parseObject(raw);
                        if (object == null) {
                            corrupt++;
                            continue;
                        }
                        body = decodeBody(object, keys);
                    } catch (CacheException error) {
                        if (error.getMessage() != null && error.getMessage().startsWith("缺少")) {
                            missingKey++;
                        } else {
                            corrupt++;
                        }
                        continue;
                    } catch (Exception error) {
                        corrupt++;
                        continue;
                    }

                    String title = meta.title == null ? "" : meta.title.trim();
                    String block = chapterBlock(title, body);
                    if (block.isEmpty() || block.equals(title)) {
                        corrupt++;
                        continue;
                    }
                    if (ok > 0) output.write(CHAPTER_SEPARATOR);
                    output.write(block.getBytes(StandardCharsets.UTF_8));
                    ok++;
                }
                output.flush();
            }

            if (ok == 0) {
                throw new CacheException(failureText(missingKey, corrupt));
            }
            int failed = missingKey + corrupt;
            keep = true;
            return new ImportResult(temp, files.size(), ok, failed, missingKey,
                    corrupt, duplicates, orderReliable);
        } catch (IOException error) {
            throw new CacheException("写入临时文件失败");
        } finally {
            if (!keep && temp != null) temp.delete();
        }
    }

    private static String failureText(int missingKey, int corrupt) {
        if (missingKey > 0 && corrupt > 0) {
            return "未解出任何正文：缺少密钥 " + missingKey + " 章、损坏 " + corrupt + " 章";
        }
        if (missingKey > 0) return "未解出任何正文：缺少对应密钥";
        if (corrupt > 0) return "未解出任何正文：缓存损坏或格式不匹配";
        return "未解出任何正文";
    }

    // ------------------------------------------------------------------
    // 缓存文件解析 / 解密
    // ------------------------------------------------------------------

    private static final class ChapterMeta {
        final String fileName;
        final String bookId;
        final long chapterId;
        final String title;
        final String keyVersion;
        final Integer order;

        ChapterMeta(String fileName, String bookId, long chapterId, String title,
                    String keyVersion, Integer order) {
            this.fileName = fileName;
            this.bookId = bookId;
            this.chapterId = chapterId;
            this.title = title;
            this.keyVersion = keyVersion;
            this.order = order;
        }
    }

    private static ChapterMeta readMeta(byte[] raw, String fallbackBookId, String fileName)
            throws Exception {
        JSONObject object = parseObject(raw);
        if (object == null) return null;
        String content = object.optString("content", "");
        if (content == null || content.trim().isEmpty()) return null;

        String bookId = object.optString("book_id", "");
        if (bookId == null || bookId.trim().isEmpty()) bookId = fallbackBookId;
        String title = cleanDisplay(object.optString("name", ""));
        long chapterId = optLong(object, "chapter_id", 0L);
        String keyVersion = object.optString("key_version", "");
        if (keyVersion == null) keyVersion = "";
        keyVersion = keyVersion.trim();

        Integer explicit = explicitOrder(object);
        Integer order = explicit != null ? explicit : chapterOrderFromTitle(title);
        return new ChapterMeta(fileName, bookId, chapterId, title, keyVersion, order);
    }

    /** 从首 64 字节内的第一个 '{' 开始，去掉可能的 Java 序列化尾部。 */
    public static int findJsonStart(byte[] raw) {
        if (raw == null) return -1;
        int limit = Math.min(raw.length, MAX_JSON_START + 1);
        for (int index = 0; index < limit; index++) {
            if (raw[index] == '{') return index;
        }
        return -1;
    }

    /** JSON 文本从首 '{' 到末 '}'（没有 '}' 时到文件尾）；标准 JSON 解析会还原 \u003d。 */
    public static String extractJsonText(byte[] raw, int start) {
        if (raw == null || start < 0 || start >= raw.length) return "";
        int end = raw.length - 1;
        while (end >= start && raw[end] != '}') end--;
        int length = (end >= start ? end : raw.length - 1) - start + 1;
        return new String(raw, start, length, StandardCharsets.UTF_8);
    }

    private static JSONObject parseObject(byte[] raw) throws Exception {
        int start = findJsonStart(raw);
        if (start < 0) return null;
        String json = extractJsonText(raw, start);
        if (json.isEmpty()) return null;
        return new JSONObject(json);
    }

    /** 缓存文件名必须是有符号纯数字；防止把目录项当路径或注入 shell。 */
    public static boolean isCacheFileName(String name) {
        return name != null && CACHE_FILE.matcher(name).matches();
    }

    /** 去重身份：book_id + chapter_id；chapter_id 只作身份，不参与排序。 */
    public static String dedupeKey(String bookId, long chapterId) {
        return bookId + ":" + chapterId;
    }

    private static long optLong(JSONObject object, String key, long fallback) {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Integer explicitOrder(JSONObject object) {
        for (String field : ORDER_FIELDS) {
            Object value = object.opt(field);
            if (value == null || value == JSONObject.NULL) continue;
            long number;
            if (value instanceof Number) {
                number = ((Number) value).longValue();
            } else {
                String text = String.valueOf(value).trim();
                if (!text.matches("\\d{1,9}")) continue;
                number = Long.parseLong(text);
            }
            if (number >= 0 && number <= Integer.MAX_VALUE) return (int) number;
        }
        return null;
    }

    /** 解析“第 N 章/节/回/卷”里的阿拉伯或常见中文数字；没有就返回 null。 */
    public static Integer chapterOrderFromTitle(String title) {
        if (title == null) return null;
        Matcher matcher = TITLE_ORDER.matcher(title);
        if (!matcher.find()) return null;
        long number = parseChineseNumber(matcher.group(1));
        if (number < 0 || number > Integer.MAX_VALUE) return null;
        return (int) number;
    }

    /** 常见中文数字（零〇○一二两三四五六七八九十百千万），也接受纯阿拉伯数字。 */
    public static long parseChineseNumber(String value) {
        if (value == null) return -1L;
        String text = value.trim();
        if (text.isEmpty()) return -1L;
        if (text.matches("\\d{1,9}")) return Long.parseLong(text);

        long total = 0L;
        long section = 0L;
        long number = 0L;
        boolean any = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            int digit = chineseDigit(ch);
            if (digit >= 0) {
                number = digit;
                any = true;
                continue;
            }
            int unit = chineseUnit(ch);
            if (unit == 10000) {
                total += (section + number) * 10000L;
                section = 0L;
                number = 0L;
                any = true;
            } else if (unit > 0) {
                if (number == 0L) number = 1L;
                section += number * unit;
                number = 0L;
                any = true;
            } else {
                return -1L;
            }
        }
        if (!any) return -1L;
        return total + section + number;
    }

    private static int chineseDigit(char ch) {
        switch (ch) {
            case '零':
            case '〇':
            case '○':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }

    private static int chineseUnit(char ch) {
        switch (ch) {
            case '十':
                return 10;
            case '百':
                return 100;
            case '千':
                return 1000;
            case '万':
                return 10000;
            default:
                return 0;
        }
    }

    private static String decodeBody(JSONObject object, Map<String, byte[]> keys)
            throws Exception {
        String content = object.optString("content", "");
        if (content == null || content.trim().isEmpty()) {
            throw new CacheException("缺少正文");
        }
        String keyVersion = object.optString("key_version", "").trim();
        byte[] key = keys.get(keyVersion);
        if (key == null) throw new CacheException("缺少密钥");

        byte[] blob = Base64.decode(content, Base64.DEFAULT);
        if (blob == null || blob.length < 32 || blob.length % 16 != 0) {
            throw new CacheException("密文长度异常");
        }

        byte[] iv = Arrays.copyOfRange(blob, 0, 16);
        byte[] cipherText = Arrays.copyOfRange(blob, 16, blob.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(cipherText);

        byte[] textBytes;
        if (plain.length >= 2 && (plain[0] & 0xff) == 0x1f && (plain[1] & 0xff) == 0x8b) {
            textBytes = gunzipBounded(plain);
        } else {
            if (object.optInt("compress_status", 0) == 1) {
                throw new CacheException("压缩格式不匹配");
            }
            textBytes = plain;
        }
        if (textBytes.length > MAX_CHAPTER_TEXT) {
            throw new CacheException("解压后单章超过 4 MiB");
        }

        String xhtml = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(textBytes)).toString();
        xhtml = xhtml.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", "");
        String body = Html.fromHtml(xhtml, Html.FROM_HTML_MODE_LEGACY).toString();
        body = normalizeBody(body);
        if (body.isEmpty()) throw new CacheException("没有正文");
        return body;
    }

    /** 有界解压：超过单章上限立即失败，绝不无限读 gzip 流。 */
    private static byte[] gunzipBounded(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(MAX_CHAPTER_TEXT, Math.max(1024, compressed.length * 3)));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                total += read;
                if (total > MAX_CHAPTER_TEXT) {
                    throw new IOException("chapter too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /** 统一换行、去行首尾空白，并把连续空行压成最多一个空行。 */
    public static String normalizeBody(String text) {
        if (text == null) return "";
        String normalized = text.replace('\u00a0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder output = new StringBuilder(normalized.length());
        int pendingBreaks = 0;
        for (String rawLine : normalized.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (output.length() > 0) pendingBreaks = 2;
                continue;
            }
            if (output.length() > 0) {
                int breaks = Math.min(pendingBreaks == 0 ? 1 : pendingBreaks, 2);
                for (int index = 0; index < breaks; index++) output.append('\n');
            }
            output.append(line);
            pendingBreaks = 0;
        }
        return output.toString().trim();
    }

    /** 生成一章的 TXT 块；正文开头已是同一标题（h1 转文字）时不再重复标题。 */
    public static void appendChapter(StringBuilder output, String title, String body) {
        String heading = title == null ? "" : title.trim();
        String content = body == null ? "" : body.trim();
        if (content.isEmpty()) return;

        if (!heading.isEmpty()) {
            int newline = content.indexOf('\n');
            String firstLine = (newline < 0 ? content : content.substring(0, newline)).trim();
            if (firstLine.equals(heading)) {
                content = newline < 0 ? "" : content.substring(newline + 1).trim();
            }
        }

        if (output.length() > 0) output.append('\n').append('\n');
        if (!heading.isEmpty()) output.append(heading);
        if (!content.isEmpty()) {
            if (!heading.isEmpty()) output.append('\n').append('\n');
            output.append(content);
        }
    }

    private static String chapterBlock(String title, String body) {
        StringBuilder output = new StringBuilder();
        appendChapter(output, title, body);
        return output.toString();
    }

    // ------------------------------------------------------------------
    // 密钥
    // ------------------------------------------------------------------

    private static Map<String, byte[]> readKeys(String uid) throws CacheException {
        String path = FILES_ROOT + "/mmkv/prefix_public_crypt_key_kv_" + uid;
        byte[] raw = Root.cat(path, MAX_KEY_FILE_BYTES);
        if (raw == null) throw new CacheException("未找到可读的密钥文件，无法解密正文");

        Map<String, byte[]> keys = new HashMap<>();
        try {
            String text = new String(raw, StandardCharsets.UTF_8);
            Matcher matcher = KEY_PAIR.matcher(text);
            while (matcher.find()) {
                String hex = matcher.group(2);
                byte[] key = new byte[16];
                for (int index = 0; index < 16; index++) {
                    key[index] = (byte) Integer.parseInt(
                            hex.substring(index * 2, index * 2 + 2), 16);
                }
                byte[] previous = keys.put(matcher.group(1), key);
                if (previous != null) Arrays.fill(previous, (byte) 0);
            }
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
        if (keys.isEmpty()) throw new CacheException("密钥文件格式不匹配，无法解密正文");
        return keys;
    }

    private static void clearKeys(Map<String, byte[]> keys) {
        if (keys == null) return;
        for (byte[] key : keys.values()) {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
        keys.clear();
    }

    // ------------------------------------------------------------------
    // 固定模板 root 调用
    // ------------------------------------------------------------------

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("已取消");
        }
    }

    private static String cleanDisplay(String raw) {
        if (raw == null) return "";
        StringBuilder output = new StringBuilder(Math.min(raw.length(), 120));
        for (int index = 0; index < raw.length() && output.length() < 120; index++) {
            char ch = raw.charAt(index);
            output.append(Character.isISOControl(ch) ? ' ' : ch);
        }
        return output.toString().trim();
    }

    private static final class Root {
        private Root() {
        }

        static boolean hasRoot() {
            ExecResult result = exec("id -u", 64);
            if (result == null || result.timeout || result.overflow || result.exitCode != 0) {
                return false;
            }
            return new String(result.stdout, StandardCharsets.UTF_8).trim().equals("0");
        }

        static List<String> ls(String path) {
            String safe = requireSafe(path);
            ExecResult result = exec("ls -1 '" + safe + "'", MAX_LS_BYTES);
            if (result == null || result.timeout || result.overflow || result.exitCode != 0) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            String text = new String(result.stdout, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
            return lines;
        }

        static byte[] cat(String path, int maxBytes) {
            String safe = requireSafe(path);
            ExecResult result = exec("cat '" + safe + "'", maxBytes);
            if (result == null || result.timeout || result.overflow || result.exitCode != 0) {
                return null;
            }
            return result.stdout;
        }

        /** 路径只允许固定的官方根 + 数字标识段；任何其它字符都直接拒绝。 */
        private static String requireSafe(String path) {
            if (path == null) throw new IllegalArgumentException("empty path");
            if (!path.equals(FILES_ROOT) && !path.startsWith(FILES_ROOT + "/")) {
                throw new IllegalArgumentException("path outside cache root");
            }
            String rest = path.substring(FILES_ROOT.length());
            if (rest.isEmpty()) return path;
            for (String segment : rest.split("/")) {
                if (segment.isEmpty()) continue;
                if (segment.equals("mmkv")
                        || UID_DIR.matcher(segment).matches()
                        || BOOK_DIR.matcher(segment).matches()
                        || CACHE_FILE.matcher(segment).matches()
                        || KEY_FILE.matcher(segment).matches()) {
                    continue;
                }
                throw new IllegalArgumentException("unsafe path segment");
            }
            return path;
        }

        private static final class ExecResult {
            final int exitCode;
            final byte[] stdout;
            final boolean overflow;
            final boolean timeout;

            ExecResult(int exitCode, byte[] stdout, boolean overflow, boolean timeout) {
                this.exitCode = exitCode;
                this.stdout = stdout;
                this.overflow = overflow;
                this.timeout = timeout;
            }
        }

        private static ExecResult exec(String command, int maxStdout) {
            checkCancelled();
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", command).start();
                final Process running = process;
                final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                final AtomicBoolean overflow = new AtomicBoolean(false);

                Thread outReader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        drain(running.getInputStream(), stdout, maxStdout, overflow);
                    }
                }, "official-cache-out");
                Thread errReader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        drain(running.getErrorStream(), null, MAX_STDERR_BYTES, null);
                    }
                }, "official-cache-err");
                outReader.setDaemon(true);
                errReader.setDaemon(true);
                outReader.start();
                errReader.start();

                boolean finished = false;
                boolean timedOut = false;
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(ROOT_TIMEOUT_MS);
                try {
                    while (!finished) {
                        if (overflow.get()) {
                            process.destroyForcibly();
                            break;
                        }
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            timedOut = true;
                            process.destroyForcibly();
                            break;
                        }
                        finished = process.waitFor(
                                Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                                TimeUnit.NANOSECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new CancellationException("已取消");
                }

                if (!finished) {
                    try {
                        process.waitFor(1, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("已取消");
                    }
                }
                joinQuietly(outReader);
                joinQuietly(errReader);

                if (timedOut) {
                    return new ExecResult(-1, new byte[0], false, true);
                }
                if (process.isAlive()) {
                    // destroyForcibly 后仍未退出：按超时处理，绝不无界等待。
                    return new ExecResult(-1, stdout.toByteArray(), overflow.get(), true);
                }
                int exit = process.exitValue();
                return new ExecResult(exit, stdout.toByteArray(), overflow.get(), false);
            } catch (IOException error) {
                if (process != null) process.destroyForcibly();
                return null;
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        }

        private static void drain(InputStream input, ByteArrayOutputStream sink,
                                  int maxBytes, AtomicBoolean overflow) {
            byte[] buffer = new byte[8192];
            int total = 0;
            try {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    total += read;
                    if (sink == null) continue;
                    if (total > maxBytes) {
                        if (overflow != null) overflow.set(true);
                        break;
                    }
                    sink.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // 流被主动关闭或进程已退出；原始错误不记录、不上抛。
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // 忽略关闭失败
                }
            }
        }

        private static void joinQuietly(Thread thread) {
            try {
                thread.join(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
