package com.watchreader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 编码嗅探：BOM → 严格 UTF-8 校验 → GB18030 → 兜底。
 *
 * 中文 TXT 最常见的就是 UTF-8（含 BOM）和 GB18030/GBK 两种，
 * 前者严格校验能可靠识别；后者在安卓上自带码表（Charset "GB18030"），所以手表端也能处理，
 * 这点比 HarmonyOS 版强（那边没有 GB18030 码表，只能报错让用户转码）。
 */
public final class TextDecoder {

    private TextDecoder() {
    }

    public static final class Decoded {
        public final String text;
        public final String encoding;

        Decoded(String text, String encoding) {
            this.text = text;
            this.encoding = encoding;
        }
    }

    public static Decoded decode(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length == 0) {
            return new Decoded("", "empty");
        }

        // 1) BOM 优先
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(stripBom(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)), "utf-8-bom");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(stripBom(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE)), "utf-16le");
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(stripBom(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE)), "utf-16be");
        }

        // 2) 严格 UTF-8 校验：必须校验整份文件。
        //    曾有过的坑：只抽样前 64KB 就把这本书判成 GB18030，结果整本显示成"鍏ㄦ伅寮?"。
        //    UTF-8 校验既严格又快（一次线性扫描），对 2MB 文本毫秒级。
        if (isValidUtf8(bytes, bytes.length)) {
            return new Decoded(stripBom(new String(bytes, StandardCharsets.UTF_8)), "utf-8");
        }

        // 3) GB18030（GBK 超集，覆盖简体中文小说绝大多数情况）
        try {
            Charset gb = Charset.forName("GB18030");
            String text = new String(bytes, gb);
            if (!text.contains("\uFFFD")) {
                return new Decoded(stripBom(text), "gb18030");
            }
        } catch (Exception ignored) {
            // 该设备没有 GB18030 码表，走兜底
        }

        // 4) 兜底：用 UTF-8 解（可能有少量替换字符，但至少能读）
        return new Decoded(stripBom(new String(bytes, StandardCharsets.UTF_8)), "utf-8?(fallback)");
    }

    /** 严格 UTF-8 校验（只查前 sample 字节，成本可控） */
    public static boolean isValidUtf8(byte[] bytes, int sample) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes, 0, sample));
            return out != null;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String stripBom(String text) {
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /** 供外部（如导入流程）使用的编码名展示 */
    public static String describe(String encoding) {
        if ("gb18030".equals(encoding)) {
            return "GB18030/GBK";
        }
        return encoding;
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    @SuppressWarnings("unused")
    private static String decodeWith(String name, byte[] bytes) throws UnsupportedEncodingException {
        return new String(bytes, Charset.forName(name));
    }
}
