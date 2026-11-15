package ttr;
import ttr.*;
import java.nio.file.*;
import java.awt.*;
public final class PrepareGuiProfile{
    public static void main(String[] args)throws Exception{
        Path base=Paths.get(args[0]).resolve("TTWR");Files.createDirectories(base.resolve("Tours"));Files.createDirectories(base.resolve("Backup"));
        Settings settings=new Settings();settings.DBPath=base.resolve("DB/db.xml").toString();settings.ToursPath=base.resolve("Tours").toString();settings.BackupPath=base.resolve("Backup").toString();
        settings.bUpdateAtStart=false;settings.bDisplay2=false;settings.bSavePass=false;settings.bHideTplWarn=true;settings.windowPos=new Point(150,120);settings.windowSize=new Dimension(1250,760);settings.Xms="64m";settings.Xmx="256m";
        if(!settings.Save(base.resolve("ttwr.cfg").toString()))throw new AssertionError("Settings save failed");
        System.out.println("ISOLATED_GUI_PROFILE_READY");
    }
}
