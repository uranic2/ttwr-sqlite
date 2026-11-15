package ttr;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;
import javax.swing.WindowConstants;

public final class SqliteShutdownChecks {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static TTRClient windowStub()throws Exception{
        Class<?> type=Class.forName("sun.misc.Unsafe");Field field=type.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (TTRClient)type.getMethod("allocateInstance",Class.class).invoke(field.get(null),TTRClient.class);
    }
    static Player player(int id){Player p=new Player();p.profile.ID=id;p.profile.Name="Проверка выхода "+id;p.Rating=123.25;return p;}
    static boolean side(Path file,String suffix){return Files.exists(Paths.get(file+suffix));}
    static void child(String mode,Path root)throws Exception{
        Files.createDirectories(root);Path file=root.resolve("Players.sqlite");
        SqlitePlayersStore store=new SqlitePlayersStore(file);store.upsert(player(7100001),"current");
        check(side(file,"-wal")&&side(file,"-shm"),"Test did not create WAL/SHM");
        if(mode.equals("window")){
            TTRClient client=windowStub();client.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            SqliteLifecycle.onWindowClosing(client);check(store.size()==1,"Cancelled exit closed database");
            check(side(file,"-wal"),"Cancelled exit removed WAL");
            client.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            SqliteLifecycle.onWindowClosing(client);SqliteLifecycle.closeAll();
            check(store.connection.isClosed(),"Window exit did not close connection");
            check(!side(file,"-wal")&&!side(file,"-shm"),"Window exit left WAL/SHM");
        }else if(mode.equals("hook")){
            System.out.println("EXIT_WITH_OPEN_STORE");System.exit(0);
        }else if(mode.equals("rollback")){
            store.begin();store.upsert(player(7100002),"current");
            System.out.println("RETURN_WITH_UNCOMMITTED_TRANSACTION");
        }else if(mode.equals("external")){
            Connection other=DriverManager.getConnection("jdbc:sqlite:"+file);
            try(Statement s=other.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM players")){check(r.next()&&r.getInt(1)==1,"Other connection read failed");}
            SqliteLifecycle.closeAll();check(side(file,"-wal")&&side(file,"-shm"),"Other connection files were manually removed");
            other.close();check(!side(file,"-wal")&&!side(file,"-shm"),"Last external close did not clean WAL/SHM");
        }else throw new IllegalArgumentException(mode);
        System.out.println("CHILD_OK "+mode);
    }
    public static void main(String[] args)throws Exception{
        if(args[0].equals("child")){child(args[1],Paths.get(args[2]));return;}
        if(args[0].equals("seed-gui")){
            SqlitePlayersStore store=new SqlitePlayersStore(Paths.get(args[1]));
            check(store.size()==149194&&!store.contains(0x70000011),"Unexpected GUI fixture contents");
            store.upsert(player(0x70000011),"current");store.close();
            System.out.println("GUI_SEED_COMMITTED id=1879048209 rating=123.25");return;
        }
        if(args[0].equals("verify-gui")){
            Path file=Paths.get(args[1]);check(!side(file,"-wal")&&!side(file,"-shm"),"GUI left WAL/SHM");
            SqlitePlayersStore store=new SqlitePlayersStore(file);check(store.size()==149195&&store.get(0x70000011).Rating==123.25,"GUI committed fixture lost");
            check(store.integrity().equals("ok"),"GUI catalog integrity failed");store.close();
            System.out.println("GUI_SHUTDOWN_OK: nonempty WAL/SHM removed, 149195 players, committed data retained, integrity ok");return;
        }
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        for(String mode:new String[]{"window","hook","rollback","external"}){
            Path folder=root.resolve(mode),log=root.resolve(mode+".txt");
            Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),SqliteShutdownChecks.class.getName(),"child",mode,folder.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Child hung: "+mode);}
            check(process.exitValue()==0,"Child failed: "+mode+"; "+new String(Files.readAllBytes(log),"UTF-8"));
            Path file=folder.resolve("Players.sqlite");
            check(!side(file,"-wal")&&!side(file,"-shm"),"Files left after child exit: "+mode);
            Class.forName("org.sqlite.JDBC");
            try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+file);Statement s=connection.createStatement()){
                try(ResultSet r=s.executeQuery("PRAGMA integrity_check")){check(r.next()&&r.getString(1).equals("ok"),"Integrity failed: "+mode);}
                try(ResultSet r=s.executeQuery("SELECT count(*),min(rating) FROM players WHERE scope='current'")){check(r.next()&&r.getInt(1)==1&&r.getDouble(2)==123.25,"Committed data lost or unfinished transaction committed: "+mode);}
            }
            System.out.println("PASS "+mode+": WAL/SHM removed, committed data retained, integrity ok");
        }
        System.out.println("SQLITE_SHUTDOWN_OK");
    }
}
