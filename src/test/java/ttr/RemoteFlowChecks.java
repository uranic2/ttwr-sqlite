package ttr;
import ttr.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;

public final class RemoteFlowChecks{
    static String payload="<Players><Player id='64'><name>Тест обновления</name><rating>-0.00</rating></Player><Player id='65'><name>Второй игрок</name></Player></Players>";
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void mainDB(TTRClient client,DBMain db)throws Exception{Field f=TTRClient.class.getDeclaredField("mainDB");f.setAccessible(true);f.set(client,db);}
    static TTRClient fake(DBMain db)throws Exception{Class<?> c=Class.forName("sun.misc.Unsafe");Field f=c.getDeclaredField("theUnsafe");f.setAccessible(true);TTRClient client=(TTRClient)c.getMethod("allocateInstance",Class.class).invoke(f.get(null),TTRClient.class);mainDB(client,db);client.m_sett=new Settings();Field g=TTRClient.class.getDeclaredField("mainWnd");g.setAccessible(true);g.set(null,client);return client;}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}public long getContentLengthLong(){return -1;}public InputStream getInputStream()throws IOException{return new ByteArrayInputStream(payload.getBytes("UTF-8"));}};}});
        DBMain original=new DBMain();Player p=new Player();p.profile.ID=1;p.profile.Name="Original";original.players.AddPlayer(p);TTRClient client=fake(original);
        SqliteUpdates.begin(client);DBMain update=new DBMain();mainDB(client,update);SqliteUpdates.remember(client,update);
        check(update.players.LoadXML("https://local.test/Players.xml",null),"Remote import failed");Path temporary=update.players.store().path;check(Files.exists(temporary),"No temporary SQLite");
        mainDB(client,original);SqliteUpdates.remember(client,original);SqliteUpdates.finish(client);
        check(!Files.exists(temporary),"Cancelled update leaked its database");check(original.players.GetPlayer(1)!=null,"Cancelled update closed original");
        SqliteUpdates.begin(client);update=new DBMain();mainDB(client,update);SqliteUpdates.remember(client,update);
        check(update.players.LoadXML("https://local.test/Players.xml",null),"Second remote import failed");temporary=update.players.store().path;
        Path destination=root.resolve("Players.sqlite");check(update.players.SaveXML(destination.toString()),"Promotion failed");update.players.switchPath(destination);SqliteUpdates.finish(client);
        check(!Files.exists(temporary),"Successful update leaked temporary database");check(update.players.GetPlayer(100)!=null&&update.players.players.size()==2,"Promoted records differ");
        check(Double.doubleToRawLongBits(update.players.GetPlayer(100).Rating)==Long.MIN_VALUE,"Remote negative zero lost");
        SqliteUpdates.begin(client);DBMain failed=new DBMain();mainDB(client,failed);SqliteUpdates.remember(client,failed);payload="<Players><Player id='1'>";
        check(!failed.players.LoadXML("https://local.test/broken.xml",null),"Broken remote accepted");SqliteUpdates.abort(client);
        check(client.GetDBMain()==update&&update.players.players.size()==2,"Failed update did not restore active catalog");
        update.players.store().close();System.out.println("REMOTE_FLOW_OK: simulated HTTPS, unknown length, cancellation, promotion, failed import, cleanup, rollback");
    }
}
