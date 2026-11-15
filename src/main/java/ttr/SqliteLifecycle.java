package ttr;

import java.util.*;
import javax.swing.*;

/** Explicit window-exit cleanup, with a JVM shutdown fallback for every open store. */
public final class SqliteLifecycle {
    private static final Set<SqlitePlayersStore> stores=Collections.newSetFromMap(new IdentityHashMap<SqlitePlayersStore,Boolean>());
    static {
        Runtime.getRuntime().addShutdownHook(new Thread("TTWR-SQLite-close") {
            public void run(){try{closeAll();}catch(RuntimeException error){System.err.println("TTWR SQLite shutdown: "+error);}}
        });
    }
    private SqliteLifecycle(){}
    static synchronized void register(SqlitePlayersStore store){stores.add(store);}
    static synchronized void unregister(SqlitePlayersStore store){stores.remove(store);}
    static void closePath(java.nio.file.Path path){
        SqlitePlayersStore[] open;
        synchronized(SqliteLifecycle.class){open=stores.toArray(new SqlitePlayersStore[0]);}
        path=path.toAbsolutePath().normalize();
        for(SqlitePlayersStore store:open)if(path.equals(store.path))store.close();
    }
    public static void closeAll(){
        SqlitePlayersStore[] open;
        synchronized(SqliteLifecycle.class){open=stores.toArray(new SqlitePlayersStore[0]);}
        RuntimeException failure=null;
        for(SqlitePlayersStore store:open)try{store.close();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
    public static void onWindowClosing(TTRClient client){
        // The original handler sets DO_NOTHING_ON_CLOSE on cancellation/save failure.
        if(client.getDefaultCloseOperation()!=WindowConstants.DISPOSE_ON_CLOSE)return;
        try{closeAll();}catch(RuntimeException error){
            client.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            SqlitePlayersIntegration.log(error);
            JOptionPane.showMessageDialog(client,"Не удалось закрыть базу игроков.\n"+error.getMessage(),"Ошибка SQLite",JOptionPane.ERROR_MESSAGE);
            return;
        }
        try{ttr.updates.ProgramUpdates.onConfirmedExit();}catch(java.io.IOException error){
            SqlitePlayersIntegration.log(error);
            JOptionPane.showMessageDialog(client,"Не удалось запустить установку обновления. Программа будет закрыта без обновления.\n"+error.getMessage(),"Обновление TTWR SQLite",JOptionPane.ERROR_MESSAGE);
        }
    }
}
