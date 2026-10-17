package com.watchreader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 章节识别：从排版文本里扫出"章标题 + 起始字符偏移"。
 *
 * 为什么单独一个类：这是**纯逻辑**，与 Android 运行时无关，
 * 抽出来后桌面测试（tools/loadtest）能直接测真实实现，
 * 而不是在测试里复制一份正则来"验证自己"。
 *
 * 匹配规则（启发式，匹配不到不报错，目录退化成一章）：
 *   第X章/节/回/卷/幕/篇/集/话（中文数字或阿拉伯数字）、Chapter N、序章/楔子/番外 等。
 */
public final class Chapters {

    /** 一个章节标题及其在排版文本中的起始字符偏移 */
    public static final class Chapter {
        public final String title;
        public final int offset;

        Chapter(String title, int offset) {
            this.title = title;
            this.offset = offset;
        }
    }

    private static final Pattern[] CHAPTER_PATTERNS = new Pattern[] {
            Pattern.compile("^第\\s*[0-9零一二三四五六七八九十百千万两]{1,12}\\s*[章节節回卷幕篇集话話]"),
            Pattern.compile("^第\\s*[0-9]{1,6}\\s*[章节節回卷幕篇集话話]"),
            Pattern.compile("^(Chapter|CHAPTER)\\s*[0-9IVXLC]{1,6}"),
            Pattern.compile("^(序章|序言|楔子|前言|引子|后记|後記|尾声|尾聲|番外|正文|终章|終章)"),
    };

    /** 章节标题行的最大长度（超过就不可能是标题） */
    private static final int MAX_TITLE_LEN = 64;

    /** 单本书最多收录多少章（防止异常文本把目录撑爆） */
    private static final int MAX_CHAPTERS = 3000;

    private Chapters() {
    }

    public static List<Chapter> scan(String text) {
        List<Chapter> result = new ArrayList<>();
        int start = 0;
        int guard = 0;
        while (start <= text.length() && guard++ < 200000) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            int len = end - start;
            if (len > 0 && len <= MAX_TITLE_LEN && mayStartChapter(firstChar(text, start, end))) {
                String line = text.substring(start, end).trim();
                line = line.replace("\u3000", " ").trim();
                if (isChapterHead(line)) {
                    result.add(new Chapter(line, start));
                    if (result.size() >= MAX_CHAPTERS) {
                        break;
                    }
                }
            }
            if (end >= text.length()) {
                break;
            }
            start = end + 1;
        }
        return result;
    }

    /**
     * 行内第一个"非行首空白"字符，取法与 scan 里 {@code trim().replace("\u3000"," ").trim()}
     * 完全一致：ASCII 空白（<= U+0020）与全角空格都算行首空白。
     */
    private static char firstChar(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\u3000' || c <= ' ') {
                continue;
            }
            return c;
        }
        return 0;
    }

    /**
     * 快速预筛：4 条正则都是 {@code ^} 锚定的，首字符只可能是下面这些。
     * 首字符不在集合里的行（正文的绝大多数）直接跳过，不再为每一行创建 4 个 Matcher。
     * 这是**纯加速**：判定集合与正则的首字符集合逐一对应，结果与"每行都跑正则"完全一致。
     */
    private static boolean mayStartChapter(char c) {
        switch (c) {
            case '第':   // ^第...章/节/回...
            case 'C':   // ^(Chapter|CHAPTER)...（正则区分大小写，'c' 开头不匹配）
            case '序':   // 序章/序言
            case '楔':   // 楔子
            case '前':   // 前言
            case '引':   // 引子
            case '后':   // 后记
            case '後':   // 後記
            case '尾':   // 尾声/尾聲
            case '番':   // 番外
            case '正':   // 正文
            case '终':   // 终章
            case '終':   // 終章
                return true;
            default:
                return false;
        }
    }

    private static boolean isChapterHead(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (Pattern p : CHAPTER_PATTERNS) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
