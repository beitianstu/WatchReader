package com.watchreader.fanqie;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Host-owned resumable TXT download. Partial data never appears as a completed library book. */
public final class LocalBookDownloader {
    public interface Progress {void update(int done,int total);}
    public static final int DEFAULT_THREADS=3,MAX_THREADS=4;
    private static final ThreadPoolExecutor FETCHERS=new ThreadPoolExecutor(MAX_THREADS,MAX_THREADS,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),task->{Thread thread=new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run();},"WatchReader-download");thread.setDaemon(true);return thread;});
    static {FETCHERS.allowCoreThreadTimeOut(true);}
    private final File books,work;private final int threads;
    public LocalBookDownloader(File books,File work){this(books,work,DEFAULT_THREADS);}
    public LocalBookDownloader(File books,File work,int threads){if(threads<1||threads>MAX_THREADS)throw new IllegalArgumentException("Download threads must be 1–4");this.books=books;this.work=work;this.threads=threads;}
    private static String hex(byte[] data)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static String prefix(List<ChapterInfo> chapters,int count)throws Exception{StringBuilder s=new StringBuilder();for(int i=0;i<count;i++){ChapterInfo c=chapters.get(i);s.append(c.id).append('|').append(c.version).append('|').append(c.title).append('\n');}return hex(s.toString().getBytes(StandardCharsets.UTF_8));}
    public File download(String scope,BookInfo book,List<ChapterInfo> chapters,ReaderPlugin source,BooleanSupplier cancelled,Progress progress)throws Exception {
        if(!book.id.matches("[0-9]+")||chapters.isEmpty())throw new IOException("下载目录无效或没有章节");
        if((!books.isDirectory()&&!books.mkdirs())||(!work.isDirectory()&&!work.mkdirs()))throw new IOException("下载目录无法创建");
        String job=hex((scope+":"+book.id).getBytes(StandardCharsets.UTF_8)).substring(0,32);
        File part=new File(work,job+".part");AtomicFile state=new AtomicFile(new File(work,job+".json"));int done=0;long checkpoint=0;
        try(InputStream input=state.openRead()){JSONObject saved=new JSONObject(new String(PluginWire.readLimited(input,65536),StandardCharsets.UTF_8));int count=saved.optInt("done",-1);long offset=saved.optLong("offset",-1);if(count>=0&&count<=chapters.size()&&offset>=0&&offset<=part.length()&&prefix(chapters,count).equals(saved.optString("prefix"))){done=count;checkpoint=offset;}}catch(Exception ignored){}
        Map<Integer,Future<ChapterContent>> pending=new LinkedHashMap<>();
        FETCHERS.purge();
        try(RandomAccessFile output=new RandomAccessFile(part,"rw")){
            output.setLength(checkpoint);output.seek(checkpoint);progress.update(done,chapters.size());
            if(cancelled.getAsBoolean())return null;
            for(int i=done;i<Math.min(done+threads,chapters.size());i++){final ChapterInfo chapter=chapters.get(i);pending.put(i,FETCHERS.submit(()->source.chapter(book.id,chapter)));}
            for(int i=done;i<chapters.size();i++){
                if(cancelled.getAsBoolean())return null;
                ChapterInfo chapter=chapters.get(i);ChapterContent content;
                while(true){if(cancelled.getAsBoolean())return null;try{content=pending.get(i).get(150,TimeUnit.MILLISECONDS);break;}catch(TimeoutException waiting){}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("下载章节失败",cause);}}
                pending.remove(i);
                if(cancelled.getAsBoolean())return null;
                if(content==null||!book.id.equals(content.bookId)||!chapter.id.equals(content.itemId)||content.paragraphs.isEmpty())throw new IOException("下载章节数据不完整，已保留进度");
                StringBuilder text=new StringBuilder(i==0?book.title+"\n"+(book.author.isEmpty()?"":book.author+"\n"):"\n");text.append(chapter.title.isEmpty()?"第"+(i+1)+"章":chapter.title).append("\n\n");for(Paragraph p:content.paragraphs)text.append("　　").append(p.text).append('\n');
                byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);if(output.length()+bytes.length>128L*1024*1024||work.getUsableSpace()<bytes.length+1024L*1024)throw new IOException("下载空间不足或书籍超过128MB，请释放空间后重试");
                output.write(bytes);output.getFD().sync();
                JSONObject saved=new JSONObject().put("done",i+1).put("offset",output.getFilePointer()).put("prefix",prefix(chapters,i+1));FileOutputStream journal=state.startWrite();try{journal.write(saved.toString().getBytes(StandardCharsets.UTF_8));state.finishWrite(journal);}catch(Exception e){state.failWrite(journal);throw e;}
                progress.update(i+1,chapters.size());
                int next=i+threads;if(next<chapters.size()&&!cancelled.getAsBoolean()){final ChapterInfo following=chapters.get(next);pending.put(next,FETCHERS.submit(()->source.chapter(book.id,following)));}
            }
        }finally{for(Future<ChapterContent> task:pending.values())task.cancel(true);FETCHERS.purge();}
        if(cancelled.getAsBoolean())return null;
        String name=book.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();if(name.isEmpty())name="番茄小说";if(name.length()>60)name=name.substring(0,60);
        File result=new File(books,name+"_"+book.id+".txt");Files.move(part.toPath(),result.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);DownloadedNovel.save(result,scope,book,chapters);state.delete();return result;
    }
}
