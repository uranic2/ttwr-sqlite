package ttr;
import java.nio.file.*;
/** Explicit import/export and validation utility, separate from normal SQLite-only operation. */
public final class SqliteCatalogTool{
    public static void main(String[] args)throws Exception{
        if(args.length<2)throw new IllegalArgumentException("Commands: import <XML> <SQLite>; export <SQLite> <XML>; check <SQLite>");
        DBMain db=new DBMain();
        if(args[0].equals("import")){
            if(args.length!=3)throw new IllegalArgumentException("import <XML> <SQLite>");
            SqlitePlayersIntegration.importXml(db.players,args[1],Paths.get(args[2]),null);
        }else{
            if(!db.players.LoadXML(args[1],null))throw new IllegalStateException("Cannot open SQLite catalog");
            if(args[0].equals("export")){
                if(args.length!=3||!StreamingPlayerWriter.save(db.players,args[2]))throw new IllegalStateException("XML export failed");
            }else if(!args[0].equals("check"))throw new IllegalArgumentException("Unknown command");
        }
        String result=db.players.store().integrity();int count=db.players.players.size();db.players.store().close();
        if(!"ok".equals(result))throw new IllegalStateException(result);
        System.out.println("SQLITE_CATALOG_OK players="+count+" integrity="+result);
    }
}
