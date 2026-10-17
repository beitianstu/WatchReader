package com.watchreader.fanqie;

import android.app.*;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Native signer/key-session concurrency with a barrier-backed fake transport, no network. */
public final class NativeParallelInstrumentation extends Instrumentation {
    JSONArray checks=new JSONArray(),failures=new JSONArray();
    void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks.put(name);}
    void set(FanqiePlugin source,String name,Object value)throws Exception{Field field=FanqiePlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(source,value);}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){ExecutorService pool=Executors.newFixedThreadPool(3);Map<String,?> before=new HashMap<>(getTargetContext().getSharedPreferences("vault",0).getAll());try{
        for(boolean rotate:new boolean[]{false,true}){Transport transport=new Transport(rotate);FanqiePlugin owner=new FanqiePlugin(new CredentialStore(getTargetContext()),transport);set(owner,"cookie","sessionid=local-mock-only");set(owner,"device","123");set(owner,"iid","456");List<Future<ChapterContent>> futures=new ArrayList<>();for(int i=0;i<3;i++){final int n=i;futures.add(pool.submit(()->owner.chapterSession().chapter("99",new ChapterInfo(Integer.toString(10+n),"章节"+n,"v"))));}for(int i=0;i<3;i++){ChapterContent chapter=futures.get(i).get(15,TimeUnit.SECONDS);check("isolated-decryption-"+rotate+"-"+i,chapter.itemId.equals(Integer.toString(10+i))&&chapter.paragraphs.get(0).text.equals("正文"+(10+i)));}check("native-http-requests-overlap-"+rotate,transport.peak.get()==3);check("negotiates-once-plus-only-required-rotation-"+rotate,transport.registers.get()==(rotate?2:1));}
        check("credential-store-not-modified",before.equals(getTargetContext().getSharedPreferences("vault",0).getAll()));
    }catch(Throwable e){failures.put(e.getClass().getSimpleName()+": "+e.getMessage());}finally{pool.shutdownNow();try{JSONObject report=new JSONObject().put("checks",checks).put("failures",failures).put("real_network_requests",0);try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),"parallel-native-report.json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}Bundle result=new Bundle();result.putString("summary","native parallel checks="+checks.length()+" failures="+failures.length());result.putString("failures",failures.toString());finish(failures.length()==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}catch(Exception ignored){finish(0,new Bundle());}}}
    static final class Transport implements FanqiePlugin.Transport {
        final boolean rotate;final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),registers=new AtomicInteger();final CyclicBarrier barrier=new CyclicBarrier(3);final ConcurrentMap<String,AtomicInteger> attempts=new ConcurrentHashMap<>();final byte[] key1=DesktopSigner.utf("0123456789abcdef"),key2=DesktopSigner.utf("fedcba9876543210");
        Transport(boolean rotate){this.rotate=rotate;}
        String blob(byte[] bytes,byte[] key)throws Exception{byte[] iv=new byte[16];return Base64.getEncoder().encodeToString(DesktopSigner.join(iv,DesktopSigner.aes(bytes,key,iv,true)));}
        String param(String query,String name){for(String part:query.split("&")){String[] p=part.split("=",2);if(p[0].equals(name))return p[1];}return "";}
        public JSONObject call(String host,String path,String query,byte[] body,Map<String,String> headers)throws Exception{
            if(path.equals("/passport/account/info/v2/"))return new JSONObject().put("message","success").put("data",new JSONObject().put("user_id_str","789"));
            if(path.equals("/reading/crypt/registerkey")){int n=registers.incrementAndGet();return new JSONObject().put("code",0).put("data",new JSONObject().put("key",blob(n==1?key1:key2,FanqiePlugin.SEED)).put("keyver",Integer.toString(n)));}
            if(!path.equals("/reading/reader/full/v/"))throw new AssertionError("Unexpected mock endpoint");
            String item=param(query,"item_id"),version=param(query,"content_key_version");int attempt=attempts.computeIfAbsent(item,k->new AtomicInteger()).incrementAndGet();int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{if(attempt==1)barrier.await(5,TimeUnit.SECONDS);if(rotate&&item.equals("10")&&attempt==1)return new JSONObject().put("code",0).put("data",new JSONObject().put("content","Invalid"));Thread.sleep(40);byte[] key=version.equals("1")?key1:key2;return new JSONObject().put("code",0).put("data",new JSONObject().put("content",blob(DesktopSigner.utf("<p idx=\"0\">正文"+item+"</p>"),key)).put("key_version",version).put("novel_data",new JSONObject().put("content_md5","v")));}finally{active.decrementAndGet();}
        }
    }
}
