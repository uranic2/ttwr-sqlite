package ttr;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Drives the real client and the shared player progress dialog against synthetic HTTPS. */
public final class PlayerUpdateUiChecks {
    static Component named(Container parent,String name){for(Component c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof JMenu){for(Component item:((JMenu)c).getMenuComponents()){if(name.equals(item.getName()))return item;}}if(c instanceof Container){Component child=named((Container)c,name);if(child!=null)return child;}}return null;}
    static void check(boolean v,String text){if(!v)throw new AssertionError(text);}
    static void image(JDialog dialog,Path file)throws Exception{
        java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();dialog.printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",file.toFile());
    }
    public static void main(String[] args)throws Exception{
        String mode=args[0];Path home=PortablePaths.home();Files.createDirectories(home);
        Path source=PlayerProfileChecks.source(home.resolve("fixture"));
        byte[] players=Files.readAllBytes(source.resolve("Список.xml"));
        final boolean unknown=mode.equals("unknown"),error=mode.equals("error"),importing=mode.equals("import");
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){protected URLConnection openConnection(URL url){return new HttpURLConnection(url){
            volatile boolean closed;public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closed=true;}
            public long getContentLengthLong(){return unknown?-1:players.length;}
            public InputStream getInputStream(){
                String path=url.getPath();
                if(path.endsWith("db.xml"))return new ByteArrayInputStream("<DB name='Синтетическая HTTPS база'><PlayerDB name='Players.xml'/><RegionList name='Regions.xml'/><TemplDB name='Templs.xml'/></DB>".getBytes(StandardCharsets.UTF_8));
                if(path.endsWith("Regions.xml"))return new ByteArrayInputStream("<Regions/>".getBytes(StandardCharsets.UTF_8));
                if(path.endsWith("Templs.xml"))return new ByteArrayInputStream("<Templates/>".getBytes(StandardCharsets.UTF_8));
                return new InputStream(){int pos;
                    public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
                    public int read(byte[] b,int off,int len)throws IOException{
                        if(closed)throw new IOException("Closed");if(error&&pos>=players.length/2)throw new IOException("Simulated disconnection");
                        if(pos>=players.length)return -1;
                        try{Thread.sleep(40);}catch(InterruptedException e){throw new IOException(e);}
                        if(closed)throw new IOException("Closed");
                        int n=Math.min(Math.min(512,len),players.length-pos);System.arraycopy(players,pos,b,off,n);pos+=n;return n;
                    }
                    public void close(){closed=true;}
                };
            }
        };}});
        PortableStart.prepareProfile();
        final String config=DbPackageTransaction.hash(home.resolve("data/ttwr.cfg"));
        final Throwable[] failure={null};final boolean[] asked={false},started={false},cancelled={false},captured={false},retried={false},finished={false};
        CountDownLatch done=new CountDownLatch(1);
        SwingUtilities.invokeAndWait(()->{
            Timer poll=new Timer(100,null);poll.setCoalesce(false);
            poll.addActionListener(event->{try{
                TTRClient client=TTRClient.getInstance();if(client==null||!client.isShowing())return;
                if(!asked[0]){
                    if(client.GetDBMain().GetFileName().isEmpty())return;
                    for(Window initial:Window.getWindows())if(initial instanceof JDialog&&initial.isShowing())return;
                    asked[0]=true;client.m_sett.bCloseUpdWnd=false;
                    if(importing)ttr.UI.UIMan.SetFontSize(18);
                    check(named(client.getJMenuBar(),"import-ttwr-profile")!=null,"Import menu missing");
                    if(importing)ProfileImports.fromMenu(client);else client.UpdateDB(false);
                    return;
                }
                for(Window w:Window.getWindows())if(w instanceof JDialog&&w.isShowing()){
                    JDialog dialog=(JDialog)w;
                    JFileChooser chooser=(JFileChooser)named(dialog,"fixture-chooser");
                    if(chooser==null)chooser=findChooser(dialog);
                    if(chooser!=null){chooser.setSelectedFile(source.toFile());chooser.approveSelection();return;}
                    JOptionPane pane=findPane(dialog);
                    if(pane!=null){if(!Boolean.TRUE.equals(pane.getClientProperty("accepted"))){pane.putClientProperty("accepted",true);pane.setValue(JOptionPane.YES_OPTION);}return;}
                    if(!(dialog instanceof ttr.UI.DBUpdateDlg))continue;
                    JButton start=(JButton)named(dialog,"players-update"),close=(JButton)named(dialog,"players-cancel");
                    JLabel phase=(JLabel)named(dialog,"players-phase");
                    if(!started[0]){started[0]=true;if(!importing)start.doClick();return;}
                    java.lang.reflect.Field field=ttr.UI.DBUpdateDlg.class.getDeclaredField("timer");field.setAccessible(true);check(((Timer)field.get(dialog)).getDelay()==1000,"Refresh interval");
                    JProgressBar bar=(JProgressBar)named(dialog,"players-progress");
                    if(!importing&&!captured[0]&&bar.getValue()>0&&bar.getValue()<1000||unknown&&!captured[0]&&((JLabel)named(dialog,"players-recent")).getText().contains("КБ/с")){
                        if(unknown){check(bar.isIndeterminate(),"Unknown length has a percent");check(((JLabel)named(dialog,"players-remaining")).getText().endsWith("—"),"Unknown length has ETA");}
                        else check(((JLabel)named(dialog,"players-volume")).getText().contains("/"),"Known volume has no total");
                        image(dialog,home.resolve("players-progress.png"));captured[0]=true;
                        if(mode.equals("button")||mode.equals("escape")||mode.equals("close")){
                            cancelled[0]=true;
                            if(mode.equals("button"))close.doClick();
                            else if(mode.equals("close"))dialog.dispatchEvent(new WindowEvent(dialog,WindowEvent.WINDOW_CLOSING));
                            else{JRootPane root=dialog.getRootPane();Object key=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0));root.getActionMap().get(key).actionPerformed(new ActionEvent(root,0,"escape"));}
                            return;
                        }
                    }
                    if(phase.getText().equals("Обновление отменено")){
                        check(client.GetDBPlayers().players.size()==0,"Cancellation changed catalog");PlayerProfileChecks.noStages(home.resolve("data"));
                        if(mode.equals("button")&&!retried[0]){retried[0]=true;start.doClick();return;}
                        finished[0]=true;close.doClick();return;
                    }
                    if(phase.getText().startsWith("Не удалось обновить базу")){
                        check(error,"Unexpected error "+((JTextArea)named(dialog,"players-details")).getText());
                        check(client.GetDBPlayers().players.size()==0,"Error changed catalog");finished[0]=true;close.doClick();return;
                    }
                    if(phase.getText().startsWith("База обновлена.")){
                        check(client.GetDBPlayers().players.size()==300,"Success count");
                        JLabel recordCount=(JLabel)named(dialog,"players-records");if(importing)check(recordCount.getFont().getSize()==18,"Application font scale ignored");check(recordCount.getText().endsWith("301"),"Parsed count mislabeled");
                        check(recordCount.getWidth()>=recordCount.getFontMetrics(recordCount.getFont()).stringWidth(recordCount.getText()),"Record count visually clipped");
                        check(recordCount.getHeight()>=recordCount.getFontMetrics(recordCount.getFont()).getHeight(),"Record count vertically clipped");
                        check(error==false,"Broken transfer succeeded");image(dialog,home.resolve("players-complete.png"));
                        finished[0]=true;close.doClick();return;
                    }
                }
                if(finished[0]){
                    check(config.equals(DbPackageTransaction.hash(home.resolve("data/ttwr.cfg"))),"Settings changed before exit");
                    PlayerProfileChecks.noStages(home.resolve("data"));
                    client.dispatchEvent(new WindowEvent(client,WindowEvent.WINDOW_CLOSING));poll.stop();done.countDown();
                }
            }catch(Throwable e){failure[0]=e;poll.stop();done.countDown();}});poll.start();
        });
        PortableStart.main(new String[0]);
        if(!done.await(40,TimeUnit.SECONDS)){System.err.println("Player UI timeout "+mode);System.exit(1);}
        if(failure[0]!=null){failure[0].printStackTrace(System.out);System.exit(1);}
        System.out.println("PLAYER_UPDATE_UI_OK "+mode+": real client/menu, 1000 ms timer, progress, data preservation, cleanup, completion after table refresh");
        System.exit(0);
    }
    static JFileChooser findChooser(Container c){for(Component x:c.getComponents()){if(x instanceof JFileChooser)return (JFileChooser)x;if(x instanceof Container){JFileChooser f=findChooser((Container)x);if(f!=null)return f;}}return null;}
    static JOptionPane findPane(Container c){for(Component x:c.getComponents()){if(x instanceof JOptionPane)return (JOptionPane)x;if(x instanceof Container){JOptionPane f=findPane((Container)x);if(f!=null)return f;}}return null;}
}
