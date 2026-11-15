package ttr;
import ttr.*;
import ttr.events.XmlLoadEvents;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class VerifyCatalog {
    static final MessageDigest digest;
    static { try { digest=MessageDigest.getInstance("SHA-256"); } catch(Exception e) { throw new RuntimeException(e); } }
    static void hash(String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte)(bytes.length >>> 24)); digest.update((byte)(bytes.length >>> 16));
        digest.update((byte)(bytes.length >>> 8)); digest.update((byte)bytes.length); digest.update(bytes);
    }
    static void lists() {
        for (ArrayList<String> list : Arrays.asList(Player.townList, Player.regionList2, Player.countryList)) {
            ArrayList<String> canonical = new ArrayList<String>(list); Collections.sort(canonical); hash("LIST:"+canonical.size()); for (String value:canonical) hash(value);
        }
    }
    static class Callback implements XmlLoadEvents {
        long elementCalls, byteCalls, lastElement, lastByte, totalElements, totalBytes;
        int sections;
        public void OnSectionStarted(String s) { sections++; }
        public void OnElementParsed(long n,long total) {
            if(n<lastElement || n>total) throw new AssertionError("Element progress");
            lastElement=n;totalElements=total;elementCalls++;
        }
        public void OnBytesRead(long n,long total) {
            if(n<lastByte || n>total) throw new AssertionError("Byte progress");
            lastByte=n;totalBytes=total;byteCalls++;
        }
        public void SetErrorCode(int n,String s) { throw new AssertionError(s); }
    }
    public static void main(String[] args) throws Exception {
        DBMain db=new DBMain();
        ArrayList<String> townIdentity=Player.townList;
        Random random=new Random(1234567);
        String[] values={"", "Москва", "москва", "МОСКВА", "A", "a", "Ёлка", "Елка", "İ", "i", "İzmir", "Istanbul", " ", "Zürich", "東京"};
        for(int round=0;round<10;round++) {
            db.players.players.clear();
            for(int id=0;id<1000;id++) {
                Player p=new Player();p.profile.ID=id;
                p.profile.Town=values[random.nextInt(values.length)]+(id%7==0?id%83:"");
                p.profile.Country=values[random.nextInt(values.length)];
                p.profile.Region=values[random.nextInt(values.length)];
                db.players.players.put(id,p);
            }
            db.players.UpdatePlayerLocationList(round%3==0);lists();
            if(Player.townList!=townIdentity) throw new AssertionError("List identity changed");
        }
        db.players.players.clear();db.players.UpdatePlayerLocationList(true);lists();
        Callback callback=new Callback();
        long start=System.nanoTime();
        if(!db.players.LoadXML(args[0],callback)) throw new AssertionError("LoadXML returned false");
        double seconds=(System.nanoTime()-start)/1e9;
        if(callback.lastElement!=callback.totalElements || callback.lastByte!=callback.totalBytes)
            throw new AssertionError("Final progress missing");
        ArrayList<Integer> ids=new ArrayList<Integer>(db.players.players.keySet());Collections.sort(ids);
        hash("PLAYERS:"+ids.size());
        for(int id:ids) {
            Player p=db.players.players.get(id);
            hash(Integer.toString(p.profile.ID));hash(p.profile.Name);hash(p.profile.ShortName);
            hash(p.profile.Country);hash(p.profile.Region);hash(p.profile.Town);
            hash(p.profile.BirthDate);hash(p.profile.Sex);
            hash(Boolean.toString(p.profile.bModified));hash(Boolean.toString(p.profile.bNew));
            hash(Integer.toString(p.Place));hash(Integer.toString(p.PlaceNew));
            hash(Double.toString(p.Rating));hash(Double.toString(p.deltaR));hash(Boolean.toString(p.bNoRating));
            for(java.lang.reflect.Field field:Player.Stats.class.getFields()) {
                if(field.getType()==int.class) {hash(field.getName());hash(field.get(p.stats).toString());hash(field.get(p.stats2).toString());}
            }
        }
        lists();
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
        System.out.println("DATA_SHA256="+hex);
        System.out.printf(Locale.ROOT,"LOAD_SECONDS=%.3f PLAYERS=%d ELEMENT_CALLBACKS=%d BYTE_CALLBACKS=%d SECTIONS=%d%n",seconds,ids.size(),callback.elementCalls,callback.byteCalls,callback.sections);
    }
}
