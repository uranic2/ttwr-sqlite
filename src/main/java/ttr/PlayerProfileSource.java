package ttr;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Read-only input validation and stable snapshot of an original TTWR DB folder. */
public final class PlayerProfileSource {
    public final Path folder;
    private final Map<String,Path> files=new LinkedHashMap<String,Path>();
    private final Map<Path,String> fingerprints=new LinkedHashMap<Path,String>();
    private final Document header;
    public PlayerProfileSource(Path source,Path destination)throws Exception{
        folder=source.toRealPath();Path target=destination.toAbsolutePath().normalize();
        Path existing=target;while(!Files.exists(existing)&&existing.getParent()!=null)existing=existing.getParent();
        target=existing.toRealPath().resolve(existing.relativize(target)).normalize();
        if(folder.startsWith(target)||target.startsWith(folder))throw new IOException("Источник и назначение перекрываются: "+folder+" / "+target);
        Path headerPath=local("db.xml");String headerBefore=fingerprint(headerPath);header=parse(headerPath,"DB");files.put("db.xml",headerPath);
        String[] sections={"PlayerDB","TemplDB","RegionList"},names={"Players.xml","Templs.xml","Regions.xml"};
        for(int i=0;i<sections.length;i++){
            NodeList elements=header.getDocumentElement().getElementsByTagName(sections[i]);
            if(elements.getLength()!=1)throw new IOException("В db.xml нужна одна ссылка "+sections[i]+": "+headerPath);
            String name=((Element)elements.item(0)).getAttribute("name");
            files.put(names[i],local(name));
        }
        if(new HashSet<Path>(files.values()).size()!=4)throw new IOException("Заголовок и три XML должны быть разными файлами: "+folder);
        for(Path file:files.values())fingerprints.put(file,fingerprint(file));
        if(!headerBefore.equals(fingerprints.get(headerPath)))throw new IOException("Заголовок изменился во время проверки: "+headerPath);
    }
    private Path local(String name)throws Exception{
        if(name.isEmpty()||name.contains(":")||name.startsWith("/")||name.startsWith("\\"))throw new IOException("Недопустимая ссылка в db.xml: "+name);
        Path relative=Paths.get(name);
        if(relative.isAbsolute())throw new IOException("Абсолютная ссылка: "+name);
        for(Path part:relative)if(part.toString().equals(".."))throw new IOException("Выход из папки DB: "+name);
        Path file=folder.resolve(relative).normalize();
        DbPackageTransaction.safeTree(folder,file);
        if(!Files.isRegularFile(file)||!file.toRealPath().startsWith(folder))throw new IOException("Файл не найден или находится вне DB: "+file);
        return file;
    }
    public void snapshot(Path target,PlayersUpdateControl control)throws Exception{
        Files.createDirectories(target);
        verify();
        for(Map.Entry<String,Path> file:files.entrySet()){
            control.check();DbPackageTransaction.copy(file.getValue(),target.resolve(file.getKey()),control);
        }
        verify();
        parse(target.resolve("Templs.xml"),"Templates");parse(target.resolve("Regions.xml"),"Regions");
        Document normalized=parse(target.resolve("db.xml"),"DB");
        if(!normalized.getDocumentElement().hasAttribute("name"))normalized.getDocumentElement().setAttribute("name","TTW rating");
        String[] sections={"PlayerDB","TemplDB","RegionList"},names={"Players.xml","Templs.xml","Regions.xml"};
        for(int i=0;i<sections.length;i++)((Element)normalized.getDocumentElement().getElementsByTagName(sections[i]).item(0)).setAttribute("name",names[i]);
        if(!Utility.SaveXMLDocument(target.resolve("db.xml").toString(),normalized))throw new IOException("Не удалось подготовить заголовок");
        Document templates=parse(target.resolve("Templs.xml"),"Templates");
        NodeList list=templates.getDocumentElement().getElementsByTagName("Template");
        for(int i=0;i<list.getLength();i++){
            control.check();Element element=(Element)list.item(i);Integer.parseInt(element.getAttribute("id"),16);
            if(!new TTemplate().LoadXML(element))throw new IOException("Повреждён шаблон в "+files.get("Templs.xml"));
        }
    }
    public void verify()throws Exception{
        for(Map.Entry<Path,String> file:fingerprints.entrySet()){
            DbPackageTransaction.safeTree(folder,file.getKey());
            if(!file.getValue().equals(fingerprint(file.getKey())))throw new IOException("Исходный профиль изменился: "+file.getKey()+". Закройте TTWR и повторите импорт.");
        }
    }
    static Document parse(Path path,String root)throws Exception{
        try(InputStream input=Files.newInputStream(path)){
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            Document document=f.newDocumentBuilder().parse(input);
            if(!root.equals(document.getDocumentElement().getTagName()))throw new IOException("Ожидается XML "+root+": "+path);
            return document;
        }catch(Exception e){throw new IOException("Не удалось проверить XML: "+path,e);}
    }
    private static String fingerprint(Path file)throws Exception{
        BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class);
        return a.size()+":"+a.lastModifiedTime()+":"+DbPackageTransaction.hash(file);
    }
    public static boolean safeRemoteName(String name){return name!=null&&!name.isEmpty()&&!name.equals(".")&&!name.equals("..")&&!name.matches(".*[\\\\/:<>\"|?*].*");}
    public static Path defaultFolder(){
        String appData=System.getenv("APPDATA");
        return appData==null?null:Paths.get(appData,"TTWR","DB");
    }
}
