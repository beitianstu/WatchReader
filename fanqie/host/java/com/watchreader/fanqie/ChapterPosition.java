package com.watchreader.fanqie;

import com.watchreader.fanqie.ReaderPlugin.*;

/** Convert server paragraph coordinates to the renderer's UTF-16 text offsets. */
public final class ChapterPosition {
    private ChapterPosition(){}
    public static int length(ChapterContent ch){int n=0;for(Paragraph p:ch.paragraphs)n+=p.text.length()+3;return n;}
    public static int offset(ChapterContent ch,ReadingProgress p){
        if(p==null||!ch.itemId.equals(p.itemId))return 0;
        int total=length(ch);if(p.charOffset>=0&&!ch.version.isEmpty()&&ch.version.equals(p.version))return Math.min(p.charOffset,Math.max(0,total-1));
        int start=0;for(Paragraph para:ch.paragraphs){if(para.index==p.paragraph)return start+2+Math.max(0,Math.min(p.paragraphOffset,para.text.length()));start+=para.text.length()+3;}
        return (int)(Math.max(0,Math.min(1,p.pageRate))*Math.max(0,total-1));
    }
    public static ReadingProgress capture(ChapterContent ch,int chapter,int count,int offset,long timestamp){
        int start=0;Paragraph chosen=ch.paragraphs.get(ch.paragraphs.size()-1);int inParagraph=chosen.text.length();
        for(Paragraph para:ch.paragraphs){if(offset<start+para.text.length()+3){chosen=para;inParagraph=Math.max(0,Math.min(para.text.length(),offset-start-2));break;}start+=para.text.length()+3;}
        double pageRate=Math.max(0,Math.min(1,offset/(double)Math.max(1,length(ch))));
        return new ReadingProgress(ch.bookId,ch.itemId,chapter,chosen.index,inParagraph,offset,timestamp,Math.min(1,(chapter+pageRate)/Math.max(1,count)),pageRate,ch.version,true);
    }
}
