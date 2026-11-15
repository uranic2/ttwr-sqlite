package ttr.updates;

import java.io.*;
import java.net.HttpURLConnection;

/** Cancellation belongs to one download, including connection setup and verification. */
public final class DownloadControl {
    private volatile boolean cancelled;
    private boolean completed;
    private HttpURLConnection connection;
    private InputStream input;
    private Thread worker;
    public synchronized void start() throws InterruptedIOException {check();worker=Thread.currentThread();}
    synchronized void connection(HttpURLConnection value)throws InterruptedIOException {check();connection=value;}
    synchronized void input(InputStream value)throws InterruptedIOException {check();input=value;}
    synchronized void disconnected(HttpURLConnection value){if(connection==value){connection=null;input=null;}}
    public synchronized void finish(){connection=null;input=null;worker=null;}
    public boolean isCancelled(){return cancelled;}
    public void check()throws InterruptedIOException {if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Загрузка отменена");}
    public synchronized boolean accept(){if(cancelled)return false;completed=true;return true;}
    public void cancel(){
        final HttpURLConnection active;final InputStream stream;final Thread thread;
        synchronized(this){if(completed||cancelled)return;cancelled=true;active=connection;stream=input;thread=worker;}
        if(thread!=null)thread.interrupt();
        // Socket/stream close may block; never perform it on the Swing event thread.
        Thread closer=new Thread(()->{if(active!=null)active.disconnect();if(stream!=null)try{stream.close();}catch(IOException ignored){}},"TTWR-download-cancel");
        closer.setDaemon(true);closer.start();
    }
}
