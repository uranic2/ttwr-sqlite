package ttr.updates;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.swing.*;
import ttr.*;

/** Real original main window and menu wiring, with no network access. Run in an isolated profile. */
public final class ProgramUpdateUiChecks {
    static JOptionPane pane(Container root) {
        for(Component c:root.getComponents()) { if(c instanceof JOptionPane)return (JOptionPane)c;if(c instanceof Container){JOptionPane result=pane((Container)c);if(result!=null)return result;} }return null;
    }
    public static void main(String[] args)throws Exception {
        java.nio.file.Path retired=java.nio.file.Paths.get(System.getProperty("ttwr.home"),"docs","source-provenance.md");
        java.nio.file.Files.createDirectories(retired.getParent());java.nio.file.Files.write(retired,"retired UI test document".getBytes(StandardCharsets.UTF_8));
        final boolean cancel=args.length>0&&args[0].equals("cancel");
        final boolean storage=args.length>0&&args[0].equals("storage");
        final java.nio.file.Path testHome=java.nio.file.Paths.get(System.getProperty("ttwr.home"));
        if(storage){
            UpdateStorageChecks.image(testHome,"0.0.1");
            UpdateStorageChecks.install(testHome,"0.0.2",1000);
            UpdateStorageChecks.bundledManifest(testHome);
            UpdateStorageChecks.install(testHome,AppVersion.current(),2000);
        }
        final byte[] zip=cancel?java.nio.file.Files.readAllBytes(ProgramUpdateChecks.fixture(java.nio.file.Files.createTempDirectory(java.nio.file.Paths.get(System.getProperty("ttwr.home")),"ui-fixture-"))):new byte[0];
        final String metadata=cancel?ProgramUpdateChecks.metadata(ReleaseClient.sha256(writeZip(zip))).replace("\"size\":4", "\"size\":"+zip.length):"";
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){protected URLConnection openConnection(URL url){return new HttpURLConnection(url){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}public int getResponseCode(){return cancel?200:404;}public InputStream getInputStream(){return new ByteArrayInputStream(url.getHost().equals("api.github.com")?metadata.getBytes(StandardCharsets.UTF_8):zip);}};}});
        CountDownLatch finished=new CountDownLatch(1);final Throwable[] failure={null};final boolean[] clicked={false};
        SwingUtilities.invokeAndWait(()->{
            javax.swing.Timer poll=new javax.swing.Timer(100,null);
            poll.addActionListener(event->{try {
                TTRClient client=null;for(Frame frame:Frame.getFrames())if(frame instanceof TTRClient&&frame.isShowing())client=(TTRClient)frame;
                if(client==null)return;
                if(!clicked[0]){
                    if(java.nio.file.Files.exists(retired))throw new AssertionError("Retired document remains after application startup");
                    if(client.getIconImage()==null||client.getIconImage().getWidth(null)!=256)throw new AssertionError("New application icon missing");
                    java.lang.reflect.Field status=TTRClient.class.getDeclaredField("jStatusSoft");status.setAccessible(true);
                    if(!((JLabel)status.get(client)).getText().equals(AppVersion.label()))throw new AssertionError("Status bar version differs from build VERSION: "+((JLabel)status.get(client)).getText());
                    if(!TTRClient.class.getField("version").get(null).equals(AppVersion.current()))throw new AssertionError("Public version differs from build VERSION");
                    if(storage&&!java.nio.file.Files.exists(testHome.resolve("data/updates/update-"+AppVersion.current()+"/confirmed-start.txt")))return;
                    if(storage&&java.nio.file.Files.exists(testHome.resolve("data/updates/update-0.0.2")))return;
                    JMenuBar bar=client.getJMenuBar();JMenuItem update=null;
                    for(int i=0;i<bar.getMenuCount();i++){JMenu menu=bar.getMenu(i);if(menu==null)continue;for(int n=0;n<menu.getItemCount();n++){JMenuItem item=menu.getItem(n);if(item!=null&&item.getText().startsWith(storage?"Файлы обновлений":"Проверить обновления программы")){if(!menu.getText().contains("Справка"))throw new AssertionError("Wrong menu");update=item;}}}
                    if(update==null)throw new AssertionError("Update menu missing from original window");clicked[0]=true;
                    update.doClick();return;
                }
                for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isShowing()){
                    JOptionPane option=pane((Container)window);if(option==null)continue;
                    String message=String.valueOf(option.getMessage());
                    if (!"handled".equals(option.getClientProperty("ui-check"))) { option.putClientProperty("ui-check","handled"); option.putClientProperty("ui-shown-at",System.nanoTime()); }
                    if(System.nanoTime()-(Long)option.getClientProperty("ui-shown-at")<600000000L)continue;
                    if(cancel){
                        if(message.startsWith("Доступна версия")){click(window,0);return;}
                        if(message.startsWith("Версия 1.2.0 загружена")){client.GetDBMain().bChanged=true;click(window,0);return;}
                        if(message.contains("Сохранить остальные изменения базы?")){click(window,2);return;}
                        if(message.startsWith("Выход отменён")){
                            click(window,0);client.GetDBMain().bChanged=false;
                            client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));poll.stop();finished.countDown();return;
                        }
                    }
                    if(storage?!message.startsWith("Файлы обновлений занимают"):!String.valueOf(option.getMessage()).contains("актуальная версия "+AppVersion.current()))throw new AssertionError("Unexpected update response: "+option.getMessage());
                    option.setValue(JOptionPane.OK_OPTION);
                    client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));
                    if(client.isDisplayable())throw new AssertionError("Main window did not close");poll.stop();finished.countDown();return;
                }
            } catch(Throwable error){failure[0]=error;poll.stop();finished.countDown();}});
            poll.start();
        });
        PortableStart.main(new String[0]);
        if(!finished.await(30,TimeUnit.SECONDS)){System.out.println("UI check timed out");for(Window w:Window.getWindows())System.out.println("WINDOW "+w.getClass()+" showing="+w.isShowing()+" enabled="+w.isEnabled()+" title="+(w instanceof Dialog?((Dialog)w).getTitle():"frame"));for(java.util.Map.Entry<Thread,StackTraceElement[]> e:Thread.getAllStackTraces().entrySet())if(e.getKey().getName().contains("AWT-EventQueue")){System.out.println(e.getKey());for(StackTraceElement f:e.getValue())System.out.println(f);}System.exit(1);}
        if(failure[0]!=null){failure[0].printStackTrace(System.out);System.exit(1);}
        System.out.println("PROGRAM_UPDATE_UI_OK: original Help menu, asynchronous check, "+(storage?"automatic retention after successful startup and storage size menu":cancel?"verified download and original exit cancellation":"current version message, normal SQLite shutdown"));
        System.exit(0);
    }
    static void buttons(Container root,java.util.List<JButton> buttons) {
        for(Component c:root.getComponents()) { if(c instanceof JButton)buttons.add((JButton)c);else if(c instanceof Container)buttons((Container)c,buttons); }
    }
    static void click(Window window,int index) {
        java.util.List<JButton> buttons=new java.util.ArrayList<JButton>();buttons(pane((Container)window),buttons);buttons.get(index).doClick();
    }
    static java.nio.file.Path writeZip(byte[] zip)throws Exception{
        java.nio.file.Path file=java.nio.file.Files.createTempFile(java.nio.file.Paths.get(System.getProperty("ttwr.home")),"ui-payload-",".zip");java.nio.file.Files.write(file,zip);return file;
    }
}
