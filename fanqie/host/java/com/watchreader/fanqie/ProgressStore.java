package com.watchreader.fanqie;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
import com.watchreader.fanqie.ReaderPlugin.ReadingProgress;

/** Durable per-account positions and pending uploads. Does not store credentials. */
public final class ProgressStore implements ProgressSync.Store {
    private final SharedPreferences prefs;
    public ProgressStore(Context context,String accountScope){prefs=context.getSharedPreferences("fanqie.progress.v1."+accountScope,0);}
    public synchronized ReadingProgress get(String book)throws Exception {String s=prefs.getString(book,"");return s.isEmpty()?null:decode(new JSONObject(s));}
    public synchronized void put(ReadingProgress p)throws Exception {if(!prefs.edit().putString(p.bookId,encode(p).toString()).commit())throw new Exception("阅读进度保存失败");}
    public synchronized boolean replace(ReadingProgress expected,ReadingProgress next)throws Exception {ReadingProgress current=get(next.bookId);if(expected==null?current!=null:!expected.same(current))return false;put(next);return true;}
    public synchronized List<String> pending()throws Exception {List<String> books=new ArrayList<>();for(String book:prefs.getAll().keySet()){ReadingProgress p=get(book);if(p!=null&&p.dirty)books.add(book);}return books;}
    static JSONObject encode(ReadingProgress p)throws Exception {return new JSONObject().put("book",p.bookId).put("item",p.itemId).put("chapter",p.chapter).put("paragraph",p.paragraph).put("paragraph_offset",p.paragraphOffset).put("char_offset",p.charOffset).put("timestamp",p.timestamp).put("book_rate",p.bookRate).put("page_rate",p.pageRate).put("version",p.version).put("dirty",p.dirty);}
    static ReadingProgress decode(JSONObject j)throws Exception {return new ReadingProgress(j.getString("book"),j.getString("item"),j.optInt("chapter"),j.optInt("paragraph",-1),j.optInt("paragraph_offset"),j.optInt("char_offset",-1),j.optLong("timestamp"),j.optDouble("book_rate"),j.optDouble("page_rate"),j.optString("version"),j.optBoolean("dirty"));}
}
