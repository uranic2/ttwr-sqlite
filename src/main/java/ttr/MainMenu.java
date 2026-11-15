package ttr;

import java.awt.Component;
import java.lang.reflect.Field;
import javax.swing.*;
import ttr.UI.DatabaseInfoDlg;

/** Reuses original actions, shortcuts and enabled state. */
public final class MainMenu {
    private MainMenu() {}
    private static JMenuItem item(TTRClient client,String name)throws Exception {
        Field field=TTRClient.class.getDeclaredField(name);field.setAccessible(true);
        return (JMenuItem)field.get(client);
    }
    public static void attach(TTRClient client) {
        try {
            JMenuBar bar=client.getJMenuBar();
            JMenu database=(JMenu)item(client,"jMenu1");
            database.setText("БД игроков");
            item(client,"jMenuDBUpdate").setText("Обновить БД игроков из интернет");
            JMenuItem backup=item(client,"jMenuBackupDB");
            if(backup.getParent()!=null)backup.getParent().remove(backup);
            JMenu settings=new JMenu("Настройка");settings.setName("settings-menu");
            JMenuItem general=item(client,"jMenuSettings"),page=item(client,"jMenuPageSetup");
            general.setText("Общие настройки");page.setText("Настройка страницы печати");
            settings.add(general);settings.add(page);
            int index=bar.getComponentIndex(item(client,"jMenu3"));
            bar.add(settings,index<0?bar.getMenuCount():index);
            database.addSeparator();
            JMenuItem info=new JMenuItem("Информация о БД игроков");info.setName("database-info");
            info.addActionListener(event->new DatabaseInfoDlg(client).setVisible(true));database.add(info);
            for(int i=0;i<bar.getMenuCount();i++)if(bar.getMenu(i)!=null)trim(bar.getMenu(i).getPopupMenu());
            bar.revalidate();bar.repaint();
        } catch(Exception error) {throw new IllegalStateException("Не удалось настроить главное меню",error);}
    }
    private static void trim(JPopupMenu popup) {
        boolean separator=true;
        for(Component component:popup.getComponents()) {
            boolean current=component instanceof JSeparator;
            if(current&&separator)popup.remove(component);else separator=current;
        }
        int count=popup.getComponentCount();
        if(count>0&&popup.getComponent(count-1) instanceof JSeparator)popup.remove(count-1);
    }
}
