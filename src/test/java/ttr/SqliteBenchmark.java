package ttr;
import ttr.*;
import java.util.*;
import java.lang.management.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
public final class SqliteBenchmark{
    static volatile boolean run=true;
    public static void main(String[] args)throws Exception{
        final MemoryMXBean mem=ManagementFactory.getMemoryMXBean();final AtomicLong peak=new AtomicLong();
        Thread sampler=new Thread(()->{while(run){peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(),Math::max);LockSupport.parkNanos(2000000);}});sampler.setDaemon(true);sampler.start();
        DBMain db=new DBMain();long start=System.nanoTime();if(!db.players.LoadXML(args[0],null))throw new AssertionError("Open failed");double open=(System.nanoTime()-start)/1e9;
        if(db.players.players.size()!=149194)throw new AssertionError("Count differs");
        start=System.nanoTime();Player[] page=db.players.store().query("","","",1,false,0,100);double firstPage=(System.nanoTime()-start)/1e6;
        long[] lookups=new long[1000];for(int i=0;i<lookups.length;i++){start=System.nanoTime();if(db.players.GetPlayer(page[i%page.length].GetID())==null)throw new AssertionError();lookups[i]=System.nanoTime()-start;}
        Arrays.sort(lookups);start=System.nanoTime();int count=db.players.store().count("ИВАНОВ","","");Player[] found=db.players.store().query("ИВАНОВ","","",1,false,0,100);double search=(System.nanoTime()-start)/1e6;
        start=System.nanoTime();if(!db.players.SaveXML(args[0]))throw new AssertionError("Checkpoint failed");double save=(System.nanoTime()-start)/1e6;
        peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(),Math::max);run=false;sampler.join();System.gc();Thread.sleep(100);
        System.out.printf(Locale.ROOT,"OPEN_SECONDS=%.4f FIRST_PAGE_MS=%.3f LOOKUP_MEDIAN_US=%.3f SEARCH_MS=%.3f FOUND=%d CHECKPOINT_MS=%.3f PEAK_HEAP_MIB=%.2f RETAINED_MIB=%.2f PAGE_SIZE=%d%n",open,firstPage,lookups[500]/1000.0,search,count,save,peak.get()/1048576.0,mem.getHeapMemoryUsage().getUsed()/1048576.0,found.length);
        db.players.store().close();
    }
}
