package ttr;
import java.nio.file.*;
import java.util.*;
import ttr.events.XmlLoadEvents;

public final class SqlitePlayersIntegration {
    private SqlitePlayersIntegration(){}
    public static boolean load(DBPlayers db,DBMain main,String source,XmlLoadEvents callback){
        try{
            boolean remote=source.startsWith("https:");
            if(remote){Path temp=Files.createTempFile("ttwr-update-",".sqlite");Files.delete(temp);try{importXml(db,source,temp,callback);db.store().temporary=true;db.file="Players.sqlite";return true;}catch(Exception e){Files.deleteIfExists(temp);throw e;}}
            Path input=Paths.get(source).toAbsolutePath().normalize();
            boolean sqlite=source.toLowerCase(Locale.ROOT).endsWith(".sqlite")||source.toLowerCase(Locale.ROOT).endsWith(".db3");
            String name=input.getFileName().toString();int dot=name.lastIndexOf('.');Path target=sqlite?input:input.resolveSibling((dot<0?name:name.substring(0,dot))+".sqlite");
            if(Files.exists(target)){
                if(Files.size(target)<100)throw new IllegalStateException("Файл SQLite пуст или повреждён: "+target);
                SqlitePlayersStore store=new SqlitePlayersStore(target);
                if(!"ok".equals(store.quickCheck())){store.close();throw new IllegalStateException("SQLite quick check failed");}
                db.use(store);db.file=target.getFileName().toString();db.UpdatePlayerLocationList(true);
                if(callback!=null)callback.OnSectionStarted("Игроки открыты из SQLite: "+store.size());return true;
            }
            if(sqlite)throw new IllegalStateException("Файл SQLite не найден: "+input);
            importXml(db,source,target,callback);db.file=target.getFileName().toString();return true;
        }catch(Exception error){PlayersUpdateControl c=PlayersUpdateControl.current();if(c!=null)c.failed(error);if(callback!=null)callback.SetErrorCode(1,error.toString());log(error);return false;}
    }
    public static void importXml(DBPlayers db,String xml,Path target,XmlLoadEvents callback)throws Exception{
        target=target.toAbsolutePath().normalize();Path stage=Files.createTempFile(target.getParent(),".ttwr-import-",".sqlite");
        SqlitePlayersStore old=db.existing();SqlitePlayersStore imported=null;boolean success=false;
        try{
            imported=new SqlitePlayersStore(stage,true);db.useWithoutClosing(imported);imported.importing=true;imported.begin();
            PlayersUpdateControl control=PlayersUpdateControl.current();if(control!=null)control.watch(imported);
            StreamingPlayers.loadInto(db,xml,callback,null);
            if(callback!=null)callback.OnSectionStarted(PlayersProgress.INDEX);
            PlayersUpdateControl.checkpoint();imported.createIndexes();
            if(callback!=null)callback.OnSectionStarted(PlayersProgress.CHECK);
            PlayersUpdateControl.checkpoint();
            imported.finish(true);imported.importing=false;
            if(!"ok".equals(imported.integrity()))throw new IllegalStateException("Imported SQLite integrity check failed");
            db.UpdatePlayerLocationList(true);
            PlayersUpdateControl.checkpoint();
            if(control!=null)control.unwatch(imported);
            imported.checkpoint();imported.close();imported=null;
            try{Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(stage,target,StandardCopyOption.REPLACE_EXISTING);}
            db.useWithoutClosing(new SqlitePlayersStore(target));db.file=target.getFileName().toString();if(old!=null)old.close();success=true;
        }finally{
            if(!success){if(imported!=null){try{org.sqlite.ProgressHandler.clearHandler(imported.connection);imported.finish(false);}finally{imported.close();}}db.useWithoutClosing(old);}
            Files.deleteIfExists(stage);Files.deleteIfExists(Paths.get(stage+"-wal"));Files.deleteIfExists(Paths.get(stage+"-shm"));
        }
    }
    public static boolean save(DBPlayers db,String name){try{
        Path target=Paths.get(name);String filename=target.getFileName().toString();if(!filename.toLowerCase(Locale.ROOT).endsWith(".sqlite"))target=target.resolveSibling(filename.replaceFirst("(?i)\\.xml$","")+".sqlite");
        db.store().copyTo(target);db.file=target.getFileName().toString();return true;
    }catch(Exception e){log(e);return false;}}
    public static Player[] exportPlayers(DBPlayers db,Tournament tournament){try{
        java.lang.reflect.Field f=Tournament.class.getDeclaredField("newPlrs");f.setAccessible(true);
        @SuppressWarnings("unchecked") Collection<Integer> ids=(Collection<Integer>)f.get(tournament);
        return db.store().exportPlayers(ids);
    }catch(Exception e){throw SqlitePlayersStore.failure(e);}}
    static void log(Exception e){boolean old=TTRClient.bLog;try{TTRClient.bLog=true;TTRClient.WriteLog("SQLite: "+e);}finally{TTRClient.bLog=old;}}
}
