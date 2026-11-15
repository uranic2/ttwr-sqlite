package ttr.UI;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.Locale;
import javax.swing.*;
import ttr.*;
import ttr.updates.DownloadStats;

/** The same progress and cancellation flow serves HTTPS updates and local profile imports. */
public class DBUpdateDlg extends JDialog {
    public DBMain mainDB;
    private final Path localSource;
    private final boolean firstRun;
    private final TTRClient client;
    private PlayersUpdateSession session;
    private boolean running,result;
    private long count;
    private final JLabel phase=new JLabel("Нажмите «Обновить», чтобы загрузить актуальный список игроков.");
    private final JProgressBar bar=new JProgressBar(0,1000);
    private final JLabel volume=new JLabel("Получено 0 КБ; общий объём неизвестен"),recent=new JLabel("—"),average=new JLabel("—");
    private final JLabel records=new JLabel("Прочитано записей: 0"),elapsed=new JLabel("Прошло: 0 с"),remaining=new JLabel("До конца чтения XML: —");
    private final JTextArea details=new JTextArea(3,54);
    private final JButton update=new JButton("Обновить"),cancel=new JButton("Закрыть");
    private final JCheckBox autoClose=new JCheckBox("Закрывать окно после успешного обновления");
    private final JCheckBox atStart=new JCheckBox("Проверять обновления при запуске");
    private final Timer timer=new Timer(1000,e->render());
    public DBUpdateDlg(Frame owner,boolean modal){this(owner,null,false);setModal(modal);}
    public DBUpdateDlg(Frame owner,Path source,boolean initial){
        super(owner,source==null?"Обновление базы данных игроков":"Импорт профиля TTWR",true);
        localSource=source;firstRun=initial;client=owner instanceof TTRClient?(TTRClient)owner:null;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);setResizable(false);
        JPanel content=new JPanel(new BorderLayout(0,DialogStyle.size(16)));DialogStyle.contentBorder(content);
        JPanel header=new JPanel(new BorderLayout(0,DialogStyle.size(8)));JLabel title=new JLabel(getTitle());DialogStyle.heading(title,3);
        header.add(title,BorderLayout.NORTH);header.add(phase,BorderLayout.SOUTH);content.add(header,BorderLayout.NORTH);
        JPanel body=new JPanel();body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
        phase.setName("players-phase");
        bar.setName("players-progress");bar.setStringPainted(true);bar.setAlignmentX(0);body.add(bar);body.add(Box.createVerticalStrut(DialogStyle.size(8)));
        volume.setName("players-volume");volume.setAlignmentX(0);volume.setMaximumSize(new Dimension(Integer.MAX_VALUE,volume.getPreferredSize().height));body.add(volume);body.add(Box.createVerticalStrut(DialogStyle.size(16)));
        JPanel speeds=new JPanel(new GridLayout(1,2,DialogStyle.size(24),0));speeds.setAlignmentX(0);
        speeds.add(metric("Скорость за последние 2 с",recent,"players-recent"));speeds.add(metric("Средняя скорость",average,"players-average"));body.add(speeds);body.add(Box.createVerticalStrut(DialogStyle.size(16)));
        records.setName("players-records");records.setAlignmentX(0);records.setMaximumSize(new Dimension(Integer.MAX_VALUE,records.getPreferredSize().height));body.add(records);body.add(Box.createVerticalStrut(DialogStyle.size(8)));
        JPanel times=new JPanel(new GridLayout(1,2,DialogStyle.size(24),0));times.setAlignmentX(0);elapsed.setName("players-elapsed");remaining.setName("players-remaining");times.add(elapsed);times.add(remaining);body.add(times);body.add(Box.createVerticalStrut(DialogStyle.size(16)));
        details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);details.setOpaque(false);details.setFont(phase.getFont());details.setAlignmentX(0);details.setName("players-details");JScrollPane info=new JScrollPane(details);info.setAlignmentX(0);body.add(info);
        autoClose.setAlignmentX(0);atStart.setAlignmentX(0);body.add(autoClose);body.add(atStart);
        if(client!=null){autoClose.setSelected(client.m_sett.bCloseUpdWnd);atStart.setSelected(client.m_sett.bUpdateAtStart);}
        autoClose.addActionListener(e->{if(client!=null)client.m_sett.bCloseUpdWnd=autoClose.isSelected();});
        atStart.addActionListener(e->{if(client!=null)client.m_sett.bUpdateAtStart=atStart.isSelected();});
        if(localSource!=null){autoClose.setVisible(false);atStart.setVisible(false);details.setText("Источник: "+localSource);}
        content.add(body,BorderLayout.CENTER);
        update.setName("players-update");cancel.setName("players-cancel");
        DialogStyle.fonts(content);
        JPanel window=new JPanel(new BorderLayout());window.add(content,BorderLayout.CENTER);
        window.add(DialogStyle.actions("players-actions",update,cancel),BorderLayout.SOUTH);
        setContentPane(window);getRootPane().setDefaultButton(update);
        update.addActionListener(e->start());cancel.addActionListener(e->requestClose());
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){requestClose();}});
        getRootPane().registerKeyboardAction(e->requestClose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        volume.setMaximumSize(new Dimension(Integer.MAX_VALUE,volume.getPreferredSize().height));
        records.setMaximumSize(new Dimension(Integer.MAX_VALUE,records.getPreferredSize().height));
        pack();setLocationRelativeTo(owner);
    }
    private JPanel metric(String text,JLabel value,String name){
        JPanel p=new JPanel(new BorderLayout(0,DialogStyle.size(8)));p.add(new JLabel(text),BorderLayout.NORTH);value.setName(name);DialogStyle.heading(value,2);p.add(value,BorderLayout.CENTER);return p;
    }
    public boolean ShowDialog(){
        if(localSource!=null)SwingUtilities.invokeLater(this::start);
        setVisible(true);return result;
    }
    private void start(){
        if(running||result)return;
        running=true;getRootPane().setDefaultButton(null);session=new PlayersUpdateSession(mainDB);update.setEnabled(false);cancel.setText("Отмена");cancel.setEnabled(true);
        autoClose.setEnabled(false);atStart.setEnabled(false);details.setText(localSource==null?"Скорость отражает чтение XML, его разбор и запись SQLite.":"Источник: "+localSource);
        timer.start();render();
        new SwingWorker<Void,Void>(){
            protected Void doInBackground()throws Exception{
                Throwable failure=null;
                try{
                    session.prepare(localSource==null?PlayerUpdates.source():null,localSource);
                    if(localSource!=null&&!firstRun){
                        final boolean[] approved={false};
                        SwingUtilities.invokeAndWait(()->approved[0]=JOptionPane.showConfirmDialog(DBUpdateDlg.this,"Профиль проверен. Прочитано записей: "+session.progress.records()+".\nИсточник: "+localSource+"\nНазначение: "+PortablePaths.profile().resolve("DB")+"\nЗаменить игроков, шаблоны и регионы?","Применение импорта",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION);
                        if(!approved[0])session.control.cancel();
                    }
                    session.apply();
                    count=session.candidate.players.players.size();
                    if(client==null)session.closePreparedCatalog();
                    if(client!=null)SwingUtilities.invokeAndWait(()->{
                        try{PlayerUpdates.activate(client,session.candidate);mainDB=session.candidate;}catch(Exception e){throw new IllegalStateException(e);}
                    });
                }catch(Throwable e){failure=e;throw e;}
                finally{
                    try{session.close();}catch(Exception cleanup){if(failure!=null){cleanup.addSuppressed(failure);}throw cleanup;}
                }
                return null;
            }
            protected void done(){
                try{
                    get();
                    if(client!=null){phase.setText("Обновление списка в интерфейсе…");PlayerUpdates.refresh(client,e->{DatabaseStatus.afterApply(client,session.publishedDate);complete(e);});}
                    else complete(null);
                }catch(Exception e){failed(e);}
            }
        }.execute();
    }
    private void complete(Exception error){
        timer.stop();session.progress.finish();render();running=false;result=error==null;
        bar.setIndeterminate(false);bar.setValue(1000);bar.setString(error==null?"Готово":"—");
        phase.setText(error==null?"База обновлена. Игроков: "+count:"База применена, но список не обновлён");
        details.setText((error==null?"":"Перезапустите программу. "+error+"\n")+(session.backup==null?"":"Резервная копия: "+session.backup));
        cancel.setEnabled(true);cancel.setText("Закрыть");getRootPane().setDefaultButton(cancel);update.setEnabled(false);autoClose.setEnabled(true);atStart.setEnabled(true);
        if(error==null&&(firstRun||localSource==null&&autoClose.isSelected()))dispose();
    }
    private void failed(Exception error){
        timer.stop();session.progress.finish();render();running=false;bar.setIndeterminate(false);bar.setString("—");
        Throwable cause=error;while(cause instanceof java.util.concurrent.ExecutionException&&cause.getCause()!=null)cause=cause.getCause();
        boolean cancelled=session.control.isCancelled();
        phase.setText(cancelled?"Обновление отменено":"Не удалось обновить базу");
        details.setText(message(cause));
        if(session.control.error()!=null&&!session.control.isCancelled())details.setText(explanation(session.control.error())+"\n"+details.getText());cancel.setEnabled(true);cancel.setText("Закрыть");update.setText("Повторить");getRootPane().setDefaultButton(update);update.setEnabled(true);
        autoClose.setEnabled(true);atStart.setEnabled(true);
        if(session.recoveryRequired()){phase.setText("Требуется восстановление при следующем запуске");update.setEnabled(false);cancel.setText("Закрыть программу");getRootPane().setDefaultButton(cancel);}
    }
    private static String explanation(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof java.net.SocketTimeoutException)return "Истекло время ожидания ответа сервера.";
            if(e instanceof org.xml.sax.SAXException)return "Получен повреждённый XML.";
            if(e instanceof java.sql.SQLException)return "Ошибка записи или проверки SQLite.";
            if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "Сервер базы игроков недоступен.";
        }
        return "Не удалось прочитать или записать файлы базы.";
    }
    private static String message(Throwable error){
        StringBuilder text=new StringBuilder();
        for(Throwable e=error;e!=null;e=e.getCause()){if(text.length()>0)text.append("\n");text.append(e.getMessage()==null?e.toString():e.getMessage());}
        for(Throwable e:error.getSuppressed())text.append("\n").append(e);
        return text.toString();
    }
    private void requestClose(){
        if(!running){if(session!=null&&session.recoveryRequired()&&client!=null){client.dispose();System.exit(1);}dispose();return;}
        if(session.control.cancel()){phase.setText("Отмена обновления…");cancel.setEnabled(false);}
    }
    private void render(){
        if(session==null)return;
        PlayersProgress p=session.progress;DownloadStats.Snapshot s=p.snapshot();
        boolean reading=p.reading(),known=s!=null&&s.total>0;
        phase.setText(session.control.isCancelled()?"Отмена обновления…":p.phase());
        if(running&&!session.control.canCancel()){cancel.setEnabled(false);if(!session.control.isCancelled())phase.setText("Завершаем обновление… "+p.phase());}
        bar.setIndeterminate(running&&(!reading||!known));
        int percent=known?(int)Math.min(1000,s.bytes*1000.0/s.total):0;bar.setValue(percent);bar.setString(reading&&known?String.format(Locale.ROOT,"%.1f%%",percent/10.0):"—");
        volume.setText(s==null?"Получено 0 КБ; общий объём неизвестен":known?String.format(new Locale("ru","RU"),"Получено %,.1f / %,.1f КБ",s.bytes/1024.0,s.total/1024.0):String.format(new Locale("ru","RU"),"Получено %,.1f КБ; общий объём неизвестен",s.bytes/1024.0));
        recent.setText(reading&&s!=null&&s.bytes>0?speed(s.recentBytesPerSecond):"—");
        average.setText(s!=null&&s.bytes>0?speed(s.averageBytesPerSecond):"—");
        records.setText("Прочитано записей: "+p.records());elapsed.setText("Прошло: "+p.elapsed()+" с");
        remaining.setText("До конца чтения XML: "+(reading&&known&&s.bytes>0&&s.remainingSeconds!=null?s.remainingSeconds+" с":"—"));
    }
    private static String speed(double value){return String.format(new Locale("ru","RU"),"%,.1f КБ/с",value/1024.0);}
}
