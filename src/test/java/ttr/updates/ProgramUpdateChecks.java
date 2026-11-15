package ttr.updates;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

public final class ProgramUpdateChecks {
    interface Checked { void run() throws Exception; }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void rejects(Checked action) throws Exception { try { action.run(); } catch(IOException expected) { return; } throw new AssertionError("Invalid input accepted"); }
    static void write(Path path,String text) throws IOException { Files.createDirectories(path.getParent());Files.write(path,text.getBytes(StandardCharsets.UTF_8)); }
    static Path fixture(Path base) throws Exception {
        Path image=base.resolve("fixture");Files.createDirectories(image.resolve("app/seed"));
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(image.resolve("app/ttwr.dat")))) {
            jar.putNextEntry(new JarEntry("ttr/build-version.txt"));jar.write("1.2.0\n".getBytes(StandardCharsets.UTF_8));jar.closeEntry();
        }
        write(image.resolve("ttwr-sqlite.exe"),"new exe");write(image.resolve("app/seed/Players.sqlite"),"empty seed");write(image.resolve("README.md"),"new readme");
        StringBuilder manifest=new StringBuilder("[");
        for(String name:Arrays.asList("app/ttwr.dat","ttwr-sqlite.exe","app/seed/Players.sqlite","README.md")) {
            if(manifest.length()>1)manifest.append(',');manifest.append("{\"path\":\"").append(name).append("\",\"sha256\":\"").append(ReleaseClient.sha256(image.resolve(name))).append("\"}");
        }
        write(image.resolve("checksums.json"),manifest.append(']').toString());
        Path zip=base.resolve("fixture.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip));java.util.stream.Stream<Path> files=Files.walk(image)) {
            Iterator<Path> it=files.iterator();while(it.hasNext()) { Path file=it.next();if(!Files.isRegularFile(file))continue;out.putNextEntry(new ZipEntry(image.relativize(file).toString().replace('\\','/')));Files.copy(file,out);out.closeEntry(); }
        }
        return zip;
    }
    static Path stage(Path base,Path zip) throws Exception { Files.createDirectories(base);Path stage=Files.createTempDirectory(base,"stage-");UpdateInstaller.extract(zip,stage.resolve("image"),"1.2.0");return stage; }
    static String contents(Path path)throws IOException{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    static void initHome(Path home)throws IOException {write(home.resolve("app/ttwr.dat"),"old jar");write(home.resolve("ttwr-sqlite.exe"),"old exe");write(home.resolve("data/DB/Players.sqlite"),"USER DATABASE");write(home.resolve("data/ttwr.cfg"),"USER SETTINGS");write(home.resolve("runtime/bin/java.exe"),"PRESERVE RUNTIME");}
    static String metadata(String digest) {return "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"v1.2.0\",\"assets\":[{\"name\":\"ttwr-sqlite-1.2.0-system-java.zip\",\"browser_download_url\":\"https://github.com/uranic2/ttwr-sqlite/releases/download/v1.2.0/ttwr-sqlite-1.2.0-system-java.zip\",\"size\":4,\"digest\":\"sha256:"+digest+"\"}]}";}
    public static void main(String[] args)throws Exception {
        if(args.length>0&&(args[0].equals("hold")||args[0].equals("hold-encoded"))) {
            Path home=args[0].equals("hold-encoded")?UpdateInstaller.decodePath(args[1]):Paths.get(args[1]);AppInstance.acquire(home);write(home.resolve("data/ready"),"ready");
            long until=System.currentTimeMillis()+20000;while(!Files.exists(home.resolve("data/release"))&&System.currentTimeMillis()<until)Thread.sleep(50);return;
        }
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        check(AppVersion.newer("v0.1.10","0.1.9"),"Lexical version comparison");check(!AppVersion.newer("v0.1.1","0.1.2"),"Downgrade");check(!AppVersion.newer("0.1.2","0.1.2"),"Equal version");rejects(()->AppVersion.newer("v1.0.0-rc1","0.1.2"));
        String hash=String.join("",Collections.nCopies(64,"0"));
        check(ReleaseClient.parseRelease(metadata(hash)).version.equals("1.2.0"),"Release metadata");
        check(ReleaseClient.parseRelease(metadata(hash).replace("\"prerelease\":false","\"prerelease\":true"))==null,"Prerelease accepted");
        rejects(()->ReleaseClient.parseRelease(metadata("bad")));rejects(()->ReleaseClient.parseRelease(metadata(hash).replace("github.com/uranic2","example.com/uranic2")));
        rejects(()->Json.parse("{\"a\":1,\"a\":2}"));rejects(()->Json.parse("[1,]"));check(Json.string(Json.object(Json.parse("{\"name\":\"\\u0418ванов\\n\"}")),"name").startsWith("Иванов"),"Unicode JSON");
        Path zip=fixture(root);Path home=root.resolve("программа с пробелами");initHome(home);
        Path stage=stage(home.resolve("data/updates"),zip);UpdateInstaller.install(home,stage,"1.2.0");
        check(contents(home.resolve("ttwr-sqlite.exe")).equals("new exe"),"Install missing");check(contents(stage.resolve("backup/ttwr-sqlite.exe")).equals("old exe"),"Backup missing");
        check(contents(home.resolve("data/DB/Players.sqlite")).equals("USER DATABASE"),"Database replaced");check(contents(home.resolve("data/ttwr.cfg")).equals("USER SETTINGS"),"Settings replaced");check(contents(home.resolve("runtime/bin/java.exe")).equals("PRESERVE RUNTIME"),"Runtime replaced");
        Path rollback=root.resolve("rollback");initHome(rollback);Path failed=stage(rollback.resolve("data/updates"),zip);final int[] calls={0};
        rejects(()->UpdateInstaller.install(rollback,failed,"1.2.0",(from,target)->{if(++calls[0]==3)throw new IOException("Simulated disk failure");UpdateInstaller.replace(from,target);}));
        check(contents(rollback.resolve("app/ttwr.dat")).equals("old jar"),"Rollback jar");check(contents(rollback.resolve("ttwr-sqlite.exe")).equals("old exe"),"Rollback exe");check(!Files.exists(rollback.resolve("README.md")),"Rollback new file");
        Path damaged=stage(root.resolve("damaged"),zip);write(damaged.resolve("image/app/ttwr.dat"),"corrupt");rejects(()->UpdateInstaller.verify(damaged.resolve("image"),"1.2.0"));
        Path malicious=root.resolve("evil.zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(malicious))){out.putNextEntry(new ZipEntry("../escape"));out.write(1);out.closeEntry();}rejects(()->UpdateInstaller.extract(malicious,root.resolve("evil-output"),"1.2.0"));
        rejects(()->UpdateInstaller.safe(home,"app/../data/DB/Players.sqlite"));check(!UpdateInstaller.allowed("data/DB/Players.sqlite"),"Data allowed");check(!UpdateInstaller.allowed("runtime/bin/java.exe"),"Runtime allowed");
        // Real JVM holds the installation lock; updater times out without modifying the files.
        Path locked=root.resolve("locked");initHome(locked);Path lockedStage=stage(locked.resolve("data/updates"),zip);
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),ProgramUpdateChecks.class.getName(),"hold",locked.toString()).redirectErrorStream(true).redirectOutput(root.resolve("lock-child.log").toFile()).start();
        try {
            long deadline=System.currentTimeMillis()+10000;while(!Files.exists(locked.resolve("data/ready"))&&System.currentTimeMillis()<deadline)Thread.sleep(50);
            check(Files.exists(locked.resolve("data/ready")),"Lock child did not start");
            rejects(()->UpdateInstaller.installWhenClosed(locked,lockedStage,"1.2.0",500));check(contents(locked.resolve("ttwr-sqlite.exe")).equals("old exe"),"Installed while running");
            write(locked.resolve("data/release"),"exit");check(process.waitFor(10,TimeUnit.SECONDS)&&process.exitValue()==0,"Lock child exit");UpdateInstaller.installWhenClosed(locked,lockedStage,"1.2.0",1000);
        } finally { process.destroy(); }
        // Anonymous HTTP simulated at URLConnection: success, bad digest and rate limiting.
        final byte[] payload={1,2,3,4};final int[] status={200};
        URL.setURLStreamHandlerFactory(protocol->!protocol.equals("https")?null:new URLStreamHandler(){protected URLConnection openConnection(URL url){return new HttpURLConnection(url){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}public int getResponseCode(){return status[0];}public InputStream getInputStream(){return new ByteArrayInputStream(url.getHost().equals("api.github.com")?metadata(hash).getBytes(StandardCharsets.UTF_8):payload);}};}});
        check(new ReleaseClient().latest().version.equals("1.2.0"),"HTTP metadata");status[0]=403;rejects(()->new ReleaseClient().latest());status[0]=200;
        Path bytes=root.resolve("bytes");Files.write(bytes,payload);ReleaseClient.Release release=new ReleaseClient.Release("1.2.0",ReleaseClient.REPO+"/fixture",ReleaseClient.sha256(bytes),4);
        new ReleaseClient().download(release,root.resolve("download.zip"));rejects(()->new ReleaseClient().download(new ReleaseClient.Release("1.2.0",release.url,hash,4),root.resolve("bad-download.zip")));
        System.out.println("PROGRAM_UPDATE_OK: versions, metadata, HTTP errors, digest, ZIP paths, manifest, backups, rollback, data/runtime preserved, real process lock and delayed installation");
    }
}
