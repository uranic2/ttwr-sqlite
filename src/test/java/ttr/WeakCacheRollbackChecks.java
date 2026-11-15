package ttr;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.*;
/** Simulates GC removing a later cache entry during refresh of an earlier entry. */
public final class WeakCacheRollbackChecks {
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        try(SqlitePlayersStore store=new SqlitePlayersStore(null)){
            List<Player> strong=new ArrayList<Player>();
            for(int id=1;id<=2;id++){Player p=new Player();p.profile.ID=id;p.profile.Name="Original "+id;store.upsert(p,"current");strong.add(p);}
            Field field=SqlitePlayersStore.class.getDeclaredField("cache");field.setAccessible(true);
            Map<String,Reference<Player>> cache=(Map<String,Reference<Player>>)field.get(store);
            List<String> keys=new ArrayList<String>(cache.keySet());
            Player retained=cache.get(keys.get(0)).get();
            Reference<Player> expired=cache.get(keys.get(1));expired.clear();expired.enqueue();
            store.begin();store.update(retained.GetID(),"current","name","Uncommitted");store.finish(false);
            if(!retained.profile.Name.equals("Original "+retained.GetID())||store.size()!=2)throw new AssertionError("Rollback did not restore live state");
            if(!strong.get(0).profile.Name.equals("Original 1")||!strong.get(1).profile.Name.equals("Original 2"))throw new AssertionError("Fixture state changed");
        }
        System.out.println("WEAK_CACHE_ROLLBACK_OK: queued later entry removed during refresh without NPE");
    }
}
