package ttr;
import java.util.*;
import org.w3c.dom.*;
import ttr.events.XmlLoadEvents;

/** ABI-compatible catalog backed exclusively by SQLite. */
public class DBPlayers {
    private final DBMain mainDB;
    public Map<Integer,Player> players;
    public String file="Players.sqlite",fileRegions="Regions.xml";
    private SqlitePlayersStore repository;
    public DBPlayers(DBMain main){mainDB=main;players=new SqlitePlayerMap(this);}
    public synchronized SqlitePlayersStore store(){try{if(repository==null)repository=new SqlitePlayersStore(null);return repository;}catch(Exception e){throw SqlitePlayersStore.failure(e);}}
    synchronized SqlitePlayersStore existing(){return repository;}
    synchronized void useWithoutClosing(SqlitePlayersStore value){repository=value;}
    synchronized void use(SqlitePlayersStore value){if(repository!=null&&repository!=value)repository.close();repository=value;}
    public synchronized void switchPath(java.nio.file.Path path)throws Exception{
        path=path.toAbsolutePath().normalize();SqlitePlayersStore old=store();if(path.equals(old.path))return;
        SqlitePlayersStore next=new SqlitePlayersStore(path);next.adoptLiveObjects(old);repository=next;old.close();
    }
    public void UpdatePlayerLocationList(boolean clear){
        if(clear){Player.countryList.clear();Player.regionList2.clear();Player.townList.clear();}
        merge(Player.countryList,store().distinct("country"));merge(Player.regionList2,store().distinct("region"));merge(Player.townList,store().distinct("town"));
    }
    private static void merge(ArrayList<String> target,List<String> values){Set<String> seen=new HashSet<String>(target);for(String v:values)if(seen.add(v))target.add(v);Collections.sort(target,String.CASE_INSENSITIVE_ORDER);}
    public boolean LoadRegionList(String name){Player.regionList.clear();try{Document d=PlayersUpdateControl.document(name,null);if(d==null)return false;NodeList nodes=d.getDocumentElement().getElementsByTagName("region");for(int i=0;i<nodes.getLength();i++){Node node=nodes.item(i).getFirstChild();String value=node==null?"":node.getNodeValue();if(value!=null&&!value.isEmpty()&&!Player.regionList.contains(value))Player.regionList.add(value);}return true;}catch(Exception e){return false;}}
    public boolean SaveRegionList(String name){try{Document d=Utility.CreateXMLDocument();Element root=d.createElement("Regions");d.appendChild(root);for(String value:Player.regionList)Utility.putXMLTagValue(d,root,"region",value);return Utility.SaveXMLDocument(name,d);}catch(Exception e){return false;}}
    public String GetFileName(){return file;}
    public Player[] GetArray(){return players.values().toArray(new Player[0]);}
    public Player GetPlayer(int id){return store().get(id);}
    public Player GetPlayer(int id,Calendar date){
        DBTours tours=mainDB.tours;if(tours.RLists.isEmpty())return GetPlayer(id);
        int index=tours.RLists.size()-1;while(index>=0&&tours.RLists.get(index).CheckTourDate(date)>0)index--;
        if(index<0)index=0;
        for(;index>=0;index--){RatingList list=tours.RLists.get(index);String scope=store().snapshot(list);Player p=store().get(id,scope);if(p!=null)return list.CheckTourDate(date)<0&&list.bFixed?new Player(p):p;}
        return GetPlayer(id);
    }
    public Player[] FindSimilarPlayers(Player player){return store().similar(player);}
    public Player[] QueryPage(Calendar date,String name,String region,String town,int column,boolean descending,int offset,int limit){
        if(date==null||column<4||column>6||mainDB.tours.RLists.isEmpty())return store().query(name,region,town,column,descending,offset,limit);
        int index=mainDB.tours.RLists.size()-1;while(index>=0&&mainDB.tours.RLists.get(index).CheckTourDate(date)>0)index--;if(index<0)index=0;
        ArrayList<String> histories=new ArrayList<String>();ArrayList<Boolean> copies=new ArrayList<Boolean>();
        for(;index>=0;index--){RatingList r=mainDB.tours.RLists.get(index);histories.add(store().snapshot(r));copies.add(r.CheckTourDate(date)<0&&r.bFixed);}
        return store().query(name,region,town,column,descending,offset,limit,histories,copies);
    }
    public void AssignPlacesToPlayers(){store().assignPlaces();}
    public boolean AddPlayer(Player p){if(p==null||p.GetID()>0&&players.containsKey(p.GetID()))return false;if(p.GetID()<=0)p.profile.ID=mainDB.GetFreeID(0);players.put(p.GetID(),p);mainDB.bChanged=true;
        if(!mainDB.tours.RLists.isEmpty()&&!mainDB.tours.RLists.get(0).players.containsKey(p.GetID())){mainDB.tours.RLists.get(0).players.put(p.GetID(),new Player(p));mainDB.tours.RLists.get(0).bChanged=true;SqliteMutations.historyChanged(mainDB.tours.RLists.get(0));}return true;}
    public boolean RemovePlayer(Player p){if(p==null||!players.containsKey(p.GetID()))return false;players.remove(p.GetID());if(!mainDB.tours.RLists.isEmpty()){mainDB.tours.RLists.get(0).players.remove(p.GetID());mainDB.tours.RLists.get(0).bChanged=true;SqliteMutations.historyChanged(mainDB.tours.RLists.get(0));}mainDB.bChanged=true;return true;}
    public boolean LoadXML(String source,XmlLoadEvents callback){return SqlitePlayersIntegration.load(this,mainDB,source,callback);}
    public boolean SaveXML(String target){return SqlitePlayersIntegration.save(this,target);}
}
