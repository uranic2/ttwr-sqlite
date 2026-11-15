package ttr;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Actual status panel: update, delayed stale reply, newer timestamp, unknown freshness, restart. */
public final class DatabaseStatusUiChecks {
    static final String OLD=DatabaseDateChecks.OLD,NEW=DatabaseDateChecks.NEW;
    static volatile String serviceDate=NEW;static volatile boolean metadataAvailable=true;
    static final AtomicInteger requests=new AtomicInteger();
    static final CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
    static void check(boolean v,String m){PlayerProfileChecks.check(v,m);}
    static JLabel status(TTRClient client)throws Exception{Field f=TTRClient.class.getDeclaredField("jStatusDB");f.setAccessible(true);return (JLabel)f.get(client);}
    static Updater updater(TTRClient client)throws Exception{Field f=TTRClient.class.getDeclaredField("updater");f.setAccessible(true);return (Updater)f.get(client);}
    static boolean icon(JLabel label,String name){Icon icon=label.getIcon();return icon instanceof ImageIcon&&((ImageIcon)icon).getDescription()!=null&&((ImageIcon)icon).getDescription().endsWith("/"+name+".png");}
    public static void main(String[] args)throws Exception{
        final boolean restart=args[0].equals("restart");Path home=PortablePaths.home();Files.createDirectories(home);
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){
            public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}
            public long getContentLengthLong(){return bytes().length;}
            byte[] bytes(){
                String path=u.getPath(),body;
                if(path.endsWith("db_last_updated.php"))body=serviceDate;
                else if(path.endsWith("db.xml"))body="<DB name='Status date fixture' date='"+OLD+"'><PlayerDB name='Players.xml'/><RegionList name='Regions.xml'/><TemplDB name='Templs.xml'/></DB>";
                else if(path.endsWith("Regions.xml"))body="<Regions/>";
                else if(path.endsWith("Templs.xml"))body="<Templates/>";
                else body="<Players><Player id='64'><name>Status fixture</name></Player></Players>";
                return body.getBytes(StandardCharsets.UTF_8);
            }
            public InputStream getInputStream()throws IOException{
                if(u.getPath().endsWith("db_last_updated.php")){
                    int n=requests.incrementAndGet();
                    if(!restart&&n==2){
                        held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Stale check timeout");}
                        catch(InterruptedException e){throw new IOException(e);}
                        returned.countDown();return new ByteArrayInputStream("16.11.2026-03:23:50".getBytes(StandardCharsets.UTF_8));
                    }
                    if(!metadataAvailable)throw new IOException("Metadata service offline");
                }
                if(u.getPath().endsWith("db.xml")&&!metadataAvailable)throw new IOException("Header offline");
                return new ByteArrayInputStream(bytes());
            }
        };}});
        PortableStart.prepareProfile();
        if(!restart){
            Path header=home.resolve("data/DB/db.xml");String text=new String(Files.readAllBytes(header),StandardCharsets.UTF_8);
            Files.write(header,text.replace("<DB name=","<DB date='"+OLD+"' name=").getBytes(StandardCharsets.UTF_8));
        }
        CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};
        int[] stage={0};boolean[] opened={false},started={false};long[] changed={0};
        SwingUtilities.invokeAndWait(()->{
            Timer poll=new Timer(80,null);poll.setCoalesce(false);
            poll.addActionListener(event->{try{
                TTRClient client=TTRClient.getInstance();if(client==null||!client.isShowing()||client.GetDBMain().GetFileName().isEmpty())return;
                JLabel label=status(client);
                if(restart){
                    if(!icon(label,"ok"))return;
                    check(label.getText().equals("15.11.2026"),"Date lost on restart");check(!updater(client).bNewDB,"Restart marked database obsolete");
                    client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));poll.stop();done.countDown();return;
                }
                if(stage[0]==0){
                    if(!icon(label,"warning")||!label.getText().equals("06.10.2026"))return;
                    stage[0]=1;updater(client).CheckForUpdateDB();return;
                }
                if(stage[0]==1){
                    if(held.getCount()!=0)return;
                    stage[0]=2;opened[0]=true;client.m_sett.bCloseUpdWnd=false;client.UpdateDB(false);return;
                }
                if(stage[0]==2){
                    for(Window w:Window.getWindows())if(w instanceof ttr.UI.DBUpdateDlg&&w.isShowing()){
                        ttr.UI.DBUpdateDlg dialog=(ttr.UI.DBUpdateDlg)w;
                        if(!started[0]){started[0]=true;((JButton)PlayerUpdateUiChecks.named(dialog,"players-update")).doClick();return;}
                        String phase=((JLabel)PlayerUpdateUiChecks.named(dialog,"players-phase")).getText();
                        if(phase.startsWith("Не удалось"))throw new AssertionError(((JTextArea)PlayerUpdateUiChecks.named(dialog,"players-details")).getText());
                        if(!phase.startsWith("База обновлена."))return;
                        check(label.getText().equals("15.11.2026")&&icon(label,"ok"),"Status not immediately refreshed after apply");
                        check(DatabaseDateChecks.equal(client.GetDBMain().date,DatabaseDate.parse(NEW)),"Live date differs from publication service");
                        DBMain reopened=new DBMain();check(reopened.LoadXML(home.resolve("data/DB/db.xml").toString(),null),"Saved header failed");
                        check(DatabaseDateChecks.equal(reopened.date,DatabaseDate.parse(NEW)),"Publication timestamp not persisted");reopened.players.store().close();
                        PlayerUpdateUiChecks.image(dialog,home.resolve("database-date-updated.png"));
                        stage[0]=3;changed[0]=System.nanoTime();release.countDown();((JButton)PlayerUpdateUiChecks.named(dialog,"players-cancel")).doClick();return;
                    }return;
                }
                if(stage[0]==3){
                    if(returned.getCount()!=0||System.nanoTime()-changed[0]<TimeUnit.MILLISECONDS.toNanos(500))return;
                    check(icon(label,"ok"),"Late check restored obsolete warning");
                    serviceDate="15.11.2026-03:23:51";stage[0]=4;updater(client).CheckForUpdateDB();return;
                }
                if(stage[0]==4){
                    if(!icon(label,"warning"))return;
                    check(label.getText().equals("15.11.2026"),"Status displays remote date instead of installed date");
                    metadataAvailable=false;stage[0]=5;updater(client).CheckForUpdateDB();return;
                }
                if(stage[0]==5){
                    if(!icon(label,"question"))return;
                    check(label.getText().equals("15.11.2026"),"Unknown freshness erased installed date");
                    metadataAvailable=true;serviceDate=NEW;stage[0]=6;updater(client).CheckForUpdateDB();return;
                }
                if(stage[0]==6){
                    if(!icon(label,"ok"))return;
                    check(client.GetDBPlayers().players.size()==1,"Players lost during status checks");
                    client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));poll.stop();done.countDown();
                }
            }catch(Throwable error){failure[0]=error;release.countDown();poll.stop();done.countDown();}});poll.start();
        });
        PortableStart.main(new String[0]);
        if(!done.await(40,TimeUnit.SECONDS)){release.countDown();System.out.println("Database status UI timeout stage="+stage[0]+" requests="+requests.get()+" opened="+opened[0]);System.exit(1);}
        if(failure[0]!=null){failure[0].printStackTrace(System.out);System.exit(1);}
        System.out.println("DATABASE_STATUS_UI_OK "+args[0]+": installed date text, original icons, current/obsolete/unknown, late reply ignored, saved timestamp");
        System.exit(0);
    }
}
