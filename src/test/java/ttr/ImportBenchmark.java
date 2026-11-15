package ttr;

import java.nio.file.*;
import java.lang.management.*;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in benchmark, outside the normal test suite. Input must contain synthetic records. */
public final class ImportBenchmark {
    public static void main(String[] args)throws Exception{
        Path xml=Paths.get(args[0]),target=Paths.get(args[1]);Files.createDirectories(target.getParent());
        AtomicLong peak=new AtomicLong();
        Thread sampler=new Thread(()->{while(!Thread.currentThread().isInterrupted()){
            long used=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();peak.accumulateAndGet(used,Math::max);
            try{Thread.sleep(10);}catch(InterruptedException end){break;}
        }},"import-memory-sampler");sampler.setDaemon(true);sampler.start();
        long start=System.nanoTime();DBMain db=new DBMain();SqlitePlayersIntegration.importXml(db.players,xml.toString(),target,null);
        double seconds=(System.nanoTime()-start)/1e9;int count=db.players.store().size();String integrity=db.players.store().integrity();db.players.store().close();sampler.interrupt();sampler.join();
        System.out.printf(java.util.Locale.ROOT,"IMPORT seconds=%.3f peakHeapMiB=%.2f players=%d integrity=%s databaseBytes=%d%n",seconds,peak.get()/1048576.0,count,integrity,Files.size(target));
    }
}
