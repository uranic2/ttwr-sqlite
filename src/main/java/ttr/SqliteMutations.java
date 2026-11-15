package ttr;
import java.util.*;

/** Tracks legacy public-field assignments without retaining the complete player catalog. */
public final class SqliteMutations {
    private SqliteMutations(){}
    private static final WeakHashMap<Object,List<Binding>> objects=new WeakHashMap<Object,List<Binding>>();
    private static final WeakHashMap<SqlitePlayersStore,Boolean> stores=new WeakHashMap<SqlitePlayersStore,Boolean>();
    private static final ThreadLocal<Batch> batches=new ThreadLocal<Batch>(){protected Batch initialValue(){return new Batch();}};
    private static final class Batch{int depth;boolean failed;final LinkedHashSet<SqlitePlayersStore> touched=new LinkedHashSet<SqlitePlayersStore>();}
    private static final class Binding{final SqlitePlayersStore store;final String scope,kind;int id;Binding(SqlitePlayersStore s,String scope,int id,String kind){store=s;this.scope=scope;this.id=id;this.kind=kind;}}
    static synchronized void track(SqlitePlayersStore store,String scope,Player p){
        stores.put(store,true);bind(p,new Binding(store,scope,p.GetID(),"player"));bind(p.profile,new Binding(store,scope,p.GetID(),"profile"));
        bind(p.stats,new Binding(store,scope,p.GetID(),"stats"));bind(p.stats2,new Binding(store,scope,p.GetID(),"stats2"));
    }
    private static void bind(Object object,Binding b){
        if(object==null)return;List<Binding> list=objects.get(object);if(list==null){list=new ArrayList<Binding>();objects.put(object,list);}
        for(Binding old:list)if(old.store==b.store&&old.scope.equals(b.scope)&&old.id==b.id&&old.kind.equals(b.kind))return;
        list.add(b);
    }
    private static synchronized void replaceComponent(Binding owner,Object replacement,String kind){
        for(Map.Entry<Object,List<Binding>> entry:objects.entrySet()){
            if(entry.getKey()==replacement)continue;
            for(Iterator<Binding> i=entry.getValue().iterator();i.hasNext();){Binding b=i.next();if(b.store==owner.store&&b.scope.equals(owner.scope)&&b.id==owner.id&&b.kind.equals(kind))i.remove();}
        }
    }
    static synchronized void detach(SqlitePlayersStore store,String scope,Integer id){
        for(List<Binding> list:objects.values())for(Iterator<Binding> i=list.iterator();i.hasNext();){Binding b=i.next();if(b.store==store&&(scope==null||scope.equals(b.scope))&&(id==null||id==b.id))i.remove();}
    }
    static synchronized void rekey(SqlitePlayersStore store,String scope,int oldId,int newId){for(List<Binding> list:objects.values())for(Binding b:list)if(b.store==store&&b.scope.equals(scope)&&b.id==oldId)b.id=newId;}
    public static void beginBatch(){Batch b=batches.get();if(b.depth++==0)b.failed=false;}
    static void enroll(SqlitePlayersStore store)throws Exception{
        Batch b=batches.get();if(b.depth>0&&!b.touched.contains(store)&&store.connection.getAutoCommit()){store.begin();b.touched.add(store);}
    }
    public static void endBatch(boolean success){
        Batch b=batches.get();if(b.depth<=0)return;b.failed|=!success;if(--b.depth!=0)return;
        try{for(SqlitePlayersStore store:b.touched)store.finish(!b.failed);}catch(Exception error){throw SqlitePlayersStore.failure(error);}finally{b.touched.clear();b.failed=false;}
    }
    public static void historyChanged(RatingList list){List<SqlitePlayersStore> copy;synchronized(SqliteMutations.class){copy=new ArrayList<SqlitePlayersStore>(stores.keySet());}for(SqlitePlayersStore store:copy)store.invalidateHistory(list);}
    public static void changed(Object object,String field,Object value){
        List<Binding> bindings;synchronized(SqliteMutations.class){List<Binding> original=objects.get(object);if(original==null||original.isEmpty())return;bindings=new ArrayList<Binding>(original);}
        for(Binding b:bindings){
            if(b.kind.equals("player")&&(field.equals("profile")||field.equals("stats")||field.equals("stats2"))){
                Player p=(Player)object;if(value==null)throw new IllegalArgumentException("Player component cannot be null");
                replaceComponent(b,value,field);
                if(field.equals("profile")){
                    Player.Profile v=(Player.Profile)value;
                    for(String name:new String[]{"Name","ShortName","Country","Region","Town","BirthDate","Sex","DateAdd","bModified","bNew"}){
                        try{b.store.update(b.id,b.scope,column("profile",name),v.getClass().getField(name).get(v));}catch(Exception e){throw SqlitePlayersStore.failure(e);}
                    }
                }else{Player.Stats v=(Player.Stats)value;b.store.update(b.id,b.scope,field+"_tours",v.tours);b.store.update(b.id,b.scope,field+"_wins",v.wins);b.store.update(b.id,b.scope,field+"_loses",v.loses);}
                track(b.store,b.scope,p);continue;
            }
            String column=column(b.kind,field);if(column!=null)b.store.update(b.id,b.scope,column,value);
        }
    }
    private static String column(String kind,String field){
        if(kind.equals("stats")||kind.equals("stats2"))return kind+"_"+field;
        if(kind.equals("profile")){
            if(field.equals("ID"))return "id";if(field.equals("Name"))return "name";if(field.equals("ShortName"))return "short_name";
            if(field.equals("Country"))return "country";if(field.equals("Region"))return "region";if(field.equals("Town"))return "town";
            if(field.equals("BirthDate"))return "birth_date";if(field.equals("Sex"))return "sex";if(field.equals("DateAdd"))return "date_added";
            if(field.equals("bModified"))return "b_modified";if(field.equals("bNew"))return "b_new";
        }else{
            if(field.equals("Place"))return "place";if(field.equals("PlaceNew"))return "place_new";if(field.equals("Rating"))return "rating";
            if(field.equals("deltaR"))return "delta_r";if(field.equals("bNoRating"))return "b_no_rating";
        }
        return null;
    }
}
