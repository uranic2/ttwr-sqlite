package ttr;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** A bad rollback copy must block startup before existing settings can bypass recovery. */
public final class DbPackageRecoveryFailureChecks {
    static void check(boolean value,String message){PlayerProfileChecks.check(value,message);}
    public static void main(String[] args)throws Exception{
        String previous=System.getProperty("ttwr.home");
        Path home=Paths.get(args[0]).toAbsolutePath(),profile=home.resolve("data");
        System.setProperty("ttwr.home",home.toString());
        try{
            DBMain original=PlayerProfileChecks.baseline(profile);
            List<List<Object>> rows=SinglePassImportChecks.rows(original.players.store());
            Path stage=Files.createTempDirectory(profile,"recovery-failure-");
            for(String name:DbPackageTransaction.FILES)Files.copy(profile.resolve("DB").resolve(name),stage.resolve(name));
            PlayerProfileChecks.text(stage.resolve("Regions.xml"),"<Regions><region>New package</region></Regions>");
            DbPackageTransaction transaction=new DbPackageTransaction(profile,stage);
            transaction.snapshot(new PlayersUpdateControl());transaction.install(stage,original);
            Path damaged=transaction.backup().resolve("Templs.xml");
            byte[] saved=Files.readAllBytes(damaged);Files.write(damaged,new byte[]{1});
            Map<String,String> mixed=PlayerProfileChecks.hashes(profile.resolve("DB"));
            String settings=DbPackageTransaction.hash(profile.resolve("ttwr.cfg"));
            try{PortableStart.prepareProfile();throw new AssertionError("Startup accepted damaged rollback copy");}
            catch(IOException expected){check(expected.getMessage().contains("копия"),"Unclear recovery failure: "+expected);}
            check(Files.exists(profile.resolve("db-package.pending")),"Failed recovery removed journal");
            check(mixed.equals(PlayerProfileChecks.hashes(profile.resolve("DB"))),"Failed verification partially restored files");
            check(settings.equals(DbPackageTransaction.hash(profile.resolve("ttwr.cfg"))),"Startup overwrote settings");
            try(final PlayersUpdateSession session=new PlayersUpdateSession(null)){
                check(session.recoveryRequired(),"Session did not require recovery");
                if(args.length>1&&args[1].equals("gui")){
                    SwingUtilities.invokeAndWait(()->{
                        try{
                            ttr.UI.DBUpdateDlg dialog=new ttr.UI.DBUpdateDlg(null,true);
                            java.lang.reflect.Field field=ttr.UI.DBUpdateDlg.class.getDeclaredField("session");field.setAccessible(true);field.set(dialog,session);
                            java.lang.reflect.Method failed=ttr.UI.DBUpdateDlg.class.getDeclaredMethod("failed",Exception.class);failed.setAccessible(true);failed.invoke(dialog,new IOException("Recovery failed"));
                            check(!PlayerUpdateUiChecks.named(dialog,"players-update").isEnabled(),"Retry enabled with mixed files");
                            check(((JButton)PlayerUpdateUiChecks.named(dialog,"players-cancel")).getText().equals("Закрыть программу"),"Recovery exit action missing");
                            check(((JLabel)PlayerUpdateUiChecks.named(dialog,"players-phase")).getText().contains("восстановление"),"Recovery state not visible");
                            dialog.dispose();
                        }catch(Exception e){throw new IllegalStateException(e);}
                    });
                }
            }
            Files.write(damaged,saved);PortableStart.prepareProfile();
            check(!Files.exists(profile.resolve("db-package.pending")),"Successful retry retained journal");
            try(SqlitePlayersStore restored=new SqlitePlayersStore(profile.resolve("DB/Players.sqlite"))){check(rows.equals(SinglePassImportChecks.rows(restored)),"Recovery lost player scopes");}
            check(settings.equals(DbPackageTransaction.hash(profile.resolve("ttwr.cfg"))),"Recovery changed settings");
            System.out.println("DB_PACKAGE_RECOVERY_FAILURE_OK: corrupted backup blocks profile preparation, journal retained, no partial restore, repair/retry succeeds"+(args.length>1?"; GUI retry disabled":""));
        }finally{if(previous==null)System.clearProperty("ttwr.home");else System.setProperty("ttwr.home",previous);}
        if(args.length>1)System.exit(0);
    }
}
