package ttr.UI;

import ttr.*;
import ttr.events.TabPanelEvents;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

/** SQL-filtered, SQL-sorted player pages. Retains at most one displayed page. */
public class PlayersListPanel extends JPanel implements TabPanelEvents {
    public Player[] players=new Player[0];
    public boolean bHideStats,bDisableBtns,bChanged;
    private boolean selected,notify=true;
    private AbstractAction action;
    private Calendar date;
    private final JTable table=new JTable();
    private final JTextField search=new JTextField(22);
    private final JComboBox<String> region=new JComboBox<String>(),town=new JComboBox<String>();
    private final JLabel status=new JLabel(" ");
    private final JButton add=new JButton("Добавить"),edit=new JButton("Профиль"),remove=new JButton("Удалить");
    private final JButton previous=new JButton("← Назад"),next=new JButton("Далее →");
    private int offset,total,sortColumn=1;
    private boolean descending;
    private long request;
    private static final int PAGE_SIZE=100;
    private final javax.swing.Timer debounce;
    private SwingWorker<Page,Void> worker;
    private final DefaultTableModel model=new DefaultTableModel(new String[]{"Место","Фамилия Имя Отчество","Регион / Город","Год рождения","Рейтинг","Турниры","Статистика"},0){
        public boolean isCellEditable(int r,int c){return false;}
        public Class<?> getColumnClass(int c){return c==0||c==5?Integer.class:c==4?Double.class:String.class;}
    };
    private static final class Page{Player[] players,display;int total,offset;}
    public PlayersListPanel(boolean hide){
        bHideStats=hide;setLayout(new BorderLayout(6,6));
        JPanel filters=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
        filters.add(add);filters.add(edit);filters.add(remove);filters.add(new JLabel("Поиск:"));filters.add(search);
        region.setEditable(true);town.setEditable(true);region.setPrototypeDisplayValue("Московская область");town.setPrototypeDisplayValue("Санкт-Петербург");
        filters.add(new JLabel("Регион:"));filters.add(region);filters.add(new JLabel("Город:"));filters.add(town);add(filters,BorderLayout.NORTH);
        table.setModel(model);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        if(hide){table.removeColumn(table.getColumnModel().getColumn(6));table.removeColumn(table.getColumnModel().getColumn(5));}
        table.setDefaultRenderer(Double.class,new DefaultTableCellRenderer(){
            public Component getTableCellRendererComponent(JTable t,Object value,boolean sel,boolean focus,int row,int col){
                return super.getTableCellRendererComponent(t,value==null?"":Utility.DoubleToString("0.00",((Number)value).doubleValue()),sel,focus,row,col);
            }
        });
        add(new JScrollPane(table),BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout());footer.add(status,BorderLayout.CENTER);JPanel pages=new JPanel(new FlowLayout(FlowLayout.RIGHT));pages.add(previous);pages.add(next);footer.add(pages,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
        debounce=new javax.swing.Timer(180,e->{offset=0;loadPage();});debounce.setRepeats(false);
        DocumentListener listener=new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}};
        search.getDocument().addDocumentListener(listener);((JTextField)region.getEditor().getEditorComponent()).getDocument().addDocumentListener(listener);((JTextField)town.getEditor().getEditorComponent()).getDocument().addDocumentListener(listener);
        table.getTableHeader().addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int c=table.columnAtPoint(e.getPoint());if(c<0)return;c=table.convertColumnIndexToModel(c);if(c==sortColumn)descending=!descending;else{sortColumn=c;descending=false;}sortHeaders();offset=0;loadPage();}});
        table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2){e.consume();choose(e.getModifiers());}}});
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"choose-player");table.getActionMap().put("choose-player",new AbstractAction(){public void actionPerformed(ActionEvent e){choose(0);}});
        search.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN,0),"first-player");search.getActionMap().put("first-player",new AbstractAction(){public void actionPerformed(ActionEvent e){if(model.getRowCount()>0){table.setRowSelectionInterval(0,0);table.requestFocusInWindow();}}});
        table.getSelectionModel().addListSelectionListener(e->buttons());
        add.addActionListener(e->addPlayer());edit.addActionListener(e->choose(0));remove.addActionListener(e->removePlayer());
        previous.addActionListener(e->{offset=Math.max(0,offset-PAGE_SIZE);loadPage();});next.addActionListener(e->{if(offset+PAGE_SIZE<total){offset+=PAGE_SIZE;loadPage();}});
        UpdateFont();sortHeaders();buttons();
    }
    private void changed(){if(notify)debounce.restart();}
    public void SetDblClickAction(AbstractAction value){action=value;edit.setText(value==null?"Профиль":"Выбрать");remove.setVisible(value==null);}
    private void sortHeaders(){for(int i=0;i<table.getColumnModel().getColumnCount();i++){TableColumn c=table.getColumnModel().getColumn(i);int m=c.getModelIndex();c.setHeaderValue(model.getColumnName(m)+(m==sortColumn?(descending?" ↓":" ↑"):""));}table.getTableHeader().repaint();}
    public void SetDate(Calendar value,boolean lock){date=value==null?null:(Calendar)value.clone();offset=0;UpdateTable(date);UpdateFilterCB();}
    public void UpdateTable(Calendar value){date=value==null?null:(Calendar)value.clone();loadPage();}
    private void loadPage(){loadPage(null);}
    public void RefreshAfterUpdate(java.util.function.Consumer<Exception> completed){offset=0;UpdateFilterCB();loadPage(completed);}
    private void loadPage(final java.util.function.Consumer<Exception> completed){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::loadPage);return;}
        if(TTRClient.getInstance()==null)return;
        final DBPlayers db=TTRClient.getInstance().GetDBPlayers();
        final String name=search.getText().trim(),reg=((JTextField)region.getEditor().getEditorComponent()).getText().trim(),city=((JTextField)town.getEditor().getEditorComponent()).getText().trim();
        final int start=offset,column=sortColumn;final boolean reverse=descending;final Calendar selectedDate=date==null?null:(Calendar)date.clone();
        final long generation=++request;if(worker!=null)worker.cancel(false);status.setText("Загрузка игроков…");
        worker=new SwingWorker<Page,Void>(){
            protected Page doInBackground(){Page p=new Page();p.total=db.store().count(name,reg,city);p.offset=p.total==0?0:Math.min(start,((p.total-1)/PAGE_SIZE)*PAGE_SIZE);p.players=db.QueryPage(selectedDate,name,reg,city,column,reverse,p.offset,PAGE_SIZE);p.display=new Player[p.players.length];for(int i=0;i<p.players.length;i++){Player historical=selectedDate==null?null:db.GetPlayer(p.players[i].GetID(),selectedDate);p.display[i]=historical==null?p.players[i]:historical;}return p;}
            protected void done(){if(isCancelled()||generation!=request)return;try{Page p=get();players=p.players;total=p.total;offset=p.offset;model.setRowCount(0);for(int i=0;i<players.length;i++){Player base=players[i],display=p.display[i];int games=display.stats.wins+display.stats.loses;String stats=games+", "+display.stats.wins+"-"+display.stats.loses+", "+(games==0?0:Math.round(100.0*display.stats.wins/games))+"%";model.addRow(new Object[]{base.Place,base.profile.Name,base.GetLocation(),base.profile.BirthDate,display.Rating,display.stats.tours,stats});}status.setText(total==0?"Игроки не найдены":"Игроки "+(offset+1)+"–"+(offset+players.length)+" из "+total);bChanged=false;buttons();if(completed!=null)completed.accept(null);}catch(Exception e){status.setText("Не удалось прочитать игроков из БД");status.setToolTipText(e.toString());if(completed!=null)completed.accept(e);}}
        };worker.execute();
    }
    public void UpdateFilterCB(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::UpdateFilterCB);return;}
        notify=false;try{String r=((JTextField)region.getEditor().getEditorComponent()).getText(),t=((JTextField)town.getEditor().getEditorComponent()).getText();region.setModel(new DefaultComboBoxModel<String>(Player.regionList2.toArray(new String[0])));region.insertItemAt("",0);region.setSelectedItem(r);town.setModel(new DefaultComboBoxModel<String>(Player.townList.toArray(new String[0])));town.insertItemAt("",0);town.setSelectedItem(t);}finally{notify=true;}
    }
    public void UpdateFont(){UIMan.ScaleTableFont(table);}
    private Player selectedPlayer(){int row=table.getSelectedRow();return row>=0&&row<players.length?players[row]:null;}
    private void buttons(){boolean chosen=selectedPlayer()!=null;add.setEnabled(!bDisableBtns);remove.setEnabled(chosen&&!bDisableBtns);edit.setEnabled(chosen);previous.setEnabled(offset>0);next.setEnabled(offset+PAGE_SIZE<total);}
    private void choose(int modifiers){Player p=selectedPlayer();if(p==null)return;if(action!=null){action.actionPerformed(new ActionEvent(p,0,"",modifiers));return;}EditPlayerDlg dialog=new EditPlayerDlg((Frame)TTRClient.getInstance(),true);dialog.player=p;if(dialog.ShowDialog()){TTRClient.getInstance().GetDBPlayers().UpdatePlayerLocationList(true);TTRClient.getInstance().GetDBMain().bChanged=true;TTRClient.getInstance().UpdatePlayersList();UpdateFilterCB();loadPage();}}
    private void addPlayer(){EditPlayerDlg dialog=new EditPlayerDlg((Frame)TTRClient.getInstance(),true);if(!search.getText().trim().isEmpty()){dialog.player=new Player();dialog.player.profile.bNew=true;dialog.player.profile.Name=search.getText().trim();dialog.player.profile.ShortName=Player.MakeShortName(dialog.player.profile.Name);}if(dialog.ShowDialog()){if(!TTRClient.getInstance().GetDBPlayers().AddPlayer(dialog.player)){JOptionPane.showMessageDialog(this,"Не удалось добавить игрока.");return;}TTRClient.getInstance().GetDBPlayers().UpdatePlayerLocationList(true);TTRClient.getInstance().UpdatePlayersList();UpdateFilterCB();loadPage();}}
    private void removePlayer(){Player p=selectedPlayer();if(p==null)return;int count=0;for(RatingList r:TTRClient.getInstance().GetDBTours().RLists){Player q=r.GetPlayer(p.GetID());if(q!=null)count+=q.stats2.tours;}for(Tournament t:TTRClient.getInstance().GetDBTours().toursNew)if(t.IsPlayerExist(p.GetID()))count++;String text="Удалить игрока «"+p.profile.Name+"»?"+(count>0?"\nИгрок встречается в турнирах: "+count:"");if(JOptionPane.showConfirmDialog(this,text,"Удаление игрока",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;TTRClient.getInstance().GetDBPlayers().RemovePlayer(p);TTRClient.getInstance().UpdatePlayersList();loadPage();}
    public void OnSetActive(){selected=true;if(bChanged||players.length==0){loadPage();UpdateFilterCB();}search.requestFocusInWindow();search.selectAll();}
    public void OnKillActive(){selected=false;}
    public void UpdateView(){offset=0;loadPage();UpdateFilterCB();}
    public void OnPListChanged(){bChanged=true;if(selected){loadPage();UpdateFilterCB();}}
}
