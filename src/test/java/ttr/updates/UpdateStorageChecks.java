package ttr.updates;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;

public final class UpdateStorageChecks {
    static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
    static void write(Path p,String s)throws Exception{Files.createDirectories(p.getParent());Files.write(p,s.getBytes(StandardCharsets.UTF_8));}
    static void image(Path dir,String version)throws Exception{
        Files.createDirectories(dir.resolve("app/seed"));
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(dir.resolve("app/ttwr.dat")))){jar.putNextEntry(new JarEntry("ttr/build-version.txt"));jar.write((version+"\n").getBytes(StandardCharsets.UTF_8));jar.closeEntry();}
        write(dir.resolve("ttwr-sqlite.exe"),"exe "+version);write(dir.resolve("app/seed/Players.sqlite"),"seed");
        StringBuilder m=new StringBuilder("[");for(String name:Arrays.asList("app/ttwr.dat","ttwr-sqlite.exe","app/seed/Players.sqlite")){
            if(m.length()>1)m.append(',');m.append("{\"path\":\"").append(name).append("\",\"sha256\":\"").append(ReleaseClient.sha256(dir.resolve(name))).append("\"}");
        }write(dir.resolve("checksums.json"),m.append(']').toString());
    }
    static void bundledManifest(Path home)throws Exception{
        Path runtime=home.resolve("runtime/keep");if(!Files.exists(runtime))write(runtime,"preserved runtime");
        Path manifest=home.resolve("checksums.json");String text=new String(Files.readAllBytes(manifest),StandardCharsets.UTF_8).trim();
        text=text.substring(0,text.length()-1)+",{\"path\":\"runtime/keep\",\"sha256\":\""+ReleaseClient.sha256(runtime)+"\"}]";
        write(manifest,text);
    }
    static Path install(Path home,String version,long time)throws Exception{
        Path stage=home.resolve("data/updates/update-"+version);Files.createDirectories(stage);image(stage.resolve("image"),version);
        UpdateInstaller.install(home,stage,version);write(stage.resolve("release.zip"),"download");write(stage.resolve("updater.dat"),"helper");
        Files.setLastModifiedTime(stage.resolve("status.txt"),FileTime.fromMillis(time));return stage;
    }
    public static void main(String[] args)throws Exception{
        Path home=Paths.get(args[0]).resolve("программа 日本語");Files.createDirectories(home);image(home,"0.8.0");
        Map<String,String> protectedFiles=new LinkedHashMap<String,String>();
        for(String name:Arrays.asList("data/DB/Players.sqlite","data/DB/Players.sqlite-wal","data/DB/Players.sqlite-shm","data/ttwr.cfg","data/Tours/keep","data/Backup/keep","runtime/keep")){write(home.resolve(name),"preserve "+name);protectedFiles.put(name,ReleaseClient.sha256(home.resolve(name)));}
        bundledManifest(home);Path first=install(home,"1.0.0",1000);
        bundledManifest(home);Path second=install(home,"1.1.0",2000);
        bundledManifest(home);Path last=install(home,"1.2.0",3000);
        Path latestManifest=last.resolve("backup/checksums.json");String originalManifest=new String(Files.readAllBytes(latestManifest),StandardCharsets.UTF_8);
        // An ordinary update image must still reject runtime entries. Only backup verification exempts them.
        ProgramUpdateChecks.rejects(()->UpdateInstaller.verify(last.resolve("backup"),"1.1.0"));
        Path savedExe=last.resolve("backup/ttwr-sqlite.exe");byte[] saved=Files.readAllBytes(savedExe);Files.delete(savedExe);
        check(UpdateStorage.afterSuccessfulStart(home,"1.2.0").removed==0&&Files.exists(first),"Missing application file waived");Files.write(savedExe,saved);
        write(latestManifest,originalManifest.replace("runtime/keep","runtime/../data/ttwr.cfg"));
        check(UpdateStorage.afterSuccessfulStart(home,"1.2.0").removed==0&&Files.exists(first),"Unsafe runtime path accepted");
        write(latestManifest,originalManifest.replace(ReleaseClient.sha256(home.resolve("runtime/keep")),"bad-digest"));
        check(UpdateStorage.afterSuccessfulStart(home,"1.2.0").removed==0&&Files.exists(first),"Invalid runtime digest accepted");write(latestManifest,originalManifest);
        Path unknown=home.resolve("data/updates/update-not-ours");write(unknown.resolve("user.txt"),"preserve unknown");
        Path failed=home.resolve("data/updates/update-failed");write(failed.resolve("status.txt"),"failed; rollback attempted");write(failed.resolve("backup/user.txt"),"preserve failed");
        Path pending=home.resolve("data/updates/update-pending");write(pending.resolve("status.txt"),"installing 1.3.0");
        Path retired=home.resolve("data/updates/retired-files/keep.md");write(retired,"preserve retired archive");
        // A valid-looking stage containing user data must be left entirely alone.
        Path suspicious=home.resolve("data/updates/update-user-data");Files.createDirectories(suspicious);image(suspicious.resolve("image"),"1.0.0");image(suspicious.resolve("backup"),"0.8.0");write(suspicious.resolve("status.txt"),"installed 1.0.0");write(suspicious.resolve("data/DB/Players.sqlite"),"preserve stray profile");
        // A damaged backup is never used as a reason to erase a previous rollback image.
        Path damaged=home.resolve("data/updates/update-damaged");Files.createDirectories(damaged);image(damaged.resolve("image"),"1.0.0");image(damaged.resolve("backup"),"0.8.0");write(damaged.resolve("status.txt"),"installed 1.0.0");write(damaged.resolve("backup/ttwr-sqlite.exe"),"corrupt");
        UpdateStorage.Report noMatch=UpdateStorage.afterSuccessfulStart(home,"1.1.0");check(noMatch.removed==0&&Files.exists(first),"Unmatched running version pruned backups");
        String backupHash=ReleaseClient.sha256(last.resolve("backup/app/ttwr.dat"));long before=UpdateStorage.bytes(home);
        UpdateStorage.Report r=UpdateStorage.afterSuccessfulStart(home,"1.2.0");
        check(r.removed==2&&!Files.exists(first)&&!Files.exists(second),"Old successful stages remain");
        check(r.bytes<before&&r.removedBytes>0,"Storage accounting did not shrink");
        check(new String(Files.readAllBytes(latestManifest),StandardCharsets.UTF_8).equals(originalManifest),"Original backup manifest changed");
        check(Files.exists(last.resolve("confirmed-start.txt"))&&ReleaseClient.sha256(last.resolve("backup/app/ttwr.dat")).equals(backupHash),"Latest backup changed");
        check(Files.exists(unknown.resolve("user.txt"))&&Files.exists(failed.resolve("backup/user.txt"))&&Files.exists(pending)&&Files.exists(retired)&&Files.exists(suspicious.resolve("data/DB/Players.sqlite"))&&Files.exists(damaged),"Protected update files removed");
        for(Map.Entry<String,String> e:protectedFiles.entrySet())check(ReleaseClient.sha256(home.resolve(e.getKey())).equals(e.getValue()),"Protected data changed: "+e.getKey());
        check(UpdateStorage.afterSuccessfulStart(home,"1.2.0").removed==0,"Cleanup is not idempotent");
        // Junctions/symlinks must not be traversed, even when they are named like an update.
        Path outside=Paths.get(args[0]).resolve("outside");Files.createDirectories(outside);write(outside.resolve("keep.txt"),"preserve outside");
        Path link=home.resolve("data/updates/update-link");boolean linked=false;
        try{Files.createSymbolicLink(link,outside.toAbsolutePath());linked=true;}catch(Exception unsupported){
            if(System.getProperty("os.name").startsWith("Windows"))linked=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(Paths.get(args[0]).resolve("link-probe.log").toFile())).start().waitFor()==0;
        }
        if(linked){UpdateStorage.afterSuccessfulStart(home,"1.2.0");check(Files.exists(outside.resolve("keep.txt"))&&Files.exists(link,LinkOption.NOFOLLOW_LINKS),"External link was changed");}
        System.out.println("UPDATE_STORAGE_OK: latest verified backup, old successes removed, failed/pending/unknown/corrupt/data/runtime/links retained; bytes and idempotence checked");
    }
}
