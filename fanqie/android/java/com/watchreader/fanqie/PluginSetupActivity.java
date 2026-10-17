package com.watchreader.fanqie;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Account setup only, invoked for result by the trusted host. No library or reader. */
public final class PluginSetupActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private LinearLayout body;private boolean alive=true,busy;
    private ScrollView scroll;private final CrownScroller crownScroller=new CrownScroller();
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().getDecorView().setSystemUiVisibility(5894);
        if(!PluginTrust.HOST.equals(getCallingPackage())||!PluginTrust.host(this)){page();note("番茄数据源插件");note("请从配套 WatchReader 主程序的“番茄插件”入口使用。");return;}
        if(getIntent().getBooleanExtra("connect_only",false)){setResult(RESULT_OK);finish();return;}account();}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){if(crownScroller.handle(event,scroll))return true;return super.dispatchGenericMotionEvent(event);}
    private void page(){crownScroller.reset();scroll=new ScrollView(this);scroll.setBackgroundColor(0xff0b0f14);body=new LinearLayout(this);body.setOrientation(1);int inset=Math.round(26*getResources().getDisplayMetrics().density);body.setPadding(inset,inset,inset,inset);scroll.addView(body);setContentView(scroll);}
    private void note(String text){TextView view=new TextView(this);view.setText(text);view.setTextColor(Color.WHITE);view.setTextSize(13);body.addView(view);}
    private void action(String text,Runnable run){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(v->{if(!busy)run.run();});body.addView(b);}
    private void account(){page();note("番茄插件 · 账号设置");note("账号仅在插件内加密保存，主程序不接收 Cookie。");action("选择 Cookie 文件",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(i,1);});action("粘贴 Cookie",()->paste());action("退出并清除账号",()->new android.app.AlertDialog.Builder(this).setMessage("清除番茄插件中的登录账号？").setPositiveButton("清除",(d,w)->clear()).setNegativeButton("取消",null).show());action("返回 WatchReader",()->{setResult(RESULT_OK);finish();});}
    private void paste(){page();note("粘贴 Cookie / JSON");EditText input=new EditText(this);input.setTextColor(Color.WHITE);input.setTextSize(12);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);input.setPrivateImeOptions("nm");body.addView(input);action("加密保存",()->{String value=input.getText().toString();input.setText("");save(value);});action("取消",()->account());}
    private void save(String value){busy=true;page();note("正在加密保存…");worker.execute(()->{try{new FanqiePlugin(new CredentialStore(this)).setCookie(value);runOnUiThread(()->{if(alive){setResult(RESULT_OK);finish();}});}catch(Exception e){runOnUiThread(()->{if(alive){busy=false;account();note("账号保存失败，请检查 Cookie 格式或有效期");}});}});}
    private void clear(){busy=true;worker.execute(()->{new CredentialStore(this).clear();runOnUiThread(()->{if(alive){setResult(RESULT_OK);finish();}});});}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=1||result!=RESULT_OK||data==null||data.getData()==null)return;busy=true;page();note("正在读取账号文件…");android.net.Uri uri=data.getData();worker.execute(()->{try{String value=new String(PluginWire.readLimited(getContentResolver().openInputStream(uri),512*1024),StandardCharsets.UTF_8);runOnUiThread(()->{if(alive)save(value);});}catch(Exception e){runOnUiThread(()->{if(alive){busy=false;account();note("无法读取账号文件");}});}});}
    @Override public void onBackPressed(){if(!busy){setResult(RESULT_OK);super.onBackPressed();}}
    @Override public void onDestroy(){alive=false;worker.shutdown();super.onDestroy();}
}
