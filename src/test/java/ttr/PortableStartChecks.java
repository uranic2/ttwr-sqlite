package ttr;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class PortableStartChecks {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static byte[] hash(Path path)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));}
    public static void main(String[] args)throws Exception{
        Path base=PortableStart.profile();check(base.toString().contains("portable-check"),"Use isolated portable-check ttwr.home");
        check(base.equals(Paths.get(System.getProperty("ttwr.home")).toAbsolutePath().normalize().resolve("data")),"Profile is not beside launcher");
        PortableStart.prepareProfile();Path file=base.resolve("DB/Players.sqlite"),config=base.resolve("ttwr.cfg"),header=base.resolve("DB/db.xml");
        DBMain main=new DBMain();check(main.LoadXML(header.toString(),null),"Fresh header cannot open");
        check(main.players.players.size()==0&&main.date==null,"Seed is populated or dated");main.players.store().close();
        check(Files.isDirectory(base.resolve("Tours"))&&Files.isDirectory(base.resolve("Backup")),"Work folders missing");
        byte[] configHash=hash(config),dbHash=hash(file);PortableStart.prepareProfile();
        check(Arrays.equals(configHash,hash(config))&&Arrays.equals(dbHash,hash(file)),"Second start replaced profile");
        Settings moved=new Settings();moved.DBPath="C:\\old-computer\\data\\DB\\db.xml";moved.ToursPath="C:\\old-computer\\data\\Tours";moved.BackupPath="C:\\old-computer\\data\\Backup";moved.ToursPathTL=moved.ToursPath;moved.ArchivePathTL="C:\\old-computer\\data\\Archive";moved.tours.add(moved.ToursPath+"\\sub\\test.xml");
        check(moved.Save(config.toString()),"Moved fixture settings save failed");
        Settings loaded=new Settings();check(loaded.Load(config.toString()),"Settings.Load failed");
        check(loaded.DBPath.equals(header.toString())&&loaded.ToursPath.equals(base.resolve("Tours").toString())&&loaded.BackupPath.equals(base.resolve("Backup").toString()),"Settings.Load kept old absolute paths");
        check(loaded.tours.get(0).equals(base.resolve("Tours/sub/test.xml").toString())&&loaded.ArchivePathTL.equals(base.resolve("Archive").toString()),"Moved tournament or archive path escaped profile");
        java.lang.reflect.Field field=TTRClient.class.getDeclaredField("appDir");field.setAccessible(true);
        check(Paths.get((String)field.get(null)).normalize().equals(base),"TTRClient static profile still uses APPDATA");
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field unsafeField=unsafeClass.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        TTRClient statsClient=(TTRClient)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafeField.get(null),TTRClient.class);
        DBMain statsMain=new DBMain();RatingList statsList=new RatingList(new GregorianCalendar(2026,0,2));Player statsPlayer=new Player();statsPlayer.profile.ID=1;statsPlayer.Rating=100;statsList.players.put(1,statsPlayer);statsMain.tours.RLists.add(statsList);
        java.lang.reflect.Field mainField=TTRClient.class.getDeclaredField("mainDB");mainField.setAccessible(true);mainField.set(statsClient,statsMain);statsClient.RecalcRatingStats();
        check(Files.isRegularFile(base.resolve("rating.csv"))&&Files.isRegularFile(base.resolve("stats").resolve(String.format("%1$td.%1$tm.%1$tY.csv",statsList.date))),"Legacy rating/statistics export escaped portable profile");
        try(SqlitePlayersStore store=new SqlitePlayersStore(file)){Player p=new Player();p.profile.ID=1879048210;p.profile.Name="Существующие данные";store.upsert(p,"current");}
        Files.delete(config);PortableStart.prepareProfile();
        try(SqlitePlayersStore store=new SqlitePlayersStore(file)){check(store.size()==1&&store.contains(1879048210),"Existing DB replaced while restoring settings");}
        dbHash=hash(file);Files.delete(config);Files.delete(header);
        try{PortableStart.prepareProfile();throw new AssertionError("Incomplete profile silently replaced");}catch(IllegalStateException expected){}
        check(Arrays.equals(dbHash,hash(file))&&!Files.exists(config),"Incomplete DB was modified");
        String originalHome=System.getProperty("ttwr.home");Path blocked=base.getParent().resolve("blocked-home");Files.write(blocked,new byte[]{1});
        try{System.setProperty("ttwr.home",blocked.toString());try{PortableStart.prepareProfile();throw new AssertionError("Unavailable local profile silently fell back");}catch(java.io.IOException expected){}}finally{System.setProperty("ttwr.home",originalHome);}
        System.out.println("PORTABLE_START_OK: adjacent data folder, original Settings.Load rebased moved paths, TTRClient static paths and CSV exports isolated, empty seed, first-run profile, repeat start, existing data retained, incomplete profile protected, no fallback on unavailable local directory");
    }
}
