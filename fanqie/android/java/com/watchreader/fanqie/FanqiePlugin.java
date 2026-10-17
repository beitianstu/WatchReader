package com.watchreader.fanqie;

import android.text.Html;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Native HTTPS provider. Auth is restricted to two explicit first-party hosts. */
public final class FanqiePlugin implements ReaderPlugin {
    static final String WEB="fanqienovel.com",APP="api5-normal.fqnovel.com";
    static final byte[] SEED=DesktopSigner.hex("ac25c67ddd8f38c1b37a2348828e222e");
    private static final String UA="com.dragon.read/68132 (Linux; U; Android 6.0.1; zh_CN; NX551J; tt-ok/3.12.13.20)";
    private final CredentialStore store;
    private String cookie,device="0",iid="0",keyVersion;private byte[] contentKey;
    private JSONArray shelf;private long shelfAt;
    public interface Transport { JSONObject call(String host,String path,String query,byte[] body,Map<String,String> headers)throws Exception; }
    private final Transport transport;
    public FanqiePlugin(CredentialStore store)throws Exception {this(store,new HttpsTransport());}
    FanqiePlugin(CredentialStore store,Transport transport)throws Exception {this.store=store;this.transport=transport;cookie=store.get("cookie");String saved=store.get("device");if(!saved.isEmpty()){JSONObject j=new JSONObject(saved);device=j.optString("device_id","0");iid=j.optString("iid","0");}}
    private FanqiePlugin(FanqiePlugin ready){store=ready.store;transport=ready.transport;cookie=ready.cookie;device=ready.device;iid=ready.iid;keyVersion=ready.keyVersion;contentKey=ready.contentKey.clone();}
    /** Initialize once, then confine each mutable chapter/key session to one worker thread. */
    synchronized FanqiePlugin chapterSession()throws Exception{requireLogin();ensureDevice();if(contentKey==null)negotiateKey();return new FanqiePlugin(this);}
    public String id(){return "fanqie";}
    public boolean loggedIn(){return !cookie.isEmpty();}
    public String accountScope()throws Exception {return DesktopSigner.hex(java.security.MessageDigest.getInstance("SHA-256").digest(DesktopSigner.utf(cookie))).substring(0,24);}
    public void setCookie(String value)throws Exception {String parsed=CredentialStore.parseCookie(value);store.put("cookie",parsed);cookie=parsed;contentKey=null;shelf=null;}
    public void logout(){store.clear();cookie="";device="0";iid="0";contentKey=null;shelf=null;}
    private void requireLogin()throws Exception {if(cookie.isEmpty())throw new Exception("请先在账号页导入番茄 Cookie");}
    private static LinkedHashMap<String,String> params(String... kv){LinkedHashMap<String,String> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put(kv[i],kv[i+1]);return m;}
    private static String query(Map<String,String> m)throws Exception {StringBuilder b=new StringBuilder();for(Map.Entry<String,String> p:m.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(p.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(p.getValue(),"UTF-8"));}return b.toString();}
    private static String first(JSONObject j,String... keys){for(String k:keys){String s=j.optString(k,"");if(!s.isEmpty()&&!s.equals("null"))return s;}return "";}
    private static JSONObject object(JSONObject j,String key){JSONObject d=j.optJSONObject(key);return d==null?new JSONObject():d;}
    private static JSONArray array(JSONObject j,String key){JSONArray a=j.optJSONArray(key);return a==null?new JSONArray():a;}
    private static void check(JSONObject j)throws Exception {int c=j.optInt("code",j.optInt("Code",-1));if(c!=0){if(c==103006)throw new Exception("登录已失效，请重新导入 Cookie");throw new Exception("番茄接口错误 "+c);}}
    private JSONObject web(String path,Map<String,String> p,JSONObject body)throws Exception {
        requireLogin();Map<String,String> h=new LinkedHashMap<>();h.put("Cookie",cookie);h.put("User-Agent","Mozilla/5.0");h.put("Referer","https://fanqienovel.com/");JSONObject j=transport.call(WEB,path,query(p),body==null?null:DesktopSigner.utf(body.toString()),h);
        if(path.equals("/passport/account/info/v2/")){if(!"success".equals(j.optString("message")))throw new Exception("登录已失效，请重新导入 Cookie");}else check(j);return object(j,"data");
    }
    private synchronized void ensureDevice()throws Exception {
        if(!device.equals("0")&&!iid.equals("0"))return;
        JSONObject header=new JSONObject();Object[][] props={{"display_name","novelapp"},{"aid",1967},{"app_name","novelapp"},{"package","com.dragon.read"},{"update_version_code",68132},{"manifest_version_code",68132},{"version_code",68132},{"version_name","6.8.1.32"},{"channel","official"},{"device_model","NX551J"},{"device_brand","nubia"},{"device_manufacturer","nubia"},{"device_platform","android"},{"os_version","6.0.1"},{"os_api",23},{"language","zh"},{"region","CN"},{"resolution","1080x1920"},{"dpi",420},{"cdid",UUID.randomUUID().toString()},{"openudid",UUID.randomUUID().toString().replace("-","").substring(0,16)}};
        for(Object[] p:props)header.put((String)p[0],p[1]);JSONObject b=new JSONObject().put("magic_tag","ss_app_log").put("header",header).put("_gen_time",System.currentTimeMillis()/1000);
        JSONObject j=transport.call("i.snssdk.com","/service/2/device_register/","",DesktopSigner.utf(b.toString()),params("User-Agent",UA));
        String did=first(j,"device_id_str","device_id"),install=first(j,"install_id_str","install_id");if(!did.matches("[1-9][0-9]+")||!install.matches("[1-9][0-9]+"))throw new Exception("设备注册未成功，请稍后重试");
        device=did;iid=install;store.put("device",new JSONObject().put("device_id",device).put("iid",iid).toString());
    }
    private JSONObject app(String path,Map<String,String> extra,JSONObject body,boolean search)throws Exception {
        ensureDevice();LinkedHashMap<String,String> p=params("aid","1967","app_name","novelapp","version_code",search?"70132":"68132","version_name",search?"7.0.1.32":"6.8.1.32","device_platform","android","device_type","NX551J","os_version","6.0.1","os_api","23","device_id",device,"iid",iid,"ac","wifi","_rticket",Long.toString(System.currentTimeMillis()));
        p.putAll(extra);if(search)p.putAll(params("channel","43536163a","os","android","ssmix","a","update_version_code","70132","manifest_version_code","70132","pv_player","70132"));
        String q=query(p);byte[] b=body==null?null:DesktopSigner.utf(body.toString());Map<String,String> h=DesktopSigner.sign(q,b);h.put("User-Agent",UA);h.put("Accept","application/json");h.put("sdk-version","2");h.put("lc","101");h.put("X-SS-REQ-TICKET",Long.toString(System.currentTimeMillis()));if(!cookie.isEmpty()&&!search)h.put("Cookie",cookie);
        JSONObject j=transport.call(search?WEB:APP,path,q,b,h);check(j);return j;
    }
    private JSONObject appData(String path,Map<String,String> p,JSONObject b)throws Exception {return object(app(path,p,b,false),"data");}
    private void ensureShelf()throws Exception {
        if(shelf==null||System.currentTimeMillis()-shelfAt>120000){JSONObject d=web("/reading/bookapi/bookshelf/info/v:version/",params("aid","1967","iid","0","version_code","57700","update_version_code","57700"),null);shelf=array(d,"book_shelf_info");shelfAt=System.currentTimeMillis();}
    }
    private static String shelfGroup(JSONObject row){String id=first(row,"group_id");return id.isEmpty()||id.equals("0")?"@ungrouped":id;}
    public List<ShelfGroup> shelfGroups()throws Exception {
        ensureShelf();Map<String,Integer> counts=new LinkedHashMap<>();Map<String,String> names=new HashMap<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<shelf.length();i++){JSONObject row=shelf.getJSONObject(i);if(!seen.add(row.optString("book_id")))continue;String group=shelfGroup(row);counts.put(group,counts.getOrDefault(group,0)+1);String name=first(row,"group_name");names.put(group,group.equals("@ungrouped")?"未分组":name.isEmpty()?"未命名分组":name);}
        List<ShelfGroup> result=new ArrayList<>();result.add(new ShelfGroup("","全部",seen.size()));for(String id:counts.keySet())result.add(new ShelfGroup(id,names.get(id),counts.get(id)));return result;
    }
    public Page<BookInfo> bookshelf(String cursor)throws Exception {return bookshelf("",cursor);}
    public Page<BookInfo> bookshelf(String group,String cursor)throws Exception {
        ensureShelf();List<String> filtered=new ArrayList<>();Set<String> seen=new HashSet<>();for(int i=0;i<shelf.length();i++){JSONObject row=shelf.getJSONObject(i);String id=row.optString("book_id");if((group.isEmpty()||group.equals(shelfGroup(row)))&&seen.add(id))filtered.add(id);}
        int start=cursor.isEmpty()?0:Integer.parseInt(cursor);if(start<0||start>filtered.size())throw new Exception("书架分页已变化，请重新打开分类");int end=Math.min(start+20,filtered.size());JSONArray ids=new JSONArray();for(int i=start;i<end;i++)ids.put(filtered.get(i));
        if(ids.length()==0)return new Page<>(new ArrayList<>(),"",false);
        JSONObject metadata=web("/api/book/simple/info",params(),new JSONObject().put("book_ids",ids));JSONArray rows=array(metadata,"bookList");Map<String,JSONObject> byId=new HashMap<>();for(int i=0;i<rows.length();i++){JSONObject b=rows.getJSONObject(i);byId.put(b.optString("book_id"),b);}
        List<BookInfo> books=new ArrayList<>();for(int i=0;i<ids.length();i++){String id=ids.getString(i);JSONObject b=byId.get(id);books.add(new BookInfo(id,b==null?id:first(b,"book_name","title"),b==null?"":first(b,"author","author_name")));}return new Page<>(books,Integer.toString(end),end<filtered.size());
    }
    public BookDetails details(BookInfo book)throws Exception {
        if(!book.id.matches("[0-9]+"))throw new Exception("无效书籍编号");
        JSONObject data=web("/api/bookshelf/multidetail",params(),new JSONObject().put("books",new JSONArray().put(new JSONObject().put("book_id",book.id).put("item_id","0"))));
        JSONArray rows=array(data,"detail_list");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!book.id.equals(row.optString("book_id")))continue;String title=first(row,"book_name"),author=first(row,"author_name","author");return new BookDetails(new BookInfo(book.id,title.isEmpty()?book.title:title,author.isEmpty()?book.author:author),plain(row.optString("abstract")),first(row,"thumb_url"),first(row,"item_show_title"),row.optInt("serial_count",0));}
        throw new Exception("这本书暂时没有详情");
    }
    public Page<BookInfo> search(String keyword,String cursor)throws Exception {
        if(keyword.trim().isEmpty())throw new Exception("请输入书名或作者");JSONObject state=cursor.isEmpty()?new JSONObject():new JSONObject(cursor);
        JSONObject j=app("/reading/bookapi/search/tab/v",params("query",keyword,"passback",state.optString("passback","0"),"offset",state.optString("offset","0"),"selected_items","","tab_type","1"),null,true);
        JSONArray tabs=array(j,"search_tabs");JSONObject tab=null;for(int i=0;i<tabs.length();i++){JSONObject t=tabs.getJSONObject(i);if(t.optInt("tab_type")==1){tab=t;break;}}if(tab==null)throw new Exception("搜索响应格式已变化");
        List<BookInfo> books=new ArrayList<>();Set<String> seen=new HashSet<>();JSONArray cells=array(tab,"data");for(int i=0;i<cells.length();i++){JSONObject cell=cells.getJSONObject(i);if(cell.optInt("show_type")!=110)continue;JSONArray a=array(cell,"book_data");for(int k=0;k<a.length();k++){JSONObject b=a.getJSONObject(k);String id=first(b,"book_id");if(!id.isEmpty()&&seen.add(id))books.add(new BookInfo(id,plain(first(b,"book_name","original_book_name")),b.optString("author")));}}
        String next=new JSONObject().put("passback",tab.optString("passback","0")).put("offset",tab.optString("next_offset","0")).toString();
        return new Page<>(books,next,truth(tab.opt("has_more"))&&!books.isEmpty()&&!next.equals(cursor));
    }
    private static boolean truth(Object x){return Boolean.TRUE.equals(x)||"1".equals(String.valueOf(x))||"true".equals(String.valueOf(x));}
    public List<ChapterInfo> directory(String book)throws Exception {
        JSONObject d=appData("/reading/bookapi/directory/all_items/v/",params("book_id",book),null);JSONArray a=array(d,"item_data_list");if(a.length()==0)a=array(d,"catalog_data");List<ChapterInfo> chapters=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONObject v=a.getJSONObject(i);String id=first(v,"item_id","itemId");if(id.matches("[0-9]+"))chapters.add(new ChapterInfo(id,first(v,"title","chapter_name"),v.optString("content_md5")));}if(chapters.isEmpty())throw new Exception("这本书暂时没有可读章节");return chapters;
    }
    public ReadingProgress readProgress(String book)throws Exception {
        requireLogin();if(!book.matches("[0-9]+"))throw new Exception("无效书籍编号");
        JSONObject body=new JSONObject().put("book_ids",new JSONObject().put("0",new JSONArray().put(Long.parseLong(book))));
        JSONArray rows=array(app("/reading/bookapi/read_progress/get/v/",params(),body,false),"data");
        for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);if(!book.equals(p.optString("book_id")))continue;String item=p.optString("item_id");long timestamp=p.optLong("read_timestamp_ms",0);if(timestamp==0)timestamp=p.optLong("read_timestamp",0)*1000L;if(!item.matches("[0-9]+")||timestamp<=0)continue;
            int paragraph=p.optInt("paragraph_id",-1),offset=p.optInt("paragraph_offset",0);JSONObject position=p.optJSONObject("progress_pos_info_v2");if(paragraph<0&&position!=null&&position.optInt("start_container_index",-1)==0){paragraph=position.optInt("start_element_index",-1);offset=position.optInt("start_element_offset",0);}
            return new ReadingProgress(book,item,Math.max(0,p.optInt("real_chapter_order",1)-1),paragraph,Math.max(0,offset),-1,timestamp,p.optDouble("progress_rate",0),p.optDouble("page_progress_rate",0),p.optString("content_md5"),false);
        }return null;
    }
    static JSONObject progressBody(ReadingProgress p)throws Exception {
        if(!p.bookId.matches("[0-9]+")||!p.itemId.matches("[0-9]+")||p.timestamp<=0||p.paragraph<0||p.paragraphOffset<0)throw new Exception("阅读进度位置无效");
        JSONObject row=new JSONObject().put("book_id",p.bookId).put("item_id",p.itemId).put("book_type",0).put("progress_rate",String.format(Locale.ROOT,"%.4f",Math.max(0,Math.min(1,p.bookRate)))).put("page_progress_rate",Math.max(0,Math.min(1,p.pageRate))).put("paragraph_id",Integer.toString(p.paragraph)).put("paragraph_offset",p.paragraphOffset).put("read_timestamp_ms",p.timestamp);
        return new JSONObject().put("books",new JSONArray().put(row));
    }
    public void uploadProgress(ReadingProgress progress)throws Exception {requireLogin();app("/reading/bookapi/read_progress/upload/v/",params(),progressBody(progress),false);}
    private void negotiateKey()throws Exception {
        requireLogin();ensureDevice();JSONObject account=web("/passport/account/info/v2/",params("aid","1967"),null);String uid=first(account,"user_id_str","user_id");if(!uid.matches("[1-9][0-9]+"))throw new Exception("登录已失效，请重新导入 Cookie");
        byte[] payload=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.parseLong(device)).putLong(Long.parseLong(uid)).array(),iv=DesktopSigner.utf(UUID.randomUUID().toString().substring(0,16));
        String encrypted=Base64.getEncoder().encodeToString(DesktopSigner.join(iv,DesktopSigner.aes(payload,SEED,iv,true)));
        JSONObject d=appData("/reading/crypt/registerkey",params(),new JSONObject().put("content",encrypted).put("keyver",1));contentKey=DesktopSigner.unblob(d.getString("key"),SEED);keyVersion=d.getString("keyver");if(contentKey.length!=16)throw new Exception("内容密钥无效");
    }
    public ChapterContent chapter(String book,ChapterInfo chapter)throws Exception {
        requireLogin();if(contentKey==null)negotiateKey();JSONObject d=appData("/reading/reader/full/v/",params("book_id",book,"item_id",chapter.id,"content_key_version",keyVersion),null);
        if("Invalid".equals(d.optString("content"))||!keyVersion.equals(d.optString("key_version",keyVersion))){negotiateKey();d=appData("/reading/reader/full/v/",params("book_id",book,"item_id",chapter.id,"content_key_version",keyVersion),null);}
        String encrypted=d.optString("content");if(encrypted.isEmpty()||encrypted.equals("Invalid"))throw new Exception("本章节暂不可读或账号无权限");byte[] bytes=encrypted.trim().startsWith("<")?DesktopSigner.utf(encrypted):DesktopSigner.unblob(encrypted,contentKey);
        InputStream in=new ByteArrayInputStream(bytes);if(bytes.length>1&&(bytes[0]&255)==31&&(bytes[1]&255)==139)in=new GZIPInputStream(in);else if(bytes.length>1&&(bytes[0]&255)==120)in=new InflaterInputStream(in);
        String markup=new String(readLimited(in,2*1024*1024),StandardCharsets.UTF_8);String version=first(object(d,"novel_data"),"content_md5");if(version.isEmpty())version=chapter.version;
        List<Paragraph> paras=parseParagraphs(markup);if(paras.isEmpty())throw new Exception("正文缺少可识别的段落");return new ChapterContent(book,chapter.id,first(d,"title").isEmpty()?chapter.title:d.optString("title"),version,paras);
    }
    static List<Paragraph> parseParagraphs(String html){List<Paragraph> result=new ArrayList<>();Matcher m=Pattern.compile("<p\\b([^>]*)>(.*?)</p>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);while(m.find()){Matcher idx=Pattern.compile("\\bidx\\s*=\\s*[\"']?(\\d+)",Pattern.CASE_INSENSITIVE).matcher(m.group(1));int index=idx.find()?Integer.parseInt(idx.group(1)):-1;String text=plain(m.group(2));if(!text.trim().isEmpty())result.add(new Paragraph(index,text));}return result;}
    static String plain(String html){return Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    public Page<Comment> comments(ChapterContent chapter,Paragraph para,String cursor)throws Exception {
        validateTarget(chapter,para);JSONObject d=appData("/reading/ugc/idea/comment_list/v/",params("book_id",chapter.bookId,"item_id",chapter.itemId,"item_version",chapter.version,"para_index",Integer.toString(para.index),"count","15","offset",cursor.isEmpty()?"0":cursor,"query_type","0","sort","1"),null);
        List<Comment> comments=new ArrayList<>();JSONArray a=array(d,"comments");for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);comments.add(new Comment(c.optString("comment_id"),object(c,"user_info").optString("user_name","读者"),c.optString("text")));}return new Page<>(comments,d.optString("next_offset"),truth(d.opt("has_more")));
    }
    static void validateTarget(ChapterContent ch,Paragraph p)throws Exception {if(ch.version.isEmpty()||p.index<0||!ch.bookId.matches("[0-9]+")||!ch.itemId.matches("[0-9]+")||!ch.paragraphs.contains(p))throw new Exception("本章缺少段落标识，无法定位段评");}
    static JSONObject publicationBody(ChapterContent ch,Paragraph p,String text)throws Exception {
        validateTarget(ch,p);String trimmed=text.trim();if(trimmed.isEmpty()||trimmed.codePointCount(0,trimmed.length())>500)throw new Exception("段评限 1–500 字");
        JSONObject pos=new JSONObject().put("start_para_index",p.index).put("end_para_index",p.index).put("start_word_pos",0).put("end_word_pos",p.text.length());
        return new JSONObject().put("book_id",ch.bookId).put("group_id",ch.itemId).put("item_version",ch.version).put("service_id",1).put("comment_type",0).put("text",trimmed).put("para_content",p.text).put("comment_pos",pos).put("force_create",false).put("text_exts",new JSONArray()).put("image_data",new JSONArray());
    }
    /** No automatic retries: a timeout may mean the server already accepted it. */
    public String publish(ChapterContent chapter,Paragraph para,String text)throws Exception {
        requireLogin();JSONObject body=publicationBody(chapter,para,text);JSONObject d=appData("/reading/ugc/novel_comment/add/v/",params(),body);String id=first(d,"comment_id");if(id.isEmpty())id=first(object(d,"comment"),"comment_id");if(id.isEmpty())throw new Exception("服务端未返回段评编号，请先刷新确认，勿重复提交");return id;
    }
    static byte[] readLimited(InputStream in,int limit)throws Exception {try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(out.size()+n>limit)throw new IOException("响应内容过大");out.write(b,0,n);}return out.toByteArray();}}
    private static final class HttpsTransport implements Transport {
        public JSONObject call(String host,String path,String query,byte[] body,Map<String,String> headers)throws Exception {
            if(!Arrays.asList(WEB,APP,"i.snssdk.com").contains(host)||!path.startsWith("/")||path.contains(".."))throw new Exception("拒绝未知服务器");
            if(headers.containsKey("Cookie")&&!Arrays.asList(WEB,APP).contains(host))throw new Exception("拒绝向该域名发送账号");
            URL u=new URL("https",host,path+(query.isEmpty()?"":"?"+query));javax.net.ssl.HttpsURLConnection c=(javax.net.ssl.HttpsURLConnection)u.openConnection();
            try{c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());c.setRequestProperty("Accept-Encoding","identity");
                if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}
                int status=c.getResponseCode();if(status!=200)throw new IOException(status==429?"请求过快，请稍后再试":"网络请求失败 HTTP "+status);
                byte[] result=readLimited(c.getInputStream(),8*1024*1024);if(result.length==0)throw new IOException("接口返回空响应，请稍后重试");try{return new JSONObject(new String(result,StandardCharsets.UTF_8));}catch(JSONException ex){throw new IOException("接口响应格式已变化");}
            }finally{c.disconnect();}
        }
    }
}
