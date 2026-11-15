package ttr;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Reproduces an old db.xml date and a newer publication service without using real player data. */
public final class DatabaseDateChecks {
    static String mode="service";static int headers,services;
    static final String OLD="06.10.2026-23:23:57",NEW="15.11.2026-03:23:50";
    static void check(boolean v,String m){PlayerProfileChecks.check(v,m);}
    static boolean equal(Calendar a,Calendar b){return a==null?b==null:b!=null&&a.getTimeInMillis()==b.getTimeInMillis();}
    static String body(URL u){
        if(u.getPath().endsWith("db_last_updated.php"))return mode.equals("invalid")?"not a date":NEW;
        if(u.getPath().endsWith("db.xml"))return "<DB name='Date fixture' "+(mode.equals("unknown")?"":"date='"+OLD+"'")+"><PlayerDB name='Players.xml'/><RegionList name='Regions.xml'/><TemplDB name='Templs.xml'/></DB>";
        if(u.getPath().endsWith("Regions.xml"))return "<Regions/>";
        if(u.getPath().endsWith("Templs.xml"))return "<Templates/>";
        return mode.equals("broken")?"<Players><Player id='1'>":"<Players><Player id='64'><name>Дата публикации</name></Player></Players>";
    }
    public static void main(String[] args)throws Exception{
        String previous=System.getProperty("ttwr.home");
        Path root=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(root);
        URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){protected URLConnection openConnection(URL u){return new HttpURLConnection(u){
            public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}
            public long getContentLengthLong(){return body(u).getBytes(StandardCharsets.UTF_8).length;}
            public InputStream getInputStream()throws IOException{
                if(u.getPath().endsWith("db_last_updated.php")){services++;if(mode.equals("unavailable")||mode.equals("unknown"))throw new IOException("Service offline");}
                if(u.getPath().endsWith("db.xml"))headers++;
                return new ByteArrayInputStream(body(u).getBytes(StandardCharsets.UTF_8));
            }
        };}});
        try{
            check(DatabaseDate.parse("29.02.2026-1:2:3")==null&&DatabaseDate.parse(NEW+" junk")==null,"Invalid date accepted");
            check(DatabaseDate.parse("")==null,"Empty date invented");
            check(!DatabaseDate.newer(DatabaseDate.parse(NEW),DatabaseDate.parse(NEW)),"Equal dates obsolete");
            check(DatabaseDate.newer(DatabaseDate.parse("15.11.2026-03:23:51"),DatabaseDate.parse(NEW)),"Time of day ignored");
            check(!DatabaseDate.newer(null,DatabaseDate.parse(NEW)),"Unavailable date treated as update");
            for(String scenario:new String[]{"service","unavailable","invalid","unknown","cancel","broken","rollback"}){
                mode=scenario;headers=services=0;
                Path home=root.resolve(scenario);System.setProperty("ttwr.home",home.toString());
                Path profile=home.resolve("data");DBMain original=PlayerProfileChecks.baseline(profile);original.date=DatabaseDate.parse(OLD);original.players.store().lastInternetUpdate(12345);
                Path header=profile.resolve("DB/db.xml");
                String oldXml=new String(Files.readAllBytes(header),StandardCharsets.UTF_8).replace("<DB name=","<DB date='"+OLD+"' name=");
                Files.write(header,oldXml.getBytes(StandardCharsets.UTF_8));String before=DbPackageTransaction.hash(header);
                try(PlayersUpdateSession session=new PlayersUpdateSession(original)){
                    if(scenario.equals("broken")){
                        try{session.prepare("https://fixture.test/db/db.xml",null);throw new AssertionError("Malformed XML accepted");}catch(IOException expected){}
                        check(services==0,"Date fetched for failed import");
                    }else{
                        session.prepare("https://fixture.test/db/db.xml",null);
                        Calendar expected=scenario.equals("unknown")?null:DatabaseDate.parse(scenario.equals("unavailable")||scenario.equals("invalid")?OLD:NEW);
                        check(equal(session.candidate.date,expected)&&equal(session.publishedDate,expected),"Wrong publication date "+scenario);
                        check(headers==1&&services==1,"Header reread or publication query missing "+scenario);
                        if(scenario.equals("cancel")){
                            session.control.cancel();try{session.apply();throw new AssertionError("Cancel accepted");}catch(java.util.concurrent.CancellationException expectedCancel){}
                        }else if(scenario.equals("rollback")){
                            java.nio.file.OpenOption deny=(java.nio.file.OpenOption)Class.forName("com.sun.nio.file.ExtendedOpenOption").getField("NOSHARE_DELETE").get(null);
                            try(FileChannel locked=FileChannel.open(profile.resolve("DB/Templs.xml"),StandardOpenOption.READ,deny)){
                                try{session.apply();throw new AssertionError("Locked file replaced");}catch(IOException expectedFailure){}
                            }
                        }else{
                            session.apply();DBMain reopened=new DBMain();check(reopened.LoadXML(header.toString(),null),"Reopen failed");
                            check(equal(reopened.date,expected),"Publication date lost after restart "+scenario);
                            check(reopened.players.store().lastInternetUpdate()>12345,"Internet update time not persisted");
                            check(reopened.players.players.size()==1,"Player data missing");reopened.players.store().close();session.closePreparedCatalog();
                        }
                    }
                }
                if(scenario.equals("cancel")||scenario.equals("broken")||scenario.equals("rollback")){
                    check(original.players.store().lastInternetUpdate()==12345,"Failed update changed download time");
                    check(before.equals(DbPackageTransaction.hash(header)),"Failed update changed stored date "+scenario);
                    check(equal(original.date,DatabaseDate.parse(OLD)),"Failed update changed live date");
                    original.players.store().close();
                }
            }
            for(String scenario:new String[]{"service","unavailable","invalid","unknown"}){
                mode=scenario;headers=services=0;
                Calendar latest=DatabaseDate.latest("https://fixture.test/db/db.xml");
                Calendar expected=scenario.equals("service")?DatabaseDate.parse(NEW):scenario.equals("unavailable")?DatabaseDate.parse(OLD):null;
                check(equal(latest,expected),"Freshness lookup fallback differs from original "+scenario);
                check(headers==(scenario.equals("unavailable")||scenario.equals("unknown")?1:0),"Malformed service response incorrectly replaced by old header "+scenario);
            }
            Path home=root.resolve("local-import");System.setProperty("ttwr.home",home.toString());DBMain original=PlayerProfileChecks.baseline(home.resolve("data"));
            Path source=PlayerProfileChecks.source(root.resolve("source"));headers=services=0;
            try(PlayersUpdateSession session=new PlayersUpdateSession(original)){
                session.prepare(null,source);
                check(equal(session.candidate.date,DatabaseDate.parse("15.11.2026-10:20:30")),"Local profile date replaced by remote date");
                check(session.candidate.players.store().lastInternetUpdate()==0,"Local import recorded internet update");
                check(session.publishedDate==null&&services==0,"Local import queried server publication date");
            }original.players.store().close();
            System.out.println("DATABASE_DATE_OK: publication service overrides old header; fallback/unknown; seconds comparison; persisted/reopened; cancel/error/rollback retain old date; local import preserves source date");
        }finally{if(previous==null)System.clearProperty("ttwr.home");else System.setProperty("ttwr.home",previous);}
    }
}
