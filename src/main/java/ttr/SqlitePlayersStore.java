package ttr;

import java.io.*;
import java.lang.ref.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.sqlite.Collation;

/** Persistent player repository. All lookups execute SQL; only live objects are weakly cached. */
public final class SqlitePlayersStore implements AutoCloseable {
    static final String CURRENT="current";
    static final String[] COLUMNS={"id","name","short_name","country","region","town","birth_date","sex","date_added","b_modified","b_new","place","place_new","rating","delta_r","b_no_rating","stats_tours","stats_wins","stats_loses","stats2_tours","stats2_wins","stats2_loses","name_key","region_key","town_key","surname_key","rating_negative_zero","delta_negative_zero"};
    final Connection connection;
    public final Path path;
    boolean importing;
    boolean temporary;
    private boolean closed;
    private final PreparedStatement upsertStatement;
    private final ReferenceQueue<Player> queue=new ReferenceQueue<Player>();
    private final Map<String,PlayerRef> cache=new HashMap<String,PlayerRef>();
    private final WeakHashMap<RatingList,String> snapshots=new WeakHashMap<RatingList,String>();
    static final class PlayerRef extends WeakReference<Player>{final String key;PlayerRef(String key,Player p,ReferenceQueue<Player> q){super(p,q);this.key=key;}}

    public SqlitePlayersStore(Path file)throws Exception{this(file,false);}
    SqlitePlayersStore(Path file,boolean deferIndexes)throws Exception{
        Class.forName("org.sqlite.JDBC");
        path=file==null?null:file.toAbsolutePath().normalize();
        boolean existing=path!=null&&Files.exists(path)&&Files.size(path)>0;
        connection=DriverManager.getConnection(path==null?"jdbc:sqlite::memory:":"jdbc:sqlite:"+path);
        try{
        Collation.create(connection,"JAVA_NOCASE",new Collation(){protected int xCompare(String a,String b){return a.compareToIgnoreCase(b);}});
        try(Statement s=connection.createStatement()){
            int version;try(ResultSet r=s.executeQuery("PRAGMA user_version")){r.next();version=r.getInt(1);}
            if((version!=0&&version!=2)||(existing&&version==0)){connection.close();throw new SQLException("Unsupported player database version: "+version);}
            if(existing){HashSet<String> columns=new HashSet<String>();try(ResultSet r=s.executeQuery("PRAGMA table_info(players)")){while(r.next())columns.add(r.getString("name"));}if(!columns.contains("scope")||!columns.containsAll(Arrays.asList(COLUMNS))){connection.close();throw new SQLException("Not a TTWR player database");}}
            s.execute("PRAGMA busy_timeout=10000");s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS players(scope TEXT NOT NULL,id INTEGER NOT NULL,name TEXT,short_name TEXT,country TEXT,region TEXT,town TEXT,birth_date TEXT,sex TEXT,date_added INTEGER,b_modified INTEGER NOT NULL,b_new INTEGER NOT NULL,place INTEGER NOT NULL,place_new INTEGER NOT NULL,rating REAL NOT NULL,delta_r REAL NOT NULL,b_no_rating INTEGER NOT NULL,stats_tours INTEGER NOT NULL,stats_wins INTEGER NOT NULL,stats_loses INTEGER NOT NULL,stats2_tours INTEGER NOT NULL,stats2_wins INTEGER NOT NULL,stats2_loses INTEGER NOT NULL,name_key TEXT NOT NULL,region_key TEXT NOT NULL,town_key TEXT NOT NULL,surname_key TEXT NOT NULL,rating_negative_zero INTEGER NOT NULL,delta_negative_zero INTEGER NOT NULL,PRIMARY KEY(scope,id)) WITHOUT ROWID");
            s.execute("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            if(version==0)s.execute("PRAGMA user_version=2");
        }
        if(!deferIndexes)createIndexes();
        StringJoiner marks=new StringJoiner(",");for(int i=0;i<COLUMNS.length+1;i++)marks.add("?");
        upsertStatement=connection.prepareStatement("INSERT OR REPLACE INTO players(scope,"+String.join(",",COLUMNS)+") VALUES("+marks+")");
        SqliteLifecycle.register(this);
        }catch(Exception error){try{connection.close();}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    synchronized void createIndexes()throws SQLException{
        try(Statement s=connection.createStatement()){
            s.execute("CREATE INDEX IF NOT EXISTS players_name ON players(scope,name_key,id)");
            s.execute("CREATE INDEX IF NOT EXISTS players_surname ON players(scope,surname_key,id)");
            s.execute("CREATE INDEX IF NOT EXISTS players_rating ON players(scope,rating DESC,id)");
            s.execute("CREATE INDEX IF NOT EXISTS players_region ON players(scope,region_key,id)");
            s.execute("CREATE INDEX IF NOT EXISTS players_town ON players(scope,town_key,id)");
            s.execute("CREATE INDEX IF NOT EXISTS players_country_values ON players(scope,country)");
            s.execute("CREATE INDEX IF NOT EXISTS players_region_values ON players(scope,region)");
            s.execute("CREATE INDEX IF NOT EXISTS players_town_values ON players(scope,town)");
        }
    }
    static RuntimeException failure(Exception e){return e instanceof RuntimeException?(RuntimeException)e:new IllegalStateException("Ошибка SQLite: "+e.getMessage(),e);}
    static String lower(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    static String locationKey(String s){return lower(s).replaceAll("[- ,\\.?]+","").trim();}
    static String surname(String s){String[] p=(s==null?"":s).split("[\\. ]+");if(p.length==0)return "";StringBuilder key=new StringBuilder();for(int i=0;i<p[0].length();){int c=p[0].codePointAt(i);key.appendCodePoint(Character.toLowerCase(Character.toUpperCase(c)));i+=Character.charCount(c);}return key.toString();}
    private void clean(){PlayerRef ref;while((ref=(PlayerRef)queue.poll())!=null)if(cache.get(ref.key)==ref)cache.remove(ref.key);}
    private static String key(String scope,int id){return scope+"#"+id;}
    /** Local download time, independent of the server publication date. */
    public synchronized long lastInternetUpdate(){
        try(PreparedStatement s=connection.prepareStatement("SELECT value FROM metadata WHERE key='last_internet_update'")){
            try(ResultSet r=s.executeQuery()){if(!r.next())return 0;try{return Math.max(0,Long.parseLong(r.getString(1)));}catch(NumberFormatException e){return 0;}}
        }catch(SQLException e){throw failure(e);}
    }
    synchronized void lastInternetUpdate(long time){
        try(PreparedStatement s=connection.prepareStatement("INSERT OR REPLACE INTO metadata(key,value) VALUES('last_internet_update',?)")){
            s.setString(1,Long.toString(time));s.executeUpdate();
        }catch(SQLException e){throw failure(e);}
    }
    public synchronized int size(){try(PreparedStatement s=connection.prepareStatement("SELECT count(*) FROM players WHERE scope=?")){s.setString(1,CURRENT);try(ResultSet r=s.executeQuery()){return r.next()?r.getInt(1):0;}}catch(Exception e){throw failure(e);}}
    public synchronized boolean contains(int id){try(PreparedStatement s=connection.prepareStatement("SELECT 1 FROM players WHERE scope=? AND id=?")){s.setString(1,CURRENT);s.setInt(2,id);try(ResultSet r=s.executeQuery()){return r.next();}}catch(Exception e){throw failure(e);}}
    public Player get(int id){return get(id,CURRENT);}
    synchronized Player get(int id,String scope){
        try(PreparedStatement s=connection.prepareStatement("SELECT * FROM players WHERE scope=? AND id=?")){
            s.setString(1,scope);s.setInt(2,id);try(ResultSet r=s.executeQuery()){return r.next()?read(r,scope):null;}
        }catch(Exception e){throw failure(e);}
    }
    private Player read(ResultSet r,String scope)throws SQLException{
        clean();int id=r.getInt("id");String key=key(scope,id);PlayerRef ref=cache.get(key);Player p=ref==null?null:ref.get();
        if(p==null){p=new Player();cache.put(key,new PlayerRef(key,p,queue));}
        p.profile.ID=id;p.profile.Name=r.getString("name");p.profile.ShortName=r.getString("short_name");
        p.profile.Country=r.getString("country");p.profile.Region=r.getString("region");p.profile.Town=r.getString("town");
        p.profile.BirthDate=r.getString("birth_date");p.profile.Sex=r.getString("sex");
        long added=r.getLong("date_added");if(r.wasNull())p.profile.DateAdd=null;else{p.profile.DateAdd=Calendar.getInstance();p.profile.DateAdd.setTimeInMillis(added);}
        p.profile.bModified=r.getBoolean("b_modified");p.profile.bNew=r.getBoolean("b_new");
        p.Place=r.getInt("place");p.PlaceNew=r.getInt("place_new");p.Rating=r.getDouble("rating");p.deltaR=r.getDouble("delta_r");p.bNoRating=r.getBoolean("b_no_rating");
        if(p.Rating==0&&r.getBoolean("rating_negative_zero"))p.Rating=-0.0;if(p.deltaR==0&&r.getBoolean("delta_negative_zero"))p.deltaR=-0.0;
        if(p.stats==null)p.stats=p.new Stats();if(p.stats2==null)p.stats2=p.new Stats();
        p.stats.tours=r.getInt("stats_tours");p.stats.wins=r.getInt("stats_wins");p.stats.loses=r.getInt("stats_loses");
        p.stats2.tours=r.getInt("stats2_tours");p.stats2.wins=r.getInt("stats2_wins");p.stats2.loses=r.getInt("stats2_loses");
        SqliteMutations.track(this,scope,p);return p;
    }
    synchronized void upsert(Player p,String scope){
        try{
            SqliteMutations.enroll(this);
            {
                PreparedStatement s=upsertStatement;
                int i=1;s.setString(i++,scope);s.setInt(i++,p.profile.ID);s.setString(i++,p.profile.Name);s.setString(i++,p.profile.ShortName);
                s.setString(i++,p.profile.Country);s.setString(i++,p.profile.Region);s.setString(i++,p.profile.Town);s.setString(i++,p.profile.BirthDate);s.setString(i++,p.profile.Sex);
                if(p.profile.DateAdd==null)s.setNull(i++,Types.BIGINT);else s.setLong(i++,p.profile.DateAdd.getTimeInMillis());
                s.setBoolean(i++,p.profile.bModified);s.setBoolean(i++,p.profile.bNew);s.setInt(i++,p.Place);s.setInt(i++,p.PlaceNew);
                s.setDouble(i++,p.Rating);s.setDouble(i++,p.deltaR);s.setBoolean(i++,p.bNoRating);
                s.setInt(i++,p.stats.tours);s.setInt(i++,p.stats.wins);s.setInt(i++,p.stats.loses);
                s.setInt(i++,p.stats2.tours);s.setInt(i++,p.stats2.wins);s.setInt(i++,p.stats2.loses);
                s.setString(i++,lower(p.profile.Name));s.setString(i++,locationKey(p.profile.Region));s.setString(i++,locationKey(p.profile.Town));s.setString(i++,surname(p.profile.Name));
                s.setBoolean(i++,Double.doubleToRawLongBits(p.Rating)==Long.MIN_VALUE);s.setBoolean(i++,Double.doubleToRawLongBits(p.deltaR)==Long.MIN_VALUE);s.executeUpdate();
            }
            if(!importing){clean();String key=key(scope,p.GetID());cache.put(key,new PlayerRef(key,p,queue));SqliteMutations.track(this,scope,p);}
        }catch(Exception e){throw failure(e);}
    }
    synchronized void delete(int id){try{SqliteMutations.enroll(this);try(PreparedStatement s=connection.prepareStatement("DELETE FROM players WHERE scope=? AND id=?")){s.setString(1,CURRENT);s.setInt(2,id);s.executeUpdate();}cache.remove(key(CURRENT,id));SqliteMutations.detach(this,CURRENT,id);}catch(Exception e){throw failure(e);}}
    synchronized void clear(){try{SqliteMutations.enroll(this);try(PreparedStatement s=connection.prepareStatement("DELETE FROM players WHERE scope=?")){s.setString(1,CURRENT);s.executeUpdate();}cache.clear();SqliteMutations.detach(this,null,null);}catch(Exception e){throw failure(e);}}
    synchronized List<Integer> idsAfter(int after,int count){try(PreparedStatement s=connection.prepareStatement("SELECT id FROM players WHERE scope=? AND id>? ORDER BY id LIMIT ?")){s.setString(1,CURRENT);s.setInt(2,after);s.setInt(3,count);ArrayList<Integer> ids=new ArrayList<Integer>();try(ResultSet r=s.executeQuery()){while(r.next())ids.add(r.getInt(1));}return ids;}catch(Exception e){throw failure(e);}}
    public synchronized Player[] query(String name,String region,String town,int column,boolean descending,int offset,int limit){return query(name,region,town,column,descending,offset,limit,null,null);}
    synchronized Player[] query(String name,String region,String town,int column,boolean descending,int offset,int limit,List<String> histories,List<Boolean> copies){
        name=lower(name.trim());region=locationKey(region);town=locationKey(town);
        String[] order={"place","name_key","region_key,town_key","birth_date","rating","stats_tours","stats_wins+stats_loses"};
        String where=where(name,region,town);String ordering=order[Math.max(0,Math.min(column,6))];
        if(histories!=null&&!histories.isEmpty()&&column>=4&&column<=6){
            StringBuilder ids=new StringBuilder(),priority=new StringBuilder("CASE h.scope"),value=new StringBuilder("CASE h.scope");
            for(int j=0;j<histories.size();j++){
                String scope="'"+histories.get(j).replace("'","''")+"'";if(j>0)ids.append(',');ids.append(scope);priority.append(" WHEN ").append(scope).append(" THEN ").append(j);
                String metric=column==4?"h.rating"+(copies.get(j)?"+h.delta_r":""):column==5?"h.stats_tours"+(copies.get(j)?"+h.stats2_tours":""):"h.stats_wins+h.stats_loses"+(copies.get(j)?"+h.stats2_wins+h.stats2_loses":"");
                value.append(" WHEN ").append(scope).append(" THEN ").append(metric);
            }
            priority.append(" END");value.append(" END");
            ordering="COALESCE((SELECT "+value+" FROM players h WHERE h.id=p.id AND h.scope IN ("+ids+") ORDER BY "+priority+" LIMIT 1),"+ordering+")";
        }
        if(descending)ordering=column==2?ordering.replace(","," DESC,")+" DESC":ordering+" DESC";
        try(PreparedStatement s=connection.prepareStatement("SELECT p.* FROM players p WHERE scope=?"+where+" ORDER BY "+ordering+",p.id LIMIT ? OFFSET ?")){
            int i=bindFilter(s,name,region,town);s.setInt(i++,limit);s.setInt(i,offset);ArrayList<Player> list=new ArrayList<Player>();
            try(ResultSet r=s.executeQuery()){while(r.next())list.add(read(r,CURRENT));}return list.toArray(new Player[0]);
        }catch(Exception e){throw failure(e);}
    }
    public synchronized int count(String name,String region,String town){name=lower(name.trim());region=locationKey(region);town=locationKey(town);try(PreparedStatement s=connection.prepareStatement("SELECT count(*) FROM players WHERE scope=?"+where(name,region,town))){bindFilter(s,name,region,town);try(ResultSet r=s.executeQuery()){return r.next()?r.getInt(1):0;}}catch(Exception e){throw failure(e);}}
    private static String where(String n,String r,String t){return (n.isEmpty()?"":" AND name_key>=? AND name_key<?")+(r.isEmpty()?"":" AND region_key>=? AND region_key<?")+(t.isEmpty()?"":" AND town_key>=? AND town_key<?");}
    private static int bindFilter(PreparedStatement s,String name,String region,String town)throws SQLException{int i=1;s.setString(i++,CURRENT);for(String value:new String[]{lower(name),locationKey(region),locationKey(town)})if(!value.isEmpty()){s.setString(i++,value);s.setString(i++,value+'\uffff');}return i;}
    public synchronized Player[] similar(Player p){
        if(p==null)return new Player[0];ArrayList<Player> list=new ArrayList<Player>();
        try(PreparedStatement s=connection.prepareStatement("SELECT * FROM players WHERE scope=? AND surname_key=? AND id<>?")){
            s.setString(1,CURRENT);s.setString(2,surname(p.profile.Name));s.setInt(3,p.GetID());try(ResultSet r=s.executeQuery()){while(r.next()){Player q=read(r,CURRENT);if(p.isSimilar(q))list.add(q);}}
            return list.toArray(new Player[0]);
        }catch(Exception e){throw failure(e);}
    }
    public synchronized ArrayList<String> distinct(String column){
        if(!Arrays.asList("country","region","town").contains(column))throw new IllegalArgumentException(column);
        try(PreparedStatement s=connection.prepareStatement("SELECT DISTINCT "+column+" FROM players WHERE scope=? AND "+column+"<>'' ORDER BY "+column+" COLLATE JAVA_NOCASE")){
            s.setString(1,CURRENT);ArrayList<String> values=new ArrayList<String>();try(ResultSet r=s.executeQuery()){while(r.next())values.add(r.getString(1));}return values;
        }catch(Exception e){throw failure(e);}
    }
    synchronized void update(int id,String scope,String column,Object value){
        if(!Arrays.asList(COLUMNS).contains(column)||column.endsWith("_key"))throw new IllegalArgumentException(column);
        try{
            SqliteMutations.enroll(this);String extras="";
            if(column.equals("name"))extras=",name_key=?,surname_key=?";
            if(column.equals("region"))extras=",region_key=?";
            if(column.equals("town"))extras=",town_key=?";
            if(column.equals("rating"))extras=",rating_negative_zero=?";
            if(column.equals("delta_r"))extras=",delta_negative_zero=?";
            try(PreparedStatement s=connection.prepareStatement("UPDATE players SET "+column+"=?"+extras+" WHERE scope=? AND id=?")){
                int i=1;if(value instanceof Calendar)s.setLong(i++,((Calendar)value).getTimeInMillis());else s.setObject(i++,value);
                if(column.equals("name")){s.setString(i++,lower((String)value));s.setString(i++,surname((String)value));}
                if(column.equals("region")||column.equals("town"))s.setString(i++,locationKey((String)value));
                if(column.equals("rating")||column.equals("delta_r"))s.setBoolean(i++,Double.doubleToRawLongBits(((Number)value).doubleValue())==Long.MIN_VALUE);
                s.setString(i++,scope);s.setInt(i,id);s.executeUpdate();
            }
            if(column.equals("id")){cache.remove(key(scope,id));SqliteMutations.rekey(this,scope,id,((Number)value).intValue());}
        }catch(Exception e){throw failure(e);}
    }
    public synchronized void assignPlaces(){try(Statement s=connection.createStatement()){s.executeUpdate("WITH ranked AS (SELECT id,RANK() OVER(ORDER BY rating DESC) AS pos FROM players WHERE scope='current') UPDATE players SET place=(SELECT pos FROM ranked WHERE ranked.id=players.id) WHERE scope='current'");refreshLive();}catch(Exception e){throw failure(e);}}
    private void refreshLive(){ArrayList<String> keys=new ArrayList<String>(cache.keySet());for(String key:keys){PlayerRef reference=cache.get(key);Player p=reference==null?null:reference.get();if(p!=null){int split=key.lastIndexOf('#');get(Integer.parseInt(key.substring(split+1)),key.substring(0,split));}}}
    public synchronized void begin()throws SQLException{if(connection.getAutoCommit())connection.setAutoCommit(false);}
    public synchronized void finish(boolean success)throws SQLException{if(!connection.getAutoCommit()){if(success)connection.commit();else connection.rollback();connection.setAutoCommit(true);}if(!success)refreshLive();}
    public synchronized void checkpoint(){try(Statement s=connection.createStatement()){if(!connection.getAutoCommit())throw new SQLException("An edit is still active");s.execute("PRAGMA wal_checkpoint(FULL)");}catch(Exception e){throw failure(e);}}
    public synchronized void copyTo(Path target)throws Exception{
        target=target.toAbsolutePath().normalize();checkpoint();if(path!=null&&path.equals(target))return;
        if(Files.exists(target)){
            try(SqlitePlayersStore destination=new SqlitePlayersStore(target)){if(!"ok".equals(destination.integrity()))throw new SQLException("Destination integrity check failed");}
            int code=((org.sqlite.SQLiteConnection)connection).getDatabase().backup("main",target.toString(),null);
            if(code!=0)throw new SQLException("SQLite backup failed: "+code);
            return;
        }
        Path stage=Files.createTempFile(target.getParent(),".ttwr-sqlite-",".part");Files.delete(stage);
        try{int code=((org.sqlite.SQLiteConnection)connection).getDatabase().backup("main",stage.toString(),null);if(code!=0)throw new SQLException("SQLite backup failed: "+code);
            try{Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(stage,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(stage);}
    }
    synchronized void adoptLiveObjects(SqlitePlayersStore old){
        synchronized(old){
            snapshots.putAll(old.snapshots);
            for(Map.Entry<String,PlayerRef> entry:old.cache.entrySet()){
                Player player=entry.getValue().get();if(player==null)continue;
                String key=entry.getKey();int split=key.lastIndexOf('#');String scope=key.substring(0,split);
                cache.put(key,new PlayerRef(key,player,queue));SqliteMutations.track(this,scope,player);
            }
        }
    }
    synchronized String snapshot(RatingList list){
        String scope=snapshots.get(list);if(scope!=null)return scope;
        scope="rating:"+list.GetFileName()+":"+(list.date==null?0:list.date.getTimeInMillis());
        boolean own=false;try{
            own=connection.getAutoCommit();if(own)begin();
            try(PreparedStatement s=connection.prepareStatement("DELETE FROM players WHERE scope=?")){s.setString(1,scope);s.executeUpdate();}
            for(Player p:list.players.values())upsert(p,scope);
            if(own)finish(true);snapshots.put(list,scope);return scope;
        }catch(Exception e){try{if(own)finish(false);}catch(Exception ignored){}throw failure(e);}
    }
    synchronized void invalidateHistory(RatingList list){snapshots.remove(list);}
    synchronized Player[] exportPlayers(Collection<Integer> ids){
        StringBuilder sql=new StringBuilder("SELECT * FROM players WHERE scope=? AND (b_new=1 OR b_modified=1");
        if(!ids.isEmpty()){sql.append(" OR id IN (");for(int i=0;i<ids.size();i++)sql.append(i==0?"?":",?");sql.append(')');}sql.append(") ORDER BY id");
        try(PreparedStatement s=connection.prepareStatement(sql.toString())){int i=1;s.setString(i++,CURRENT);for(int id:ids)s.setInt(i++,id);ArrayList<Player> result=new ArrayList<Player>();try(ResultSet r=s.executeQuery()){while(r.next())result.add(read(r,CURRENT));}return result.toArray(new Player[0]);}catch(Exception e){throw failure(e);}
    }
    public synchronized String integrity(){try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("PRAGMA integrity_check")){return r.next()?r.getString(1):"no result";}catch(Exception e){throw failure(e);}}
    public synchronized String quickCheck(){try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("PRAGMA quick_check(1)")){return r.next()?r.getString(1):"no result";}catch(Exception e){throw failure(e);}}
    public synchronized void close(){
        if(closed)return;
        Exception error=null;
        try{if(!connection.getAutoCommit())connection.rollback();}catch(Exception e){error=e;}
        try{upsertStatement.close();}catch(Exception e){if(error==null)error=e;else error.addSuppressed(e);}
        // Always attempt connection.close(), even if rollback or statement cleanup failed.
        try{connection.close();}catch(Exception e){if(error==null)error=e;else error.addSuppressed(e);}
        try{
            if(connection.isClosed()){
                closed=true;SqliteLifecycle.unregister(this);SqliteMutations.detach(this,null,null);cache.clear();
                // Permanent WAL/SHM files are cleaned by SQLite itself, never manually.
                if(temporary&&path!=null){Files.deleteIfExists(path);Files.deleteIfExists(Paths.get(path+"-wal"));Files.deleteIfExists(Paths.get(path+"-shm"));}
            }
        }catch(Exception e){if(error==null)error=e;else error.addSuppressed(e);}
        if(error!=null)throw failure(error);
    }
}
