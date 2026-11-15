package ttr;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real JVM termination with an installed first file and pending multi-file transaction. */
public final class DbPackageCrashChecks {
    public static void main(String[] args)throws Exception{
        if(args.length>1&&args[0].equals("child")){
            Path stage=Paths.get(args[1]);DBMain original=new DBMain();
            PlayerProfileChecks.check(original.LoadXML(PortablePaths.profile().resolve("DB/db.xml").toString(),null),"Child catalog open");
            DbPackageTransaction transaction=new DbPackageTransaction(PortablePaths.profile(),stage);
            transaction.snapshot(new PlayersUpdateControl());
            try{transaction.install(stage,original);throw new AssertionError("Expected locked template destination");}
            catch(IOException expected){
                Files.write(PortablePaths.home().resolve("crash-ready"),new byte[]{1});
                new CountDownLatch(1).await(); // Parent terminates this JVM before rollback.
            }
            return;
        }
        if(args.length>0&&args[0].equals("recover")){
            DbPackageTransaction.recover(PortablePaths.profile());
            DBMain restored=new DBMain();PlayerProfileChecks.check(restored.LoadXML(PortablePaths.profile().resolve("DB/db.xml").toString(),null),"Restored header open");
            restored.players.store().close();System.out.println("DB_PACKAGE_RECOVERY_CHILD_OK");return;
        }
        Path home=Paths.get(args[0]).toAbsolutePath(),profile=home.resolve("data");
        Files.createDirectories(home);DBMain old=PlayerProfileChecks.baseline(profile);
        List<List<Object>> rows=SinglePassImportChecks.rows(old.players.store());old.players.store().close();
        Map<String,String> before=PlayerProfileChecks.hashes(profile.resolve("DB"));
        String settings=DbPackageTransaction.hash(profile.resolve("ttwr.cfg")),tours=DbPackageTransaction.hash(profile.resolve("Tours/турнир.xml"));
        Path stage=Files.createTempDirectory(profile,"crash-stage-");
        for(String name:DbPackageTransaction.FILES)Files.copy(profile.resolve("DB").resolve(name),stage.resolve(name));
        try(SqlitePlayersStore store=new SqlitePlayersStore(stage.resolve("Players.sqlite"))){Player p=new Player();p.profile.ID=901;p.profile.Name="Partly installed";store.upsert(p,"current");}
        String java=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),cp=System.getProperty("java.class.path");
        java.nio.file.OpenOption denyDelete=(java.nio.file.OpenOption)Class.forName("com.sun.nio.file.ExtendedOpenOption").getField("NOSHARE_DELETE").get(null);
        Process child=null;
        try(FileChannel lock=FileChannel.open(profile.resolve("DB/Templs.xml"),StandardOpenOption.READ,denyDelete)){
            child=new ProcessBuilder(java,"-Dttwr.home="+home,"-cp",cp,DbPackageCrashChecks.class.getName(),"child",stage.toString()).redirectErrorStream(true).redirectOutput(home.resolve("crash-child.log").toFile()).start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!Files.exists(home.resolve("crash-ready"))&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(10);
            PlayerProfileChecks.check(Files.exists(home.resolve("crash-ready")),"Child never reached partial installation: "+new String(Files.readAllBytes(home.resolve("crash-child.log")),"UTF-8"));
            PlayerProfileChecks.check(!before.get("Players.sqlite").equals(DbPackageTransaction.hash(profile.resolve("DB/Players.sqlite"))),"First file was not installed");
            PlayerProfileChecks.check(before.get("Templs.xml").equals(DbPackageTransaction.hash(profile.resolve("DB/Templs.xml"))),"Second file unexpectedly replaced");
            child.destroyForcibly();PlayerProfileChecks.check(child.waitFor(5,TimeUnit.SECONDS),"Child still running");
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor();}}
        Properties pending=new Properties();try(InputStream in=Files.newInputStream(profile.resolve("db-package.pending"))){pending.load(in);}
        // Also model a partially copied private write file, whose name belongs to this journal.
        Path partial=profile.resolve(pending.getProperty("write"));Files.write(partial,new byte[]{1,2,3});
        Process recovered=new ProcessBuilder(java,"-Dttwr.home="+home,"-cp",cp,DbPackageCrashChecks.class.getName(),"recover").redirectErrorStream(true).redirectOutput(home.resolve("recovery-child.log").toFile()).start();
        if(!recovered.waitFor(15,TimeUnit.SECONDS)){recovered.destroyForcibly();throw new AssertionError("Recovery child hung");}
        PlayerProfileChecks.check(recovered.exitValue()==0,"Recovery process failed: "+new String(Files.readAllBytes(home.resolve("recovery-child.log")),"UTF-8"));
        Map<String,String> restoredFiles=PlayerProfileChecks.hashes(profile.resolve("DB"));
        before.remove("Players.sqlite");restoredFiles.remove("Players.sqlite");
        PlayerProfileChecks.check(before.equals(restoredFiles),"Restart left mixed metadata/template/region files");
        try(SqlitePlayersStore store=new SqlitePlayersStore(profile.resolve("DB/Players.sqlite"))){PlayerProfileChecks.check(rows.equals(SinglePassImportChecks.rows(store)),"Restart lost current/history rows");}
        PlayerProfileChecks.check(settings.equals(DbPackageTransaction.hash(profile.resolve("ttwr.cfg")))&&tours.equals(DbPackageTransaction.hash(profile.resolve("Tours/турнир.xml"))),"Restart changed settings/tournaments");
        PlayerProfileChecks.check(!Files.exists(partial)&&!Files.exists(profile.resolve("db-package.pending")),"Recovery state not cleared");
        System.out.println("DB_PACKAGE_CRASH_OK: child forcibly stopped after first replacement; next JVM restored all files/scopes, removed modeled partial write, retained settings/tournaments");
    }
}
