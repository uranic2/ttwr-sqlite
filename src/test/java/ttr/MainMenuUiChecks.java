package ttr;

import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;
import ttr.UI.DatabaseInfoDlg;

/** Actual transformed menu, asynchronous data and the close button. */
public final class MainMenuUiChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JMenuItem item(TTRClient client,String name)throws Exception {
        java.lang.reflect.Field f=TTRClient.class.getDeclaredField(name);f.setAccessible(true);return (JMenuItem)f.get(client);
    }
    public static void main(String[] args)throws Exception {
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){
            protected URLConnection openConnection(URL url)throws IOException {throw new IOException("No network in menu test");}
        });
        PortableStart.prepareProfile();
        CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};int[] stage={0};
        SwingUtilities.invokeAndWait(()->{
            Timer poll=new Timer(80,null);poll.setCoalesce(false);
            poll.addActionListener(event->{try {
                TTRClient client=TTRClient.getInstance();
                if(client==null||!client.isShowing()||client.GetDBMain().GetFileName().isEmpty())return;
                if(stage[0]==0){
                    for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isShowing())return;
                    JMenu database=(JMenu)item(client,"jMenu1");check(database.getText().equals("БД игроков"),"Database caption");
                    check(item(client,"jMenuDBUpdate").getText().equals("Обновить БД игроков из интернет"),"Update caption");
                    check(item(client,"jMenuBackupDB").getParent()==null,"Backup still visible");
                    JMenu settings=null;
                    for(int i=0;i<client.getJMenuBar().getMenuCount();i++){
                        JMenu m=client.getJMenuBar().getMenu(i);if(m!=null&&m.getText().equals("Настройка"))settings=m;
                    }
                    check(settings!=null&&settings.getItemCount()==2,"Settings menu");
                    check(settings.getItem(0)==item(client,"jMenuSettings")&&settings.getItem(1)==item(client,"jMenuPageSetup"),"Original actions replaced");
                    check(settings.getItem(0).getText().equals("Общие настройки"),"General caption");
                    check(settings.getItem(1).getText().equals("Настройка страницы печати"),"Print caption");
                    check(settings.getItem(0).getActionListeners().length>0&&settings.getItem(1).getActionListeners().length>0,"Settings lost listeners");
                    client.GetDBPlayers().store().lastInternetUpdate(1234567890000L);
                    stage[0]=1;
                    ((JMenuItem)PlayerUpdateUiChecks.named(database.getPopupMenu(),"database-info")).doClick();return;
                }
                for(Window w:Window.getWindows())if(w instanceof DatabaseInfoDlg&&w.isShowing()){
                    DatabaseInfoDlg dlg=(DatabaseInfoDlg)w;
                    JTextArea updated=(JTextArea)PlayerUpdateUiChecks.named(dlg,"database-updated");
                    if(updated.getText().equals("Загрузка…"))return;
                    check(updated.getText().equals(DatabaseInfoDlg.updateTime(1234567890000L)),"Last download time");
                    check(((JTextArea)PlayerUpdateUiChecks.named(dlg,"database-path")).getText().equals(client.GetDBPlayers().store().path.toString()),"SQLite path");
                    String expected=DatabaseInfoDlg.kilobytes(Files.size(client.GetDBPlayers().store().path));
                    check(((JTextArea)PlayerUpdateUiChecks.named(dlg,"database-size")).getText().equals(expected),"Size format");
                    check(((JTextArea)PlayerUpdateUiChecks.named(dlg,"database-count")).getText().equals("0"),"Empty count");
                    check(DatabaseInfoDlg.kilobytes(1264128).replace('\u00a0',' ').replace('\u202f',' ').equals("1 234,50 Кбайт"),"Grouping or units");
                    check(DatabaseInfoDlg.updateTime(0).equals("Нет сведений"),"Unknown time invented");
                    PlayerUpdateUiChecks.image(dlg,PortablePaths.home().resolve("database-info.png"));
                    ((JButton)PlayerUpdateUiChecks.named(dlg,"database-info-close")).doClick();
                    check(!dlg.isDisplayable(),"Close did not dispose");
                    client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));poll.stop();done.countDown();return;
                }
            }catch(Throwable error){failure[0]=error;poll.stop();done.countDown();}});poll.start();
        });
        PortableStart.main(new String[0]);
        if(!done.await(25,TimeUnit.SECONDS)){System.err.println("Menu test timeout stage="+stage[0]);System.exit(1);}
        if(failure[0]!=null){failure[0].printStackTrace();System.exit(1);}
        System.out.println("MAIN_MENU_UI_OK: original settings actions, captions, backup removed, SQLite information and close");System.exit(0);
    }
}
