package com.watchreader;

import java.io.File;
import java.io.IOException;

/**
 * 进程内"最近打开的一本书"缓存。
 *
 * 解决的是最典型的重复打开路径：书库 → 阅读页 → 返回书库 → 再点同一本。
 * 这种情况下索引缓存本来就能命中，但每次仍要把 2MB 级别的缓存文件读一遍、
 * 再把索引与正文解析成对象（手表上这是几百毫秒）。只要书没变，
 * 上一次的 {@link Book} 是不可变的，可以直接复用。
 *
 * 只留最近 1 本（单本书的内存约等于它自己的正文 + 行索引），不会无限增长；
 * 命中条件 = 绝对路径 + fileSize + lastModified 全一致，所以书被改过/换过一定不会复用。
 * 线程安全：整个 open 串行化，进程里同时只可能有一本书在装载。
 */
public final class BookCache {

    private static String key;
    private static Book book;

    private BookCache() {
    }

    public static synchronized Book open(File file, File cacheDir, Book.LoadListener listener) throws IOException {
        String k = keyOf(file);
        if (book != null && k.equals(key)) {
            // 内存里就是这本书：连缓存文件都不用读
            if (listener != null) {
                listener.onPhase(Book.LoadPhase.FROM_CACHE);
            }
            return book;
        }
        Book opened = Book.open(file, cacheDir, listener);
        key = k;
        book = opened;
        return opened;
    }

    private static String keyOf(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }
}
