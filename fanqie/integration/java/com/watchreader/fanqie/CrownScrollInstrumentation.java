package com.watchreader.fanqie;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Rect;
import com.watchreader.ReaderView;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Activity routing and the real plugin configuration window; never changes account data. */
public final class CrownScrollInstrumentation extends Instrumentation {
    JSONArray checks=new JSONArray(),failures=new JSONArray();FanqieActivity activity;String stage="start";
    void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks.put(name);}
    void main(Runnable r){runOnMainSync(r);waitForIdleSync();}
    <T extends View>T find(View root,Class<T> type,String label){if(type.isInstance(root)&&(label==null||(root instanceof TextView&&((TextView)root).getText().toString().equals(label))))return type.cast(root);if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){T result=find(g.getChildAt(i),type,label);if(result!=null)return result;}}return null;}
    View decor(){return activity.getWindow().getDecorView();}
    ScrollView scroll(){return find(decor(),ScrollView.class,null);}
    ReaderView reader(){return find(decor(),ReaderView.class,null);}
    void await(BooleanSupplier ready)throws Exception{long end=System.currentTimeMillis()+45000;while(System.currentTimeMillis()<end){boolean[] ok={false};main(()->ok[0]=ready.getAsBoolean());if(ok[0])return;Thread.sleep(150);}throw new IOException("UI timeout at "+stage);}
    void button(String label)throws Exception{await(()->find(decor(),Button.class,label)!=null);}
    void click(String label){main(()->{Button b=find(decor(),Button.class,label);if(b==null)throw new AssertionError("Missing "+label);b.performClick();});}
    MotionEvent event(float raw){long now=SystemClock.uptimeMillis();MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.setAxisValue(MotionEvent.AXIS_SCROLL,raw);return MotionEvent.obtain(now,now,MotionEvent.ACTION_SCROLL,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_ROTARY_ENCODER,0);}
    void crown(float raw){main(()->{MotionEvent e=event(raw);activity.dispatchGenericMotionEvent(e);e.recycle();});}
    void verify(String name)throws Exception{stage=name;await(()->scroll()!=null&&scroll().getHeight()>0&&scroll().getChildAt(0).getHeight()>scroll().getHeight());ScrollView view=scroll();main(()->{view.scrollTo(0,0);Button focus=find(view,Button.class,null);if(focus!=null)focus.requestFocus();});crown(-.2f);int down=view.getScrollY();check(name+"-crown-scrolls-with-button-focus",down>0);crown(.2f);check(name+"-reverse-scrolls-up",view.getScrollY()<down);main(()->view.scrollTo(0,0));crown(-.001f);check(name+"-jitter-does-not-scroll",view.getScrollY()==0);main(()->view.scrollTo(0,view.getChildAt(0).getHeight()));int bottom=view.getScrollY();crown(-.5f);check(name+"-bottom-is-clamped",view.getScrollY()==bottom);}
    void set(String name,Object value){try{Field f=FanqieActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new RuntimeException(e);}}
    Rect bounds(String label){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return null;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(label);for(AccessibilityNodeInfo n:nodes)if(label.contentEquals(n.getText()==null?"":n.getText())){Rect rect=new Rect();n.getBoundsInScreen(rect);return rect;}return null;}
    void inject(float raw){MotionEvent e=event(raw);boolean ok=getUiAutomation().injectInputEvent(e,true);e.recycle();if(!ok)throw new AssertionError("Rotary injection failed");}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){try{
        activity=(FanqieActivity)startActivitySync(new Intent(getTargetContext(),FanqieActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("progress_test_readonly",true));button("书架分类");Fake fake=new Fake();main(()->{activity.plugin=fake;activity.home();});button("测试书 0");verify("bookshelf");
        main(()->activity.home());button("测试书 0");crown(-.06f);main(()->activity.home());button("测试书 0");crown(-.06f);check("new-page-clears-previous-crown-energy",scroll().getScrollY()==0);
        click("书架分类");button("分类 0 · 1");verify("categories");main(()->activity.searchForm());main(()->find(decor(),EditText.class,null).setText("测试"));click("开始搜索");button("测试书 0");verify("search-results");
        BookInfo book=new BookInfo("99221","表冠测试书","");main(()->activity.openBook(book));button("继续阅读 · 1");verify("chapter-directory");
        main(()->activity.bookDetails(book));button("返回书籍菜单");verify("details");
        main(()->{set("bookId",book.id);set("chapters",fake.chapters);set("chapterIndex",0);set("entryOffset",Integer.valueOf(0));activity.current=fake.content();activity.showReader();});await(()->reader()!=null&&reader().getWidth()>0&&reader().getCharOffset()==0);int before=reader().getCharOffset();crown(-.15f);crown(-.15f);crown(-.15f);check("reader-keeps-original-crown-page-turn",reader().getCharOffset()>before);check("reader-keeps-full-viewport",reader().getWidth()==466&&reader().getHeight()==466);
        main(()->activity.readerMenu());verify("reading-menu");main(()->activity.paragraphs(0));verify("paragraph-list");
        stage="plugin-account-window";main(()->activity.accountPage());click("插件账号设置");long until=System.currentTimeMillis()+15000;Rect original=null;while(System.currentTimeMillis()<until){original=bounds("选择 Cookie 文件");if(original!=null&&original.height()>0)break;Thread.sleep(150);}check("real-plugin-account-window-opened",original!=null&&original.height()>0);int top=original.top;inject(-.5f);Thread.sleep(250);Rect after=bounds("选择 Cookie 文件");check("plugin-account-crown-scrolls-down",after==null||after.top<top);inject(.5f);Thread.sleep(250);Rect restored=bounds("选择 Cookie 文件");check("plugin-account-crown-scrolls-up",restored!=null&&restored.top==top);getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);button("书架分类");
    }catch(Throwable e){failures.put(stage+": "+e.getClass().getSimpleName()+" "+e.getMessage());}finally{try{JSONObject report=new JSONObject().put("checks",checks).put("failures",failures).put("real_cloud_test_position_writes",0).put("real_public_posts",0);try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),"crown-scroll-report.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}Bundle b=new Bundle();b.putString("summary","crown scroll checks="+checks.length()+" failures="+failures.length());b.putString("failures",failures.toString());finish(failures.length()==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,b);}catch(Exception ignored){finish(0,new Bundle());}}}
    static final class Fake implements ReaderPlugin {
        List<ChapterInfo> chapters=new ArrayList<>();Fake(){for(int i=0;i<80;i++)chapters.add(new ChapterInfo("99221"+i,"第"+(i+1)+"章 表冠测试","v"));}
        public String id(){return "test";}public List<ShelfGroup> shelfGroups(){List<ShelfGroup> groups=new ArrayList<>();for(int i=0;i<40;i++)groups.add(new ShelfGroup("g"+i,"分类 "+i,1));return groups;}
        public Page<BookInfo> bookshelf(String c){List<BookInfo> books=new ArrayList<>();for(int i=0;i<50;i++)books.add(new BookInfo("99221"+i,"测试书 "+i,""));return new Page<>(books,"",false);}public Page<BookInfo> search(String q,String c){return bookshelf(c);}public List<ChapterInfo> directory(String id){return chapters;}
        ChapterContent content(){List<Paragraph> p=new ArrayList<>();for(int i=0;i<80;i++)p.add(new Paragraph(i,"验证表冠在阅读器翻页，在列表页面上下滚动；测试段落"+i));return new ChapterContent("99221",chapters.get(0).id,chapters.get(0).title,"v",p);}
        public ChapterContent chapter(String book,ChapterInfo c){return new ChapterContent(book,c.id,c.title,"v",content().paragraphs);}public BookDetails details(BookInfo book){StringBuilder text=new StringBuilder();for(int i=0;i<150;i++)text.append("小说简介用于验证详情页的表冠滚动。");return new BookDetails(book,text.toString(),"","",80);}public Page<Comment> comments(ChapterContent c,Paragraph p,String next){return new Page<>(Collections.emptyList(),"",false);}public String publish(ChapterContent c,Paragraph p,String text){throw new AssertionError("No real posts");}
    }
}
