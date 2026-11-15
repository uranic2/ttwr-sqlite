package ttr;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic HTTPS transport; never reads real AppData or contacts a server. */
public final class PlayerStreamChecks {
    static volatile String mode="known";
    static byte[] players;
    static volatile boolean ended;
    static CountDownLatch waiting;
    static final AtomicBoolean streamClosed=new AtomicBoolean();
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);Path local=PlayerProfileChecks.source(root);
        players=Files.readAllBytes(local.resolve("Список.xml"));
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){
            protected URLConnection openConnection(URL url){return new HttpURLConnection(url){
                volatile boolean closed;
                public void connect(){}public boolean usingProxy(){return false;}
                public synchronized void disconnect(){closed=true;notifyAll();}
                public long getContentLengthLong(){return mode.equals("unknown")?-1:players.length;}
                public InputStream getInputStream()throws IOException{
                    check(getConnectTimeout()==15000&&getReadTimeout()==15000,"Explicit network timeouts missing");
                    if(url.getPath().endsWith("db.xml"))return new ByteArrayInputStream(("<DB name='HTTPS'><PlayerDB name='Players.xml'/><TemplDB name='Templs.xml'/><RegionList name='Regions.xml'/></DB>").getBytes(StandardCharsets.UTF_8));
                    if(url.getPath().endsWith("Templs.xml"))return new ByteArrayInputStream("<Templates/>".getBytes(StandardCharsets.UTF_8));
                    if(url.getPath().endsWith("Regions.xml"))return new ByteArrayInputStream("<Regions/>".getBytes(StandardCharsets.UTF_8));
                    if(mode.equals("headers")){
                        synchronized(this){waiting.countDown();while(!closed)try{wait();}catch(InterruptedException e){throw new IOException(e);}}
                        throw new IOException("Closed headers");
                    }
                    final HttpURLConnection owner=this;
                    return new InputStream(){int position;
                        public int read()throws IOException{byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:one[0]&255;}
                        public int read(byte[] b,int off,int len)throws IOException{
                            if(closed)throw new IOException("Closed");
                            if(mode.equals("body")&&position>4096){
                                synchronized(owner){waiting.countDown();while(!closed)try{owner.wait();}catch(InterruptedException e){throw new IOException(e);}}
                                throw new IOException("Closed body");
                            }
                            if(mode.equals("broken")&&position>8192)throw new IOException("Simulated connection lost");
                            int limit=mode.equals("badxml")?players.length-5:players.length;
                            if(position>=limit){ended=true;return -1;}
                            try{Thread.sleep(2);}catch(InterruptedException e){throw new IOException(e);}
                            int count=Math.min(Math.min(512,len),limit-position);System.arraycopy(players,position,b,off,count);position+=count;return count;
                        }
                        public void close(){streamClosed.set(true);disconnect();}
                    };
                }
            };}
        });
        DBMain original=PlayerProfileChecks.baseline(PortablePaths.profile());
        List<List<Object>> before=SinglePassImportChecks.rows(original.players.store());
        for(String scenario:new String[]{"known","unknown","broken","badxml","headers","body"}){
            mode=scenario;ended=false;streamClosed.set(false);waiting=new CountDownLatch(1);
            final AtomicBoolean liveRecords=new AtomicBoolean();
            PlayersProgress progress=new PlayersProgress(){public void OnElementParsed(long a,long b){super.OnElementParsed(a,b);if(!ended&&a>0)liveRecords.set(true);}};
            try(final PlayersUpdateSession session=new PlayersUpdateSession(original,progress)){
                if(scenario.equals("headers")||scenario.equals("body")){
                    FutureTask<Boolean> task=new FutureTask<Boolean>(()->{try{session.prepare("https://fixture.test/db.xml",null);return false;}catch(Exception expected){return true;}});
                    new Thread(task,"cancel-network-test").start();check(waiting.await(5,TimeUnit.SECONDS),"Transport never blocked");
                    check(session.control.cancel(),"Cancel refused");check(task.get(5,TimeUnit.SECONDS),"Cancel did not abort transport");
                }else if(scenario.equals("broken")||scenario.equals("badxml")){
                    try{session.prepare("https://fixture.test/db.xml",null);throw new AssertionError("Broken stream accepted "+scenario);}catch(IOException expected){}
                }else{
                    session.prepare("https://fixture.test/db.xml",null);
                    check(liveRecords.get(),"Record count updated only at EOF");
                    check(progress.records()==301,"Read count changed by template callbacks");
                    check(progress.snapshot().bytes==players.length,"Player byte count includes metadata or templates");
                    check(!progress.reading(),"Network speed remained current after parsing");
                    check(scenario.equals("unknown")?progress.snapshot().total==-1:progress.snapshot().total==players.length,"Size state wrong");
                    check(session.candidate.players.players.size()==300,"Network result wrong");
                }
            }
            check(before.equals(SinglePassImportChecks.rows(original.players.store())),"Failed/prepared operation changed active DB "+scenario);
            if(!scenario.equals("headers"))check(streamClosed.get(),"Input stream not closed "+scenario);
            PlayerProfileChecks.noStages(PortablePaths.profile());
        }
        original.players.store().close();
        System.out.println("PLAYER_STREAM_OK: known/unknown size, live records, exact XML volume, frozen measurements, broken XML/connection, header/body cancellation, cleanup and original rows retained");
    }
}
