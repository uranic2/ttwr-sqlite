package ttr;

import java.awt.*;
import java.nio.file.*;
import java.util.ArrayList;
import javax.swing.*;
import ttr.UI.DBUpdateDlg;

/** Explicit consent, source choice, and first-run routing; original AppData is read-only. */
public final class ProfileImports {
    private ProfileImports(){}
    public static boolean firstRun(Path profile){
        String fromLauncher=System.getProperty("ttwr.firstRun");
        return fromLauncher==null?!Files.exists(profile):"true".equals(fromLauncher)&&!Files.exists(profile.resolve("ttwr.cfg"))&&!Files.exists(profile.resolve("DB"));
    }
    public static boolean shouldOffer(boolean first,Path source){return first&&source!=null&&Files.isRegularFile(source.resolve("db.xml"));}
    public static void offerFirstRun()throws Exception{
        offerFirstRun(PlayerProfileSource.defaultFolder());
    }
    public static void offerFirstRun(Path source)throws Exception{
        offerFirstRun(source,firstRun(PortablePaths.profile()));
    }
    public static void offerFirstRun(Path source,boolean first)throws Exception{
        if(!shouldOffer(first,source))return;
        final Path initial=source;
        SwingUtilities.invokeAndWait(()->{
            Path selected=initial;
            if(!consent(null,selected))return;
            for(;;){
                DBUpdateDlg dialog=new DBUpdateDlg(null,selected,true);
                if(dialog.ShowDialog())break;
                if(Files.exists(PortablePaths.profile().resolve("db-package.pending")))throw new IllegalStateException("Для восстановления базы перезапустите программу. Журнал: "+PortablePaths.profile().resolve("db-package.pending"));
                Object[] choices={"Повторить / выбрать папку","Продолжить с пустой базой"};
                if(JOptionPane.showOptionDialog(null,"Импорт не завершён. Повторить попытку?","Импорт TTWR",JOptionPane.DEFAULT_OPTION,JOptionPane.QUESTION_MESSAGE,null,choices,choices[0])!=0)break;
                selected=choose(null,selected);if(selected==null)break;
                if(!consent(null,selected))break;
            }
        });
    }
    public static void fromMenu(TTRClient client){
        if(!PlayerUpdates.acquire())return;
        try{
            Path source=choose(client,PlayerProfileSource.defaultFolder());if(source==null)return;
            if(!consent(client,source))return;
            DBMain original=client.GetDBMain();
            if(!original.tours.toursNew.isEmpty()){
                if(JOptionPane.showConfirmDialog(client,"Сохранить и закрыть открытые турниры перед импортом?","Импорт TTWR",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
                if(!client.AutoSaveTours()){JOptionPane.showMessageDialog(client,"Не удалось сохранить турниры. Сохраните и закройте их штатно, затем повторите импорт.");return;}
                for(Tournament tournament:new ArrayList<Tournament>(original.tours.toursNew))client.RemoveTournament(tournament);
                client.m_sett.tours.clear();
            }
            if(original.bChanged){
                if(JOptionPane.showConfirmDialog(client,"Сохранить изменения базы перед импортом?","Импорт TTWR",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION||!client.SaveDB())return;
            }
            DBUpdateDlg dialog=new DBUpdateDlg(client,source,false);dialog.mainDB=original;dialog.ShowDialog();
        }finally{PlayerUpdates.release();}
    }
    private static Path choose(Component owner,Path initial){
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Выберите папку DB оригинального TTWR");chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if(initial!=null)chooser.setSelectedFile(initial.toFile());
        return chooser.showOpenDialog(owner)==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile().toPath():null;
    }
    private static boolean consent(Component owner,Path source){
        return JOptionPane.showConfirmDialog(owner,"Закройте оригинальный TTWR перед импортом.\nИсточник: "+source+"\nНазначение: "+PortablePaths.profile().resolve("DB")+"\n\nБудут заменены игроки, шаблоны и регионы.\nНастройки и файлы турниров сохраняются.\nПеред заменой будет создана резервная копия.\n\nПродолжить?","Импорт профиля TTWR",JOptionPane.YES_NO_OPTION,JOptionPane.QUESTION_MESSAGE)==JOptionPane.YES_OPTION;
    }
}
