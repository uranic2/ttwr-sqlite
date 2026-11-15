package ttr;

import java.nio.file.*;
import java.util.*;

/** All automatic application paths belong to the directory beside the SQLite launcher. */
public final class PortablePaths {
    private PortablePaths(){}
    public static Path home(){
        String configured=System.getProperty("ttwr.home");
        if(configured!=null&&!configured.trim().isEmpty())return Paths.get(configured).toAbsolutePath().normalize();
        try{
            Path archive=Paths.get(PortablePaths.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if(Files.isRegularFile(archive)&&archive.getParent()!=null&&archive.getParent().getFileName().toString().equalsIgnoreCase("app"))return archive.getParent().getParent();
        }catch(Exception e){throw new IllegalStateException("Cannot locate SQLite application directory",e);}
        throw new IllegalStateException("Set -Dttwr.home to the directory containing ttwr-sqlite.exe");
    }
    public static Path profile(){return home().resolve("data");}
    public static String getenv(String name){return "APPDATA".equalsIgnoreCase(name)?home().toString():System.getenv(name);}
    private static String relocate(String value,String oldRoot,Path newRoot){
        if(value==null||value.isEmpty())return value;
        try{
            Path path=Paths.get(value).normalize(),target=newRoot.toAbsolutePath().normalize();
            if(path.isAbsolute()&&path.startsWith(target))return path.toString();
            Path relative=path;
            if(path.isAbsolute()){
                Path old=oldRoot==null||oldRoot.isEmpty()?null:Paths.get(oldRoot).normalize();
                relative=old!=null&&path.startsWith(old)?old.relativize(path):path.getFileName();
            }
            Path result=target.resolve(relative).normalize();
            return (result.startsWith(target)?result:target.resolve(path.getFileName())).toString();
        }catch(InvalidPathException error){throw new IllegalStateException("Invalid portable profile path",error);}
    }
    public static boolean afterLoad(boolean loaded,Settings settings){
        if(!loaded)return false;
        Path base=profile();String oldTours=settings.ToursPath;
        for(int i=0;i<settings.tours.size();i++)settings.tours.set(i,relocate(settings.tours.get(i),oldTours,base.resolve("Tours")));
        settings.DBPath=base.resolve("DB/db.xml").toString();settings.ToursPath=base.resolve("Tours").toString();settings.BackupPath=base.resolve("Backup").toString();
        settings.ToursPathTL=settings.ToursPath;
        if(settings.ArchivePathTL!=null&&!settings.ArchivePathTL.isEmpty())settings.ArchivePathTL=base.resolve("Archive").toString();
        return true;
    }
}
