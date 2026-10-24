package com.watchreader.fanqie;

/**
 * 主程序 UI 的封面入口：与手表封面页共用 {@link CoverLoader} 的 HTTPS/域名/尺寸限制，
 * 不复制解码逻辑，也不接触任何账号数据。调用方负责放到后台线程。
 */
public final class CoverArt {
    private CoverArt() {}

    public static android.graphics.Bitmap load(String url) throws Exception {
        return CoverLoader.load(url);
    }
}
