package com.watchreader.fanqie;

import java.util.List;
import com.watchreader.fanqie.ReaderPlugin.ReadingProgress;

/** Timestamp-based reconciliation. Never use chapter order to infer recency. */
public final class ProgressSync {
    public interface Store {
        ReadingProgress get(String book)throws Exception;
        void put(ReadingProgress progress)throws Exception;
        boolean replace(ReadingProgress expected,ReadingProgress next)throws Exception;
        List<String> pending()throws Exception;
    }
    public enum Result { NONE,SENT,REMOTE_NEWER,DEFERRED }
    public interface Client {ReadingProgress read(String book)throws Exception;void upload(ReadingProgress progress)throws Exception;}
    private final Client client;public final Store store;
    public ProgressSync(ReaderPlugin plugin,Store store){this(new Client(){public ReadingProgress read(String book)throws Exception{return plugin.readProgress(book);}public void upload(ReadingProgress p)throws Exception{plugin.uploadProgress(p);}},store);}
    public ProgressSync(Client client,Store store){this.client=client;this.store=store;}
    public static ReadingProgress latest(ReadingProgress local,ReadingProgress remote){return remote!=null&&(local==null||remote.timestamp>local.timestamp)?remote:local;}
    public ReadingProgress pull(String book)throws Exception {
        ReadingProgress local=store.get(book),remote=client.read(book);
        if(remote!=null&&(local==null||remote.timestamp>local.timestamp)){
            // A page turn while GET was in flight must remain newer than the snapshot.
            store.replace(local,remote.clean());
        }
        return store.get(book);
    }
    public Result flush(String book)throws Exception {
        ReadingProgress snapshot=store.get(book);if(snapshot==null||!snapshot.dirty)return Result.NONE;
        ReadingProgress remote=client.read(book),current=store.get(book);
        if(!snapshot.same(current))return Result.DEFERRED;
        if(remote!=null&&remote.timestamp>snapshot.timestamp){store.replace(snapshot,remote.clean());return Result.REMOTE_NEWER;}
        client.upload(snapshot);
        // An acknowledgement for an old page never clears a newer unsent page.
        store.replace(snapshot,snapshot.clean());return Result.SENT;
    }
}
