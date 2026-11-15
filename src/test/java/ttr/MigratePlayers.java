package ttr;
import ttr.*;
import java.nio.file.*;
public final class MigratePlayers{
    public static void main(String[] args)throws Exception{
        DBMain db=new DBMain();long started=System.nanoTime();
        SqlitePlayersIntegration.importXml(db.players,args[0],Paths.get(args[1]),null);
        if(db.players.players.size()!=149194)throw new AssertionError("Player count differs");
        System.out.println("SQLITE_IMPORT_OK players="+db.players.players.size()+" seconds="+((System.nanoTime()-started)/1e9)+" integrity="+db.players.store().integrity());
        db.players.store().close();
    }
}
