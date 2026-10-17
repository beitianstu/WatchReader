package com.watchreader.fanqie;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.watchreader.ReaderView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Invoked with the plugin disabled; proves the completed host TXT is independent. */
public final class OfflinePluginInstrumentation extends Instrumentation {
    JSONArray checks=new JSONArray(),failures=new JSONArray();
    void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks.put(name);}
    <T extends View>T find(View v,Class<T> c){if(c.isInstance(v))return c.cast(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){T r=find(g.getChildAt(i),c);if(r!=null)return r;}}return null;}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){try{
        Context ctx=getTargetContext();android.content.pm.ProviderInfo provider=ctx.getPackageManager().resolveContentProvider(PluginTrust.AUTHORITY,0);check("plugin-is-disabled-during-offline-test",provider==null||!provider.enabled);
        File file=new File(ctx.getFilesDir(),"books/联动下载自动化测试_9912349.txt");check("completed-download-is-owned-by-host",file.isFile());Activity reader=startActivitySync(new Intent(ctx,com.watchreader.ReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(com.watchreader.ReaderActivity.EXTRA_PATH,file.getAbsolutePath()));
        ReaderView view=null;long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){view=find(reader.getWindow().getDecorView(),ReaderView.class);if(view!=null&&view.getCharCount()>0)break;Thread.sleep(150);}check("main-reads-downloaded-book-with-plugin-disabled",view!=null&&view.getCharCount()>0);ReaderView bound=view;runOnMainSync(bound::restoreToEnd);check("offline-page-count-remains-correct",view.getPageIndex()+1==view.getPageCount());runOnMainSync(reader::finish);
        FanqieActivity online=(FanqieActivity)startActivitySync(new Intent(ctx,FanqieActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("progress_test_readonly",true));Thread.sleep(600);StringBuilder labels=new StringBuilder();collect(online.getWindow().getDecorView(),labels);check("missing-plugin-shows-actionable-message",labels.toString().contains("插件未安装、未启用")&&labels.toString().contains("本地书库"));runOnMainSync(online::finish);
        check("test-book-cleaned-without-touching-user-books",file.delete());
    }catch(Throwable e){failures.put(e.getClass().getSimpleName()+": "+e.getMessage());}finally{try{JSONObject report=new JSONObject().put("checks",checks).put("failures",failures);try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),"offline-plugin-report.json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}Bundle b=new Bundle();b.putString("summary","offline checks="+checks.length()+" failures="+failures.length());b.putString("failures",failures.toString());finish(failures.length()==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,b);}catch(Exception ignored){finish(0,new Bundle());}}}
    void collect(View view,StringBuilder text){if(view instanceof TextView)text.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),text);}}
}
