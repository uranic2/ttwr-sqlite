package ttr;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Shared operation ownership for network updates and local TTWR profile imports. */
public final class PlayersUpdateSession implements AutoCloseable {
    public final PlayersUpdateControl control=new PlayersUpdateControl();
    public final PlayersProgress progress;
    public final DBMain candidate=new DBMain();
    private final DBMain original;
    private final List<String> countries,regions,towns,regionNames;
    private Path directory;
    private boolean applied,keepRecovery;
    private PlayerProfileSource source;
    public Path backup;
    public Calendar publishedDate;
    public PlayersUpdateSession(DBMain original){this(original,new PlayersProgress());}
    public PlayersUpdateSession(DBMain original,PlayersProgress progress){
        this.progress=progress;
        this.original=original;
        countries=new ArrayList<String>(Player.countryList);regions=new ArrayList<String>(Player.regionList2);
        towns=new ArrayList<String>(Player.townList);regionNames=new ArrayList<String>(Player.regionList);
    }
    public void prepare(String remote,Path local)throws Exception{
        control.enter();
        try{
            Path profile=PortablePaths.profile();Files.createDirectories(profile);
            directory=Files.createTempDirectory(profile,".players-operation-");
            String input=remote;
            if(local!=null){
                progress.OnSectionStarted("Проверка и копирование профиля TTWR");
                source=new PlayerProfileSource(local,profile.resolve("DB"));
                Path snapshot=directory.resolve("input");source.snapshot(snapshot,control);input=snapshot.resolve("db.xml").toString();
            }
            candidate.bUpdating=true;
            if(!candidate.LoadXML(input,progress)){
                control.check();
                throw new IOException("Не удалось загрузить базу игроков: "+input,control.error());
            }
            control.check();
            if(local==null){
                progress.OnSectionStarted("Получение даты публикации базы");
                candidate.date=DatabaseDate.downloaded(input,candidate.date);
                publishedDate=DatabaseDate.copy(candidate.date);
            }
            control.check();progress.OnSectionStarted("Подготовка результата");
            candidate.players.file="Players.sqlite";candidate.players.fileRegions="Regions.xml";candidate.templates.file="Templs.xml";
            candidate.players.store().lastInternetUpdate(local==null?System.currentTimeMillis():0);
            if(!candidate.SaveXML(directory.resolve("db.xml").toString(),true))throw new IOException("Не удалось записать временную базу",control.error());
            if(local!=null){
                // Keep the validated original template/region XML byte for byte.
                Files.copy(directory.resolve("input/Templs.xml"),directory.resolve("Templs.xml"),StandardCopyOption.REPLACE_EXISTING);
                Files.copy(directory.resolve("input/Regions.xml"),directory.resolve("Regions.xml"),StandardCopyOption.REPLACE_EXISTING);
                source.verify();
            }
            candidate.templates.file="Templs.xml";
            control.check();
        }finally{candidate.bUpdating=false;control.leave();}
    }
    public void apply()throws Exception{
        control.enter();
        DbPackageTransaction transaction=null;
        try{
            progress.OnSectionStarted("Создание резервной копии");
            transaction=new DbPackageTransaction(PortablePaths.profile(),directory);
            transaction.snapshot(control);
            if(source!=null)source.verify();
            control.applying();progress.OnSectionStarted(PlayersProgress.APPLY);
            try{
                transaction.install(directory,original);
                candidate.rebase(PortablePaths.profile().resolve("DB/db.xml"));
                transaction.commit();applied=true;backup=transaction.backup();
            }catch(Exception failure){
                try{
                    if(candidate.players.existing()!=null)candidate.players.existing().close();
                    transaction.rollback();
                    if(original!=null){original.players.useWithoutClosing(new SqlitePlayersStore(PortablePaths.profile().resolve("DB/Players.sqlite")));original.players.UpdatePlayerLocationList(true);}
                }catch(Exception rollback){keepRecovery=true;failure.addSuppressed(rollback);throw new IOException("Не удалось восстановить комплект. Перезапустите программу; журнал: "+PortablePaths.profile().resolve("db-package.pending"),failure);}
                throw new IOException("Обновление не применено. Прежний комплект сохранён или восстановлен.",failure);
            }
        }finally{control.leave();}
    }
    public boolean recoveryRequired(){return keepRecovery||Files.exists(PortablePaths.profile().resolve("db-package.pending"));}
    public void closePreparedCatalog(){if(candidate.players.existing()!=null)candidate.players.existing().close();}
    private static void restore(List<String> target,List<String> saved){target.clear();target.addAll(saved);}
    public void close()throws Exception{
        Exception error=null;
        try{if(!applied&&candidate.players.existing()!=null)candidate.players.existing().close();}catch(Exception e){error=e;}
        if(!applied){restore(Player.countryList,countries);restore(Player.regionList2,regions);restore(Player.townList,towns);restore(Player.regionList,regionNames);}
        if(directory!=null&&!keepRecovery){
            try{clean(directory);}catch(Exception e){if(error==null)error=e;else error.addSuppressed(e);}
        }
        if(error!=null)throw new IOException("Не удалось очистить временные файлы: "+directory,error);
    }
    private static void clean(Path folder)throws IOException{
        // Walk without FOLLOW_LINKS. Reject junctions before entering each directory.
        Files.walkFileTree(folder,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path path,java.nio.file.attribute.BasicFileAttributes a)throws IOException{DbPackageTransaction.safeTree(folder,path);return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes a)throws IOException{Files.delete(path);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException{if(error!=null)throw error;Files.delete(path);return FileVisitResult.CONTINUE;}
        });
    }
}
