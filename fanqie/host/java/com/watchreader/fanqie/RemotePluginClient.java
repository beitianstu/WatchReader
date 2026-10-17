package com.watchreader.fanqie;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Host-side adapter. No signer, decryption key, Cookie, or account-file access. */
public final class RemotePluginClient implements ReaderPlugin,AutoCloseable {
    private final Context context;private volatile boolean loggedIn;private volatile String scope="unconnected";
    private ContentProviderClient session;private boolean closed;
    public RemotePluginClient(Context context){this.context=context.getApplicationContext();}
    public String id(){return "fanqie";}
    public boolean loggedIn(){return loggedIn;}
    public String accountScope(){return scope;}
    public List<ReadingProgress> legacyProgress()throws Exception{JSONArray a=(JSONArray)rpc("legacy_progress",new JSONObject());List<ReadingProgress> result=new ArrayList<>();for(int i=0;i<a.length();i++)result.add(PluginWire.progress(a.getJSONObject(i)));return result;}
    private void verify()throws Exception {ProviderInfo provider=context.getPackageManager().resolveContentProvider(PluginTrust.AUTHORITY,0);if(provider==null||!provider.enabled||!PluginTrust.PLUGIN.equals(provider.packageName)||!PluginTrust.plugin(context))throw new IOException("番茄插件未安装、未启用或版本不受信任，请先安装配套插件");}
    private synchronized ContentProviderClient session()throws Exception{if(closed)throw new IOException("番茄插件连接已关闭，请重新打开");if(session==null)session=context.getContentResolver().acquireContentProviderClient(PluginTrust.AUTHORITY);if(session==null)throw new IOException("番茄插件连接中断，请从主程序重新打开插件");return session;}
    @Override public synchronized void close(){closed=true;if(session!=null){session.close();session=null;}}
    private Object rpc(String method,JSONObject request)throws Exception {
        verify();Uri uri=new Uri.Builder().scheme("content").authority(PluginTrust.AUTHORITY).appendPath("v1").appendPath(method).appendQueryParameter("request",request.toString()).build();
        try(ParcelFileDescriptor fd=session().openFile(uri,"r")){
            if(fd==null)throw new IOException("番茄插件未返回数据");JSONObject result=new JSONObject(new String(PluginWire.readLimited(new ParcelFileDescriptor.AutoCloseInputStream(fd),8*1024*1024),StandardCharsets.UTF_8));
            if(!result.optBoolean("ok"))throw new IOException(result.optString("error","番茄插件请求失败"));return result.opt("value");
        }catch(android.os.DeadObjectException|FileNotFoundException e){close();throw new IOException("番茄插件连接已中断，请从主程序重新打开插件",e);}
        catch(android.os.OperationCanceledException|IllegalArgumentException e){throw new IOException("番茄插件连接已中断，请重新打开书架",e);}
    }
    public void refresh()throws Exception {JSONObject status=(JSONObject)rpc("hello",new JSONObject());if(status.optInt("protocol")!=PluginWire.VERSION)throw new IOException("番茄插件接口版本不兼容");loggedIn=status.getBoolean("logged_in");scope=status.getString("scope");}
    private Page<BookInfo> books(JSONObject q,String method)throws Exception{JSONObject j=(JSONObject)rpc(method,q);List<BookInfo> list=new ArrayList<>();JSONArray a=j.getJSONArray("items");for(int i=0;i<a.length();i++)list.add(PluginWire.book(a.getJSONObject(i)));return new Page<>(list,j.optString("next"),j.optBoolean("more"));}
    public Page<BookInfo> bookshelf(String cursor)throws Exception{return bookshelf("",cursor);}
    public Page<BookInfo> bookshelf(String group,String cursor)throws Exception{return books(new JSONObject().put("group",group).put("cursor",cursor),"shelf");}
    public Page<BookInfo> search(String keyword,String cursor)throws Exception{return books(new JSONObject().put("keyword",keyword).put("cursor",cursor),"search");}
    public List<ShelfGroup> shelfGroups()throws Exception{JSONArray a=(JSONArray)rpc("groups",new JSONObject());List<ShelfGroup> list=new ArrayList<>();for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);list.add(new ShelfGroup(j.getString("id"),j.getString("name"),j.optInt("count")));}return list;}
    public List<ChapterInfo> directory(String book)throws Exception{JSONArray a=(JSONArray)rpc("directory",new JSONObject().put("book",book));List<ChapterInfo> list=new ArrayList<>();for(int i=0;i<a.length();i++)list.add(PluginWire.chapter(a.getJSONObject(i)));return list;}
    public ChapterContent chapter(String book,ChapterInfo chapter)throws Exception{return PluginWire.content((JSONObject)rpc("chapter",new JSONObject().put("book",book).put("chapter",PluginWire.chapter(chapter))));}
    public BookDetails details(BookInfo book)throws Exception{JSONObject j=(JSONObject)rpc("details",new JSONObject().put("book",PluginWire.book(book)));return new BookDetails(PluginWire.book(j.getJSONObject("book")),j.optString("description"),j.optString("cover"),j.optString("chapter_title"),j.optInt("count"));}
    public ReadingProgress readProgress(String book)throws Exception{Object j=rpc("progress",new JSONObject().put("book",book));return j instanceof JSONObject?PluginWire.progress((JSONObject)j):null;}
    public void uploadProgress(ReadingProgress p)throws Exception{rpc("upload",new JSONObject().put("progress",PluginWire.progress(p)));}
    private JSONObject target(ChapterContent chapter,Paragraph paragraph)throws Exception{if(!chapter.paragraphs.contains(paragraph))throw new IOException("段评目标不属于当前章节");return new JSONObject().put("chapter",PluginWire.content(new ChapterContent(chapter.bookId,chapter.itemId,chapter.title,chapter.version,Collections.singletonList(paragraph))));}
    public Page<Comment> comments(ChapterContent chapter,Paragraph paragraph,String cursor)throws Exception{JSONObject j=(JSONObject)rpc("comments",target(chapter,paragraph).put("cursor",cursor));List<Comment> list=new ArrayList<>();JSONArray a=j.getJSONArray("items");for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);list.add(new Comment(c.getString("id"),c.optString("author"),c.optString("text")));}return new Page<>(list,j.optString("next"),j.optBoolean("more"));}
    public String publish(ChapterContent chapter,Paragraph paragraph,String text)throws Exception{return (String)rpc("publish",target(chapter,paragraph).put("text",text));}
    public Intent configureIntent()throws Exception{verify();return new Intent().setClassName(PluginTrust.PLUGIN,PluginTrust.PLUGIN+".PluginSetupActivity");}
    public Intent bootstrapIntent()throws Exception{return configureIntent().putExtra("connect_only",true);}
}
