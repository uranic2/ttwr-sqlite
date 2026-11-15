package ttr.updates;

import java.util.*;

/** Monotonic download measurements; retain at most the two-second window plus its boundary. */
public final class DownloadStats {
    private static final long WINDOW=2000000000L, SAMPLE=100000000L;
    private final long total,started;
    private long received,finished;
    private boolean complete;
    private final Deque<Sample> samples=new ArrayDeque<Sample>();
    private static final class Sample {final long time,bytes;Sample(long t,long b){time=t;bytes=b;}}
    public static final class Snapshot {
        public final long bytes,total,elapsedSeconds;
        public final double recentBytesPerSecond,averageBytesPerSecond;
        public final Long remainingSeconds;
        Snapshot(long b,long t,long elapsed,double recent,double average,Long remaining){bytes=b;total=t;elapsedSeconds=elapsed;recentBytesPerSecond=recent;averageBytesPerSecond=average;remainingSeconds=remaining;}
    }
    public DownloadStats(long total){this(total,System.nanoTime());}
    DownloadStats(long total,long now){if(total==0||total< -1)throw new IllegalArgumentException("Download size must be positive or unknown (-1)");this.total=total;started=now;samples.add(new Sample(now,0));}
    public synchronized void received(long bytes){received(bytes,System.nanoTime());}
    synchronized void received(long bytes,long now){received=Math.max(received,(total>0?Math.min(total,bytes):Math.max(0,bytes)));if(now-samples.getLast().time>=SAMPLE)sample(now);}
    public synchronized void downloaded(){downloaded(System.nanoTime());}
    synchronized void downloaded(long now){finished=now;complete=true;sample(now);}
    private void sample(long now){
        if(samples.getLast().time==now)samples.removeLast();samples.add(new Sample(now,received));
        long cutoff=now-WINDOW;
        while(samples.size()>2){Iterator<Sample> i=samples.iterator();i.next();if(i.next().time>cutoff)break;samples.removeFirst();}
    }
    public synchronized Snapshot snapshot(){return snapshot(System.nanoTime());}
    synchronized Snapshot snapshot(long now){
        now=complete?finished:now;sample(now);
        long boundary=Math.max(started,now-WINDOW);Sample first=samples.getFirst();double baseline=first.bytes;
        Iterator<Sample> it=samples.iterator();it.next();
        if(first.time<boundary&&it.hasNext()){Sample second=it.next();if(second.time>first.time)baseline+=(second.bytes-first.bytes)*(double)(boundary-first.time)/(second.time-first.time);}
        double seconds=Math.max(0,now-started)/1e9,recentSeconds=Math.max(0,now-boundary)/1e9;
        double average=seconds>0?received/seconds:0,recent=recentSeconds>0?Math.max(0,received-baseline)/recentSeconds:0;
        Long remaining=total<0?null:received>=total?Long.valueOf(0):recent>0?Long.valueOf((long)Math.ceil((total-received)/recent)):null;
        return new Snapshot(received,total,(long)seconds,recent,average,remaining);
    }
}
