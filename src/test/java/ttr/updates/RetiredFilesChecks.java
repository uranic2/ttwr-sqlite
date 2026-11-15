package ttr.updates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class RetiredFilesChecks {
    static void write(Path file,String text)throws IOException {Files.createDirectories(file.getParent());Files.write(file,text.getBytes(StandardCharsets.UTF_8));}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path base=Paths.get(args[0]);Files.createDirectories(base);
        Path home=base.resolve("программа 日本語");Files.createDirectories(home);
        String[] protectedFiles={"data/DB/Players.sqlite","data/DB/Players.sqlite-wal","data/DB/Players.sqlite-shm","data/ttwr.cfg","docs/user-notes.md","runtime/keep.txt"};
        Map<String,String> hashes=new LinkedHashMap<String,String>();
        for(String name:protectedFiles){write(home.resolve(name),"preserve "+name);hashes.put(name,ReleaseClient.sha256(home.resolve(name)));}
        Path document=home.resolve("docs/source-provenance.md");write(document,"retired test document");String original=ReleaseClient.sha256(document);
        Path backup=RetiredFiles.cleanup(home);
        check(!Files.exists(document),"Retired document remains");check(backup!=null&&ReleaseClient.sha256(backup).equals(original),"Backup missing or changed");
        for(String name:protectedFiles)check(ReleaseClient.sha256(home.resolve(name)).equals(hashes.get(name)),"Protected file changed: "+name);
        check(RetiredFiles.cleanup(home)==null,"Cleanup is not idempotent");
        write(document,"retired test document");check(RetiredFiles.cleanup(home).equals(backup),"Identical backup should be reused");
        write(document,"changed retired document");
        Path conflicting=home.resolve("data/updates/retired-files/"+ReleaseClient.sha256(document)+".md");write(conflicting,"invalid existing backup");
        boolean refusedBackup=false;try{RetiredFiles.cleanup(home);}catch(IOException expected){refusedBackup=true;}
        check(refusedBackup&&Files.exists(document),"A failed backup deleted the original document");
        Path empty=base.resolve("empty");Files.createDirectories(empty);check(RetiredFiles.cleanup(empty)==null&&!Files.exists(empty.resolve("data")),"Missing document created a profile");
        Path external=base.resolve("outside");write(external.resolve("source-provenance.md"),"outside document");
        Path linked=base.resolve("linked");Files.createDirectories(linked);boolean linkCreated=false;
        try{Files.createSymbolicLink(linked.resolve("docs"),external.toAbsolutePath());linkCreated=true;}catch(IOException|UnsupportedOperationException unavailable){System.out.println("SYMLINK_TEST_SKIPPED: privilege or platform unavailable");}
        if(linkCreated){
            boolean refused=false;try{RetiredFiles.cleanup(linked);}catch(IOException expected){refused=true;}
            check(refused&&Files.exists(external.resolve("source-provenance.md")),"Cleanup followed a link outside the program folder");
        }
        System.out.println("RETIRED_FILES_OK: exact document removed, verified backup, idempotence, SQLite/settings/WAL/SHM/runtime/other docs unchanged");
    }
}
