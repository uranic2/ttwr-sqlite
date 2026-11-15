package ttr.UI;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.event.KeyEvent;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.net.URI;
import javax.swing.SwingWorker;
import javax.swing.JOptionPane;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** About dialog for the SQLite modification; the build supplies its version. */
public class AboutDlg extends JDialog {
    public AboutDlg(Frame parent, boolean modal) {
        super(parent, "О программе", modal);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImage(new javax.swing.ImageIcon(AboutDlg.class.getResource("/ttr/data/ttwr.png")).getImage());
        JPanel content = new JPanel(new BorderLayout(0, DialogStyle.size(16)));
        DialogStyle.contentBorder(content);
        JLabel title = new JLabel("TTWR SQLite — версия " + ttr.updates.AppVersion.current());
        DialogStyle.heading(title, 3);
        content.add(title, BorderLayout.NORTH);
        JPanel information = new JPanel(new BorderLayout(0, DialogStyle.size(16)));
        information.add(new JLabel("<html><div style='width:360px'>"
                + "Программа для проведения турниров по настольному теннису "
                + "и их обсчёта в TTW-Рейтинге.<br><br>"
                + "В SQLite-версии список игроков хранится в базе данных. "
                + "Поиск, фильтрация и выбор игроков выполняются по запросу, "
                + "без загрузки всего Players.xml в память при каждом старте.<br><br>"
                + "База и настройки находятся в папке data рядом с программой.<br><br>"
                + "</div></html>"), BorderLayout.CENTER);
        final String projectUrl="https://github.com/uranic2/ttwr-sqlite";
        JButton website=new JButton("<html><a href='"+projectUrl+"'>"+projectUrl+"</a></html>");
        website.setName("project-website-link");website.setHorizontalAlignment(JButton.LEFT);
        website.setContentAreaFilled(false);website.setBorderPainted(false);website.setBorder(BorderFactory.createEmptyBorder(2,0,2,0));
        website.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));website.setToolTipText("Открыть сайт программы в браузере");
        website.getAccessibleContext().setAccessibleName("Сайт программы: "+projectUrl);
        website.addActionListener(event->{
            website.setEnabled(false);
            new SwingWorker<Void,Void>(){
                protected Void doInBackground()throws Exception{
                    if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new java.io.IOException("Браузер по умолчанию недоступен");
                    Desktop.getDesktop().browse(URI.create(projectUrl));return null;
                }
                protected void done(){website.setEnabled(true);try{get();}catch(Exception error){if(isDisplayable())JOptionPane.showMessageDialog(AboutDlg.this,"Не удалось открыть браузер.\n"+projectUrl,"Сайт программы",JOptionPane.ERROR_MESSAGE);}}
            }.execute();
        });
        JPanel site=new JPanel(new BorderLayout(0,DialogStyle.size(8)));site.add(new JLabel("Сайт программы:"),BorderLayout.NORTH);site.add(website,BorderLayout.CENTER);
        JPanel linksAndPath = new JPanel(new BorderLayout(0, DialogStyle.size(16)));
        linksAndPath.add(site, BorderLayout.NORTH);
        JPanel launchPath = new JPanel(new BorderLayout(0, DialogStyle.size(8)));
        launchPath.add(new JLabel("Путь запуска программы:"), BorderLayout.NORTH);
        javax.swing.JTextArea path = new javax.swing.JTextArea(ttr.PortablePaths.home().resolve("ttwr-sqlite.exe").toAbsolutePath().normalize().toString(), 3, 40);
        path.setName("about-launch-path");
        path.setEditable(false);
        path.setLineWrap(true);
        path.setWrapStyleWord(false);
        path.setFont(site.getFont());
        path.setCaretPosition(0);
        path.getAccessibleContext().setAccessibleName("Полный путь запуска программы");
        launchPath.add(new javax.swing.JScrollPane(path), BorderLayout.CENTER);
        linksAndPath.add(launchPath, BorderLayout.CENTER);
        information.add(linksAndPath, BorderLayout.SOUTH);
        content.add(information, BorderLayout.CENTER);
        JButton close = new JButton("Закрыть");
        close.addActionListener(event -> dispose());
        close.setName("about-close");
        JPanel window = new JPanel(new BorderLayout());
        window.add(content, BorderLayout.CENTER);
        DialogStyle.fonts(content);
        window.add(DialogStyle.actions("about-actions", close), BorderLayout.SOUTH);
        setContentPane(window);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(close);
        pack();
        setResizable(false);
    }

    public boolean ShowDialog() {
        setLocationRelativeTo(getParent());
        setVisible(true);
        return true;
    }

}
