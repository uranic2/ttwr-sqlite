package ttr.updates;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Full helper process, lock handoff and native restart against a disposable installation. */
public final class UpdateHelperSmoke {
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        Path zip=ProgramUpdateChecks.fixture(root),home=root.resolve("программа с пробелами 日本語");ProgramUpdateChecks.initHome(home);
        Path stage=ProgramUpdateChecks.stage(home.resolve("data/updates"),zip),image=stage.resolve("image");
        String original=ReleaseClient.sha256(image.resolve("ttwr-sqlite.exe"));
        Files.copy(Paths.get(args[1]),image.resolve("ttwr-sqlite.exe"),StandardCopyOption.REPLACE_EXISTING);
        ProgramUpdateChecks.write(image.resolve("checksums.json"),ProgramUpdateChecks.contents(image.resolve("checksums.json")).replace(original,ReleaseClient.sha256(image.resolve("ttwr-sqlite.exe"))));
        Files.copy(Paths.get(UpdateInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI()),stage.resolve("updater.dat"));
        String java=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),cp=System.getProperty("java.class.path");
        Process app=new ProcessBuilder(java,"-cp",cp,ProgramUpdateChecks.class.getName(),"hold-encoded",UpdateInstaller.encodePath(home)).redirectErrorStream(true).redirectOutput(root.resolve("application.log").toFile()).start();
        Process helper=null;
        try {
            long deadline=System.currentTimeMillis()+30000;while(!Files.exists(home.resolve("data/ready"))&&app.isAlive()&&System.currentTimeMillis()<deadline)Thread.sleep(50);
            ProgramUpdateChecks.check(Files.exists(home.resolve("data/ready")),"Application did not acquire lock; child alive="+app.isAlive()+"; log="+ProgramUpdateChecks.contents(root.resolve("application.log")));
            helper=UpdateInstaller.helperProcess(home,stage,"1.2.0").redirectErrorStream(true).redirectOutput(root.resolve("helper.log").toFile()).start();
            Thread.sleep(500);ProgramUpdateChecks.check(ProgramUpdateChecks.contents(home.resolve("ttwr-sqlite.exe")).equals("old exe"),"Helper did not wait for application");
            ProgramUpdateChecks.write(home.resolve("data/release"),"exit");ProgramUpdateChecks.check(app.waitFor(10,TimeUnit.SECONDS),"Application did not exit");
            ProgramUpdateChecks.check(helper.waitFor(15,TimeUnit.SECONDS)&&helper.exitValue()==0,"Helper failed: "+ProgramUpdateChecks.contents(root.resolve("helper.log")));
            deadline=System.currentTimeMillis()+5000;boolean restarted=false;
            while(System.currentTimeMillis()<deadline){
                try{restarted=Files.exists(home.resolve("restarted.txt"))&&ProgramUpdateChecks.contents(home.resolve("restarted.txt")).equals("restart-ok");}catch(java.io.IOException writing){/* The probe may still be writing its marker. */}
                if(restarted)break;Thread.sleep(50);
            }
            ProgramUpdateChecks.check(restarted,"Native restart marker was not completed");
            ProgramUpdateChecks.check(ProgramUpdateChecks.contents(stage.resolve("status.txt")).contains("installed 1.2.0"),"Installation status missing");
            ProgramUpdateChecks.check(ProgramUpdateChecks.contents(home.resolve("data/DB/Players.sqlite")).equals("USER DATABASE"),"Data changed");
            ProgramUpdateChecks.check(ProgramUpdateChecks.contents(home.resolve("runtime/bin/java.exe")).equals("PRESERVE RUNTIME"),"Runtime changed");
            System.out.println("UPDATE_HELPER_OK: real processes, lock wait, installation, backup, native restart, Unicode paths, data/runtime retained");
        } finally {app.destroy();if(helper!=null)helper.destroy();}
    }
}
