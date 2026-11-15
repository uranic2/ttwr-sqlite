package ttr;

import java.awt.*;
import java.io.*;
import java.nio.file.*;
import javax.swing.*;

/** First-run setup for the self-contained Windows distribution. Never replaces user data. */
public final class PortableStart {
    private PortableStart(){}
    static Path profile(){return PortablePaths.profile();}
    public static void prepareProfile()throws Exception{
        Path base=profile(),config=base.resolve("ttwr.cfg");
        DbPackageTransaction.recover(base);
        Files.createDirectories(base);Files.createDirectories(base.resolve("Tours"));Files.createDirectories(base.resolve("Backup"));Files.createDirectories(base.resolve("stats"));
        if(Files.exists(config))return;
        Files.createDirectories(base);Path db=base.resolve("DB");Files.createDirectories(db);
        if(!Files.exists(db.resolve("db.xml"))){
            String location=System.getProperty("ttwr.seed");
            if(location==null)throw new IllegalStateException("Не задан путь к стартовой базе");
            Path seed=Paths.get(location);String[] files={"Players.sqlite","Regions.xml","Templs.xml","db.xml"};
            for(String name:files){if(Files.exists(db.resolve(name)))throw new IllegalStateException("Неполная существующая база: "+db+". Файлы не заменены.");if(!Files.isRegularFile(seed.resolve(name)))throw new FileNotFoundException(seed.resolve(name).toString());}
            // Header goes last; no REPLACE_EXISTING: existing data is never overwritten.
            for(String name:files)Files.copy(seed.resolve(name),db.resolve(name));
        }
        Path tours=base.resolve("Tours"),backup=base.resolve("Backup");Files.createDirectories(tours);Files.createDirectories(backup);
        Settings settings=new Settings();settings.DBPath=db.resolve("db.xml").toString();settings.ToursPath=tours.toString();settings.BackupPath=backup.toString();
        settings.bUpdateAtStart=false;settings.bDisplay2=false;settings.bSavePass=false;settings.bHideTplWarn=true;
        settings.windowPos=new Point(100,100);settings.windowSize=new Dimension(1250,760);settings.Xms="64m";settings.Xmx="32".equals(System.getProperty("sun.arch.data.model"))?"512m":"1024m";
        if(!settings.Save(config.toString()))throw new IOException("Не удалось сохранить настройки: "+config);
    }
    public static void main(String[] args){
        try{
            boolean first=ProfileImports.firstRun(profile());
            ttr.updates.AppInstance.acquire(PortablePaths.home());
            try{ttr.updates.RetiredFiles.cleanup(PortablePaths.home());}catch(IOException error){System.err.println("Retired document retained: "+error);}
            DbPackageTransaction.recover(profile());
            ProfileImports.offerFirstRun(PlayerProfileSource.defaultFolder(),first);
            prepareProfile();TTRClient.main(args);
        }catch(Throwable error){
            Path log=null;
            try{log=profile().resolve("ttwr-sqlite-startup.log");Files.createDirectories(log.getParent());try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(log))){error.printStackTrace(writer);}}catch(Exception ignored){}
            JOptionPane.showMessageDialog(null,"Не удалось запустить TTWR SQLite.\n"+error+ (log==null?"":"\nЖурнал: "+log),"TTWR SQLite",JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
