package ttr.updates;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Opt-in test agent: clicks Swing controls only; never transforms application bytecode. */
public final class LiveUpgradeAgent {
    public static void premain(String argument,Instrumentation instrumentation)throws Exception {
        String command=System.getProperty("sun.java.command","");
        // Native launcher's Java probes and the installer must not create an AWT thread.
        if(!command.equals("ttr.PortableStart")&&!command.startsWith("ttr.PortableStart "))return;
        Class.forName("ttr.updates.LiveUpgradeAgent$Ui").getMethod("start").invoke(null);
    }
    public static final class Ui {
        static Path report;
        static String version;
        static String fromVersion,toVersion;
        static boolean started;
        static javax.swing.Timer timer;
        static void log(String text)throws IOException {Files.write(report.resolve("events.log"),Arrays.asList(java.time.Instant.now()+" "+text),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        public static void start()throws Exception {
            report=Paths.get(System.getenv("TTWR_UPGRADE_TEST_REPORT"));Files.createDirectories(report);
            fromVersion=System.getenv("TTWR_UPGRADE_TEST_FROM");toVersion=System.getenv("TTWR_UPGRADE_TEST_TO");
            if(fromVersion==null)fromVersion="0.1.2";if(toVersion==null)toVersion="0.1.4";
            SwingUtilities.invokeLater(()->{
                timer=new javax.swing.Timer(200,event->{try{tick();}catch(Throwable error){try{log("FAILED "+error);try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(report.resolve("failure.txt")))){error.printStackTrace(writer);}}catch(Exception ignored){}timer.stop();}});
                timer.start();
            });
        }
        static void tick()throws Exception {
            ttr.TTRClient client=null;
            for(Frame frame:Frame.getFrames())if(frame instanceof ttr.TTRClient&&frame.isShowing())client=(ttr.TTRClient)frame;
            if(client==null){
                // A running observer timer would itself keep the old JVM alive and block the installer.
                if(started){copySettings("closed-"+version);log("CLOSED "+version);timer.stop();observeClose();}
                return;
            }
            if(!started){
                version=ttr.updates.AppVersion.current();
                if(!version.equals(fromVersion)&&!version.equals(toVersion))throw new AssertionError("Unexpected running version: "+version);
                if(client.GetDBMain().players.players.size()!=301)return;
                Properties expected=new Properties();try(Reader reader=Files.newBufferedReader(report.resolve("expected-settings.properties"),StandardCharsets.UTF_8)){expected.load(reader);}
                Properties actual=new Properties();
                for(String field:expected.stringPropertyNames()){
                    String value=String.valueOf(ttr.Settings.class.getField(field).get(client.m_sett));actual.setProperty(field,value);
                    if(!value.equals(expected.getProperty(field)))throw new AssertionError("Setting changed: "+field+" / "+value);
                }
                ttr.Player marker=client.GetDBMain().players.GetPlayer(900001);
                if(marker==null||!marker.profile.Name.equals("Ёлкин José — контроль обновления")||marker.deltaR!=12.75||marker.stats.wins!=23)throw new AssertionError("Marker player changed");
                java.lang.reflect.Field field=ttr.TTRClient.class.getDeclaredField("jStatusSoft");field.setAccessible(true);
                if(!((JLabel)field.get(client)).getText().equals("v "+version))return;
                actual.setProperty("runningVersion",version);actual.setProperty("players","301");actual.setProperty("javaVersion",System.getProperty("java.version"));actual.setProperty("javaBits",System.getProperty("sun.arch.data.model"));
                try(Writer writer=Files.newBufferedWriter(report.resolve("boot-"+version+".properties"),StandardCharsets.UTF_8)){actual.store(writer,"Live application state");}
                copySettings("boot-"+version);log("BOOT "+version+" players=301 settings=unchanged");started=true;
                if(version.equals(toVersion)){
                    if(!"true".equals(System.getenv("TTWR_UPGRADE_TEST_STORAGE")))finishTarget(client);
                    return;
                }
                JMenuBar bar=client.getJMenuBar();
                for(int i=0;i<bar.getMenuCount();i++){
                    JMenu menu=bar.getMenu(i);if(menu==null)continue;
                    for(int n=0;n<menu.getItemCount();n++){
                        JMenuItem item=menu.getItem(n);if(item!=null&&item.getText().startsWith("Проверить обновления программы")){log("CLICK Help/check updates");item.doClick();return;}
                    }
                }
                throw new AssertionError("Update menu missing");
            }
            if(version.equals(toVersion)){
                if(!"true".equals(System.getenv("TTWR_UPGRADE_TEST_STORAGE"))||Files.exists(report.resolve("storage-verified.txt")))finishTarget(client);
                return;
            }
            for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isShowing()){
                JOptionPane pane=pane((Container)window);if(pane==null)continue;
                if(pane.getClientProperty("live-upgrade-seen")==null){pane.putClientProperty("live-upgrade-seen",System.nanoTime());return;}
                if(System.nanoTime()-(Long)pane.getClientProperty("live-upgrade-seen")<700000000L)continue;
                if(Boolean.TRUE.equals(pane.getClientProperty("live-upgrade-clicked")))continue;
                String message=String.valueOf(pane.getMessage());
                if(message.startsWith("Доступна версия "+toVersion+".")&&message.contains("Установлена "+fromVersion+".")){
                    capture((Container)window,report.resolve("offer.png"));log("ACCEPT official release "+toVersion);
                }else if(message.startsWith("Версия "+toVersion+" загружена и проверена")){
                    capture((Container)window,report.resolve("ready.png"));log("ACCEPT verified download and normal shutdown");
                }else if(message.contains("Сохранить остальные изменения базы?")){
                    log("ACCEPT normal save prompt");
                }else{throw new AssertionError("Unexpected application dialog: "+message);}
                pane.putClientProperty("live-upgrade-clicked",true);java.util.List<JButton> buttons=new ArrayList<JButton>();buttons(pane,buttons);buttons.get(0).doClick();return;
            }
        }
        static void finishTarget(ttr.TTRClient client)throws Exception{
            capture(client,report.resolve("after.png"));
            client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));
            if(client.isDisplayable())throw new AssertionError("Updated application did not close");
            copySettings("closed-"+toVersion);log("CLOSED "+toVersion);Files.write(report.resolve("finished.txt"),Arrays.asList(fromVersion+" -> "+toVersion+" GUI upgrade completed"));timer.stop();observeClose();
        }
        static JOptionPane pane(Container root){for(Component c:root.getComponents()){if(c instanceof JOptionPane)return(JOptionPane)c;if(c instanceof Container){JOptionPane found=pane((Container)c);if(found!=null)return found;}}return null;}
        static void buttons(Container root,java.util.List<JButton> result){for(Component c:root.getComponents()){if(c instanceof JButton)result.add((JButton)c);else if(c instanceof Container)buttons((Container)c,result);}}
        static void capture(Container component,Path path)throws Exception{
            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(component.getWidth(),component.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=image.createGraphics();component.printAll(graphics);graphics.dispose();javax.imageio.ImageIO.write(image,"png",path.toFile());
        }
        static void copySettings(String phase)throws IOException {
            Files.copy(Paths.get(System.getProperty("ttwr.home"),"data","ttwr.cfg"),report.resolve(phase+"-ttwr.cfg"),StandardCopyOption.REPLACE_EXISTING);
        }
        static void observeClose() {
            Thread observer=new Thread(()->{
                try {
                    Thread.sleep(10000);
                    try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(report.resolve("threads-after-close-"+version+".txt")))){
                        for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){
                            writer.println(entry.getKey().getName()+" daemon="+entry.getKey().isDaemon()+" state="+entry.getKey().getState());
                            for(StackTraceElement frame:entry.getValue())writer.println("  "+frame);
                        }
                    }
                }catch(Exception ignored){}
            },"live-upgrade-close-observer");observer.setDaemon(true);observer.start();
        }
    }
}
