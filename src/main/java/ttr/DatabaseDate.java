package ttr;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import org.w3c.dom.Element;

/** TTWR publication date, not the download time or the filesystem timestamp. */
public final class DatabaseDate {
    private DatabaseDate(){}
    public static Calendar parse(String text){
        if(text==null||text.trim().isEmpty())return null;
        text=text.trim();
        SimpleDateFormat format=new SimpleDateFormat("d.M.yyyy-H:m:s",Locale.ROOT);
        format.setLenient(false);
        ParsePosition position=new ParsePosition(0);
        Date date=format.parse(text,position);
        if(date==null||position.getIndex()!=text.length())return null;
        Calendar result=new GregorianCalendar();result.setTime(date);return result;
    }
    public static Calendar fromHeader(Element root){return parse(root.getAttribute("date"));}
    public static Calendar copy(Calendar date){return date==null?null:(Calendar)date.clone();}
    public static boolean newer(Calendar published,Calendar installed){
        return published!=null&&(installed==null||published.compareTo(installed)>0);
    }
    /** Preserve the original successful-update rule; reuse the already downloaded header on failure. */
    public static Calendar downloaded(String headerSource,Calendar headerDate){
        Calendar published=parse(service(headerSource));return published==null?copy(headerDate):published;
    }
    public static Calendar latest(String headerSource){
        String reply=service(headerSource);
        if(reply!=null&&!reply.isEmpty())return parse(reply);
        PlayersUpdateControl control=PlayersUpdateControl.current();
        boolean own=control==null;if(own){control=new PlayersUpdateControl();control.enter();}
        try{
            org.w3c.dom.Document header=PlayersUpdateControl.document(headerSource,null);
            control.check();return header==null?null:fromHeader(header.getDocumentElement());
        }finally{if(own)control.leave();}
    }
    private static String service(String headerSource){
        PlayersUpdateControl control=PlayersUpdateControl.current();
        boolean own=control==null;if(own){control=new PlayersUpdateControl();control.enter();}
        HttpURLConnection connection=null;
        try{
            String address=new java.net.URL(new java.net.URL(headerSource),"../db_last_updated.php").toString();
            connection=control.connect(address);
            try(Reader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8))){
                StringBuilder first=new StringBuilder();int c;
                while(first.length()<256&&(c=reader.read())!=-1&&c!='\n'){control.check();if(c!='\r')first.append((char)c);}
                control.check();return first.toString().trim();
            }
        }catch(Exception error){control.check();return null;}
        finally{if(connection!=null)control.release(connection);if(own)control.leave();}
    }
}
