package com.watchreader.fanqie;

import java.io.*;
import java.util.*;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Versioned data-only IPC. Account secrets and arbitrary file paths are never part of it. */
public final class PluginWire {
    public static final int VERSION=1;
    public static JSONObject book(BookInfo b)throws Exception{return new JSONObject().put("id",b.id).put("title",b.title).put("author",b.author);}
    public static BookInfo book(JSONObject j)throws Exception{return new BookInfo(j.getString("id"),j.optString("title"),j.optString("author"));}
    public static JSONObject chapter(ChapterInfo c)throws Exception{return new JSONObject().put("id",c.id).put("title",c.title).put("version",c.version);}
    public static ChapterInfo chapter(JSONObject j)throws Exception{return new ChapterInfo(j.getString("id"),j.optString("title"),j.optString("version"));}
    public static JSONObject paragraph(Paragraph p)throws Exception{return new JSONObject().put("index",p.index).put("text",p.text);}
    public static Paragraph paragraph(JSONObject j)throws Exception{return new Paragraph(j.getInt("index"),j.getString("text"));}
    public static JSONObject content(ChapterContent c)throws Exception{JSONArray a=new JSONArray();for(Paragraph p:c.paragraphs)a.put(paragraph(p));return new JSONObject().put("book",c.bookId).put("item",c.itemId).put("title",c.title).put("version",c.version).put("paragraphs",a);}
    public static ChapterContent content(JSONObject j)throws Exception{List<Paragraph> list=new ArrayList<>();JSONArray a=j.getJSONArray("paragraphs");for(int i=0;i<a.length();i++)list.add(paragraph(a.getJSONObject(i)));return new ChapterContent(j.getString("book"),j.getString("item"),j.optString("title"),j.optString("version"),list);}
    public static JSONObject progress(ReadingProgress p)throws Exception{return new JSONObject().put("book",p.bookId).put("item",p.itemId).put("chapter",p.chapter).put("paragraph",p.paragraph).put("paragraph_offset",p.paragraphOffset).put("char_offset",p.charOffset).put("timestamp",p.timestamp).put("book_rate",p.bookRate).put("page_rate",p.pageRate).put("version",p.version).put("dirty",p.dirty);}
    public static ReadingProgress progress(JSONObject j)throws Exception{return new ReadingProgress(j.getString("book"),j.getString("item"),j.optInt("chapter"),j.optInt("paragraph",-1),j.optInt("paragraph_offset"),j.optInt("char_offset",-1),j.optLong("timestamp"),j.optDouble("book_rate"),j.optDouble("page_rate"),j.optString("version"),j.optBoolean("dirty"));}
    public static byte[] readLimited(InputStream in,int limit)throws Exception{try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>limit)throw new IOException("接口响应过大");out.write(b,0,n);}return out.toByteArray();}}
    private PluginWire(){}
}
