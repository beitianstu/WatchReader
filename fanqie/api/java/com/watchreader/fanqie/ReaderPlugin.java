package com.watchreader.fanqie;

import java.util.List;

/** A source plugin, independent of the watch UI and local ReaderView. */
public interface ReaderPlugin {
    String id();
    Page<BookInfo> bookshelf(String cursor)throws Exception;
    Page<BookInfo> search(String keyword,String cursor)throws Exception;
    List<ChapterInfo> directory(String bookId)throws Exception;
    ChapterContent chapter(String bookId,ChapterInfo chapter)throws Exception;
    Page<Comment> comments(ChapterContent chapter,Paragraph paragraph,String cursor)throws Exception;
    String publish(ChapterContent chapter,Paragraph paragraph,String text)throws Exception;
    default ReadingProgress readProgress(String bookId)throws Exception {return null;}
    default void uploadProgress(ReadingProgress progress)throws Exception {throw new UnsupportedOperationException("Progress sync unavailable");}
    default List<ShelfGroup> shelfGroups()throws Exception {return java.util.Collections.singletonList(new ShelfGroup("","全部",-1));}
    default Page<BookInfo> bookshelf(String group,String cursor)throws Exception {return bookshelf(cursor);}
    default BookDetails details(BookInfo book)throws Exception {return new BookDetails(book,"","","",0);}

    final class ReadingProgress {
        public final String bookId,itemId,version;public final int chapter,paragraph,paragraphOffset,charOffset;
        public final long timestamp;public final double bookRate,pageRate;public final boolean dirty;
        public ReadingProgress(String book,String item,int chapter,int paragraph,int paragraphOffset,int charOffset,long timestamp,double bookRate,double pageRate,String version,boolean dirty){this.bookId=book;this.itemId=item;this.chapter=chapter;this.paragraph=paragraph;this.paragraphOffset=paragraphOffset;this.charOffset=charOffset;this.timestamp=timestamp;this.bookRate=bookRate;this.pageRate=pageRate;this.version=version;this.dirty=dirty;}
        public ReadingProgress clean(){return new ReadingProgress(bookId,itemId,chapter,paragraph,paragraphOffset,charOffset,timestamp,bookRate,pageRate,version,false);}
        public boolean same(ReadingProgress p){return p!=null&&bookId.equals(p.bookId)&&itemId.equals(p.itemId)&&timestamp==p.timestamp&&paragraph==p.paragraph&&paragraphOffset==p.paragraphOffset&&charOffset==p.charOffset;}
    }

    final class Page<T> {
        public final List<T> items;public final String next;public final boolean more;
        public Page(List<T> items,String next,boolean more){this.items=items;this.next=next;this.more=more;}
    }
    final class BookInfo {
        public final String id,title,author;
        public BookInfo(String id,String title,String author){this.id=id;this.title=title;this.author=author;}
    }
    final class ShelfGroup {
        public final String id,name;public final int count;
        public ShelfGroup(String id,String name,int count){this.id=id;this.name=name;this.count=count;}
    }
    final class BookDetails {
        public final BookInfo book;public final String description,coverUrl,latestChapter;public final int chapters;
        public BookDetails(BookInfo book,String description,String coverUrl,String latestChapter,int chapters){this.book=book;this.description=description;this.coverUrl=coverUrl;this.latestChapter=latestChapter;this.chapters=chapters;}
    }
    final class ChapterInfo {
        public final String id,title,version;
        public ChapterInfo(String id,String title,String version){this.id=id;this.title=title;this.version=version;}
    }
    final class Paragraph {
        public final int index;public final String text;
        public Paragraph(int index,String text){this.index=index;this.text=text;}
    }
    final class ChapterContent {
        public final String bookId,itemId,title,version;public final List<Paragraph> paragraphs;
        public ChapterContent(String book,String item,String title,String version,List<Paragraph> paras){bookId=book;itemId=item;this.title=title;this.version=version;paragraphs=paras;}
    }
    final class Comment {
        public final String id,author,text;
        public Comment(String id,String author,String text){this.id=id;this.author=author;this.text=text;}
    }
}
