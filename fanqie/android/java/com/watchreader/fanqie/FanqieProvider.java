package com.watchreader.fanqie;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Installed data source only. Large replies stream through a pipe, never Binder payloads. */
public final class FanqieProvider extends ContentProvider {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ExecutorService chapterExecutor=Executors.newFixedThreadPool(4);
    private final Semaphore requests=new Semaphore(4);
    private final Semaphore chapterRequests=new Semaphore(8);
    private volatile FanqiePlugin activeSource;
    private final ConcurrentMap<Thread,ChapterSession> chapterSessions=new ConcurrentHashMap<>();
    private static final class ChapterSession {final FanqiePlugin owner,client;ChapterSession(FanqiePlugin owner)throws Exception{this.owner=owner;client=owner.chapterSession();}}
    private synchronized FanqiePlugin source(boolean refresh)throws Exception{if(activeSource==null||refresh){activeSource=new FanqiePlugin(new CredentialStore(getContext()));chapterSessions.clear();}return activeSource;}
    private FanqiePlugin chapterClient(FanqiePlugin owner)throws Exception{Thread thread=Thread.currentThread();ChapterSession session=chapterSessions.get(thread);if(session==null||session.owner!=owner){session=new ChapterSession(owner);chapterSessions.put(thread,session);}return session.client;}
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode){
        PluginTrust.enforceHost(getContext(),Binder.getCallingUid());
        if(!"r".equals(mode)||!PluginTrust.AUTHORITY.equals(uri.getAuthority())||uri.getPathSegments().size()!=2||!uri.getPathSegments().get(0).equals("v1"))throw new IllegalArgumentException("Unsupported plugin protocol");
        final String method=uri.getPathSegments().get(1);final boolean chapter=method.equals("chapter");final Semaphore permits=chapter?chapterRequests:requests;final String request=uri.getQueryParameter("request")==null?"{}":uri.getQueryParameter("request");if(request.length()>65536||!permits.tryAcquire())throw new IllegalArgumentException("Plugin request limit exceeded");
        final ParcelFileDescriptor[] pipe;
        try{pipe=ParcelFileDescriptor.createPipe();}catch(IOException e){permits.release();throw new IllegalStateException("Cannot create plugin stream");}
        (chapter?chapterExecutor:executor).execute(()->{FanqiePlugin owner=null;try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
            JSONObject response;try{owner=source(method.equals("hello"));response=new JSONObject().put("ok",true).put("value",dispatch(chapter?chapterClient(owner):owner,method,new JSONObject(request)));}
            catch(Exception e){String message=e.getMessage();if(message==null||!(message.startsWith("番茄")||message.startsWith("登录")||message.startsWith("请先")||message.startsWith("本章")||message.startsWith("这本")||message.startsWith("设备")||message.startsWith("网络")||message.startsWith("段评")||message.startsWith("请求过快")))message="番茄插件请求失败，请检查网络或账号";response=new JSONObject().put("ok",false).put("error",message);}
            byte[] data=response.toString().getBytes(StandardCharsets.UTF_8);if(data.length>8*1024*1024)throw new IOException("Plugin reply exceeds limit");output.write(data);
        }catch(Exception ignored){}finally{if(chapter&&owner!=activeSource)chapterSessions.remove(Thread.currentThread());permits.release();}});
        return pipe[0];
    }
    private Object dispatch(FanqiePlugin source,String method,JSONObject q)throws Exception {
        if(method.equals("hello"))return new JSONObject().put("protocol",PluginWire.VERSION).put("logged_in",source.loggedIn()).put("scope",source.accountScope());
        if(method.equals("legacy_progress")){JSONArray result=new JSONArray();for(Object value:getContext().getSharedPreferences("fanqie.progress.v1."+source.accountScope(),0).getAll().values())if(value instanceof String){try{result.put(PluginWire.progress(PluginWire.progress(new JSONObject((String)value))));}catch(Exception ignored){}}return result;}
        if(method.equals("groups")){JSONArray a=new JSONArray();for(ShelfGroup g:source.shelfGroups())a.put(new JSONObject().put("id",g.id).put("name",g.name).put("count",g.count));return a;}
        if(method.equals("shelf")||method.equals("search")){Page<BookInfo> page=method.equals("shelf")?source.bookshelf(q.optString("group"),q.optString("cursor")):source.search(q.getString("keyword"),q.optString("cursor"));JSONArray a=new JSONArray();for(BookInfo b:page.items)a.put(PluginWire.book(b));return new JSONObject().put("items",a).put("next",page.next).put("more",page.more);}
        if(method.equals("directory")){JSONArray a=new JSONArray();for(ChapterInfo c:source.directory(q.getString("book")))a.put(PluginWire.chapter(c));return a;}
        if(method.equals("chapter"))return PluginWire.content(source.chapter(q.getString("book"),PluginWire.chapter(q.getJSONObject("chapter"))));
        if(method.equals("details")){BookDetails d=source.details(PluginWire.book(q.getJSONObject("book")));return new JSONObject().put("book",PluginWire.book(d.book)).put("description",d.description).put("cover",d.coverUrl).put("chapter_title",d.latestChapter).put("count",d.chapters);}
        if(method.equals("progress")){ReadingProgress p=source.readProgress(q.getString("book"));return p==null?JSONObject.NULL:PluginWire.progress(p);}
        if(method.equals("upload")){source.uploadProgress(PluginWire.progress(q.getJSONObject("progress")));return true;}
        if(method.equals("comments")||method.equals("publish")){ChapterContent chapter=PluginWire.content(q.getJSONObject("chapter"));if(chapter.paragraphs.size()!=1)throw new IllegalArgumentException("Expected one target paragraph");Paragraph paragraph=chapter.paragraphs.get(0);
            if(method.equals("publish"))return source.publish(chapter,paragraph,q.getString("text"));
            Page<Comment> page=source.comments(chapter,paragraph,q.optString("cursor"));JSONArray a=new JSONArray();for(Comment c:page.items)a.put(new JSONObject().put("id",c.id).put("author",c.author).put("text",c.text));return new JSONObject().put("items",a).put("next",page.next).put("more",page.more);
        }
        throw new IllegalArgumentException("Unknown plugin operation");
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String sort){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return "application/vnd.watchreader.source";}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
