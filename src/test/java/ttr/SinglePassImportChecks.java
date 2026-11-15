package ttr;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import ttr.events.XmlLoadEvents;

/** Compare direct SAX decoding against the original Player.LoadXML semantics. */
public final class SinglePassImportChecks {
    static void check(boolean v,String text){if(!v)throw new AssertionError(text);}
    static List<List<Object>> rows(SqlitePlayersStore s)throws Exception{
        List<List<Object>> out=new ArrayList<List<Object>>();
        try(Statement st=s.connection.createStatement();ResultSet r=st.executeQuery("SELECT * FROM players ORDER BY scope,id")){
            while(r.next()){List<Object> row=new ArrayList<Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getObject(i));out.add(row);}
        }return out;
    }
    static void rejected(DBPlayers db,Path xml,Path target,String text)throws Exception{
        List<List<Object>> before=rows(db.store());Files.write(xml,text.getBytes(StandardCharsets.UTF_8));
        try{SqlitePlayersIntegration.importXml(db,xml.toString(),target,null);throw new AssertionError("Invalid XML accepted");}catch(org.xml.sax.SAXException|NumberFormatException expected){}
        check(rows(db.store()).equals(before),"Failed import changed active database");
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);Path xml=root.resolve("Players.xml"),target=root.resolve("Players.sqlite");
        String text="<Players><wrapper>"
            +"<Player id='1' bModified='true' bNew='1' bNoRating='true'><name>Ёлкин José 日本語 &amp; тест</name><name>ignored</name><shortname>Ё. J.</shortname><country>Россия</country><region>Юг</region><location>Таганрог</location><birthdate>2000</birthdate><sex>m</sex><rating>-0.00</rating><deltaR>-0.00</deltaR><place>7</place><stats>3;2;1</stats><stats2>6;4;2</stats2></Player>"
            +"<Player id='2'><name><![CDATA[CDATA first]]>ignored</name><stats>bad;1;2</stats><rating>bad</rating></Player>"
            +"<Player id='3'><name>first<![CDATA[ignored]]>ignored</name><stats>1;2;</stats></Player>"
            +"<Player id='4'><name><!--first comment-->ignored</name></Player>"
            +"<Player id='5'><name><?first data?>ignored</name></Player>"
            +"<Player id='6'><name>first<child/>ignored</name><rating>1,25</rating></Player>"
            +"<Player id='7'><name><child/>ignored</name><stats2><child/></stats2></Player>"
            +"<Player id='8'><wrapper><name>descendant</name></wrapper><name>ignored</name><rating>1e3</rating></Player>"
            +"<Player id='9'><Player id='9'><name>nested wins duplicate</name></Player></Player>"
            +"<Player id='a'><name>duplicate old</name></Player><Player id='a'><name>duplicate new</name></Player>"
            +"<Player id='b'><name><![CDATA[]]>ignored</name></Player><Player id='c'><name/></Player>"
            +"</wrapper></Players>";
        Files.write(xml,text.getBytes(StandardCharsets.UTF_8));
        DBMain actual=new DBMain();check(actual.players.LoadXML(xml.toString(),null),"Direct import failed");
        try(SqlitePlayersStore expected=new SqlitePlayersStore(null)){
            expected.begin();Document d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());NodeList list=d.getDocumentElement().getElementsByTagName("Player");
            for(int i=0;i<list.getLength();i++){Element e=(Element)list.item(i);Player p=new Player();p.profile.ID=Integer.parseInt(e.getAttribute("id"),16);if(p.LoadXML(e))expected.upsert(p,"current");}
            expected.finish(true);check(rows(actual.players.store()).equals(rows(expected)),"Direct parser differs from original DOM reader");
        }
        try(Statement s=actual.players.store().connection.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'players_%'")){r.next();check(r.getInt(1)==8,"Deferred indexes missing");}
        rejected(actual.players,xml,target,text.substring(0,text.length()-10));
        rejected(actual.players,xml,target,text.replace("id='c'","id='not-hex'"));
        rejected(actual.players,xml,target,"<!DOCTYPE Players [<!ENTITY external SYSTEM 'file:///must-not-open'>]><Players><Player id='1'><name>&external;</name></Player></Players>");
        Files.write(xml,text.getBytes(StandardCharsets.UTF_8));List<List<Object>> before=rows(actual.players.store());
        XmlLoadEvents cancel=new XmlLoadEvents(){public void OnSectionStarted(String s){}public void OnElementParsed(long a,long b){}public void SetErrorCode(int c,String s){}public void OnBytesRead(long a,long b){throw new IllegalStateException("test cancellation");}};
        try{SqlitePlayersIntegration.importXml(actual.players,xml.toString(),target,cancel);throw new AssertionError("Cancellation ignored");}catch(IllegalStateException expected){}
        check(rows(actual.players.store()).equals(before),"Cancelled import changed database");
        Thread.currentThread().interrupt();try{SqlitePlayersIntegration.importXml(actual.players,xml.toString(),target,null);throw new AssertionError("Interrupt ignored");}catch(java.io.IOException|org.xml.sax.SAXException expected){}finally{Thread.interrupted();}
        check(rows(actual.players.store()).equals(before),"Interrupted import changed database");
        try(java.util.stream.Stream<Path> files=Files.list(root)){check(files.noneMatch(p->p.getFileName().toString().startsWith(".ttwr-import-")),"Import stage leaked");}
        Path empty=root.resolve("empty.xml");Files.write(empty,"<Players/>".getBytes(StandardCharsets.UTF_8));
        DBMain emptyDB=new DBMain();SqlitePlayersIntegration.importXml(emptyDB.players,empty.toString(),root.resolve("empty.sqlite"),new XmlLoadEvents(){
            public void OnSectionStarted(String s){}public void SetErrorCode(int c,String s){}public void OnBytesRead(long a,long b){}
            public void OnElementParsed(long a,long b){check(b>0,"Zero progress denominator");}
        });check(emptyDB.players.store().size()==0,"Empty XML import failed");emptyDB.players.store().close();
        actual.players.store().close();System.out.println("SINGLE_PASS_IMPORT_OK: original XML semantics, duplicate/nested IDs, Unicode, negative zero, indexes, malformed tail, external entities, cancellation and rollback");
    }
}
