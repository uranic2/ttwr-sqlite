package ttr.updates;

import java.awt.*;
import java.awt.event.*;
import java.util.Locale;
import javax.swing.*;
import ttr.UI.DialogStyle;

/** The timer reads a bounded measurement window; network threads never touch Swing controls. */
final class UpdateDownloadDialog extends JDialog {
    private final DownloadStats stats;
    private final DownloadControl control;
    private final JProgressBar bar=new JProgressBar(0,1000);
    private final JLabel volume=new JLabel(),recent=new JLabel(),average=new JLabel(),elapsed=new JLabel(),remaining=new JLabel();
    private final JLabel phase=new JLabel("Соединяемся с сервером…");
    private final JButton cancel=new JButton("Отмена");
    private final javax.swing.Timer timer;
    private volatile String phaseText="Соединяемся с сервером…";
    private static final Locale RU=new Locale("ru","RU");
    UpdateDownloadDialog(Frame owner,String version,DownloadStats stats,DownloadControl control){
        super(owner,"Загрузка обновления",false);this.stats=stats;this.control=control;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);setResizable(false);
        JPanel content=new JPanel(new BorderLayout(0,DialogStyle.size(16)));DialogStyle.contentBorder(content);
        JPanel header=new JPanel(new BorderLayout(0,DialogStyle.size(8)));JLabel title=new JLabel("Обновление до версии "+version);
        DialogStyle.heading(title,3);header.add(title,BorderLayout.NORTH);header.add(phase,BorderLayout.SOUTH);content.add(header,BorderLayout.NORTH);
        JPanel body=new JPanel();body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
        bar.setStringPainted(true);bar.setName("download-progress");bar.setAlignmentX(0);body.add(bar);body.add(Box.createVerticalStrut(DialogStyle.size(8)));
        volume.setName("download-volume");volume.setAlignmentX(0);body.add(volume);body.add(Box.createVerticalStrut(DialogStyle.size(16)));
        JPanel speeds=new JPanel(new GridLayout(1,2,DialogStyle.size(24),0));speeds.setAlignmentX(0);
        speeds.add(metric("Скорость за последние 2 с",recent,"download-recent"));speeds.add(metric("Средняя скорость",average,"download-average"));body.add(speeds);body.add(Box.createVerticalStrut(DialogStyle.size(16)));
        JPanel times=new JPanel(new GridLayout(1,2,DialogStyle.size(24),0));times.setAlignmentX(0);elapsed.setName("download-elapsed");remaining.setName("download-remaining");times.add(elapsed);times.add(remaining);body.add(times);content.add(body,BorderLayout.CENTER);
        cancel.setName("download-cancel");cancel.addActionListener(e->cancel());
        DialogStyle.fonts(content);
        JPanel window=new JPanel(new BorderLayout());window.add(content,BorderLayout.CENTER);
        window.add(DialogStyle.actions("download-actions",cancel),BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){cancel();}});
        getRootPane().registerKeyboardAction(e->cancel(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        setContentPane(window);render();pack();setSize(Math.max(getWidth(),DialogStyle.size(560)),getHeight());setLocationRelativeTo(owner);
        timer=new javax.swing.Timer(1000,e->render());timer.start();
    }
    private static JPanel metric(String caption,JLabel value,String name){
        JPanel p=new JPanel(new BorderLayout(0,DialogStyle.size(8)));p.add(new JLabel(caption),BorderLayout.NORTH);value.setName(name);DialogStyle.heading(value,2);p.add(value,BorderLayout.SOUTH);return p;
    }
    void phase(String value){phaseText=value;}
    private void cancel(){if(control.isCancelled())return;control.cancel();cancel.setEnabled(false);cancel.setText("Отмена…");render();}
    private void render(){
        DownloadStats.Snapshot s=stats.snapshot();double percent=100.0*s.bytes/s.total;
        bar.setValue((int)Math.round(percent*10));bar.setString(String.format(RU,"%.1f%%",percent));bar.setIndeterminate(control.isCancelled());
        volume.setText(String.format(RU,"%,.1f / %,.1f КБ",s.bytes/1024.0,s.total/1024.0));
        recent.setText(String.format(RU,"%,.1f КБ/с",s.recentBytesPerSecond/1024.0));average.setText(String.format(RU,"%,.1f КБ/с",s.averageBytesPerSecond/1024.0));
        elapsed.setText("Прошло: "+s.elapsedSeconds+" с");remaining.setText("Осталось: "+(s.remainingSeconds==null?"—":"≈ "+s.remainingSeconds+" с"));
        phase.setText(control.isCancelled()?"Отмена загрузки. Удаляем временные файлы…":phaseText);
    }
    void finish(){timer.stop();dispose();}
}
