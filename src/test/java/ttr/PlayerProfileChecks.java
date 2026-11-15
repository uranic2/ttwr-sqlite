package ttr;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated synthetic profiles: source immutability, all scopes, cancellation and restart recovery. */
public final class PlayerProfileChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void text(Path path,String value)throws Exception{Files.createDirectories(path.getParent());Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    static Path source(Path root)throws Exception{
        Path db=root.resolve("Старый TTWR с пробелами/DB");Files.createDirectories(db);
        text(db.resolve("db.xml"),"<DB name='Локальный профиль' date='15.11.2026-10:20:30'><PlayerDB name='Список.xml'/><TemplDB name='Шаблоны.xml'/><RegionList name='Регионы.xml'/></DB>");
        StringBuilder xml=new StringBuilder("<Players>");
        for(int i=1;i<=300;i++)xml.append("<Player id='").append(Integer.toHexString(i)).append("'><name><![CDATA[Игрок 日本語 ").append(i).append("]]></name><rating>-0.00</rating><deltaR>-0.00</deltaR><country>Россия</country><region>Юг</region><location>Таганрог</location></Player>");
        xml.append("<Player id='1'><name>Повторный ID</name></Player></Players>");text(db.resolve("Список.xml"),xml.toString());
        text(db.resolve("Шаблоны.xml"),"<?xml version='1.0'?><Templates><!-- keep original XML --></Templates>");
        text(db.resolve("Регионы.xml"),"<Regions><region><![CDATA[Ростовская область]]></region></Regions>");
        return db;
    }
    static Map<String,String> hashes(Path directory)throws Exception{
        Map<String,String> hashes=new TreeMap<String,String>();
        try(java.util.stream.Stream<Path> files=Files.walk(directory)){Iterator<Path> i=files.iterator();while(i.hasNext()){Path p=i.next();if(Files.isRegularFile(p)&&!p.toString().endsWith("-wal")&&!p.toString().endsWith("-shm"))hashes.put(directory.relativize(p).toString(),DbPackageTransaction.hash(p));}}return hashes;
    }
    static DBMain baseline(Path profile)throws Exception{
        Path db=profile.resolve("DB");Files.createDirectories(db);
        try(SqlitePlayersStore store=new SqlitePlayersStore(db.resolve("Players.sqlite"))){
            Player p=new Player();p.profile.ID=900;p.profile.Name="Прежний игрок";store.upsert(p,"current");
            Player history=new Player(p);history.Rating=123.45;store.upsert(history,"rating:history");
        }
        text(db.resolve("db.xml"),"<DB name='Прежняя база'><PlayerDB name='Players.sqlite'/><TemplDB name='Templs.xml'/><RegionList name='Regions.xml'/></DB>");
        text(db.resolve("Templs.xml"),"<Templates/>");text(db.resolve("Regions.xml"),"<Regions><region>Старый регион</region></Regions>");
        text(db.resolve("extra.txt"),"existing extra file");text(profile.resolve("ttwr.cfg"),"settings unchanged");text(profile.resolve("Tours/турнир.xml"),"tournament unchanged");text(profile.resolve("Backup/previous/keep.txt"),"previous backup");
        DBMain main=new DBMain();check(main.LoadXML(db.resolve("db.xml").toString(),null),"Baseline load");return main;
    }
    static void noStages(Path profile)throws Exception{try(java.util.stream.Stream<Path> list=Files.list(profile)){check(list.noneMatch(p->p.getFileName().toString().startsWith(".players-operation-")),"Stage leaked");}}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(root);Path source=source(root);
        Map<String,String> beforeSource=hashes(source);Path profile=PortablePaths.profile();DBMain original=baseline(profile);
        List<List<Object>> old=SinglePassImportChecks.rows(original.players.store());
        String settings=DbPackageTransaction.hash(profile.resolve("ttwr.cfg")),tour=DbPackageTransaction.hash(profile.resolve("Tours/турнир.xml"));
        try(PlayersUpdateSession session=new PlayersUpdateSession(original)){
            session.prepare(null,source);
            check(session.progress.records()==301,"Record count must include duplicate ID, not templates");
            check(session.candidate.players.players.size()==300,"Unique player count");
            check(Double.doubleToRawLongBits(session.candidate.players.GetPlayer(2).Rating)==Long.MIN_VALUE,"Negative zero");
            check(session.candidate.players.GetPlayer(2).profile.Name.contains("日本語"),"Unicode/CDATA");
            session.apply();
            check(session.backup!=null,"Backup not reported");
            try(SqlitePlayersStore backup=new SqlitePlayersStore(session.backup.resolve("Players.sqlite"))){check(old.equals(SinglePassImportChecks.rows(backup)),"Backup lost current/history rows");}
            check(Files.exists(session.backup.resolve("extra.txt")),"Complete DB backup missing");
            check(Arrays.equals(Files.readAllBytes(source.resolve("Шаблоны.xml")),Files.readAllBytes(profile.resolve("DB/Templs.xml"))),"Template XML not copied exactly");
            check(Arrays.equals(Files.readAllBytes(source.resolve("Регионы.xml")),Files.readAllBytes(profile.resolve("DB/Regions.xml"))),"Regions not copied exactly");
            check(session.candidate.date!=null&&session.candidate.toString().contains("Локальный профиль"),"Header metadata lost");
            original=session.candidate;
        }
        check(beforeSource.equals(hashes(source)),"Import wrote to original profile");
        check(settings.equals(DbPackageTransaction.hash(profile.resolve("ttwr.cfg")))&&tour.equals(DbPackageTransaction.hash(profile.resolve("Tours/турнир.xml"))),"Settings/tournament changed");
        check(new String(Files.readAllBytes(profile.resolve("DB/db.xml")),StandardCharsets.UTF_8).contains("Players.sqlite"),"Header points to XML");
        for(final String phase:new String[]{PlayersProgress.READ,PlayersProgress.INDEX,PlayersProgress.CHECK,"before-apply"}){
            List<List<Object>> before=SinglePassImportChecks.rows(original.players.store());
            final PlayersUpdateSession[] holder=new PlayersUpdateSession[1];
            PlayersProgress progress=new PlayersProgress(){
                public void OnSectionStarted(String s){super.OnSectionStarted(s);if(s.equals(phase))holder[0].control.cancel();}
            };
            try(PlayersUpdateSession session=new PlayersUpdateSession(original,progress)){
                holder[0]=session;
                try{session.prepare(null,source);if(phase.equals("before-apply"))session.control.cancel();session.apply();throw new AssertionError("Cancellation ignored "+phase);}
                catch(java.util.concurrent.CancellationException expected){}
                catch(IOException expected){check(session.control.isCancelled(),"Unexpected failure "+expected);}
            }
            check(before.equals(SinglePassImportChecks.rows(original.players.store())),"Cancellation changed old DB "+phase);noStages(profile);
        }
        String header=new String(Files.readAllBytes(source.resolve("db.xml")),StandardCharsets.UTF_8);
        for(String bad:new String[]{"../outside.xml","https://evil.test/a.xml","C:\\outside.xml","/absolute.xml"}){
            text(source.resolve("db.xml"),header.replace("Список.xml",bad));
            try{new PlayerProfileSource(source,profile.resolve("DB"));throw new AssertionError("Unsafe link accepted "+bad);}catch(IOException expected){}
        }
        text(source.resolve("db.xml"),header);
        try{new PlayerProfileSource(source,source);throw new AssertionError("Overlap accepted");}catch(IOException expected){}
        PlayerProfileSource changed=new PlayerProfileSource(source,profile.resolve("DB"));text(source.resolve("Регионы.xml"),"<Regions/>");
        try{changed.verify();throw new AssertionError("Changed source accepted");}catch(IOException expected){}
        text(source.resolve("Регионы.xml"),"<Regions><region><![CDATA[Ростовская область]]></region></Regions>");
        for(String file:new String[]{"db.xml","Список.xml","Шаблоны.xml","Регионы.xml"}){
            Path input=source.resolve(file),saved=source.resolve(file+".saved");Files.move(input,saved);
            try(PlayersUpdateSession session=new PlayersUpdateSession(original)){
                try{session.prepare(null,source);throw new AssertionError("Missing file accepted "+file);}catch(IOException expected){}
            }finally{Files.move(saved,input);}noStages(profile);
        }
        String players=new String(Files.readAllBytes(source.resolve("Список.xml")),StandardCharsets.UTF_8);
        text(source.resolve("Список.xml"),players.substring(0,players.length()-5));
        List<List<Object>> stable=SinglePassImportChecks.rows(original.players.store());
        try(PlayersUpdateSession session=new PlayersUpdateSession(original)){
            try{session.prepare(null,source);throw new AssertionError("Malformed tail accepted");}catch(IOException expected){}
        }
        check(stable.equals(SinglePassImportChecks.rows(original.players.store())),"Bad XML changed old catalog");
        text(source.resolve("Список.xml"),players);
        // A Windows sharing violation on the second file exercises rollback after SQLite changed.
        java.nio.file.OpenOption denyDelete=(java.nio.file.OpenOption)Class.forName("com.sun.nio.file.ExtendedOpenOption").getField("NOSHARE_DELETE").get(null);
        List<List<Object>> beforeFailure=SinglePassImportChecks.rows(original.players.store());
        try(PlayersUpdateSession session=new PlayersUpdateSession(original)){
            session.prepare(null,source);
            try(java.nio.channels.FileChannel locked=java.nio.channels.FileChannel.open(profile.resolve("DB/Templs.xml"),StandardOpenOption.READ,denyDelete)){
                try{session.apply();throw new AssertionError("Locked destination accepted");}catch(IOException expected){check(expected.getMessage().contains("восстановлен"),"Rollback did not confirm restoration: "+expected);}
            }
        }
        check(beforeFailure.equals(SinglePassImportChecks.rows(original.players.store())),"Replacement failure changed all-scope contents");
        check(!Files.exists(profile.resolve("db-package.pending")),"Resolved failure retained recovery journal");
        // Junction links must not allow reads outside the selected source DB.
        Path external=root.resolve("outside");Files.createDirectories(external);text(external.resolve("players.xml"),"<Players/>");
        Path link=source.resolve("linked");
        boolean linked=false;
        try{Files.createSymbolicLink(link,external.toAbsolutePath());linked=true;}catch(Exception unsupported){
            linked=new ProcessBuilder(System.getenv("SystemRoot")+"\\System32\\cmd.exe","/c","mklink","/J",link.toString(),external.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(root.resolve("junction-probe.log").toFile()).start().waitFor()==0;
        }
        check(linked,"Cannot test source junction");
        text(source.resolve("db.xml"),header.replace("Список.xml","linked/players.xml"));
        try{new PlayerProfileSource(source,profile.resolve("DB"));throw new AssertionError("External junction accepted");}catch(IOException expected){}
        text(source.resolve("db.xml"),header);Files.delete(link);
        // Pending journal survives an interrupted process. A later startup restores the whole package.
        Path stage=Files.createTempDirectory(profile,"recovery-fixture-");
        for(String file:DbPackageTransaction.FILES)Files.copy(profile.resolve("DB").resolve(file),stage.resolve(file));
        text(stage.resolve("Regions.xml"),"<Regions><region>New partial package</region></Regions>");
        Map<String,String> beforeCrash=hashes(profile.resolve("DB"));
        DbPackageTransaction transaction=new DbPackageTransaction(profile,stage);transaction.snapshot(new PlayersUpdateControl());transaction.install(stage,original);
        check(Files.exists(profile.resolve("db-package.pending")),"No durable recovery journal");
        DbPackageTransaction.recover(profile);
        check(beforeCrash.equals(hashes(profile.resolve("DB"))),"Restart recovery mixed old/new files");
        original.players.useWithoutClosing(new SqlitePlayersStore(profile.resolve("DB/Players.sqlite")));
        // SQLite's own progress callback interrupts a SQL statement, even with no SAX callbacks.
        PlayersUpdateControl control=new PlayersUpdateControl();control.watch(original.players.store());
        final SqlitePlayersStore sqlStore=original.players.store();CountDownLatch entered=new CountDownLatch(1);
        FutureTask<Boolean> sql=new FutureTask<Boolean>(()->{entered.countDown();try(Statement statement=sqlStore.connection.createStatement()){statement.executeQuery("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000000) SELECT sum(x) FROM n");return false;}catch(SQLException expected){return true;}});
        new Thread(sql,"cancel-sql-test").start();entered.await();control.cancel();check(sql.get(5,TimeUnit.SECONDS),"Long SQL ignored cancellation");control.unwatch(sqlStore);
        check(ProfileImports.shouldOffer(true,source)&&!ProfileImports.shouldOffer(false,source),"First-run offer predicate");
        check(!ProfileImports.firstRun(profile),"Existing profile counted as first run");
        original.players.store().close();noStages(profile);
        System.out.println("PLAYER_PROFILE_OK: Unicode, custom names, 301/300 records, source unchanged, all-scope backup, cancellation, malformed/missing/unsafe files, SQL interrupt, durable recovery, settings/tournaments retained");
    }
}
