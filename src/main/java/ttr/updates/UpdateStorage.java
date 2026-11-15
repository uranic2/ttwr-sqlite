package ttr.updates;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.*;

/** Retains the latest verified rollback image after a successful application start.
 * Unknown, incomplete, failed or linked update trees are deliberately left alone. */
public final class UpdateStorage {
    private UpdateStorage() {}
    public static final class Report {
        public long bytes, removedBytes;
        public int removed;
        public Path backup;
    }
    private static final Set<String> TOP=new HashSet<String>(Arrays.asList("release.zip","updater.dat","installer.log","status.txt","confirmed-start.txt"));
    private static final class Candidate {
        Path path;
        String version;
        long installedAt;
        List<String> files;
    }
    /** Also rejects directory junctions: no visited path may resolve somewhere else. */
    static void ordinary(Path path)throws IOException {
        BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(a.isSymbolicLink()||a.isOther()||!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException("Linked update path: "+path);
    }
    private static Path root(Path home)throws IOException {
        home=home.toRealPath();Path data=home.resolve("data");ordinary(data);
        Path root=data.resolve("updates");if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return null;
        ordinary(root);return root;
    }
    private static String smallText(Path file)throws IOException {
        ordinary(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("Invalid update marker");
        return new String(Files.readAllBytes(file),StandardCharsets.UTF_8).trim();
    }
    private static String archiveVersion(Path archive)throws IOException {
        try(JarFile jar=new JarFile(archive.toFile())){
            JarEntry entry=jar.getJarEntry("ttr/build-version.txt");
            if(entry==null||entry.getSize()>100)throw new IOException("Missing backup version");
            try(BufferedReader r=new BufferedReader(new InputStreamReader(jar.getInputStream(entry),StandardCharsets.UTF_8))){return AppVersion.stable(r.readLine());}
        }
    }
    private static void treeIsOrdinary(Path stage)throws IOException {
        try(java.util.stream.Stream<Path> walk=Files.walk(stage)){
            Iterator<Path> it=walk.iterator();while(it.hasNext()){
                Path p=it.next();ordinary(p);if(p.equals(stage))continue;
                String name=stage.relativize(p).toString().replace('\\','/');
                if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){
                    if(name.equals("image")||name.equals("backup"))continue;
                    if(!(name.startsWith("image/")||name.startsWith("backup/")))throw new IOException("Unknown update directory");
                    String relative=name.substring(name.indexOf('/')+1);
                    if(!Arrays.asList("app","app/lib","app/seed","assets").contains(relative))throw new IOException("Unknown package directory");
                }else if(!(TOP.contains(name)||name.startsWith("image/")||name.startsWith("backup/")))throw new IOException("Unknown update file");
            }
        }
    }
    private static Candidate candidate(Path stage)throws IOException {
        if(!stage.getFileName().toString().startsWith("update-")||!Files.isDirectory(stage,LinkOption.NOFOLLOW_LINKS))return null;
        treeIsOrdinary(stage);
        Path status=stage.resolve("status.txt");String text=smallText(status);
        if(!text.startsWith("installed "))return null;
        Candidate c=new Candidate();c.path=stage;c.version=AppVersion.stable(text.substring(10));c.installedAt=Files.getLastModifiedTime(status).toMillis();
        c.files=UpdateInstaller.verify(stage.resolve("image"),c.version);
        Path backup=stage.resolve("backup");UpdateInstaller.verifyBackup(backup,archiveVersion(backup.resolve("app/ttwr.dat")));
        return c;
    }
    private static boolean installedMatches(Path home,Candidate c)throws IOException {
        for(String name:c.files){Path file=UpdateInstaller.safe(home,name);if(!Files.isRegularFile(file)||!ReleaseClient.sha256(file).equals(ReleaseClient.sha256(c.path.resolve("image").resolve(name))))return false;}
        return true;
    }
    /** Call only after the main window/profile have successfully opened, while owning AppInstance. */
    public static synchronized Report afterSuccessfulStart(Path home,String version)throws IOException {
        home=home.toRealPath();version=AppVersion.stable(version);Path root=root(home);
        if(root==null)return new Report();
        List<Candidate> candidates=new ArrayList<Candidate>();
        try(DirectoryStream<Path> dirs=Files.newDirectoryStream(root,"update-*")){
            for(Path dir:dirs)try{Candidate c=candidate(dir);if(c!=null)candidates.add(c);}catch(IOException|RuntimeException ignored){/* Preserve anything not fully recognized. */}
        }
        Candidate keep=null;
        for(Candidate c:candidates)if(c.version.equals(version)&&installedMatches(home,c)&&(keep==null||c.installedAt>keep.installedAt))keep=c;
        Report report=new Report();
        if(keep!=null){
            // All application files match the installed image and both backup manifests are valid.
            Files.write(keep.path.resolve("confirmed-start.txt"),Arrays.asList(version),StandardCharsets.UTF_8);
            report.backup=keep.path.resolve("backup");
            for(Candidate c:candidates){
                if(c==keep||c.installedAt>=keep.installedAt||AppVersion.newer(c.version,version))continue;
                try{
                    // Revalidate before deletion. Do not follow links or delete an unknown subtree.
                    Candidate checked=candidate(c.path);if(checked==null||!checked.version.equals(c.version))continue;
                    long bytes=size(c.path);deleteOrdinaryTree(root,c.path);report.removed++;report.removedBytes+=bytes;
                }catch(IOException|RuntimeException ignored){/* Locked/changed tree is retained for a later start. */}
            }
        }
        report.bytes=size(root);return report;
    }
    private static void deleteOrdinaryTree(Path root,Path stage)throws IOException {
        ordinary(root);if(!stage.toAbsolutePath().normalize().getParent().equals(root))throw new IOException("Unexpected update directory");
        treeIsOrdinary(stage);
        List<Path> entries=new ArrayList<Path>();
        try(java.util.stream.Stream<Path> walk=Files.walk(stage)){walk.forEach(entries::add);}
        entries.sort(Comparator.reverseOrder());
        for(Path p:entries){ordinary(p);Files.delete(p);}
    }
    private static long size(Path root)throws IOException {
        final long[] bytes={0};
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException{
                try{ordinary(dir);return FileVisitResult.CONTINUE;}catch(IOException link){return FileVisitResult.SKIP_SUBTREE;}
            }
            public FileVisitResult visitFile(Path file,BasicFileAttributes a){if(a.isRegularFile()&&!a.isSymbolicLink())bytes[0]+=a.size();return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFileFailed(Path file,IOException e){return FileVisitResult.CONTINUE;}
        });return bytes[0];
    }
    public static long bytes(Path home)throws IOException {Path root=root(home);return root==null?0:size(root);}
}
