package ttr.updates;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;
import javax.swing.*;
import ttr.*;

/** Drives only its own real Swing window; slow HTTPS is deterministic and never uses the network. */
public final class DownloadUiChecks {
    static Component named(Container c,String name){for(Component x:c.getComponents()){if(name.equals(x.getName()))return x;if(x instanceof Container){Component found=named((Container)x,name);if(found!=null)return found;}}return null;}
    static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
    static byte[] fixture(Path root)throws Exception{
        ProgramUpdateChecks.fixture(root);Path image=root.resolve("fixture"),extra=image.resolve("app/lib/download-fixture.jar");
        byte[] bytes=new byte[262144];new Random(17).nextBytes(bytes);Files.createDirectories(extra.getParent());Files.write(extra,bytes);
        Path manifest=image.resolve("checksums.json");String text=ProgramUpdateChecks.contents(manifest).trim();
        ProgramUpdateChecks.write(manifest,text.substring(0,text.length()-1)+",{\"path\":\"app/lib/download-fixture.jar\",\"sha256\":\""+ReleaseClient.sha256(extra)+"\"}]");
        ByteArrayOutputStream raw=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(raw);java.util.stream.Stream<Path> walk=Files.walk(image)){
            Iterator<Path> i=walk.iterator();while(i.hasNext()){Path p=i.next();if(!Files.isRegularFile(p))continue;z.putNextEntry(new ZipEntry(image.relativize(p).toString().replace('\\','/')));Files.copy(p,z);z.closeEntry();}
        }return raw.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        final String mode=args[0];final boolean success=mode.equals("success"),networkError=mode.equals("error");Path home=Paths.get(System.getProperty("ttwr.home"));Files.createDirectories(home);
        final byte[] payload=fixture(home.resolve("fixture-input"));Path zip=home.resolve("fixture-payload.zip");Files.write(zip,payload);
        final String metadata=ProgramUpdateChecks.metadata(ReleaseClient.sha256(zip)).replace("\"size\":4","\"size\":"+payload.length);
        Path retained=home.resolve("data/updates/update-installed/backup/keep.txt");ProgramUpdateChecks.write(retained,"retain previous backup");
        final AtomicLong received=new AtomicLong();
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){
            volatile boolean closed;
            public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closed=true;}
            public int getResponseCode(){return 200;}
            public InputStream getInputStream(){if(u.getHost().equals("api.github.com"))return new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8));
                return new InputStream(){int position;
                    public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
                    public int read(byte[] b,int offset,int length)throws IOException{
                        if(closed)throw new IOException("Closed");if(networkError&&position>=131072)throw new IOException("Simulated broken connection");if(position>=payload.length)return -1;
                        try{Thread.sleep(100);}catch(InterruptedException interrupted){throw new InterruptedIOException("Cancelled read");}
                        if(closed)throw new IOException("Closed");int n=Math.min(Math.min(4096,length),payload.length-position);System.arraycopy(payload,position,b,offset,n);position+=n;received.set(position);return n;
                    }
                    public void close(){closed=true;}
                };
            }
        };}});
        CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};boolean[] asked={false},cancelled={false},ready={false},captured={false};String[] dbHash={null},cfgHash={null};
        SwingUtilities.invokeAndWait(()->{
            javax.swing.Timer poll=new javax.swing.Timer(100,null);poll.setCoalesce(false);
            poll.addActionListener(event->{try{
                TTRClient client=null;for(Frame f:Frame.getFrames())if(f instanceof TTRClient&&f.isShowing())client=(TTRClient)f;if(client==null)return;
                if(!asked[0]){
                    dbHash[0]=ReleaseClient.sha256(home.resolve("data/DB/Players.sqlite"));cfgHash[0]=ReleaseClient.sha256(home.resolve("data/ttwr.cfg"));
                    JMenuBar bar=client.getJMenuBar();for(int i=0;i<bar.getMenuCount();i++){JMenu menu=bar.getMenu(i);if(menu==null)continue;for(int j=0;j<menu.getItemCount();j++){JMenuItem item=menu.getItem(j);if(item!=null&&item.getText().startsWith("Проверить обновления программы")){asked[0]=true;item.doClick();return;}}}return;
                }
                for(Window w:Window.getWindows())if(w instanceof JDialog&&w.isShowing()){
                    JDialog dialog=(JDialog)w;
                    if(dialog.getTitle().equals("Загрузка обновления")){
                        check(!client.isEnabled(),"Main window enabled during download");
                        java.lang.reflect.Field timerField=UpdateDownloadDialog.class.getDeclaredField("timer");timerField.setAccessible(true);check(((javax.swing.Timer)timerField.get(dialog)).getDelay()==1000,"Indicators must refresh once per second");
                        for(String name:Arrays.asList("download-volume","download-recent","download-average","download-elapsed","download-remaining","download-progress","download-cancel"))check(named(dialog,name)!=null,"Missing field "+name);
                        long threshold=success?212992:49152;if(received.get()<threshold||cancelled[0])return;
                        check(((JLabel)named(dialog,"download-volume")).getText().contains("КБ"),"Volume unit missing");
                        check(((JLabel)named(dialog,"download-recent")).getText().contains("КБ/с"),"Speed missing");
                        JProgressBar progress=(JProgressBar)named(dialog,"download-progress");check(progress.getValue()>0&&progress.getValue()<progress.getMaximum(),"Progress did not advance");
                        if(!captured[0]){java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();dialog.printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",home.resolve("download-window.png").toFile());captured[0]=true;}
                        if(success||networkError)return;cancelled[0]=true;
                        if(mode.equals("close"))dialog.dispatchEvent(new WindowEvent(dialog,WindowEvent.WINDOW_CLOSING));
                        else if(mode.equals("escape")){JRootPane root=dialog.getRootPane();Object key=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0));check(key!=null,"Escape not bound");root.getActionMap().get(key).actionPerformed(new ActionEvent(root,0,"escape"));}
                        else ((JButton)named(dialog,"download-cancel")).doClick();return;
                    }
                    JOptionPane option=ProgramUpdateUiChecks.pane(dialog);if(option==null)continue;String message=String.valueOf(option.getMessage());
                    if(Boolean.TRUE.equals(option.getClientProperty("download-ui-clicked")))continue;option.putClientProperty("download-ui-clicked",true);
                    if(message.startsWith("Доступна версия")){ProgramUpdateUiChecks.click(dialog,0);return;}
                    if(networkError&&message.startsWith("Не удалось обновить программу")){ready[0]=true;ProgramUpdateUiChecks.click(dialog,0);return;}
                    if(message.startsWith("Версия 1.2.0 загружена")){check(success,"Cancelled download reached installation prompt");ready[0]=true;ProgramUpdateUiChecks.click(dialog,1);return;}
                    throw new AssertionError("Unexpected dialog "+message);
                }
                if((cancelled[0]||ready[0])&&client.isEnabled()){
                    check(captured[0],"No preview captured");check(ProgramUpdateChecks.contents(retained).equals("retain previous backup"),"Retained backup changed");
                    try(java.util.stream.Stream<Path> dirs=Files.list(home.resolve("data/updates"))){check(dirs.noneMatch(p->p.getFileName().toString().startsWith("update-")&&!p.getFileName().toString().equals("update-installed")),"Partial stage remains");}
                    check(dbHash[0].equals(ReleaseClient.sha256(home.resolve("data/DB/Players.sqlite"))),"Database changed");check(cfgHash[0].equals(ReleaseClient.sha256(home.resolve("data/ttwr.cfg"))),"Settings changed");
                    client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));poll.stop();done.countDown();
                }
            }catch(Throwable error){failure[0]=error;poll.stop();done.countDown();}});poll.start();
        });
        PortableStart.main(new String[0]);if(!done.await(30,TimeUnit.SECONDS)){
            System.out.println("STATE asked="+asked[0]+" cancelled="+cancelled[0]+" ready="+ready[0]+" received="+received.get());
            SwingUtilities.invokeAndWait(()->{for(Window w:Window.getWindows())System.out.println("WINDOW "+w.getClass().getName()+" showing="+w.isShowing()+" enabled="+w.isEnabled()+" displayable="+w.isDisplayable()+" title="+(w instanceof Dialog?((Dialog)w).getTitle():"frame"));});
            System.err.println("Download UI timed out");System.exit(1);
        }if(failure[0]!=null){failure[0].printStackTrace();System.exit(1);}
        System.out.println("DOWNLOAD_UI_OK "+mode+": real window, visible progress, cancellation/cleanup, data/settings/backup preserved; "+home.resolve("download-window.png"));System.exit(0);
    }
}
