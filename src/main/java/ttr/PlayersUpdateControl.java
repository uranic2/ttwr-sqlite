package ttr;

import java.io.*;
import java.net.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.sqlite.ProgressHandler;

/** Per-operation cancellation; socket close never runs on the EDT. */
public final class PlayersUpdateControl {
    private static final ThreadLocal<PlayersUpdateControl> CURRENT=new ThreadLocal<PlayersUpdateControl>();
    private volatile boolean cancelled;
    private boolean applying;
    private volatile HttpURLConnection connection;
    private volatile Throwable error;
    public static PlayersUpdateControl current(){return CURRENT.get();}
    public void enter(){CURRENT.set(this);}
    public void leave(){CURRENT.remove();}
    public boolean isCancelled(){return cancelled;}
    public synchronized boolean cancel(){
        if(applying||cancelled)return false;
        cancelled=true;
        final HttpURLConnection c=connection;
        if(c!=null){Thread closer=new Thread(()->c.disconnect(),"players-network-close");closer.setDaemon(true);closer.start();}
        return true;
    }
    public synchronized void applying(){check();applying=true;}
    public synchronized boolean canCancel(){return !applying&&!cancelled;}
    public void check(){if(cancelled)throw new java.util.concurrent.CancellationException("Обновление отменено");}
    public static void checkpoint(){PlayersUpdateControl c=current();if(c!=null)c.check();}
    public void failed(Throwable e){if(error==null)error=e;}
    public Throwable error(){return error;}
    public void watch(SqlitePlayersStore store)throws Exception{
        ProgressHandler.setHandler(store.connection,1000,new ProgressHandler(){protected int progress(){return cancelled?1:0;}});
        check();
    }
    public void unwatch(SqlitePlayersStore store)throws Exception{ProgressHandler.clearHandler(store.connection);}
    public HttpURLConnection connect(String source)throws IOException{
        check();
        HttpURLConnection c=(HttpURLConnection)new URL(source.replace('\\','/')).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setUseCaches(false);
        c.setRequestProperty("Accept-Encoding","identity");
        c.setRequestProperty("User-Agent","TTWR-SQLite");
        connection=c;
        try{check();return c;}catch(RuntimeException e){c.disconnect();throw e;}
    }
    public void release(HttpURLConnection c){if(connection==c)connection=null;c.disconnect();}
    /** Metadata and templates are small DOM documents; Players.xml always uses SAX. */
    public static Document document(String source,ttr.events.XmlLoadEvents callback){
        PlayersUpdateControl c=current();
        if(c==null||!source.startsWith("https:"))return Utility.LoadXMLDocument(source,callback);
        HttpURLConnection connection=null;
        try{
            connection=c.connect(source);
            try(InputStream in=connection.getInputStream()){
                DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                Document result=f.newDocumentBuilder().parse(new FilterInputStream(in){
                    public int read()throws IOException{c.check();return super.read();}
                    public int read(byte[] b,int o,int n)throws IOException{c.check();return super.read(b,o,n);}
                });
                c.check();return result;
            }
        }catch(Exception e){c.failed(e);if(callback!=null)callback.SetErrorCode(1,e.toString());return null;}
        finally{if(connection!=null)c.release(connection);}
    }
}
