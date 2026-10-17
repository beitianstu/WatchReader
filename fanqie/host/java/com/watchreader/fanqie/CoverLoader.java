package com.watchreader.fanqie;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Public CDN requests never use the authenticated API transport or account headers. */
final class CoverLoader {
    static URL checkedUrl(String source)throws Exception {
        URL url=new URL(source);String host=url.getHost().toLowerCase(java.util.Locale.ROOT);
        if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)||!host.endsWith(".byteimg.com"))throw new IOException("封面地址不受支持");
        return url;
    }
    static Bitmap load(String source)throws Exception {
        URL url=checkedUrl(source);
        for(int redirects=0;redirects<=2;redirects++){
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
            try{
                c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
                c.setRequestProperty("User-Agent","WatchReader/1.1");c.setRequestProperty("Cookie","");
                int status=c.getResponseCode();
                if(status==301||status==302||status==307||status==308){url=checkedUrl(new URL(url,c.getHeaderField("Location")).toString());continue;}
                if(status!=200)throw new IOException("封面暂时无法加载");
                byte[] bytes=PluginWire.readLimited(c.getInputStream(),2*1024*1024);
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(options.outWidth<=0||options.outHeight<=0||options.outWidth>8192||options.outHeight>8192)throw new IOException("封面格式不受支持");
                options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>700)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;options.inScaled=false;Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(image==null)throw new IOException("封面格式不受支持");return image;
            }finally{c.disconnect();}
        }
        throw new IOException("封面重定向次数过多");
    }
}
