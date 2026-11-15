package ttr;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Synthetic profile for an opt-in live update of a pristine released executable. */
public final class PrepareUpgradeProfile {
    public static final String[] FIELDS={"fontSize","bFormalView","bCoach","defineTablePlacesMethod","bUpdateAtStart","bDisplay2","bSavePass","bHideTplWarn","bTLConfirm","bTLRemoveFile","bSelModPlayers","bClearDB","mailReplyTo","mailSender","Xms","Xmx"};
    public static void main(String[] args)throws Exception {
        PortableStart.prepareProfile();
        Path home=PortablePaths.home(),data=home.resolve("data");
        Settings settings=new Settings();if(!settings.Load(data.resolve("ttwr.cfg").toString()))throw new AssertionError("Settings load failed");
        settings.fontSize=14;settings.bFormalView=true;settings.bCoach=true;settings.defineTablePlacesMethod=1;
        settings.bUpdateAtStart=false;settings.bDisplay2=false;settings.bSavePass=false;settings.bHideTplWarn=true;
        settings.bTLConfirm=false;settings.bTLRemoveFile=false;settings.bSelModPlayers=true;settings.bClearDB=false;
        settings.mailReplyTo="upgrade-check@example.invalid";settings.mailSender="Проверка обновления Ёлка";
        settings.windowPos=new Point(180,120);settings.windowSize=new Dimension(1180,740);settings.Xms="64m";settings.Xmx="768m";
        if(!settings.Save(data.resolve("ttwr.cfg").toString()))throw new AssertionError("Settings save failed");
        try(SqlitePlayersStore store=new SqlitePlayersStore(data.resolve("DB/Players.sqlite"))) {
            store.begin();
            for(int i=1;i<=300;i++){
                Player p=new Player();p.profile.ID=i;p.profile.Name=String.format("Тестовый игрок %04d",i);
                p.profile.Town="Тестовый город";p.profile.Region="Регион проверки";p.Rating=i+0.125;
                p.stats.tours=i%7;p.stats.wins=i%11;p.stats.loses=i%5;store.upsert(p,"current");
            }
            Player marker=new Player();marker.profile.ID=900001;marker.profile.Name="Ёлкин José — контроль обновления";
            marker.profile.ShortName="Ёлкин J.";marker.profile.Country="Тестовая страна";marker.profile.Region="Южный тестовый регион";marker.profile.Town="Таганрог — тест";
            marker.Rating=-0.0;marker.deltaR=12.75;marker.Place=17;marker.PlaceNew=16;marker.stats.tours=7;marker.stats.wins=23;marker.stats.loses=11;
            marker.stats2.tours=3;marker.stats2.wins=9;marker.stats2.loses=4;store.upsert(marker,"current");
            marker.Rating=456.875;store.upsert(marker,"history-upgrade-check");
            try(java.sql.Statement statement=store.connection.createStatement()){statement.executeUpdate("INSERT INTO metadata(key,value) VALUES('upgrade-check','preserve-metadata')");}
            store.finish(true);if(store.size()!=301||!store.integrity().equals("ok"))throw new AssertionError("Fixture invalid");
        }
        Files.write(data.resolve("Tours/preserve-marker.txt"),Arrays.asList("preserve tournament marker"),StandardCharsets.UTF_8);
        Files.write(data.resolve("Backup/preserve-marker.txt"),Arrays.asList("preserve backup marker"),StandardCharsets.UTF_8);
        // Skip the automatic prompt, then invoke the real manual check through its menu item.
        Files.write(data.resolve("program-update-check.txt"),Arrays.asList("manual live upgrade test"),StandardCharsets.UTF_8);
        Path report=Paths.get(args[0]);Files.createDirectories(report);
        Properties expected=new Properties();for(String field:FIELDS)expected.setProperty(field,String.valueOf(Settings.class.getField(field).get(settings)));
        try(java.io.Writer writer=Files.newBufferedWriter(report.resolve("expected-settings.properties"),StandardCharsets.UTF_8)){expected.store(writer,"Synthetic settings only; no credentials");}
        System.out.println("UPGRADE_PROFILE_READY current=301 history=1 settings="+FIELDS.length);
    }
}
