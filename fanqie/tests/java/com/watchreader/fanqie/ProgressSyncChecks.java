package com.watchreader.fanqie;

import java.util.*;
import static com.watchreader.fanqie.ReaderPlugin.*;

/** Deterministic conflict/offline/ack-race tests; no Android or real accounts. */
public final class ProgressSyncChecks {
    static int checks;
    static void check(String label,boolean ok){if(!ok)throw new AssertionError(label);checks++;}
    static ReadingProgress p(long time,int chapter,boolean dirty){return new ReadingProgress("123",""+(456+chapter),chapter,3,2,7,time,.5,.1,"md5",dirty);}
    static final class Store implements ProgressSync.Store {
        ReadingProgress value;
        public synchronized ReadingProgress get(String b){return value;}
        public synchronized void put(ReadingProgress p){value=p;}
        public synchronized boolean replace(ReadingProgress expected,ReadingProgress p){if(expected==null?value!=null:!expected.same(value))return false;value=p;return true;}
        public List<String> pending(){return value!=null&&value.dirty?Arrays.asList(value.bookId):Collections.emptyList();}
    }
    static final class Client implements ProgressSync.Client {
        ReadingProgress remote,last;int uploads;boolean fail;Runnable duringRead,duringUpload;
        public ReadingProgress read(String book)throws Exception {if(duringRead!=null)duringRead.run();if(fail)throw new Exception("offline");return remote;}
        public void upload(ReadingProgress p)throws Exception {uploads++;last=p;if(duringUpload!=null)duringUpload.run();if(fail)throw new Exception("offline");remote=p.clean();}
    }
    public static void main(String[] args)throws Exception {
        Store s=new Store();Client c=new Client();ProgressSync sync=new ProgressSync(c,s);
        c.remote=p(100,9,false);ReadingProgress read=sync.pull("123");check("first pull restores cloud without dirtying",read.chapter==9&&!read.dirty);check("pull never writes cloud",c.uploads==0);
        s.put(p(200,1,true));check("later backward reread beats earlier higher chapter",sync.pull("123").chapter==1);sync.flush("123");check("upload actual chapter",c.last.chapter==1);check("ack clears only uploaded position",!s.value.dirty);
        s.put(p(210,2,true));c.remote=p(300,8,false);check("cloud newer blocks stale upload",sync.flush("123")==ProgressSync.Result.REMOTE_NEWER&&c.uploads==1&&!s.value.dirty&&s.value.chapter==8);
        s.put(p(400,3,true));c.fail=true;try{sync.flush("123");throw new AssertionError("offline accepted");}catch(Exception expected){}check("offline keeps pending",s.value.dirty&&s.value.timestamp==400);c.fail=false;sync.flush("123");check("retry succeeds",!s.value.dirty&&c.last.timestamp==400);
        s.put(p(500,4,true));c.duringUpload=()->s.put(p(501,5,true));sync.flush("123");check("old ack preserves later page",s.value.timestamp==501&&s.value.dirty);c.duringUpload=null;
        c.duringRead=()->s.put(p(502,6,true));int before=c.uploads;check("page changes during preflight defer",sync.flush("123")==ProgressSync.Result.DEFERRED&&c.uploads==before);c.duringRead=null;
        c.remote=p(600,20,false);c.duringRead=()->s.put(p(601,21,true));sync.pull("123");check("pull cannot overwrite newer page during network",s.value.timestamp==601&&s.value.chapter==21);c.duringRead=null;
        c.remote=null;s.value=null;check("unread book has no invented progress",sync.pull("123")==null);check("no pending no request",sync.flush("123")==ProgressSync.Result.NONE);
        List<Paragraph> paras=Arrays.asList(new Paragraph(7,"甲😀乙"),new Paragraph(42,"另一段"));ChapterContent ch=new ChapterContent("123","456","标题","md5",paras);
        ReadingProgress pos=ChapterPosition.capture(ch,2,10,5,700);check("UTF16 paragraph offset",pos.paragraph==7&&pos.paragraphOffset==3);check("local coordinate roundtrip",ChapterPosition.offset(ch,pos)==5);
        ReadingProgress cloud=new ReadingProgress("123","456",2,42,2,-1,800,.2,0,"md5",false);check("sparse paragraph ids map to text",ChapterPosition.offset(ch,cloud)==11);check("remote offset clamps",ChapterPosition.offset(ch,new ReadingProgress("123","456",2,42,999,-1,800,.2,0,"md5",false))==12);
        check("book percentage includes chapter fraction",pos.bookRate>.2&&pos.bookRate<.3);check("unknown chapter never uses old local offset",ChapterPosition.offset(new ChapterContent("123","999","other","md5",paras),pos)==0);
        System.out.println("{\"progress_sync_checks\":"+checks+",\"failures\":0}");
    }
}
