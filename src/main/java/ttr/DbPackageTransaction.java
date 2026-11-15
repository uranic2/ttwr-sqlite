package ttr;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Recoverable four-file DB replacement shared by download and TTWR profile import. */
public final class DbPackageTransaction {
    static final String[] FILES={"Players.sqlite","Templs.xml","Regions.xml","db.xml"};
    private final Path profile,db,marker;
    private Path backup;
    private final Properties journal=new Properties();
    private boolean installed;
    public DbPackageTransaction(Path profile,Path temporary)throws Exception{
        this.profile=profile.toAbsolutePath().normalize();
        Files.createDirectories(this.profile);db=this.profile.resolve("DB");Files.createDirectories(db);
        marker=this.profile.resolve("db-package.pending");
        if(Files.exists(marker))throw new IOException("Есть незавершённая замена базы: "+marker+". Перезапустите программу для восстановления.");
        safeTree(this.profile,db);
        Path backups=this.profile.resolve("Backup");Files.createDirectories(backups);safeTree(this.profile,backups);
        backup=temporary.resolve("rollback");Files.createDirectory(backup);
    }
    public Path backup(){return backup;}
    static void safeTree(Path root,Path target)throws IOException{
        Path base=root.toAbsolutePath().normalize(),path=target.toAbsolutePath().normalize();
        if(!path.startsWith(base))throw new IOException("Путь вне каталога: "+path);
        Path cursor=base;
        if(Files.isSymbolicLink(cursor))throw new IOException("Ссылка недопустима: "+cursor);
        for(Path part:base.relativize(path)){
            cursor=cursor.resolve(part);
            if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)){
                if(Files.isSymbolicLink(cursor)||!cursor.toRealPath().startsWith(base.toRealPath()))throw new IOException("Ссылка за пределы каталога: "+cursor);
            }
        }
    }
    public void snapshot(PlayersUpdateControl control)throws Exception{
        // Snapshot the whole DB directory, including all SQLite scopes; never copy live WAL.
        try(java.util.stream.Stream<Path> walk=Files.walk(db)){
            Iterator<Path> paths=walk.iterator();
            while(paths.hasNext()){
                control.check();Path source=paths.next();safeTree(db,source);
                Path relative=db.relativize(source),target=backup.resolve(relative);
                if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)){Files.createDirectories(target);continue;}
                if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Недопустимый файл базы: "+source);
                String name=source.getFileName().toString();
                if(name.endsWith("-wal")||name.endsWith("-shm"))continue;
                if(name.toLowerCase(Locale.ROOT).endsWith(".sqlite")){
                    try(SqlitePlayersStore store=new SqlitePlayersStore(source)){store.copyTo(target);}
                    try(SqlitePlayersStore check=new SqlitePlayersStore(target)){if(!"ok".equals(check.integrity()))throw new IOException("Повреждена резервная база: "+target);}
                }else copy(source,target,control);
            }
        }
        for(String name:FILES){
            Path file=db.resolve(name);boolean exists=Files.exists(file);
            journal.setProperty(name+".exists",Boolean.toString(exists));
            if(exists)journal.setProperty(name+".sha256",hash(backup.resolve(name)));
        }

        journal.setProperty("state","prepared");
        journal.setProperty("write",".db-package-write-"+UUID.randomUUID()+".tmp");
        control.check();
    }
    public void install(Path stage,DBMain original)throws Exception{
        for(String name:FILES){safeTree(stage,stage.resolve(name));if(!Files.isRegularFile(stage.resolve(name)))throw new IOException("Неполный результат: "+stage.resolve(name));}
        Path saved=profile.resolve("Backup").resolve("DB-before-import-"+UUID.randomUUID());
        Files.move(backup,saved);backup=saved;
        journal.setProperty("backup",profile.relativize(backup).toString());
        SqliteLifecycle.closePath(db.resolve("Players.sqlite"));
        if(original!=null&&original.players.existing()!=null)original.players.existing().close();
        saveJournal();
        installed=true;
        try{
            for(String name:FILES){
                safeTree(profile,db.resolve(name));replace(stage.resolve(name),db.resolve(name),writePath(profile,journal));
                if(!hash(stage.resolve(name)).equals(hash(db.resolve(name))))throw new IOException("Проверка записи не пройдена: "+db.resolve(name));
            }
        }catch(Exception e){throw e;}
    }
    public void commit()throws Exception{
        journal.setProperty("state","committed");saveJournal();Files.delete(marker);installed=false;
    }
    public void rollback()throws Exception{if(installed){restore(profile,journal);Files.delete(marker);installed=false;}}
    private void saveJournal()throws Exception{
        Path temp=Files.createTempFile(profile,".db-journal-",".tmp");
        try{try(OutputStream out=Files.newOutputStream(temp)){journal.store(out,"TTWR DB package recovery");}force(temp);move(temp,marker);}
        finally{Files.deleteIfExists(temp);}
    }
    public static void recover(Path profile)throws Exception{
        Path marker=profile.resolve("db-package.pending");
        if(!Files.exists(marker))return;
        safeTree(profile,marker);
        Properties journal=new Properties();try(InputStream in=Files.newInputStream(marker)){journal.load(in);}
        Path write=writePath(profile,journal);safeTree(profile,write);Files.deleteIfExists(write);
        if(!"committed".equals(journal.getProperty("state")))restore(profile,journal);
        Files.delete(marker);
    }
    private static void restore(Path profile,Properties journal)throws Exception{
        String relative=journal.getProperty("backup","");
        Path backup=profile.resolve(relative).normalize(),db=profile.resolve("DB");
        if(relative.isEmpty()||!backup.startsWith(profile.resolve("Backup").normalize()))throw new IOException("Недопустимый журнал восстановления");
        safeTree(profile,backup);safeTree(profile,db);
        // Verify every rollback file before touching any destination.
        for(String name:FILES){
            String exists=journal.getProperty(name+".exists");
            if(!"true".equals(exists)&&!"false".equals(exists))throw new IOException("Неполный журнал: "+name);
            if("true".equals(exists)){safeTree(backup,backup.resolve(name));if(!hash(backup.resolve(name)).equals(journal.getProperty(name+".sha256")))throw new IOException("Повреждена резервная копия: "+backup.resolve(name));}
        }
        for(String name:FILES){
            Path target=db.resolve(name);safeTree(profile,target);
            if(Boolean.parseBoolean(journal.getProperty(name+".exists"))){
                if(!Files.isRegularFile(target)||!hash(target).equals(journal.getProperty(name+".sha256")))replace(backup.resolve(name),target,writePath(profile,journal));
            }
            else Files.deleteIfExists(target);
        }
    }
    static void copy(Path source,Path target,PlayersUpdateControl control)throws Exception{
        try(InputStream in=Files.newInputStream(source);OutputStream out=Files.newOutputStream(target)){
            byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1){control.check();out.write(buffer,0,count);}
        }
        force(target);
    }
    private static Path writePath(Path profile,Properties journal)throws IOException{
        String name=journal.getProperty("write","");
        if(!name.matches("[.]db-package-write-[0-9a-f-]{36}[.]tmp"))throw new IOException("Недопустимый временный путь в журнале");
        return profile.resolve(name);
    }
    private static void replace(Path source,Path target,Path temp)throws Exception{
        try{Files.copy(source,temp);force(temp);move(temp,target);}finally{Files.deleteIfExists(temp);}
    }
    private static void move(Path from,Path to)throws IOException{
        try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void force(Path file)throws IOException{try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}}
    static String hash(Path file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){PlayersUpdateControl.checkpoint();digest.update(b,0,n);}}
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
}
