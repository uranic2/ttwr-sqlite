package ttr;
import java.util.*;

/** Compatibility view for legacy code. The complete catalog never resides in this map. */
public final class SqlitePlayerMap extends AbstractMap<Integer,Player>{
    private final DBPlayers owner;
    public SqlitePlayerMap(DBPlayers owner){this.owner=owner;}
    public int size(){return owner.store().size();}
    public boolean containsKey(Object id){return id instanceof Integer&&owner.store().contains((Integer)id);}
    public Player get(Object id){return id instanceof Integer?owner.store().get((Integer)id):null;}
    public Player put(Integer id,Player p){if(p==null||id!=p.GetID())throw new IllegalArgumentException("Player ID mismatch");SqlitePlayersStore s=owner.store();Player old=s.importing?null:s.get(id);s.upsert(p,SqlitePlayersStore.CURRENT);return old;}
    public Player remove(Object id){Player old=get(id);if(old!=null)owner.store().delete((Integer)id);return old;}
    public void clear(){owner.store().clear();}
    public Set<Map.Entry<Integer,Player>> entrySet(){return new AbstractSet<Map.Entry<Integer,Player>>(){
        public int size(){return SqlitePlayerMap.this.size();}
        public Iterator<Map.Entry<Integer,Player>> iterator(){return new Iterator<Map.Entry<Integer,Player>>(){
            List<Integer> batch=Collections.emptyList();int index;int after=Integer.MIN_VALUE;Integer last;boolean end;
            public boolean hasNext(){if(index<batch.size())return true;if(end)return false;batch=owner.store().idsAfter(after,256);index=0;end=batch.isEmpty();return !end;}
            public Map.Entry<Integer,Player> next(){if(!hasNext())throw new NoSuchElementException();last=batch.get(index++);after=last;return new SimpleEntry<Integer,Player>(last,get(last)){public Player setValue(Player p){Player old=put(getKey(),p);super.setValue(p);return old;}};}
            public void remove(){if(last==null)throw new IllegalStateException();SqlitePlayerMap.this.remove(last);last=null;}
        };}
    };}
}
