package com.watchreader.fanqie;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import com.watchreader.Book;
import java.util.*;
import java.util.concurrent.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Joins the local reader to the same account-scoped progress store as online reading. */
public final class DownloadedReading implements AutoCloseable {
    public interface Listener {void restore(int offset,long timestamp);void status(String status);}
    private final Activity activity;private final Book book;private final ReaderPlugin source;private final String scope;
    private final ProgressSync sync;private final Listener listener;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());private final Runnable delayed=()->synchronize(false);
    private final LinkedHashMap<Integer,DownloadedNovel.Match> matches=new LinkedHashMap<Integer,DownloadedNovel.Match>(4,.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,DownloadedNovel.Match> e){return size()>4;}};
    private volatile DownloadedNovel.Index index;private volatile int offset,revision;private volatile long timestamp;private volatile boolean closed;private boolean busy,again;
    private volatile String status="等待与番茄同步";
    public DownloadedReading(Activity activity,Book book,ReaderPlugin source,String scope,int offset,long timestamp,Listener listener){
        this.activity=activity;this.book=book;this.source=source;this.scope=scope;this.offset=offset;this.timestamp=timestamp;this.listener=listener;
        sync=new ProgressSync(source,new ProgressStore(activity,scope));
    }
    public String status(){return status;}
    public void changed(int offset,long timestamp){this.offset=offset;this.timestamp=timestamp;revision++;report("本地已保存，等待同步");handler.removeCallbacks(delayed);handler.postDelayed(delayed,2000);}
    private void report(String message){status=message;activity.runOnUiThread(()->{if(!closed)listener.status(message);});}
    private void guard()throws Exception{if(source instanceof RemotePluginClient){RemotePluginClient account=(RemotePluginClient)source;account.refresh();if(!account.loggedIn()||!scope.equals(account.accountScope()))throw new Exception("登录账号已变更");}}
    private DownloadedNovel.Match match(int chapter)throws Exception{DownloadedNovel.Match m=matches.get(chapter);if(m==null){m=index.match(chapter,source.chapter(index.novel.info.id,index.novel.chapters.get(chapter)));matches.put(chapter,m);index.novel.saveIfMissing(book.file);}return m;}
    public void synchronize(boolean explicit){
        if(closed)return;handler.removeCallbacks(delayed);if(busy){again=true;return;}busy=true;
        final int requested=revision,position=offset;final long changedAt=timestamp;
        worker.execute(()->{try{
            guard();if(index==null)index=DownloadedNovel.load(book.file,scope,source).index(book);
            String id=index.novel.info.id;ReadingProgress saved=sync.store.get(id);
            if(changedAt>0&&(saved==null||changedAt>saved.timestamp)){
                ReadingProgress captured=match(index.chapterAt(position)).capture(position,changedAt);
                // A page arriving during a network read is handled by the following pass.
                if(requested==revision)sync.store.replace(saved,captured);
            }
            sync.flush(id);ReadingProgress p=sync.pull(id);Integer restore=null;
            if(p!=null&&p.timestamp>=changedAt){for(int i=0;i<index.novel.chapters.size();i++)if(index.novel.chapters.get(i).id.equals(p.itemId)){restore=match(i).offset(p);break;}}
            final Integer target=restore;final long remoteTime=p==null?0:p.timestamp;
            activity.runOnUiThread(()->{if(!closed&&requested==revision&&target!=null){offset=target;timestamp=remoteTime;listener.restore(target,remoteTime);} });
            report(p!=null&&p.dirty?"本地进度待上传":"已与番茄同步");
        }catch(Exception e){String m=e.getMessage();report(m!=null&&(m.startsWith("下载")||m.startsWith("登录")||m.startsWith("这本"))?m:"同步失败，本地进度已保留");}
        finally{activity.runOnUiThread(()->{busy=false;if(!closed&&(again||requested!=revision)){again=false;synchronize(false);}});}});
    }
    public android.content.Intent actionIntent(String action){
        DownloadedNovel.Index current=index;if(current==null)return null;
        return new android.content.Intent(activity,FanqieActivity.class).putExtra("reader_book",current.novel.info.id).putExtra("reader_title",current.novel.info.title).putExtra("reader_item",current.novel.chapters.get(current.chapterAt(offset)).id).putExtra("reader_action",action);
    }
    public void flush(){synchronize(false);}
    @Override public void close(){if(closed)return;handler.removeCallbacks(delayed);closed=true;worker.execute(()->{if(source instanceof RemotePluginClient)((RemotePluginClient)source).close();});worker.shutdown();}
}
