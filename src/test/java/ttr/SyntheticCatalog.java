package ttr;
import java.nio.file.*;
/** Public tests use generated records, never a real player database. */
public final class SyntheticCatalog {
    public static void main(String[] args)throws Exception{
        Path file=Paths.get(args[0]);Files.createDirectories(file.getParent());
        try(SqlitePlayersStore store=new SqlitePlayersStore(file)){
            store.begin();
            for(int i=1;i<=300;i++){Player p=new Player();p.profile.ID=i;p.profile.Name=String.format("Синтетический игрок %04d",i);p.profile.Region="Тестовый регион";p.profile.Town="Тестовый город";p.Rating=i;store.upsert(p,"current");}
            store.finish(true);if(store.size()!=300||!store.integrity().equals("ok"))throw new AssertionError("Synthetic catalog failed");
        }
        System.out.println("SYNTHETIC_CATALOG_OK players=300");
    }
}
