package ttr;

import java.lang.reflect.Field;
import java.nio.file.*;
import javax.swing.*;
import ttr.UI.DBUpdateDlg;

/** Entry point used by the transformed TTRClient.UpdateDB. */
public final class PlayerUpdates {
    private PlayerUpdates(){}
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean();
    public static boolean acquire(){return BUSY.compareAndSet(false,true);}
    public static void release(){BUSY.set(false);}
    public static void attach(TTRClient client){
        MainMenu.attach(client);
        try{
            Field field=TTRClient.class.getDeclaredField("jMenuDBUpdate");field.setAccessible(true);
            JMenuItem update=(JMenuItem)field.get(client);JPopupMenu parent=(JPopupMenu)update.getParent();
            JMenuItem item=new JMenuItem("Импорт профиля TTWR…");item.setName("import-ttwr-profile");item.addActionListener(e->ProfileImports.fromMenu(client));parent.add(item);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static boolean update(TTRClient client,boolean required){
        if(!acquire())return false;
        try{
        DBMain original=client.GetDBMain();
        if(!original.tours.toursNew.isEmpty()){
            JOptionPane.showMessageDialog(client,"Перед обновлением базы необходимо обсчитать новые турниры.");
            return false;
        }
        if(original.bChanged){
            int answer=JOptionPane.showConfirmDialog(client,"Перед обновлением сохранить остальные изменения базы?","Обновление базы",JOptionPane.YES_NO_CANCEL_OPTION);
            if(answer==JOptionPane.CANCEL_OPTION||answer==JOptionPane.CLOSED_OPTION)return false;
            if(answer==JOptionPane.YES_OPTION&&!client.SaveDB())return false;
        }
        DBUpdateDlg dialog=new DBUpdateDlg(client,true);
        dialog.mainDB=original;
        return dialog.ShowDialog();
        }finally{release();}
    }
    public static void activate(TTRClient client,DBMain candidate)throws Exception{
        Field field=TTRClient.class.getDeclaredField("mainDB");field.setAccessible(true);field.set(client,candidate);
    }
    public static void refresh(TTRClient client,java.util.function.Consumer<Exception> completed)throws Exception{
        Field field=TTRClient.class.getDeclaredField("playersList");field.setAccessible(true);
        ttr.UI.PlayersListPanel panel=(ttr.UI.PlayersListPanel)field.get(client);
        Field rating=TTRClient.class.getDeclaredField("ratingLists");rating.setAccessible(true);
        ttr.UI.RListView view=(ttr.UI.RListView)rating.get(client);
        if(view!=null){ttr.UI.RListView.bUpdate=true;view.UpdateView();}
        TTRClient.ClearChangedFiles("","");
        if(client.playersDlg!=null)client.playersDlg.OnPListChanged();
        panel.RefreshAfterUpdate(completed);
    }
    public static Path destination(TTRClient client,DBMain original){
        String path=original.GetDirPath()+original.GetFileName();
        if(original.GetFileName().isEmpty())path=client.m_sett.DBPath;
        if(path==null||path.isEmpty())throw new IllegalStateException("Не задан путь к рабочей базе");
        return Paths.get(path);
    }
    public static String source(){
        return Utility.EncryptString("19d084516ed114117c31ffa42d2540d1270104e1d240ee1433e127a18da5e0a1273057c1c9c5d1b0509474203c8095c5",false)+"db.xml";
    }
}
