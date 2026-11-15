package ttr;
import ttr.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
public final class SqliteIntegrationChecks{
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static TTRClient fake(DBMain main)throws Exception{
        Class<?> type=Class.forName("sun.misc.Unsafe");Field f=type.getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);
        TTRClient client=(TTRClient)type.getMethod("allocateInstance",Class.class).invoke(unsafe,TTRClient.class);
        Field db=TTRClient.class.getDeclaredField("mainDB");db.setAccessible(true);db.set(client,main);client.m_sett=new Settings();
        Field instance=TTRClient.class.getDeclaredField("mainWnd");instance.setAccessible(true);instance.set(null,client);return client;
    }
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[1]);Files.createDirectories(root);Path file=root.resolve("Players.sqlite");Files.copy(Paths.get(args[0]),file);
        DBMain main=new DBMain();check(main.players.LoadXML(file.toString(),null),"SQLite open failed");fake(main);
        check(main.players.players instanceof SqlitePlayerMap,"Catalog still uses an in-memory map");check(main.players.players.size()==(args.length>2?Integer.parseInt(args[2]):149194),"Catalog count differs");
        check(!Files.exists(root.resolve("Players.xml")),"Unexpected XML in SQLite-only test");DBMain legacyHeader=new DBMain();check(legacyHeader.players.LoadXML(root.resolve("Players.xml").toString(),null),"Legacy header still requires XML");legacyHeader.players.store().close();
        Player[] first=main.players.store().query("","","",1,false,0,100),second=main.players.store().query("","","",1,false,100,100);
        check(first.length==100&&second.length==100,"Paging failed");HashSet<Integer> ids=new HashSet<Integer>();for(Player p:first)ids.add(p.GetID());for(Player p:second)check(!ids.contains(p.GetID()),"Pages overlap");
        String prefix=first[0].profile.Name.substring(0,Math.min(3,first[0].profile.Name.length()));
        for(Player p:main.players.store().query(prefix.toUpperCase(Locale.ROOT),"","",1,false,0,100))check(p.profile.Name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)),"Unicode prefix differs");
        check(main.players.store().count("' OR 1=1 --","","")==0,"SQL input not parameterized");
        main.players.store().query(""," ","",1,false,0,10);
        for(Player p:main.players.store().query("","","",4,true,0,100)){check(p!=null,"Rating query failed");}
        Player p=new Player();p.profile.ID=0x70000001;p.profile.Name="Тест SQLite";p.profile.Country="Россия";p.profile.Region="Москва";p.profile.Town="Москва";
        check(main.players.AddPlayer(p),"Add failed");check(!main.players.AddPlayer(p),"Duplicate accepted");
        MutationScenario.edit(p,"Ёлкин SQLite",12345.25);check(main.players.GetPlayer(p.GetID())==p,"Live identity not retained");
        check(main.players.GetPlayer(p.GetID()).profile.Name.equals("Ёлкин SQLite"),"Profile assignment not persisted");check(main.players.store().count("ЁЛКИН SQLITE","","")==1,"Edited name index stale");
        int wins=p.stats.wins;
        try{MutationScenario.fail(p);throw new AssertionError("Planned error disappeared");}catch(IllegalStateException expected){}
        check(main.players.GetPlayer(p.GetID()).profile.Name.equals("Ёлкин SQLite")&&p.Rating==12345.25,"Exception did not roll back");
        check(!MutationScenario.reject(p),"False result changed");check(main.players.GetPlayer(p.GetID()).profile.Name.equals("Ёлкин SQLite"),"False result did not roll back");
        MutationScenario.rating(p,-0.0,-0.0);check(Double.doubleToRawLongBits(main.players.GetPlayer(p.GetID()).Rating)==Long.MIN_VALUE&&Double.doubleToRawLongBits(p.deltaR)==Long.MIN_VALUE,"Negative zero lost");MutationScenario.rating(p,12345.25,0.0);
        Player unicode=new Player();unicode.profile.ID=0x70000003;unicode.profile.Name="İrmak SQLite";main.players.AddPlayer(unicode);Player lookup=new Player();lookup.profile.ID=0x70000004;lookup.profile.Name="ırmak SQLite";check(main.players.FindSimilarPlayers(lookup).length==1,"Java Unicode similarity semantics lost");
        Player.Stats detached=p.stats,newStats=p.new Stats();newStats.wins=17;MutationScenario.replaceStats(p,newStats);MutationScenario.wins(detached,99);
        check(main.players.GetPlayer(p.GetID()).stats.wins==17,"Detached stats still update the row");
        Calendar day=new GregorianCalendar(2020,0,1);RatingList history=new RatingList(day);history.bFixed=true;Player h=new Player(p);h.Rating=200;h.deltaR=5;history.players.put(p.GetID(),h);main.tours.RLists.add(history);
        check(main.players.GetPlayer(p.GetID(),day).Rating==200,"Historical lookup differs");
        MutationScenario.rating(h,300,7);check(main.players.GetPlayer(p.GetID(),day).Rating==300,"Historical mutation not persisted");
        Player onPage=main.players.QueryPage(day,"Ёлкин","","",4,true,0,100)[0];check(onPage.GetID()==p.GetID(),"Historical sorting failed");
        history.ApplyToPlayersDB();check(main.players.GetPlayer(p.GetID()).Rating==307,"Original rating application not persisted");
        Player tie=new Player();tie.profile.ID=0x70000002;tie.profile.Name="Тест равного рейтинга";tie.Rating=1000000000;main.players.AddPlayer(tie);MutationScenario.rating(p,1000000000,0);
        main.players.AssignPlacesToPlayers();check(main.players.GetPlayer(p.GetID()).Place==1&&main.players.GetPlayer(tie.GetID()).Place==1,"SQL rank ties differ");
        Path backup=root.resolve("Резервная копия.sqlite");if(!main.players.SaveXML(backup.toString())){main.players.store().copyTo(backup);throw new AssertionError("Backup failed");}DBMain other=new DBMain();check(other.players.LoadXML(backup.toString(),null),"Backup cannot open");
        MutationScenario.name(p,"Обновление открытой копии");check(main.players.SaveXML(backup.toString()),"Backup over open destination failed");check(other.players.GetPlayer(p.GetID()).profile.Name.equals("Обновление открытой копии"),"Open destination is stale");other.players.store().close();
        Path broken=root.resolve("broken.xml");Files.write(broken,"<Players><Player id='1'>".getBytes("UTF-8"));
        try{SqlitePlayersIntegration.importXml(main.players,broken.toString(),file,null);throw new AssertionError("Broken import accepted");}catch(org.xml.sax.SAXException expected){}
        check(main.players.GetPlayer(p.GetID()).profile.Name.equals("Обновление открытой копии"),"Broken import changed original store");
        Path saveAs=root.resolve("Сохранить как");Files.createDirectories(saveAs);check(main.SaveXML(saveAs.resolve("db.xml").toString(),true),"SaveAs failed");
        MutationScenario.name(p,"После SaveAs");DBMain reopened=new DBMain();check(reopened.LoadXML(saveAs.resolve("db.xml").toString(),null),"Saved header cannot reopen");
        check(reopened.players.GetPlayer(p.GetID()).profile.Name.equals("После SaveAs"),"SaveAs did not transfer field tracking");
        check(reopened.players.RemovePlayer(reopened.players.GetPlayer(tie.GetID())),"Remove failed");check(reopened.players.GetPlayer(tie.GetID())==null,"Removed row still exists");
        check("ok".equals(reopened.players.store().integrity()),"Final integrity failed");reopened.players.store().close();main.players.store().close();
        System.out.println("SQLITE_INTEGRATION_OK: paging, Unicode, parameter binding, field tracking, transactions, history, original rating application, ranking, open backup, failed import, SaveAs, reopen, removal, integrity");
    }
}
