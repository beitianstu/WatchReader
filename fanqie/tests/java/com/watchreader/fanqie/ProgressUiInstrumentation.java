package com.watchreader.fanqie;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import com.watchreader.ReaderView;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Real UI callbacks and durable queue, with uploads bound to an in-memory fake. */
public final class ProgressUiInstrumentation extends Instrumentation {
    JSONArray checks=new JSONArray();FanqieActivity activity;ReaderView view;
    void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks.put(name);}
    void main(Runnable task){runOnMainSync(task);waitForIdleSync();}
    Field field(String name)throws Exception{Field f=FanqieActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    void tap(){main(()->{long t=SystemClock.uptimeMillis();MotionEvent a=MotionEvent.obtain(t,t,0,view.getWidth()*.8f,view.getHeight()*.5f,0),b=MotionEvent.obtain(t,t+20,1,view.getWidth()*.8f,view.getHeight()*.5f,0);view.dispatchTouchEvent(a);view.dispatchTouchEvent(b);a.recycle();b.recycle();});}
    void waitFor(java.util.function.BooleanSupplier ready)throws Exception{long end=System.currentTimeMillis()+10000;while(!ready.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(100);if(!ready.getAsBoolean())throw new Exception("UI progress timeout");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){JSONArray failures=new JSONArray();ProgressSync old=null;String scope="instrumentation-ui-sync";try{
        Context ctx=getTargetContext();Intent intent=new Intent(ctx,FanqieActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("progress_test_readonly",true);activity=(FanqieActivity)startActivitySync(intent);old=(ProgressSync)field("progressSync").get(activity);
        ProgressStore store=new ProgressStore(ctx,scope);Client client=new Client();ProgressSync engine=new ProgressSync(client,store);
        List<Paragraph> paras=new ArrayList<>();for(int i=0;i<150;i++)paras.add(new Paragraph(i,"自动进度测试：这一段用于验证翻页后保存与发送，不会写入真实账号。"));ChapterContent content=new ChapterContent("123","456","进度模拟测试","md5",paras);
        main(()->{try{field("progressSync").set(activity,engine);field("progressReadOnly").setBoolean(activity,false);field("bookId").set(activity,"123");field("chapterIndex").setInt(activity,0);field("chapters").set(activity,Arrays.asList(new ChapterInfo("456","测试","md5")));activity.current=content;activity.showReader();}catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(600);
        view=(ReaderView)field("reader").get(activity);check("restore-does-not-enqueue-upload",store.pending().isEmpty()&&client.uploads==0);
        tap();check("page-turn-creates-durable-pending",store.get("123")!=null&&store.get("123").dirty);main(()->activity.flushSavedProgress());waitFor(()->client.uploads>0);waitFor(()->{try{return !store.get("123").dirty;}catch(Exception e){return false;}});check("ui-upload-has-real-paragraph-coordinates",client.last.itemId.equals("456")&&client.last.paragraph>=0&&client.last.timestamp>0);check("ui-ack-clears-pending",store.pending().isEmpty());
        Thread.sleep(500);client.offline=true;tap();main(()->activity.flushSavedProgress());waitFor(()->{try{return field("syncStatus").get(activity).toString().startsWith("同步失败");}catch(Exception e){return false;}});check("ui-offline-keeps-pending",new ProgressStore(ctx,scope).get("123").dirty);
        client.offline=false;main(()->activity.flushSavedProgress());waitFor(()->{try{return !store.get("123").dirty;}catch(Exception e){return false;}});check("ui-retry-uploads-persisted-position",client.uploads>=2&&store.pending().isEmpty());
        List<Paragraph> nextParas=Arrays.asList(new Paragraph(0,"下一章开始：跨章阅读应保存新的章节、段落与全书进度。"));ChapterContent next=new ChapterContent("123","457","进度测试下一章","md5-next",nextParas);
        main(()->{try{field("chapters").set(activity,Arrays.asList(new ChapterInfo("456","测试","md5"),new ChapterInfo("457","下一章","md5-next")));@SuppressWarnings("unchecked") Map<String,ChapterContent> cache=(Map<String,ChapterContent>)field("cache").get(activity);cache.put("123/457/md5-next",next);view.restoreToEnd();}catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(150);tap();waitFor(()->activity.current==next);Thread.sleep(300);ReadingProgress crossed=store.get("123");check("automatic-chapter-entry-queues-new-item-and-index",crossed.itemId.equals("457")&&crossed.chapter==1&&crossed.charOffset==0&&crossed.paragraph==0&&crossed.dirty);main(()->activity.flushSavedProgress());waitFor(()->client.last!=null&&client.last.itemId.equals("457"));check("automatic-chapter-upload-has-correct-book-rate",client.last.bookRate>=.5&&client.last.bookRate<=1);

    }catch(Throwable e){failures.put(e.getClass().getSimpleName()+": "+e.getMessage());}finally{
        try{if(activity!=null){ProgressSync restore=old;main(()->{try{field("progressReadOnly").setBoolean(activity,true);field("progressSync").set(activity,restore);}catch(Exception ignored){}activity.finish();});}getTargetContext().deleteSharedPreferences("fanqie.progress.v1."+scope);JSONObject result=new JSONObject().put("checks",checks).put("failures",failures).put("real_cloud_position_writes",0);try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"test-report.json"))){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));}Bundle b=new Bundle();b.putString("summary","progress UI checks="+checks.length()+" failures="+failures.length()+" real_cloud_writes=0");b.putString("failures",failures.toString());finish(failures.length()==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,b);}catch(Exception e){finish(0,new Bundle());}
    }}
    static final class Client implements ProgressSync.Client {
        volatile boolean offline;volatile int uploads;volatile ReadingProgress last;
        public ReadingProgress read(String book)throws Exception{if(offline)throw new IOException("offline mock");return last==null?null:last.clean();}
        public void upload(ReadingProgress p)throws Exception{if(offline)throw new IOException("offline mock");last=p;uploads++;}
    }
}
