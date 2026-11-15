package ttr;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** First-run consent against an explicit synthetic source, without inspecting real AppData. */
public final class ProfileFirstRunUiChecks {
    public static void main(String[] args)throws Exception{
        boolean accept=args[0].equals("accept");Path home=PortablePaths.home();
        Path source=PlayerProfileChecks.source(home.resolve("fixture"));
        Map<String,String> before=PlayerProfileChecks.hashes(source);
        PlayerProfileChecks.check(ProfileImports.firstRun(home.resolve("data")),"Fresh profile not detected");
        Throwable[] failure={null};CountDownLatch finished=new CountDownLatch(1);
        SwingUtilities.invokeAndWait(()->{
            javax.swing.Timer timer=new javax.swing.Timer(100,null);timer.setCoalesce(false);
            timer.addActionListener(event->{try{
                for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isShowing()){
                    JDialog dialog=(JDialog)window;JOptionPane pane=PlayerUpdateUiChecks.findPane(dialog);
                    if(pane!=null){if(!Boolean.TRUE.equals(pane.getClientProperty("answered"))){pane.putClientProperty("answered",true);pane.setValue(accept?JOptionPane.YES_OPTION:JOptionPane.NO_OPTION);}return;}
                    if(dialog instanceof ttr.UI.DBUpdateDlg){
                        JLabel phase=(JLabel)PlayerUpdateUiChecks.named(dialog,"players-phase");
                        if(phase.getText().startsWith("Не удалось"))throw new AssertionError(((JTextArea)PlayerUpdateUiChecks.named(dialog,"players-details")).getText());
                        if(phase.getText().startsWith("База обновлена.")){PlayerUpdateUiChecks.image(dialog,home.resolve("first-run-import.png"));((JButton)PlayerUpdateUiChecks.named(dialog,"players-cancel")).doClick();return;}
                    }
                }
            }catch(Throwable e){failure[0]=e;timer.stop();for(Window w:Window.getWindows())w.dispose();finished.countDown();}});timer.start();
        });
        Thread task=new Thread(()->{try{ProfileImports.offerFirstRun(source);}catch(Throwable e){failure[0]=e;}finally{finished.countDown();}},"first-run-import-test");task.start();
        if(!finished.await(30,TimeUnit.SECONDS)){System.err.println("First run import timeout");System.exit(1);}
        if(failure[0]!=null){failure[0].printStackTrace();System.exit(1);}
        PortableStart.prepareProfile();
        DBMain db=new DBMain();PlayerProfileChecks.check(db.LoadXML(home.resolve("data/DB/db.xml").toString(),null),"First-run result cannot open");
        PlayerProfileChecks.check(db.players.players.size()==(accept?300:0),"Consent result incorrect");db.players.store().close();
        PlayerProfileChecks.check(before.equals(PlayerProfileChecks.hashes(source)),"Original profile changed");
        PlayerProfileChecks.check(!ProfileImports.firstRun(home.resolve("data")),"Repeated start counted as fresh");
        PlayerProfileChecks.check(!ProfileImports.shouldOffer(false,source),"Repeated offer");
        PlayerProfileChecks.check(!ProfileImports.shouldOffer(true,home.resolve("missing")),"Missing source offered");
        System.out.println("PROFILE_FIRST_RUN_UI_OK "+args[0]+": explicit consent, correct catalog, source unchanged, no repeated offer");
        System.exit(0);
    }
}
