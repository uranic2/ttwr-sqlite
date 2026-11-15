package ttr.updates;

/** Deterministic monotonic clocks, unknown XML length and a stalled two-second window. */
public final class PlayerDownloadStatsChecks {
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    public static void main(String[] args){
        DownloadStats stats=new DownloadStats(-1,0);
        stats.received(1024,1000000000L);
        DownloadStats.Snapshot a=stats.snapshot(1000000000L);
        check(a.bytes==1024&&a.total==-1&&a.remainingSeconds==null,"Unknown size invented total/ETA");
        check(a.averageBytesPerSecond==1024&&a.recentBytesPerSecond==1024,"Initial available interval");
        stats.received(3072,2000000000L);
        a=stats.snapshot(2000000000L);check(a.recentBytesPerSecond==1536,"Two-second speed");
        a=stats.snapshot(4000000000L);check(a.recentBytesPerSecond==0&&a.remainingSeconds==null,"Stall did not decay");
        stats.downloaded(4000000000L);
        a=stats.snapshot(9000000000L);check(a.averageBytesPerSecond==768&&a.elapsedSeconds==4,"Download measurements did not freeze");
        DownloadStats known=new DownloadStats(8192,0);known.received(4096,1000000000L);
        check(known.snapshot(1000000000L).remainingSeconds==1,"Known-size ETA");
        check(known.snapshot(3000000000L).remainingSeconds==null,"Zero-speed ETA must be unknown");
        System.out.println("PLAYER_DOWNLOAD_STATS_OK: unknown size, 2-second window, stalled speed, ETA, frozen average");
    }
}
