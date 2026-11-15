package ttr;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import javax.xml.parsers.*;
import java.util.*;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;
import ttr.events.XmlLoadEvents;

/** Single-pass SAX import; no DOM and no complete player list. */
public final class StreamingPlayers {
    private StreamingPlayers() {}

    public static boolean load(DBPlayers db, DBMain main, String source, XmlLoadEvents events) {
        try {
            Path cache = null;
            if (main.bUpdating) {
                String path = TTRClient.getInstance().m_sett.DBPath;
                if (path.isEmpty()) path = PortablePaths.getenv("APPDATA") + "\\data\\DB\\";
                int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                cache = Paths.get((separator >= 0 ? path.substring(0, separator + 1) : "") + "Players.xml.tmp");
            }
            loadInto(db, source, events, cache);
            db.UpdatePlayerLocationList(true);
            return true;
        } catch (Exception error) {
            boolean logging = TTRClient.bLog;
            try {
                TTRClient.bLog = true;
                TTRClient.WriteLog("Exception - ошибка при загрузке списка игроков: " + error);
            } finally { TTRClient.bLog = logging; }
            return false;
        }
    }

    // The cache target is explicit here so update I/O can be verified independently of the UI.
    static void loadInto(DBPlayers db, String source, XmlLoadEvents events, Path cache) throws Exception {
        XmlLoadEvents callback = LoadPerformanceFix.throttle(events);
        PlayersUpdateControl control=PlayersUpdateControl.current();
        PlayersUpdateControl.checkpoint();
        db.players.clear();
        if (callback != null) callback.OnSectionStarted(PlayersProgress.CONNECT);
        boolean remote = source.startsWith("https:");
        Path spool = null;
        HttpURLConnection connection = null;
        try {
            if (cache != null) {
                cache = cache.toAbsolutePath();
                spool = Files.createTempFile(cache.getParent(), ".ttwr-players-", ".part");
            }
            long size;
            InputStream raw;
            if (remote) {
                connection = control==null ? (HttpURLConnection)new URL(source.replace('\\','/')).openConnection() : control.connect(source);
                connection.setConnectTimeout(15000);connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept-Encoding","identity");
                connection.setUseCaches(false);
                raw = connection.getInputStream();
                String encoding=connection.getContentEncoding();
                size = encoding==null||"identity".equalsIgnoreCase(encoding)?connection.getContentLengthLong():-1;
            } else {
                Path file = Paths.get(source);
                size = Files.size(file);
                raw = Files.newInputStream(file);
            }
            Builder builder = new Builder(db, callback);
            try (InputStream input = raw;
                 OutputStream copy = spool == null ? null : new BufferedOutputStream(Files.newOutputStream(spool))) {
                if(callback!=null)callback.OnSectionStarted(PlayersProgress.READ);
                if(callback!=null)callback.OnBytesRead(0,size);
                ProgressInput stream = new ProgressInput(new BufferedInputStream(input), copy, size, callback);
                parse(stream, builder);
                byte[] remaining = new byte[16384];
                while (stream.read(remaining) != -1) { }
                if(size>0&&stream.count!=size)throw new EOFException("Соединение прервано: размер XML не соответствует полученным данным");
                if(events!=null)events.OnBytesRead(stream.count,size);
            }
            if (callback != null && builder.parsed > 0) callback.OnElementParsed(builder.parsed, builder.parsed);
            // A malformed download or invalid ID never replaces the previous cache.
            if (cache != null) {
                try { Files.move(spool, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException error) {
                    Files.move(spool, cache, StandardCopyOption.REPLACE_EXISTING);
                }
                spool = null;
            }
        } finally {
            if (connection != null) {if(control==null)connection.disconnect();else control.release(connection);}
            if (spool != null) Files.deleteIfExists(spool);
        }
    }

    private static void parse(InputStream input, DefaultHandler2 handler) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false); // Matches the original DocumentBuilder configuration.
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver(handler);
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        reader.parse(new InputSource(input));
    }

    private static class Counter extends DefaultHandler2 {
        long count;
        int depth;
        public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
            depth++;
            if (depth > 1 && name.equals("Player")) count++;
        }
        public void endElement(String uri, String local, String name) throws SAXException { depth--; }
        public void error(SAXParseException error) throws SAXException { throw error; }
        public void fatalError(SAXParseException error) throws SAXException { throw error; }
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw new SAXException("External XML entities are not supported in the player database");
        }
        public InputSource resolveEntity(String name, String publicId, String base, String systemId) throws SAXException {
            return resolveEntity(publicId, systemId);
        }
    }

    /** Matches Utility.getXMLTagValue: first descendant element, first child node value.
     * Text is coalesced across SAX chunks, but not across CDATA/comments/elements. */
    private static final class Value {
        int kind; // 0=empty, 1=text, 2=CDATA, 3=other node, 4=element (null node value)
        final StringBuilder text = new StringBuilder();
        String get() { return kind == 4 ? null : text.toString(); }
        void node(int type, String data) { if (kind == 0) { kind=type; if(data!=null)text.append(data); } }
    }
    private static final class Frame {
        final Player player = new Player();
        final Map<String,Value> fields = new HashMap<String,Value>();
        Frame(Attributes attributes) {
            player.profile.ID=Integer.parseInt(attributes.getValue("id")==null?"":attributes.getValue("id"),16);
            player.profile.bModified=Utility.StringToBoolean(attribute(attributes,"bModified"));
            player.profile.bNew=Utility.StringToBoolean(attribute(attributes,"bNew"));
            player.bNoRating=Utility.StringToBoolean(attribute(attributes,"bNoRating"));
        }
        static String attribute(Attributes a,String name){String v=a.getValue(name);return v==null?"":v;}
        String value(String name) { Value v=fields.get(name); return v==null?"":v.get(); }
        boolean finish() {
            try {
                player.profile.Name=value("name");player.profile.ShortName=value("shortname");
                player.profile.Country=value("country");player.profile.Region=value("region");player.profile.Town=value("location");
                player.profile.BirthDate=value("birthdate");player.profile.Sex=value("sex");
                player.Rating=Utility.StringToDouble(value("rating"));player.deltaR=Utility.StringToDouble(value("deltaR"));
                player.Place=Utility.StringToInteger(value("place"),10);
                stats(player.stats,value("stats"));stats(player.stats2,value("stats2"));return true;
            } catch(Exception invalid) { return false; } // Same per-record acceptance as Player.LoadXML.
        }
        void stats(Player.Stats stats,String value) {
            String[] parts=value.split(";");
            if(parts.length>=3){stats.tours=Utility.StringToInteger(parts[0],10);stats.wins=Utility.StringToInteger(parts[1],10);stats.loses=Utility.StringToInteger(parts[2],10);}
        }
    }
    private static final class ElementState {
        final String name;
        Value value;
        ElementState(String name){this.name=name;}
    }
    private static final class Builder extends Counter {
        static final Set<String> FIELDS=new HashSet<String>(Arrays.asList("name","shortname","country","region","location","birthdate","sex","rating","deltaR","place","stats","stats2"));
        final DBPlayers db;
        final XmlLoadEvents callback;
        final Deque<ElementState> elements=new ArrayDeque<ElementState>();
        final Deque<Frame> active=new ArrayDeque<Frame>();
        // Only nested Player records need buffering to preserve legacy document order.
        final List<Frame> ordered=new ArrayList<Frame>();
        final Set<Frame> valid=Collections.newSetFromMap(new IdentityHashMap<Frame,Boolean>());
        boolean cdata;
        long parsed;
        Builder(DBPlayers db,XmlLoadEvents callback){this.db=db;this.callback=callback;}
        public void startElement(String uri,String local,String name,Attributes attributes)throws SAXException {
            super.startElement(uri,local,name,attributes);
            if(depth==1&&PlayersUpdateControl.current()!=null&&!"Players".equals(name))throw new SAXException("Ожидается XML списка игроков: Players");
            if(!elements.isEmpty()&&elements.peek().value!=null){seal();elements.peek().value.node(4,null);}
            ElementState element=new ElementState(name);
            if(FIELDS.contains(name))for(Frame f:active)if(!f.fields.containsKey(name)){
                if(element.value==null)element.value=new Value();f.fields.put(name,element.value);
            }
            elements.push(element);
            if(depth>1&&name.equals("Player")){Frame f=new Frame(attributes);active.push(f);ordered.add(f);}
        }
        public void endElement(String uri,String local,String name)throws SAXException {
            if(depth>1&&name.equals("Player")&&!active.isEmpty()){
                Frame f=active.pop();if(f.finish())valid.add(f);
                if(active.isEmpty()){
                    for(Frame item:ordered){if(valid.contains(item))db.players.put(item.player.GetID(),item.player);parsed++;}
                    if(callback!=null)callback.OnElementParsed(parsed,-1);
                    ordered.clear();valid.clear();
                }
            }
            elements.pop();super.endElement(uri,local,name);
            PlayersUpdateControl.checkpoint();
            if(Thread.currentThread().isInterrupted())throw new SAXException("Player import cancelled");
        }
        public void characters(char[] chars,int start,int length){
            if(elements.isEmpty()||length==0)return;Value v=elements.peek().value;if(v==null)return;
            int kind=cdata?2:1;
            if(v.kind==0)v.kind=kind;
            if(v.kind==kind)v.text.append(chars,start,length);
        }
        public void ignorableWhitespace(char[] c,int s,int n){characters(c,s,n);}
        public void startCDATA(){cdata=true;if(!elements.isEmpty()&&elements.peek().value!=null)elements.peek().value.node(2,"");}
        public void endCDATA(){cdata=false;seal();}
        private void seal(){if(!elements.isEmpty()&&elements.peek().value!=null){Value v=elements.peek().value;if(v.kind==1||v.kind==2)v.kind=3;}}
        public void comment(char[] c,int s,int n){if(!elements.isEmpty()&&elements.peek().value!=null){seal();elements.peek().value.node(3,new String(c,s,n));}}
        public void processingInstruction(String target,String data){if(!elements.isEmpty()&&elements.peek().value!=null){seal();elements.peek().value.node(3,data);}}
    }

    private static final class ProgressInput extends FilterInputStream {
        final OutputStream copy;
        final long size;
        final XmlLoadEvents callback;
        long count;
        ProgressInput(InputStream input, OutputStream copy, long size, XmlLoadEvents callback) {
            super(input); this.copy=copy; this.size=size; this.callback=callback;
        }
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) { if (copy != null) copy.write(value); count++; notifyProgress(); }
            return value;
        }
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) { if (copy != null) copy.write(buffer, offset, n); count+=n; notifyProgress(); }
            return n;
        }
        private void notifyProgress() throws IOException { PlayersUpdateControl.checkpoint(); if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Player import cancelled"); if (callback != null) callback.OnBytesRead(count, size); }
        // SAX may close its input. The owner closes raw input and the spool in its finally block.
        public void close() {}
    }
}
