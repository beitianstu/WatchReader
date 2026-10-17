package com.watchreader;

import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;

/**
 * 打开书籍加载性能测试（桌面，合成数据，不碰任何真实书籍）。
 *
 * 目的：把"打开一本书"拆成可测量的阶段，量化到底哪里慢：
 *   A 冷开（真正建索引） / 热开（缓存命中） / 多次重开
 *   B 恢复进度时的分页定位（PageMap.locateChar 从 0 顺序推进）
 *   C 首次取页 / 总页数估算
 *   D 失效边界（源文件变化、缓存损坏、版本不符）必须安全重建
 *   E 编码兼容（UTF-8 BOM / CRLF / GB18030）在缓存复用后结果一致
 *
 * 度量说明：
 *   · breakText 调用次数是**设备无关**的工作量指标 —— 真机上一次 breakText
 *     要真正测量字形宽度，远比桌面 stub 贵，所以"调用次数"比桌面 ms 更能反映手表开销。
 *   · 计时用同一套 stub 跑改动前后，比较相对变化；绝对值不代表手表。
 *   · PageMap 用桌面 stub 的固定字宽测量，页容量与手表同量级（400x380，字号 13）。
 */
public final class LoadPerfCheck {

    private static final int PAGE_W = 400;
    private static final int PAGE_H = 380;
    private static final float SPACING = 2f;
    private static final float FONT_SIZE = 13f;

    /**
     * 测试里模拟 ReaderView.layoutKey()：字号|字体|可用宽|可用高|行距。
     * 桌面测不了 ReaderView 本身，但页码缓存的 key 逻辑与格式都在这里验证。
     */
    private static final String LAYOUT_KEY = "13|sans-serif|" + PAGE_W + "|" + PAGE_H + "|" + SPACING;

    /** 统计 breakText 次数（设备无关的工作量指标） */
    static final class CountingPaint extends TextPaint {
        static long breakTextCalls;
        static long measureTextCalls;

        @Override
        public int breakText(CharSequence text, int start, int end, boolean forwards,
                            float maxWidth, float[] measured) {
            breakTextCalls++;
            return super.breakText(text, start, end, forwards, maxWidth, measured);
        }

        @Override
        public float measureText(CharSequence text, int start, int end) {
            measureTextCalls++;
            return super.measureText(text, start, end);
        }
    }

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        int targetMb = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        File work = args.length > 1 ? new File(args[1]) : new File(System.getProperty("java.io.tmpdir"), "wr-loadtest-data");
        if (work.exists()) {
            deleteRecursively(work);
        }
        if (!work.mkdirs()) {
            throw new IllegalStateException("无法创建测试目录: " + work);
        }
        System.out.println("== 合成数据目录: " + work.getAbsolutePath());

        File books = new File(work, "books");
        File cacheDir = new File(work, "index");
        books.mkdirs();
        cacheDir.mkdirs();

        // ---- 合成一本大书：UTF-8、中文、LF 与 CRLF 混合、带章节标题 ----
        File book = new File(books, "合成大书(测试).txt");
        long t0 = System.nanoTime();
        long bytes = generate(book, targetMb * 1024 * 1024, 20240517L, false, false);
        System.out.printf("== 合成书: %s  %,d 字节  生成 %d ms%n", book.getName(), bytes, ms(t0));

        CountingPaint paint = new CountingPaint();
        paint.setTextSize(FONT_SIZE);

        // ================= A. 冷开 / 热开 =================
        File cache = cacheFile(cacheDir, book);
        cache.delete();

        Phase cold = timeBookOpen(book, cacheDir);
        print("A1 cold-open(建索引+写缓存)", cold, "buildMs=" + cold.book.buildMs
                + " chars=" + cold.book.charCount() + " lines=" + cold.book.lineCount()
                + " cacheBytes=" + cache.length());

        Phase warm1 = timeBookOpen(book, cacheDir);
        print("A2 warm-open-1(缓存命中)", warm1, "buildMs=" + warm1.book.buildMs);
        check("warm-hit-must-not-rebuild", warm1.book.buildMs == 0,
                "buildMs=" + warm1.book.buildMs);
        check("warm-text-equals-cold", cold.book.text.equals(warm1.book.text),
                "chars " + cold.book.text.length() + " vs " + warm1.book.text.length());
        check("warm-index-equals-cold", cold.book.index.length == warm1.book.index.length,
                "lines " + cold.book.index.length + " vs " + warm1.book.index.length);

        long[] warmRuns = new long[5];
        for (int i = 0; i < warmRuns.length; i++) {
            Phase w = timeBookOpen(book, cacheDir);
            warmRuns[i] = w.ms;
            check("warm-repeat-" + (i + 2) + "-cache-hit", w.book.buildMs == 0, "buildMs=" + w.book.buildMs);
        }
        System.out.printf("A3 warm-open-x5 ms=%s median=%d%n", java.util.Arrays.toString(warmRuns),
                median(warmRuns));

        long t = System.nanoTime();
        int cachedChars = Book.cachedCharCount(cacheDir, book);
        System.out.printf("A4 cached-char-count ms=%d value=%d (charCount=%d) breakText=%d%n",
                ms(t), cachedChars, warm1.book.charCount(), bt());
        check("cached-char-count", cachedChars == warm1.book.charCount(), cachedChars + " vs " + warm1.book.charCount());

        // ================= B. 恢复进度的分页定位 =================
        Book b = warm1.book;
        int offset25 = b.charCount() / 4;
        int offset75 = b.charCount() * 3 / 4;

        LocResult l25 = timeLocate(b, paint, offset25);
        print("B1 locate-25%(PageMap.locateChar)", l25.phase, "page=" + l25.page);
        LocResult l75 = timeLocate(b, paint, offset75);
        print("B2 locate-75%(PageMap.locateChar)", l75.phase, "page=" + l75.page);

        Phase load75 = timePaginateAt(b, paint, offset75);
        print("B3 paginateAt-75%(取当页)", load75, "rows=" + load75.rows);

        Phase est = timeEstimate(b, paint);
        print("B4 estimate-page-count", est, "pages=" + est.pages);

        // ================= C. 应用真实打开路径（缓存命中 + 恢复 75%） =================
        CountingPaint.breakTextCalls = 0;
        long tApp = System.nanoTime();
        Book appBook = Book.open(book, cacheDir);
        PageMap map = new PageMap(appBook, paint, PAGE_W, PAGE_H, SPACING);
        PageMap.Loc loc = map.locateChar(offset75);
        Paginator.Page page = Paginator.paginateAt(appBook, paint, loc.startChar, PAGE_W, PAGE_H, SPACING);
        long appMs = ms(tApp);
        long appBreakText = bt();
        System.out.printf("C1 app-open-resume-75(无页码缓存) ms=%d breakText=%d page=%d startChar=%d endChar=%d%n",
                appMs, appBreakText, loc.page, page.startChar, page.endChar);
        check("app-locate-contains-offset", loc.startChar <= offset75,
                "loc.startChar=" + loc.startChar + " offset=" + offset75);
        check("app-page-after-locate", page.startChar >= loc.startChar && page.endChar > page.startChar,
                "page=" + page.startChar + ".." + page.endChar);

        // ================= C2. 页码缓存：第二次打开（同一个版面） =================
        int[] walkedStarts = map.contiguousPageStarts();
        System.out.printf("C2.0 顺序量出的连续页码前缀: pages=%d (第0页起点=%d, 第%d页起点=%d)%n",
                walkedStarts.length, walkedStarts[0], walkedStarts.length - 1,
                walkedStarts[walkedStarts.length - 1]);
        check("prefix-covers-target-page", walkedStarts.length == loc.page + 1,
                "prefix=" + walkedStarts.length + " page=" + loc.page);

        File sidecar = PageIndexCache.fileFor(cacheDir, appBook.file);
        PageIndexCache.save(cacheDir, appBook, LAYOUT_KEY, walkedStarts, walkedStarts.length);
        check("page-index-file-written", sidecar.isFile(), "size=" + sidecar.length());
        int[] loaded = PageIndexCache.load(cacheDir, appBook, LAYOUT_KEY);
        check("page-index-roundtrip", loaded != null && java.util.Arrays.equals(loaded, walkedStarts),
                loaded == null ? "(null)" : "len=" + loaded.length);

        CountingPaint.breakTextCalls = 0;
        long tSeed = System.nanoTime();
        PageMap seeded = new PageMap(appBook, paint, PAGE_W, PAGE_H, SPACING,
                PageIndexCache.load(cacheDir, appBook, LAYOUT_KEY));
        PageMap.Loc locSeed = seeded.locateChar(offset75);
        Paginator.Page pageSeed = Paginator.paginateAt(appBook, paint, locSeed.startChar, PAGE_W, PAGE_H, SPACING);
        long seedMs = ms(tSeed);
        long seedBreakText = bt();
        System.out.printf("C2 app-open-resume-75(有页码缓存) ms=%d breakText=%d page=%d startChar=%d endChar=%d%n",
                seedMs, seedBreakText, locSeed.page, pageSeed.startChar, pageSeed.endChar);
        check("seeded-locate-exact-page", locSeed.page == loc.page,
                locSeed.page + " vs walked " + loc.page);
        check("seeded-locate-exact-start", locSeed.startChar == loc.startChar,
                locSeed.startChar + " vs " + loc.startChar);
        check("seeded-page-content-identical",
                pageSeed.startChar == page.startChar && pageSeed.endChar == page.endChar
                        && java.util.Arrays.equals(pageSeed.lineTexts, page.lineTexts),
                pageSeed.startChar + ".." + pageSeed.endChar + " vs " + page.startChar + ".." + page.endChar);
        check("seeded-locate-cheaper", seedBreakText < appBreakText / 10,
                "seeded=" + seedBreakText + " walked=" + appBreakText);
        System.out.printf("   → 定位开销: 无缓存 %d 次 breakText → 有缓存 %d 次（%.1f%%）%n",
                appBreakText, seedBreakText, appBreakText == 0 ? 0.0 : 100.0 * seedBreakText / appBreakText);

        // 往回翻：有页码缓存时也必须是"命中前缀"，而不是从 0 重量
        CountingPaint.breakTextCalls = 0;
        PageMap.Loc prevSeed = seeded.prevPage(locSeed.page, locSeed.startChar);
        long prevBreakText = bt();
        int prevStartWalked = map.startOfPage(locSeed.page - 1);
        System.out.printf("C3 prev-page(有页码缓存) page=%d startChar=%d breakText=%d%n",
                prevSeed.page, prevSeed.startChar, prevBreakText);
        check("seeded-prev-page-exact", prevSeed.startChar == prevStartWalked,
                prevSeed.startChar + " vs walked " + prevStartWalked);
        check("seeded-prev-page-no-walk", prevBreakText == 0, "breakText=" + prevBreakText);

        // 缓存 key 不匹配时必须拒绝：版面/字数任何一项不同都不能用
        check("page-index-rejects-layout", PageIndexCache.load(cacheDir, appBook,
                LAYOUT_KEY + "x") == null, "wrong layout accepted");
        Book wrongChars = new Book(appBook.file, appBook.size, appBook.mtime, "t", "",
                appBook.encoding, "x", new int[][] { { 0, 1 } }, 0);
        check("page-index-rejects-charcount", PageIndexCache.load(cacheDir, wrongChars, LAYOUT_KEY) == null,
                "wrong charCount accepted");
        int[] badSeed = new int[] { 5, 9, 20 };
        PageMap badSeeded = new PageMap(appBook, paint, PAGE_W, PAGE_H, SPACING, badSeed);
        check("pagemap-ignores-bad-seed",
                badSeeded.locateChar(offset75).page == loc.page, "bad seed changed page number");
        // 损坏文件（截断）→ 必须当作没有缓存，而不是抛异常
        byte[] good = Files.readAllBytes(sidecar.toPath());
        try (FileOutputStream out = new FileOutputStream(sidecar)) {
            out.write(good, 0, good.length / 3);
        }
        check("page-index-rejects-corrupt",
                PageIndexCache.load(cacheDir, appBook, LAYOUT_KEY) == null,
                "corrupt file accepted");
        // 头部把 count 写成 Integer.MAX_VALUE：旧写法 "2 + count" 会溢出成负数而放过它，
        // 然后 new int[count]（现在必须先过 count <= lines-2 与 count <= charCount）
        try (FileOutputStream out = new FileOutputStream(sidecar)) {
            String header = "1\t" + appBook.size + "\t" + appBook.mtime + "\t" + appBook.charCount()
                    + "\t" + LAYOUT_KEY + "\n";
            out.write((header + Integer.MAX_VALUE + "\n0\n0\n").getBytes(StandardCharsets.UTF_8));
        }
        check("page-index-rejects-huge-count",
                PageIndexCache.load(cacheDir, appBook, LAYOUT_KEY) == null,
                "overflowed count accepted");
        // 超过 8MiB 的坏缓存：先看长度再读，必须直接拒绝
        try (FileOutputStream out = new FileOutputStream(sidecar)) {
            out.write(new byte[9 * 1024 * 1024]);
        }
        check("page-index-rejects-oversize",
                PageIndexCache.load(cacheDir, appBook, LAYOUT_KEY) == null,
                "oversize file accepted");
        PageIndexCache.save(cacheDir, appBook, LAYOUT_KEY, walkedStarts, walkedStarts.length);

        // C5. 像 ReaderView 那样连续前进 20 页 → 落盘 → 用页码缓存重开：每一页都要精确
        PageMap live = new PageMap(appBook, paint, PAGE_W, PAGE_H, SPACING);
        Paginator.Page cur = Paginator.paginateAt(appBook, paint, 0, PAGE_W, PAGE_H, SPACING);
        int pg = 0;
        live.register(0, cur.startChar);
        java.util.List<int[]> recorded = new java.util.ArrayList<>();
        recorded.add(new int[] { 0, cur.startChar });
        for (int i = 0; i < 20; i++) {
            PageMap.Loc nx = live.nextPage(pg, cur.endChar);
            cur = Paginator.paginateAt(appBook, paint, nx.startChar, PAGE_W, PAGE_H, SPACING);
            pg = nx.page;
            live.register(pg, cur.startChar);
            recorded.add(new int[] { pg, cur.startChar });
        }
        int[] flipStarts = live.contiguousPageStarts();
        PageIndexCache.save(cacheDir, appBook, LAYOUT_KEY, flipStarts, flipStarts.length);
        PageMap reopened = new PageMap(appBook, paint, PAGE_W, PAGE_H, SPACING,
                PageIndexCache.load(cacheDir, appBook, LAYOUT_KEY));
        int mismatched = 0;
        long reopenBreakText0 = bt();
        for (int[] rec : recorded) {
            PageMap.Loc l = reopened.locateChar(rec[1]);
            if (l.page != rec[0] || l.startChar != rec[1]) {
                mismatched++;
            }
        }
        long reopenBreakText = bt() - reopenBreakText0;
        System.out.printf("C5 flip-20-then-reopen(match=%d/%d) breakText=%d%n",
                recorded.size() - mismatched, recorded.size(), reopenBreakText);
        check("flip-roundtrip-all-pages-exact", mismatched == 0, "mismatched=" + mismatched);
        // 21 页里只有"前缀最后一页"需要量一次（它的下一页起点未知）；其余全是二分命中
        check("flip-roundtrip-no-walk", reopenBreakText <= 30, "breakText=" + reopenBreakText);

        // ================= C4. 进程内复用 + 阶段回调 =================
        Book first = BookCache.open(book, cacheDir, null);
        CountingPaint.breakTextCalls = 0;
        long tReuse = System.nanoTime();
        Book second = BookCache.open(book, cacheDir, null);
        long reuseMs = ms(tReuse);
        System.out.printf("C4 book-cache-reuse(同进程再打开) ms=%d sameInstance=%s breakText=%d%n",
                reuseMs, first == second, bt());
        check("book-cache-same-object", first == second, "not reused");
        check("book-cache-fast", reuseMs <= 5, "reuseMs=" + reuseMs);

        // 阶段回调：命中缓存说 FROM_CACHE，缺缓存说 BUILD_INDEX，损坏缓存要补报一次 BUILD_INDEX
        String freshPhases = phases(book, cacheDir);
        check("phase-fresh-cache", freshPhases.equals("FROM_CACHE"), freshPhases);
        File noCacheBook = new File(books, "无缓存书.txt");
        Files.write(noCacheBook.toPath(), "第一段\n第二段\n".getBytes(StandardCharsets.UTF_8));
        noCacheBook.setLastModified(1700000123456L);
        String firstPhases = phases(noCacheBook, cacheDir);
        check("phase-first-open", firstPhases.equals("BUILD_INDEX"), firstPhases);
        File corruptBook = new File(books, "缓存损坏书.txt");
        Files.write(corruptBook.toPath(), "甲\n乙\n丙\n丁\n戊\n".getBytes(StandardCharsets.UTF_8));
        corruptBook.setLastModified(1700000234567L);
        Book.open(corruptBook, cacheDir);
        File corruptCache = new File(cacheDir, Book.cacheId(corruptBook) + ".idx");
        byte[] cb = Files.readAllBytes(corruptCache.toPath());
        try (FileOutputStream out = new FileOutputStream(corruptCache)) {
            out.write(cb, 0, cb.length / 2);
        }
        String corruptPhases = phases(corruptBook, cacheDir);
        check("phase-corrupt-cache-rebuilds", corruptPhases.contains("BUILD_INDEX"), corruptPhases);
        check("has-fresh-cache-after-rebuild", Book.hasFreshCache(cacheDir, corruptBook),
                "hasFresh=" + Book.hasFreshCache(cacheDir, corruptBook));
        long savedMtime = book.lastModified();
        book.setLastModified(savedMtime + 1000);
        boolean staleFresh = Book.hasFreshCache(cacheDir, book);
        book.setLastModified(savedMtime);
        check("has-fresh-cache-false-when-stale", !staleFresh, "hasFreshWhenStale=" + staleFresh);

        // ================= H. 页码缓存必须绑定 Book 的打开快照 =================
        // 场景：拿着旧 Book 阅读期间源文件被替换（**同字节长度、同字数**，mtime 变），
        // 旧书的页码不能被盖章保存，也不能被新书加载 —— 否则同字数新文件会错用旧页码。
        // 两本书刻意造成"字节数相同 / 排版字数相同 / 但分页边界不同"：
        //   A：200 行 × 20 字（每行 1 视觉行）→ 约 200 视觉行
        //   B：100 行 × 39 字 + 100 行 × 1 字（39 字折成 2 行）→ 约 300 视觉行
        File swap = new File(books, "同字数换源.txt");
        StringBuilder c1 = new StringBuilder();
        StringBuilder c2 = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            c1.append(repeat('A', 20)).append('\n');
        }
        for (int i = 0; i < 100; i++) {
            c2.append(repeat('B', 39)).append('\n');
        }
        for (int i = 0; i < 100; i++) {
            c2.append('C').append('\n');
        }
        Files.write(swap.toPath(), c1.toString().getBytes(StandardCharsets.UTF_8));
        swap.setLastModified(1700001000000L);
        Book oldBook = Book.open(swap, cacheDir);
        PageMap oldMap = new PageMap(oldBook, paint, PAGE_W, PAGE_H, SPACING);
        oldMap.locateChar(oldBook.charCount() / 2);
        int[] oldStarts = oldMap.contiguousPageStarts();
        File swapSidecar = PageIndexCache.fileFor(cacheDir, swap);
        PageIndexCache.save(cacheDir, oldBook, LAYOUT_KEY, oldStarts, oldStarts.length);
        boolean savedBeforeSwap = PageIndexCache.load(cacheDir, oldBook, LAYOUT_KEY) != null;
        check("swap-cache-valid-before-change", savedBeforeSwap, "cache not usable before swap");
        check("swap-prefix-has-pages", oldStarts.length > 1, "prefix=" + oldStarts.length);

        // 换源：同字节长度、同排版字数，mtime 变
        Files.write(swap.toPath(), c2.toString().getBytes(StandardCharsets.UTF_8));
        swap.setLastModified(1700001000000L + 5000L);
        check("swap-source-same-length", swap.length() == oldBook.size,
                swap.length() + " vs " + oldBook.size);

        check("swap-load-old-book-rejected", PageIndexCache.load(cacheDir, oldBook, LAYOUT_KEY) == null,
                "old Book still loaded its page cache after source changed");
        if (swapSidecar.isFile()) {
            swapSidecar.delete();
        }
        PageIndexCache.save(cacheDir, oldBook, LAYOUT_KEY, oldStarts, oldStarts.length);
        check("swap-save-old-book-refused", !swapSidecar.isFile(),
                "old Book stamped page cache for the changed source");

        Book freshBook = Book.open(swap, cacheDir);
        check("swap-new-book-same-size", freshBook.size == oldBook.size,
                freshBook.size + " vs " + oldBook.size);
        check("swap-new-book-same-charcount", freshBook.charCount() == oldBook.charCount(),
                freshBook.charCount() + " vs " + oldBook.charCount());
        check("swap-old-pages-not-valid-for-new-book",
                PageIndexCache.load(cacheDir, freshBook, LAYOUT_KEY) == null,
                "old page index was reused for the same-length new source");
        // 新书重新量出的分页必须和旧书不同（同字数但换行结构不同），否则这条回归没有意义
        PageMap freshMap = new PageMap(freshBook, paint, PAGE_W, PAGE_H, SPACING);
        freshMap.locateChar(freshBook.charCount() / 2);
        int[] freshStarts = freshMap.contiguousPageStarts();
        boolean sameBoundaries = oldStarts.length == freshStarts.length;
        if (sameBoundaries) {
            for (int i = 0; i < oldStarts.length && sameBoundaries; i++) {
                sameBoundaries = oldStarts[i] == freshStarts[i];
            }
        }
        System.out.printf("H 换源前后页码: oldPages=%d newPages=%d 边界相同=%s%n",
                oldStarts.length, freshStarts.length, sameBoundaries ? "YES(样本无意义)" : "NO");
        check("swap-boundaries-actually-differ", !sameBoundaries,
                "same boundaries: regression sample would be meaningless");
        // 新书的页码缓存可以（也必须能）正常落盘
        PageIndexCache.save(cacheDir, freshBook, LAYOUT_KEY, freshStarts, freshStarts.length);
        check("swap-new-book-cache-works",
                PageIndexCache.load(cacheDir, freshBook, LAYOUT_KEY) != null,
                "new Book could not save/load its own page cache");

        // ================= D. 失效边界 =================
        long originalMtime = book.lastModified();
        // D1：源文件 mtime 变了（内容没变）→ 必须重建
        book.setLastModified(originalMtime + 5000);
        Phase afterTouch = timeBookOpen(book, cacheDir);
        System.out.printf("D1 source-mtime-changed rebuild=%s ms=%d buildMs=%d%n",
                afterTouch.book.buildMs > 0 ? "YES" : "NO", afterTouch.ms, afterTouch.book.buildMs);
        check("stale-mtime-rebuilds", afterTouch.book.buildMs > 0, "buildMs=" + afterTouch.book.buildMs);
        check("stale-mtime-text-still-right", afterTouch.book.text.equals(cold.book.text), "text mismatch");
        check("cached-char-count-after-stale", Book.cachedCharCount(cacheDir, book) > 0, "should be refreshed by rebuild");

        // D2：缓存被截断（损坏）→ 必须重建
        byte[] cached = Files.readAllBytes(cache.toPath());
        try (FileOutputStream out = new FileOutputStream(cache)) {
            out.write(cached, 0, cached.length / 2);
        }
        Phase afterCorrupt = timeBookOpen(book, cacheDir);
        System.out.printf("D2 corrupt-cache rebuild=%s ms=%d buildMs=%d%n",
                afterCorrupt.book.buildMs > 0 ? "YES" : "NO", afterCorrupt.ms, afterCorrupt.book.buildMs);
        check("corrupt-cache-rebuilds", afterCorrupt.book.buildMs > 0, "buildMs=" + afterCorrupt.book.buildMs);
        check("corrupt-cache-text-right", afterCorrupt.book.text.equals(cold.book.text), "text mismatch");
        check("corrupt-cache-recovers-to-hit", timeBookOpen(book, cacheDir).book.buildMs == 0, "should hit after rebuild");

        // D3：源文件内容/大小真的变了 → 不能用旧缓存
        File changed = new File(books, "合成大书(测试).txt");
        try (FileOutputStream out = new FileOutputStream(changed, true)) {
            out.write("\n追加的一段新内容，用来改变文件大小与 mtime。\n".getBytes(StandardCharsets.UTF_8));
        }
        Phase afterAppend = timeBookOpen(changed, cacheDir);
        System.out.printf("D3 source-appended rebuild=%s buildMs=%d chars=%d%n",
                afterAppend.book.buildMs > 0 ? "YES" : "NO", afterAppend.book.buildMs, afterAppend.book.charCount());
        check("changed-source-rebuilds", afterAppend.book.buildMs > 0, "buildMs=" + afterAppend.book.buildMs);
        check("changed-source-text-grew", afterAppend.book.charCount() > cold.book.charCount(),
                afterAppend.book.charCount() + " vs " + cold.book.charCount());
        check("changed-source-new-cache-hits", timeBookOpen(changed, cacheDir).book.buildMs == 0, "no hit after rebuild");

        // ================= E. 编码 / 换行兼容 =================
        encodingRoundTrip(cacheDir, paint, "utf8-bom-crlf.txt", StandardCharsets.UTF_8, true, true,
                "第1章 起始\r\n正文一二三\r\n\r\n\r\n第2章 后续\r\n结尾", "utf-8-bom");
        encodingRoundTrip(cacheDir, paint, "utf8-lf.txt", StandardCharsets.UTF_8, false, false,
                "第一段\n\n\n第二段", "utf-8");
        encodingRoundTrip(cacheDir, paint, "gb18030.txt", Charset.forName("GB18030"), false, false,
                "第一章 中文编码测试\n正文内容：一二三四五六七八九十\n第二章 结束\n收尾", "gb18030");
        encodingRoundTrip(cacheDir, paint, "utf16le-bom.txt", StandardCharsets.UTF_16LE, true, false,
                "第一章 宽字符\n正文\n第二章 结束", "utf-16le");

        // ================= F. 章节识别（真实实现，不复制正则） =================
        long cf0 = System.nanoTime();
        java.util.List<Chapters.Chapter> chapters = Chapters.scan(b.text);
        long chaptersMs = ms(cf0);
        System.out.printf("F1 chapters-scan ms=%d chapters=%d (每章约 %d 字)%n",
                chaptersMs, chapters.size(), chapters.isEmpty() ? 0 : b.charCount() / chapters.size());
        check("chapters-found", chapters.size() > 100, "chapters=" + chapters.size());
        check("chapters-first-at-zero", !chapters.isEmpty() && chapters.get(0).offset == 0,
                chapters.isEmpty() ? "(none)" : "offset=" + chapters.get(0).offset);
        // 注意：下面这些期望值就是**改造前**（逐行跑正则）的真实行为，
        // 包括两个既有特性：① "正文" 本身在关键字表里，会被当成章节头；
        // ② "卷三 风起" 这类"卷在数字前"的写法不匹配（正则要求数字在前）。
        // 加速预筛不得改变任何一条。
        chapterCase("第1章 开始\n正文\n第十二章 结束\n正文2",
                new String[] { "第1章 开始", "正文", "第十二章 结束", "正文2" });
        chapterCase("\u3000\u3000序章\n正文", new String[] { "序章", "正文" });
        chapterCase("Chapter 3 名字\n正文", new String[] { "Chapter 3 名字", "正文" });
        chapterCase("CHAPTER IV\n正文", new String[] { "CHAPTER IV", "正文" });
        chapterCase("第二天他出门了\n第2章 到了", new String[] { "第2章 到了" });
        chapterCase("普通段落\n另一段", new String[] {});
        chapterCase("楔子\n正文\n番外 一\n正文", new String[] { "楔子", "正文", "番外 一", "正文" });
        chapterCase("卷三 风起", new String[] {});
        chapterCase("第三卷 风起", new String[] { "第三卷 风起" });
        chapterCase("  第9章 前面有空格", new String[] { "第9章 前面有空格" });
        chapterCase("chapter 3 小写不匹配", new String[] {});
        chapterCase(longNonChapterLine(), new String[] {});
        chapterCase("这里提到第1章 但不是行首", new String[] {});

        // 章节扫描的加速效果：同一段文本，改造前后结果必须一致（上面用例断言结果），
        // 这里另外给出规模与耗时，便于对比。
        long scanAgain0 = System.nanoTime();
        int again = Chapters.scan(b.text).size();
        System.out.printf("F2 chapters-scan-repeat ms=%d chapters=%d (第一次=%d)%n",
                ms(scanAgain0), again, chapters.size());
        check("chapters-scan-stable", again == chapters.size(), again + " vs " + chapters.size());

        System.out.println();
        if (failures == 0) {
            System.out.println("=== LoadPerfCheck: 全部通过 ===");
            System.exit(0);
        } else {
            System.out.println("=== LoadPerfCheck: " + failures + " 项失败 ===");
            System.exit(1);
        }
    }

    // ---------------- 各阶段 ----------------

    static final class Phase {
        long ms;
        long breakText;
        Book book;
        int rows;
        int pages;
        String note = "";
    }

    /** 记录 Book.open 的阶段回调顺序，例如 "FROM_CACHE,BUILD_INDEX" */
    private static String phases(File file, File cacheDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        Book.open(file, cacheDir, p -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.name());
        });
        return sb.toString();
    }

    static final class LocResult {
        Phase phase;
        int page;
    }

    private static Phase timeBookOpen(File file, File cacheDir) throws Exception {        long c0 = CountingPaint.breakTextCalls;
        long t = System.nanoTime();
        Book book = Book.open(file, cacheDir);
        Phase p = new Phase();
        p.ms = ms(t);
        p.book = book;
        p.breakText = CountingPaint.breakTextCalls - c0;
        return p;
    }

    private static LocResult timeLocate(Book book, TextPaint paint, int offset) {
        long c0 = CountingPaint.breakTextCalls;
        long t = System.nanoTime();
        PageMap map = new PageMap(book, paint, PAGE_W, PAGE_H, SPACING);
        PageMap.Loc loc = map.locateChar(offset);
        LocResult r = new LocResult();
        r.phase = new Phase();
        r.phase.ms = ms(t);
        r.phase.breakText = CountingPaint.breakTextCalls - c0;
        r.page = loc.page;
        return r;
    }

    private static Phase timePaginateAt(Book book, TextPaint paint, int offset) {
        long c0 = CountingPaint.breakTextCalls;
        long t = System.nanoTime();
        Paginator.Page page = Paginator.paginateAt(book, paint, offset, PAGE_W, PAGE_H, SPACING);
        Phase p = new Phase();
        p.ms = ms(t);
        p.breakText = CountingPaint.breakTextCalls - c0;
        p.rows = page.lineCount();
        return p;
    }

    private static Phase timeEstimate(Book book, TextPaint paint) {
        long c0 = CountingPaint.breakTextCalls;
        long t = System.nanoTime();
        int pages = Paginator.estimatePageCount(book, paint, PAGE_W, PAGE_H, SPACING);
        Phase p = new Phase();
        p.ms = ms(t);
        p.breakText = CountingPaint.breakTextCalls - c0;
        p.pages = pages;
        return p;
    }

    // ---------------- 编码兼容 ----------------

    private static void encodingRoundTrip(File cacheDir, TextPaint paint, String name, Charset cs,
                                          boolean bom, boolean crlf, String content, String expectedEncoding) throws Exception {
        File dir = new File(cacheDir.getParentFile(), "books-enc");
        dir.mkdirs();
        File f = new File(dir, name);
        byte[] payload = content.getBytes(cs);
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (bom) {
                // BOM 必须与编码一致：UTF-8 → EF BB BF；UTF-16LE → FF FE（写错会变成"UTF-8 BOM 的乱码"）
                if (cs.equals(StandardCharsets.UTF_16LE)) {
                    out.write(new byte[] { (byte) 0xFF, (byte) 0xFE });
                } else {
                    out.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
                }
            }
            out.write(payload);
        }
        f.setLastModified(1700000000000L + name.hashCode());

        long c0 = System.nanoTime();
        Book cold = Book.open(f, cacheDir);
        long coldMs = ms(c0);
        long w0 = System.nanoTime();
        Book warm = Book.open(f, cacheDir);
        long warmMs = ms(w0);
        boolean normalized = !cold.text.contains("\r");
        System.out.printf("E %-20s enc=%-8s coldMs=%-4d warmMs=%-3d warmBuildMs=%d chars=%d crlf-normalized=%s%n",
                name, cold.encoding, coldMs, warmMs, warm.buildMs, cold.charCount(), normalized ? "YES" : "NO");
        check("enc-" + name + "-warming", warm.buildMs == 0, "buildMs=" + warm.buildMs);
        check("enc-" + name + "-detected", expectedEncoding.equals(cold.encoding),
                "detected=" + cold.encoding + " expect=" + expectedEncoding);
        check("enc-" + name + "-text-equal", cold.text.equals(warm.text), "text mismatch");
        check("enc-" + name + "-no-cr", normalized, "still contains CR");
        check("enc-" + name + "-index-equal", cold.index.length == warm.index.length, "line count differs");
        // 复用缓存后分页仍可工作
        Paginator.Page p = Paginator.paginateAt(warm, paint, 0, PAGE_W, PAGE_H, SPACING);
        check("enc-" + name + "-paginates", p.lineCount() > 0 && p.endChar > 0, "empty page");
    }

    // ---------------- 章节识别用例 ----------------

    /** 一行超长（>64 字）的文本，即使行首像章节也不该被当成章节（与既有行为一致） */
    private static String longNonChapterLine() {
        StringBuilder sb = new StringBuilder("第1章 ");
        for (int i = 0; i < 80; i++) {
            sb.append('字');
        }
        return sb.toString();
    }

    private static void chapterCase(String text, String[] expected) {
        java.util.List<Chapters.Chapter> got = Chapters.scan(text);
        StringBuilder sb = new StringBuilder();
        for (Chapters.Chapter c : got) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(c.title);
        }
        boolean ok = got.size() == expected.length;
        if (ok) {
            for (int i = 0; i < expected.length; i++) {
                if (!expected[i].equals(got.get(i).title)) {
                    ok = false;
                    break;
                }
            }
        }
        check("chapter-case", ok, "got=[" + sb + "] expect=" + java.util.Arrays.toString(expected));
    }

    // ---------------- 合成数据 ----------------

    /** 生成中文合成书；返回实际字节数。crlf=true 时用 CRLF 换行。 */
    static long generate(File out, long targetBytes, long seed, boolean crlf, boolean bom) throws Exception {
        Random rnd = new Random(seed);
        String pool = "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身者被高已亲其进此话常与活正感";
        int targetChars = (int) (targetBytes / 3);
        StringBuilder sb = new StringBuilder(targetChars + 4096);
        if (bom) {
            sb.append('\uFEFF');
        }
        String nl = crlf ? "\r\n" : "\n";
        int chapters = 0;
        while (sb.length() < targetChars) {
            chapters++;
            sb.append("第").append(chapters).append("章 合成章节标题").append(chapters).append(nl);
            int paragraphs = 3 + rnd.nextInt(5);
            for (int p = 0; p < paragraphs; p++) {
                int len = 30 + rnd.nextInt(120);
                for (int i = 0; i < len; i++) {
                    sb.append(pool.charAt(rnd.nextInt(pool.length())));
                }
                sb.append(nl);
            }
            if (chapters % 4 == 0) {
                sb.append(nl);
            }
        }
        try (FileOutputStream out2 = new FileOutputStream(out)) {
            out2.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out.length();
    }

    // ---------------- 工具 ----------------

    private static File cacheFile(File cacheDir, File book) {
        return new File(cacheDir, Book.cacheId(book) + ".idx");
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long bt() {
        return CountingPaint.breakTextCalls;
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void print(String label, Phase p, String extra) {
        System.out.printf("%-36s ms=%-6d breakText=%-7d %s%n", label, p.ms, p.breakText, extra);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.printf("  [%s] %-34s %s%n", ok ? "OK" : "FAIL", name, detail);
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
