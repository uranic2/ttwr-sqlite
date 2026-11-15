package ttr;
import java.util.*;
/** Owns repositories created by the legacy update dialog, including cancellation paths. */
public final class SqliteUpdates{
    private SqliteUpdates(){}
    private static final class Pending{DBMain original;final Set<DBMain> databases=Collections.newSetFromMap(new IdentityHashMap<DBMain,Boolean>());}
    private static final ThreadLocal<Pending> pending=new ThreadLocal<Pending>();
    public static void begin(TTRClient client){Pending p=new Pending();p.original=client.GetDBMain();p.databases.add(p.original);pending.set(p);}
    public static void remember(TTRClient client,DBMain database){Pending p=pending.get();if(p!=null&&database!=null)p.databases.add(database);}
    public static void finish(TTRClient client){Pending p=pending.get();if(p==null)return;try{DBMain active=client.GetDBMain();for(DBMain db:p.databases)if(db!=active&&db.players.existing()!=null)db.players.existing().close();}finally{pending.remove();}}
    public static void abort(TTRClient client){Pending p=pending.get();if(p==null)return;try{java.lang.reflect.Field field=TTRClient.class.getDeclaredField("mainDB");field.setAccessible(true);field.set(client,p.original);}catch(Exception e){throw new IllegalStateException(e);}finally{finish(client);}}
}
