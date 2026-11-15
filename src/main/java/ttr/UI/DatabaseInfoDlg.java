package ttr.UI;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.text.*;
import java.util.*;
import javax.swing.*;
import ttr.*;

/** Read-only database information; no checkpoint or network access. */
public final class DatabaseInfoDlg extends JDialog {
    private final JTextArea path=value("database-path");
    private final JTextArea size=value("database-size");
    private final JTextArea count=value("database-count");
    private final JTextArea updated=value("database-updated");
    public DatabaseInfoDlg(TTRClient owner) {
        super(owner,"Информация о БД игроков",true);
        setName("database-info-dialog");setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content=new JPanel(new GridBagLayout());DialogStyle.contentBorder(content);
        row(content,0,"Расположение:",path);
        row(content,1,"Размер SQLite-файла:",size);
        row(content,2,"Количество игроков:",count);
        row(content,3,"Обновлена из интернета:",updated);
        setLayout(new BorderLayout());add(content,BorderLayout.CENTER);
        JButton close=new JButton("Закрыть");close.setName("database-info-close");
        close.addActionListener(event->dispose());
        add(DialogStyle.actions("database-info-actions",close),BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(event->dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
        DialogStyle.fonts(this);
        setSize(DialogStyle.size(700),DialogStyle.size(290));
        setMinimumSize(new Dimension(DialogStyle.size(520),DialogStyle.size(260)));
        setLocationRelativeTo(owner);
        final SqlitePlayersStore store=owner.GetDBPlayers().store();
        new SwingWorker<String[],Void>() {
            protected String[] doInBackground()throws Exception {
                Path file=store.path;
                return new String[]{file==null?"В памяти":file.toString(),
                    file==null?"—":kilobytes(Files.size(file)),
                    NumberFormat.getIntegerInstance(new Locale("ru","RU")).format(store.size()),
                    updateTime(store.lastInternetUpdate())};
            }
            protected void done() {
                if(!isDisplayable())return;
                try {
                    String[] values=get();path.setText(values[0]);size.setText(values[1]);count.setText(values[2]);updated.setText(values[3]);
                } catch(Exception error) {
                    path.setText(store.path==null?"В памяти":store.path.toString());
                    size.setText("Не удалось прочитать сведения о базе");
                    count.setText("—");updated.setText("—");
                    Throwable cause=error.getCause()==null?error:error.getCause();
                    size.setToolTipText(cause.toString());
                }
            }
        }.execute();
    }
    public static String kilobytes(long bytes) {
        NumberFormat format=NumberFormat.getNumberInstance(new Locale("ru","RU"));
        format.setMinimumFractionDigits(2);format.setMaximumFractionDigits(2);
        return format.format(bytes/1024.0)+" Кбайт";
    }
    public static String updateTime(long time) {
        return time<=0?"Нет сведений":new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(time));
    }
    private static JTextArea value(String name) {
        JTextArea field=new JTextArea("Загрузка…");field.setName(name);
        field.setEditable(false);field.setLineWrap(true);field.setWrapStyleWord(true);field.setOpaque(false);
        return field;
    }
    private static void row(JPanel panel,int index,String text,JTextArea field) {
        GridBagConstraints c=new GridBagConstraints();c.gridy=index;c.anchor=GridBagConstraints.NORTHEAST;
        c.insets=new Insets(DialogStyle.size(6),0,DialogStyle.size(6),DialogStyle.size(12));
        JLabel label=new JLabel(text);label.setLabelFor(field);panel.add(label,c);
        c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.NORTHWEST;
        c.insets=new Insets(DialogStyle.size(6),0,DialogStyle.size(6),0);panel.add(field,c);
        field.getAccessibleContext().setAccessibleName(text);
    }
}
