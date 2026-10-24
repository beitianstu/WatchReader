package com.watchreader.fanqie;

import android.util.AtomicFile;
import com.watchreader.Book;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Source association only; never contains credentials or a second copy of the novel. */
public final class DownloadedNovel {
    public final String scope;public final BookInfo info;public final List<ChapterInfo> chapters;
    private DownloadedNovel(String scope,BookInfo info,List<ChapterInfo> chapters){this.scope=scope;this.info=info;this.chapters=chapters;}
    private static File sidecar(File file){return new File(file.getParentFile(),file.getName()+".fanqie.json");}
    public static boolean candidate(File file,File books){try{return file.getCanonicalFile().getParentFile().equals(books.getCanonicalFile())&&(sidecar(file).isFile()||file.getName().matches(".+_[0-9]+\\.txt"));}catch(Exception e){return false;}}
    private static String digest(File file)throws Exception{MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)sha.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:sha.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    public static void save(File file,String scope,BookInfo info,List<ChapterInfo> chapters)throws Exception{
        JSONArray list=new JSONArray();for(ChapterInfo c:chapters)list.put(PluginWire.chapter(c));
        JSONObject json=new JSONObject().put("schema",1).put("scope",scope).put("book",PluginWire.book(info)).put("chapters",list).put("sha256",digest(file));
        AtomicFile out=new AtomicFile(sidecar(file));FileOutputStream stream=out.startWrite();try{stream.write(json.toString().getBytes(StandardCharsets.UTF_8));out.finishWrite(stream);}catch(Exception e){out.failWrite(stream);throw e;}
    }
    public static DownloadedNovel load(File file,String scope,ReaderPlugin source)throws Exception{
        File metadata=sidecar(file);
        if(metadata.isFile()){
            JSONObject json;try(InputStream in=new AtomicFile(metadata).openRead()){json=new JSONObject(new String(PluginWire.readLimited(in,8*1024*1024),StandardCharsets.UTF_8));}
            if(json.getInt("schema")!=1||!scope.equals(json.getString("scope")))throw new IOException("下载书籍属于其他账号，保留本地进度");
            if(!digest(file).equals(json.getString("sha256")))throw new IOException("下载文件已修改，保留本地进度");
            List<ChapterInfo> chapters=new ArrayList<>();JSONArray list=json.getJSONArray("chapters");for(int i=0;i<list.length();i++)chapters.add(PluginWire.chapter(list.getJSONObject(i)));
            return new DownloadedNovel(scope,PluginWire.book(json.getJSONObject("book")),chapters);
        }
        String stem=file.getName().replaceFirst("\\.txt$","");int split=stem.lastIndexOf('_');
        if(split<1||!stem.substring(split+1).matches("[0-9]+"))throw new IOException("这本TXT没有番茄来源信息");
        BookInfo info=new BookInfo(stem.substring(split+1),stem.substring(0,split),"");
        return new DownloadedNovel(scope,info,source.directory(info.id));
    }
    public void saveIfMissing(File file)throws Exception{if(!sidecar(file).isFile())save(file,scope,info,chapters);}
    public Index index(Book book)throws Exception{return new Index(this,book);}
    private static String clean(String s){int a=0,b=s.length();while(a<b&&(Character.isWhitespace(s.charAt(a))||s.charAt(a)=='\u3000'))a++;while(b>a&&(Character.isWhitespace(s.charAt(b-1))||s.charAt(b-1)=='\u3000'))b--;return s.substring(a,b);}
    public static final class Index {
        public final DownloadedNovel novel;public final Book book;public final int[] starts,bodyStarts,ends;
        Index(DownloadedNovel novel,Book book)throws Exception{
            this.novel=novel;this.book=book;int count=novel.chapters.size();if(count==0)throw new IOException("下载目录为空");starts=new int[count];bodyStarts=new int[count];ends=new int[count];int cursor=0;
            for(int i=0;i<count;i++){
                String title=novel.chapters.get(i).title;if(title.isEmpty())title="第"+(i+1)+"章";
                boolean found=false;
                while(cursor<book.index.length){int[] line=book.index[cursor++];if(clean(book.text.substring(line[0],line[1])).equals(title)){starts[i]=line[0];bodyStarts[i]=Math.min(book.text.length(),line[1]+1);found=true;break;}}
                if(!found)throw new IOException("下载目录与正文不匹配，请重新下载");
                if(i>0)ends[i-1]=starts[i];
            }
            ends[count-1]=book.text.length();
        }
        public int chapterAt(int offset){int n=Arrays.binarySearch(starts,offset);return n>=0?n:Math.max(0,-n-2);}
        public Match match(int chapter,ChapterContent content)throws Exception{return new Match(this,chapter,content);}
    }
    public static final class Match {
        private final Index index;public final int chapter;public final ChapterContent content;private final int[] starts;
        Match(Index index,int chapter,ChapterContent content)throws Exception{
            this.index=index;this.chapter=chapter;this.content=content;
            if(!index.novel.info.id.equals(content.bookId)||!index.novel.chapters.get(chapter).id.equals(content.itemId)||content.paragraphs.isEmpty())throw new IOException("下载章节来源不匹配");
            starts=new int[content.paragraphs.size()];int cursor=index.bodyStarts[chapter];
            for(int i=0;i<starts.length;i++){
                Paragraph p=content.paragraphs.get(i);String serialized=Book.toLayoutText(Book.normalize("　　"+p.text+"\n"));
                int found=index.book.text.indexOf(serialized,cursor);
                if(found<cursor||found+serialized.length()>index.ends[chapter]||!clean(index.book.text.substring(cursor,found)).isEmpty())throw new IOException("下载正文与在线版本不一致，请重新下载后同步");
                starts[i]=found+4;cursor=found+serialized.length();
            }
            if(!clean(index.book.text.substring(cursor,index.ends[chapter])).isEmpty())throw new IOException("下载章节内容已变更，请重新下载");
        }
        public ReadingProgress capture(int offset,long timestamp){
            int n=0;for(int i=1;i<starts.length&&offset>=starts[i];i++)n=i;
            Paragraph p=content.paragraphs.get(n);int delta=Math.max(0,offset-starts[n]),low=0,high=p.text.length();
            while(low<high){int mid=(low+high+1)/2;if(layoutOffset(p,mid)<=delta)low=mid;else high=mid-1;}int inside=low;double rate=Math.max(0,Math.min(1,(offset-index.bodyStarts[chapter])/(double)Math.max(1,index.ends[chapter]-index.bodyStarts[chapter])));
            return new ReadingProgress(content.bookId,content.itemId,chapter,p.index,inside,-1,timestamp,(chapter+rate)/index.starts.length,rate,content.version,true);
        }
        private static int layoutOffset(Paragraph p,int raw){return Math.max(0,Book.toLayoutText(Book.normalize("　　"+p.text.substring(0,raw))).length()-4);}
        public int offset(ReadingProgress p){for(int i=0;i<starts.length;i++)if(content.paragraphs.get(i).index==p.paragraph)return starts[i]+layoutOffset(content.paragraphs.get(i),Math.max(0,Math.min(content.paragraphs.get(i).text.length(),p.paragraphOffset)));return index.bodyStarts[chapter]+(int)(Math.max(0,Math.min(1,p.pageRate))*Math.max(0,index.ends[chapter]-index.bodyStarts[chapter]-1));}
    }
}
