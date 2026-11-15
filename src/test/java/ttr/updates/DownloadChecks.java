package ttr.updates;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class DownloadChecks {
    static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
    static void near(double a,double b){check(Math.abs(a-b)<0.001,"Rate mismatch "+a+" / "+b);}
    static long sec(long n){return n*1000000000L;}
    static volatile String mode="success";
    static volatile CountDownLatch blocked=new CountDownLatch(1),closed=new CountDownLatch(1);
    static byte[] bytes=new byte[131072];
    static void awaitClose()throws IOException{
        blocked.countDown();boolean done=false;
        while(!done)try{done=closed.await(2,TimeUnit.SECONDS);if(!done)throw new IOException("Disconnect did not unblock read");}catch(InterruptedException ignored){/* Require a real connection close, not just interruption. */}
        throw new IOException("Closed by cancellation");
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        DownloadStats stats=new DownloadStats(10000,0);DownloadStats.Snapshot s=stats.snapshot(0);near(s.recentBytesPerSecond,0);check(s.remainingSeconds==null,"ETA before data");
        stats.received(1000,sec(1));stats.received(2000,sec(2));s=stats.snapshot(sec(2));near(s.averageBytesPerSecond,1000);near(s.recentBytesPerSecond,1000);check(s.remainingSeconds==8,"Startup ETA");
        s=stats.snapshot(sec(7));near(s.recentBytesPerSecond,0);check(s.remainingSeconds==null,"Stalled ETA must be unknown");
        stats.received(4000,sec(8));s=stats.snapshot(sec(8));near(s.recentBytesPerSecond,1000);near(s.averageBytesPerSecond,500);check(s.remainingSeconds==6,"Window ETA");
        stats.received(10000,sec(12));stats.downloaded(sec(12));s=stats.snapshot(sec(30));check(s.elapsedSeconds==12&&s.remainingSeconds==0,"Completed measurements did not freeze");
        stats=new DownloadStats(1000,-sec(20));stats.received(1000,-sec(19));stats.downloaded(-sec(19));check(stats.snapshot(0).elapsedSeconds==1,"Negative monotonic timestamp");
        new Random(7).nextBytes(bytes);Path source=root.resolve("source");Files.write(source,bytes);String hash=ReleaseClient.sha256(source);
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){
            public void connect(){}public boolean usingProxy(){return false;}
            public void disconnect(){closed.countDown();}
            public int getResponseCode()throws IOException{if(mode.equals("headers"))awaitClose();return 200;}
            public InputStream getInputStream(){return new InputStream(){
                int pos;
                public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
                public int read(byte[] b,int o,int n)throws IOException{
                    if(mode.equals("body")&&pos>=8192)awaitClose();
                    if(pos>=bytes.length)return -1;int count=Math.min(Math.min(n,8192),bytes.length-pos);System.arraycopy(bytes,pos,b,o,count);pos+=count;return count;
                }
                public void close(){closed.countDown();}
            };}
        };}});
        ReleaseClient.Release release=new ReleaseClient.Release("1.2.0",ReleaseClient.REPO+"/fixture",hash,bytes.length);
        final long[] received={-1};final boolean[] downloaded={false};
        new ReleaseClient().download(release,root.resolve("complete.zip"),new ReleaseClient.Progress(){public void received(long b,long t){check(b>=received[0]&&b<=t,"Progress regressed");received[0]=b;}public void downloaded(){downloaded[0]=true;}},new DownloadControl());
        check(received[0]==bytes.length&&downloaded[0],"Final progress missing");
        for(String blockedMode:Arrays.asList("headers","body")){
            mode=blockedMode;blocked=new CountDownLatch(1);closed=new CountDownLatch(1);DownloadControl control=new DownloadControl();Path partial=root.resolve(blockedMode+".zip");
            ExecutorService executor=Executors.newSingleThreadExecutor();Future<?> worker=executor.submit(()->{try{new ReleaseClient().download(release,partial,null,control);throw new AssertionError("Cancelled download succeeded");}catch(IOException|java.io.UncheckedIOException expected){}finally{control.finish();}});
            try{check(blocked.await(3,TimeUnit.SECONDS),"Read did not block");long started=System.nanoTime();control.cancel();check(System.nanoTime()-started<sec(1),"Cancel blocked caller");worker.get(3,TimeUnit.SECONDS);check(!Files.exists(partial),"Cancelled ZIP remains");}finally{control.cancel();executor.shutdownNow();}
        }
        mode="success";DownloadControl early=new DownloadControl();early.cancel();try{new ReleaseClient().download(release,root.resolve("early.zip"),null,early);throw new AssertionError("Early cancellation ignored");}catch(InterruptedIOException expected){}
        check(!Files.exists(root.resolve("early.zip")),"Early cancellation created a ZIP");
        DownloadControl late=new DownloadControl();late.start();late.finish();late.cancel();check(!late.accept(),"Cancellation between worker completion and EDT callback ignored");
        DownloadControl done=new DownloadControl();check(done.accept(),"Completion rejected");done.cancel();check(!done.isCancelled(),"Late cancellation changed accepted completion");
        Path existing=root.resolve("existing.zip");Files.write(existing,new byte[]{42});try{new ReleaseClient().download(release,existing);throw new AssertionError("Existing file overwritten");}catch(FileAlreadyExistsException expected){}check(Files.readAllBytes(existing)[0]==42,"Existing file deleted");
        Path bad=root.resolve("bad.zip");try{new ReleaseClient().download(new ReleaseClient.Release("1.2.0",release.url,String.join("",Collections.nCopies(64,"0")),bytes.length),bad);throw new AssertionError("Bad digest accepted");}catch(IOException expected){}check(!Files.exists(bad),"Failed ZIP remains");
        Path updates=ttr.PortablePaths.profile().resolve("updates");Files.createDirectories(updates);Path stage=Files.createTempDirectory(updates,"update-");Files.createDirectories(stage.resolve("image/app"));Files.write(stage.resolve("release.zip"),bytes);Files.write(stage.resolve("image/app/partial"),bytes);
        Path keep=updates.resolve("update-installed/backup/keep");Files.createDirectories(keep.getParent());Files.write(keep,new byte[]{9});ProgramUpdates.removeStage(stage);check(!Files.exists(stage)&&Files.readAllBytes(keep)[0]==9,"Cleanup touched retained backup");
        try{ProgramUpdates.removeStage(root);throw new AssertionError("Outside stage accepted");}catch(IOException expected){}
        System.out.println("DOWNLOAD_OK: two-second and average rates, ETA/stalls/completion, cancellation during headers/body, early/late cancellation, partial cleanup and retained backups");
    }
}
