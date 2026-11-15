package ttr;
import ttr.*;
import java.nio.file.*;
public final class PrepareMinimalCatalog {
    public static void main(String[] args)throws Exception{
        Path path=Paths.get(args[0]);Files.createDirectories(path);
        try(SqlitePlayersStore store=new SqlitePlayersStore(path.resolve("Players.sqlite"))){
            if(store.size()!=0||!store.integrity().equals("ok"))throw new AssertionError("Seed must be empty and valid");
        }
        Files.write(path.resolve("db.xml"),("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<DB name=\"TTWR SQLite\"><PlayerDB name=\"Players.sqlite\"/><RegionList name=\"Regions.xml\"/><TemplDB name=\"Templs.xml\"/></DB>\n").getBytes("UTF-8"));
        Files.write(path.resolve("Regions.xml"),"<?xml version=\"1.0\" encoding=\"UTF-8\"?><Regions/>\n".getBytes("UTF-8"));
        Files.write(path.resolve("Templs.xml"),"<?xml version=\"1.0\" encoding=\"UTF-8\"?><Templates/>\n".getBytes("UTF-8"));
        System.out.println("MINIMAL_CATALOG_OK players=0 integrity=ok");
    }
}
