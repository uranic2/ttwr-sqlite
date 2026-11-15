package ttr;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/** Refreshes the original database status on the EDT; old network replies cannot undo an update. */
public final class DatabaseStatus {
    private DatabaseStatus(){}
    private static final AtomicLong generation=new AtomicLong();
    public static void check(final Updater updater){
        final TTRClient client=TTRClient.getInstance();
        if(client==null)return;
        final long request=generation.incrementAndGet();
        Thread worker=new Thread(()->{
            Calendar published=null;
            try{published=DatabaseDate.latest(PlayerUpdates.source());}
            catch(Exception ignored){/* Unknown freshness must not become an obsolete/fresh claim. */}
            final Calendar result=published;
            SwingUtilities.invokeLater(()->{
                if(generation.get()!=request||TTRClient.getInstance()!=client||!client.isDisplayable())return;
                render(client,updater,result);
            });
        },"TTWR-database-date");
        worker.setDaemon(true);worker.start();
    }
    public static void afterApply(final TTRClient client,final Calendar published){
        if(!SwingUtilities.isEventDispatchThread()){
            SwingUtilities.invokeLater(()->afterApply(client,published));return;
        }
        generation.incrementAndGet();
        try{
            Field field=TTRClient.class.getDeclaredField("updater");field.setAccessible(true);
            Updater updater=(Updater)field.get(client);
            render(client,updater,published);
            if(published==null)check(updater); // Local profile import needs a separate freshness check.
        }catch(Exception error){throw new IllegalStateException("Не удалось обновить статус базы",error);}
    }
    private static void render(TTRClient client,Updater updater,Calendar published){
        updater.bNewDB=DatabaseDate.newer(published,client.GetDBMain().date);
        // The original callback formats DBMain.date and chooses question/warning/ok icons.
        updater.OnCompleteDBCheck(published!=null);
    }
}
