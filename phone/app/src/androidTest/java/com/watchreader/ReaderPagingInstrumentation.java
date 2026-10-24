package com.watchreader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Real Android text measurement; verifies page boundaries instead of mocking Paint. */
public final class ReaderPagingInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            TextPaint paint = new TextPaint();
            paint.setTextSize(24f);
            StringBuilder text = new StringBuilder("导言\n");
            for (int chapter = 1; chapter <= 3; chapter++) {
                text.append("第").append(chapter).append("章 标题\n");
                for (int paragraph = 0; paragraph < 15; paragraph++) {
                    text.append("第").append(paragraph).append("段正文，分页必须连续，不能丢字，也不能重复。\n");
                }
            }
            Book book = book(text.toString());
            List<Chapters.Chapter> chapters = Chapters.scan(book.text);
            require(chapters.size() == 3, "Chapter scan");
            ChapterDecor decor = ChapterDecor.of(chapters, 4);
            List<Integer> starts = new ArrayList<>();
            StringBuilder rendered = new StringBuilder();
            int contentEnd = book.index[book.index.length - 1][1];
            int cursor = 0;
            for (int guard = 0; cursor < contentEnd && guard < 500; guard++) {
                starts.add(cursor);
                Paginator.Measure measured = Paginator.measure(book, paint, cursor, 340, 430, 5f, decor);
                Paginator.Page page = Paginator.paginateAt(book, paint, cursor, 340, 430, 5f, decor);
                require(measured.endChar > cursor, "Pagination advances");
                require(measured.endChar == page.endChar, "Measure/draw boundary agreement");
                require(measured.topRows == page.topRows, "Heading space agreement");
                if (page.topRows > 0) rendered.append(book.text, cursor, ChapterDecor.bodyStartAfterTitle(book.text, cursor));
                for (int i = 0; i < page.lineTexts.length; i++) {
                    require(book.text.startsWith(page.lineTexts[i], page.lineStarts[i]), "No altered text");
                    rendered.append(page.lineTexts[i]);
                }
                cursor = measured.endChar;
            }
            require(cursor >= contentEnd, "Reached text end");
            require(rendered.toString().replaceAll("\\s+", "").equals(book.text.replaceAll("\\s+", "")), "No missing or duplicate characters");
            for (Chapters.Chapter chapter : chapters) require(starts.contains(chapter.offset), "Chapter starts a page");
            PageMap map = new PageMap(book, paint, 340, 430, 5f, null, decor);
            for (int i = 0; i < starts.size(); i++) {
                require(map.startOfPage(i) == starts.get(i), "Page map uses decorated boundaries");
                PageMap.Loc loc = map.locateChar(starts.get(i));
                require(loc.page == i && loc.startChar == starts.get(i), "Reverse location");
            }
            Paginator.Measure legacy = Paginator.measure(book, paint, 0, 340, 430, 5f);
            Paginator.Measure unchanged = Paginator.measure(book, paint, 0, 340, 430, 5f, null);
            require(legacy.endChar == unchanged.endChar && unchanged.topRows == 0, "Watch default unchanged");
            Book online = book("在线正文不带标题前缀。\n下一段正文。\n");
            ChapterDecor onlineDecor = new ChapterDecor(new int[]{0}, new int[]{3}, false);
            Paginator.Page onlinePage = Paginator.paginateAt(online, paint, 0, 340, 430, 5f, onlineDecor);
            require(onlinePage.lineStarts[0] == 0 && onlinePage.lineTexts[0].startsWith("在线正文"), "Online offsets unchanged");
            verifySpacingVariants(book, paint);
            verifyReaderViewAppearance(getTargetContext(), book);
            verifyCacheDecoder();
            result.putString("stream", "PASS: decorated pagination, chapter boundaries, reverse lookup, watch defaults, online offsets, cache AES/gzip and rejection\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.toString() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** 行距变体仍要满足 measure/draw 一致、无丢字/重复；页数随行距单调增加。 */
    private static void verifySpacingVariants(Book book, TextPaint paint) {
        ChapterDecor decor = ChapterDecor.of(Chapters.scan(book.text), 4);
        int compact = paginateAll(book, paint, 340, 430, 2f, decor);
        int normal = paginateAll(book, paint, 340, 430, 5f, decor);
        int spacious = paginateAll(book, paint, 340, 430, 14f, decor);
        require(compact <= normal && normal <= spacious, "More line spacing must not reduce pages");
        require(compact < spacious, "Line spacing changes page count");
        verifyCharacters(book, paint, 340, 430, 2f, decor);
        verifyCharacters(book, paint, 340, 430, 14f, decor);
    }

    private static int paginateAll(Book book, TextPaint paint, int width, int height,
                                  float spacing, ChapterDecor decor) {
        int contentEnd = book.index[book.index.length - 1][1];
        int cursor = 0;
        int count = 0;
        for (int guard = 0; cursor < contentEnd && guard < 500; guard++) {
            Paginator.Measure measured = Paginator.measure(book, paint, cursor, width, height, spacing, decor);
            Paginator.Page page = Paginator.paginateAt(book, paint, cursor, width, height, spacing, decor);
            require(measured.endChar > cursor, "Spacing pagination advances");
            require(measured.endChar == page.endChar, "Spacing measure/draw agreement");
            require(measured.topRows == page.topRows, "Spacing heading agreement");
            cursor = measured.endChar;
            count++;
        }
        require(cursor >= contentEnd, "Spacing reached text end");
        return count;
    }

    private static void verifyCharacters(Book book, TextPaint paint, int width, int height,
                                        float spacing, ChapterDecor decor) {
        int contentEnd = book.index[book.index.length - 1][1];
        int cursor = 0;
        StringBuilder rendered = new StringBuilder();
        for (int guard = 0; cursor < contentEnd && guard < 500; guard++) {
            Paginator.Page page = Paginator.paginateAt(book, paint, cursor, width, height, spacing, decor);
            if (page.topRows > 0) {
                rendered.append(book.text, cursor, ChapterDecor.bodyStartAfterTitle(book.text, cursor));
            }
            for (int i = 0; i < page.lineTexts.length; i++) {
                require(book.text.startsWith(page.lineTexts[i], page.lineStarts[i]), "Spacing no altered text");
                rendered.append(page.lineTexts[i]);
            }
            int end = Paginator.measure(book, paint, cursor, width, height, spacing, decor).endChar;
            require(end > cursor, "Spacing character advance");
            cursor = end;
        }
        require(cursor >= contentEnd, "Spacing characters reached end");
        require(
            rendered.toString().replaceAll("\\s+", "").equals(book.text.replaceAll("\\s+", "")),
            "Spacing no missing or duplicate characters");
    }

    /** 用真实 ReaderView 验证行距 setter 参与 layoutKey/行高/页数，且字号变化保留倍率。 */
    private void verifyReaderViewAppearance(Context context, Book book) {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                ReaderView view = new ReaderView(context);
                view.setPhoneLayout(true);
                view.setChromeVisible(false);
                view.setChapterLayout(true);
                view.setFontSize(20);
                view.setLineHeightMultiplier(1.0f);
                view.setBook(book);
                measureAndLayout(view, 360, 640);
                String compactKey = view.layoutKeyForTest();
                float compactSpacing = view.lineSpacingAddForTest();
                int compactPages = view.getPageCount();

                view.setLineHeightMultiplier(2.0f);
                String spaciousKey = view.layoutKeyForTest();
                float spaciousSpacing = view.lineSpacingAddForTest();
                int spaciousPages = view.getPageCount();
                require(!compactKey.equals(spaciousKey), "Layout key updates with line spacing");
                require(spaciousSpacing > compactSpacing, "Line spacing participates in real metrics");
                require(spaciousPages >= compactPages, "Line spacing updates page count");

                view.setFontSize(24);
                require(Math.abs(view.getLineHeightMultiplier() - 2.0f) < 0.001f,
                        "Font size preserves line spacing multiplier");

                ReaderView legacy = new ReaderView(context);
                legacy.setPhoneLayout(true);
                legacy.setFontSize(20);
                legacy.setBook(book);
                measureAndLayout(legacy, 360, 640);
                require(Math.abs(legacy.lineSpacingAddForTest() - (20f * 1.3f - 20f)) < 0.01f,
                        "Default ReaderView line spacing remains legacy");
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void measureAndLayout(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Book book(String text) {
        List<int[]> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            lines.add(new int[]{start, end});
            start = end + 1;
        }
        return new Book(new File("paging-test.txt"), text.length(), 1, "样本", "", "UTF-8", text,
                lines.toArray(new int[0][]), 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyCacheDecoder() throws Exception {
        // Only synthetic test keys; no root, credentials or device cache access.
        byte[] key = new byte[16], iv = new byte[16];
        for (int i = 0; i < 16; i++) { key[i] = (byte) i; iv[i] = (byte) (i + 16); }
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(compressed)) {
            gzip.write("<html><body><h1>第1章 合成样本</h1><p>保留中文与标点。</p><script>应当移除</script></body></html>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        java.util.Map<String, byte[]> keys = new java.util.HashMap<>();
        keys.put("test", key);
        java.lang.reflect.Method decode = com.watchreader.phone.OfficialCache.class
                .getDeclaredMethod("decodeBody", org.json.JSONObject.class, java.util.Map.class);
        decode.setAccessible(true);
        String json = cacheJson(compressed.toByteArray(), key, iv).toString().replace("=", "\\u003d");
        String text = (String) decode.invoke(null, new org.json.JSONObject(json), keys);
        require(text.contains("第1章 合成样本") && text.contains("保留中文与标点。") && !text.contains("应当移除"), "Cache round trip");
        try {
            decode.invoke(null, cacheJson("not gzip".getBytes(java.nio.charset.StandardCharsets.UTF_8), key, iv), keys);
            throw new AssertionError("Invalid compressed cache accepted");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            require(expected.getCause() instanceof com.watchreader.phone.OfficialCache.CacheException, "Compression mismatch rejected");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            keys.clear();
        }
    }

    private static org.json.JSONObject cacheJson(byte[] payload, byte[] key, byte[] iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(payload);
        byte[] blob = new byte[16 + ciphertext.length];
        System.arraycopy(iv, 0, blob, 0, 16);
        System.arraycopy(ciphertext, 0, blob, 16, ciphertext.length);
        return new org.json.JSONObject().put("key_version", "test").put("compress_status", 1)
                .put("content", android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP));
    }
}
