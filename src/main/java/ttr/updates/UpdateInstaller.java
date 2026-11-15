package ttr.updates;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.swing.JOptionPane;

/** Runs from a private copy of the old archive, after the application has exited. */
public final class UpdateInstaller {
    private UpdateInstaller() {}
    static String encodePath(Path path) { return Base64.getUrlEncoder().encodeToString(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8)); }
    static Path decodePath(String value) { return Paths.get(new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8)); }
    public static ProcessBuilder helperProcess(Path home,Path stage,String version) {
        // Windows Java launchers can lose non-ANSI arguments. Keep command-line arguments ASCII,
        // and load the helper archive relative to its Unicode-aware working directory.
        String java=Paths.get(System.getProperty("java.home"),"bin","javaw.exe").toString();
        return new ProcessBuilder(java,"-cp","updater.dat",UpdateInstaller.class.getName(),"--encoded-paths",encodePath(home),encodePath(stage),version).directory(stage.toFile());
    }
    private static final long MAX_EXPANDED = 256L * 1024 * 1024;
    static boolean allowed(String name) {
        if (name.equals("ttwr-sqlite.exe") || name.equals("checksums.json")) return true;
        if (Arrays.asList("README.md","changes.md","building.md","testing.md","architecture.md","program-updates.md","legacy-updater-audit.md","THIRD_PARTY_NOTICES.md").contains(name)) return true;
        if (Arrays.asList("assets/README.md","assets/sqlite-logo.svg","assets/ttwr-sqlite.svg","assets/ttwr-sqlite.png","assets/ttwr-sqlite.ico").contains(name)) return true;
        if (name.equals("app/ttwr.dat")) return true;
        if (name.matches("app/lib/[A-Za-z0-9._-]+\\.jar")) return true;
        return Arrays.asList("app/seed/Players.sqlite","app/seed/Regions.xml","app/seed/Templs.xml","app/seed/db.xml").contains(name);
    }
    static Path safe(Path root, String name) throws IOException {
        if (name.isEmpty() || name.contains("\\") || name.contains(":") || name.startsWith("/") || name.endsWith("/") || name.contains("//")) throw new IOException("Unsafe update path: " + name);
        for (String part : name.split("/")) if (part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) throw new IOException("Unsafe update path: " + name);
        Path result = root.resolve(name).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new IOException("Update path escapes root");
        for (Path part = result; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part) || (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && !part.toRealPath().startsWith(root.toRealPath()))) throw new IOException("Update path is a link: " + part);
        }
        return result;
    }
    static void extract(Path zip, Path destination, String version) throws IOException {
        Files.createDirectory(destination);
        Set<String> seen = new HashSet<String>(); long total = 0; int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 1000) throw new IOException("Too many ZIP entries");
                String name = entry.getName();
                if (entry.isDirectory()) { safe(destination, name.substring(0, name.length()-1)); continue; }
                if (!allowed(name) || !seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Unexpected ZIP file: " + name);
                Path target = safe(destination, name); Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) { total += ReleaseClient.copy(input, output, MAX_EXPANDED-total); }
                input.closeEntry();
            }
        }
        verify(destination, version);
    }
    static List<String> verify(Path directory, String version) throws IOException { return verify(directory,version,false); }
    /** A rollback image contains replaced application files; bundled Java stays in the installation. */
    static List<String> verifyBackup(Path directory,String version)throws IOException { return verify(directory,version,true); }
    private static List<String> verify(Path directory, String version,boolean backup) throws IOException {
        Path manifest = safe(directory, "checksums.json");
        if (Files.size(manifest) > 1024 * 1024) throw new IOException("Manifest too large");
        String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        Set<String> seen = new HashSet<String>(); List<String> files = new ArrayList<String>();
        for (Object value : Json.array(Json.parse(text))) {
            Map<String,Object> item = Json.object(value); String name = Json.string(item, "path");
            boolean retainedRuntime=backup&&name.startsWith("runtime/");
            if ((!allowed(name)&&!retainedRuntime) || name.equals("checksums.json") || !seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Invalid manifest path");
            Path file = safe(directory, name);
            if(retainedRuntime){
                // Validate the path/digest but never waive a missing application file.
                if(!Json.string(item,"sha256").matches("[0-9a-fA-F]{64}")||Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid retained runtime entry");
                continue;
            }
            if (!Files.isRegularFile(file) || !ReleaseClient.sha256(file).equals(Json.string(item, "sha256"))) throw new IOException("File checksum mismatch: " + name);
            files.add(name);
        }
        if (!files.contains("app/ttwr.dat") || !files.contains("ttwr-sqlite.exe") || !files.contains("app/seed/Players.sqlite")) throw new IOException("Incomplete update");
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            Iterator<Path> it = paths.iterator();
            while (it.hasNext()) {
                Path file = it.next();
                if (Files.isSymbolicLink(file)) throw new IOException("Symlink in update");
                if (!Files.isRegularFile(file)) continue;
                String name = directory.relativize(file).toString().replace('\\','/');
                if (!name.equals("checksums.json") && !seen.contains(name.toLowerCase(Locale.ROOT))) throw new IOException("Unlisted update file: " + name);
            }
        }
        try (JarFile jar = new JarFile(directory.resolve("app/ttwr.dat").toFile())) {
            JarEntry entry = jar.getJarEntry("ttr/build-version.txt");
            if (entry == null || entry.getSize() > 100) throw new IOException("Missing archive version");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                if (!version.equals(reader.readLine())) throw new IOException("Archive version differs from release");
            }
        }
        Collections.sort(files); files.add("checksums.json"); return files;
    }
    static void replace(Path from, Path target) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            Path temporary = null;
            try {
                Files.createDirectories(target.getParent());
                temporary = Files.createTempFile(target.getParent(), ".ttwr-update-", ".tmp");
                Files.copy(from, temporary, StandardCopyOption.REPLACE_EXISTING);
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch (IOException ex) { failure = ex; }
            finally { if (temporary != null) Files.deleteIfExists(temporary); }
            try { Thread.sleep(250); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        }
        throw failure;
    }
    interface Copier { void copy(Path from, Path target) throws IOException; }
    static void install(Path home, Path stage, String version) throws IOException { install(home,stage,version,UpdateInstaller::replace); }
    static void install(Path home, Path stage, String version, Copier copier) throws IOException {
        Path image = stage.resolve("image"); List<String> files = verify(image, version);
        Path backup = stage.resolve("backup"); Files.createDirectory(backup);
        Set<String> existed = new HashSet<String>();
        // Complete the backup and check every destination before changing the installation.
        for (String name : files) {
            Path target = safe(home, name);
            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) throw new IOException("Destination is not a file: " + name);
                Path copy = safe(backup, name); Files.createDirectories(copy.getParent()); Files.copy(target, copy); existed.add(name);
            }
        }
        Files.write(stage.resolve("status.txt"), Arrays.asList("installing " + version), StandardCharsets.UTF_8);
        List<String> changed = new ArrayList<String>();
        try {
            for (String name : files) { changed.add(name); copier.copy(image.resolve(name), safe(home, name)); }
        } catch (IOException failure) {
            Collections.reverse(changed);
            for (String name : changed) try {
                if (existed.contains(name)) copier.copy(backup.resolve(name), safe(home,name)); else Files.deleteIfExists(safe(home,name));
            } catch (IOException rollback) { failure.addSuppressed(rollback); }
            Files.write(stage.resolve("status.txt"), Arrays.asList("failed; rollback attempted", failure.toString()), StandardCharsets.UTF_8);
            throw failure;
        }
        Files.write(stage.resolve("status.txt"), Arrays.asList("installed " + version), StandardCharsets.UTF_8);
    }
    static void installWhenClosed(Path home, Path stage, String version, long timeoutMillis) throws Exception {
        try (FileChannel channel = FileChannel.open(home.resolve("data/application.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null; long deadline = System.nanoTime() + timeoutMillis * 1000000L;
            while (lock == null && System.nanoTime() < deadline) { lock = channel.tryLock(); if (lock == null) Thread.sleep(250); }
            if (lock == null) throw new IOException("Программа не завершилась за 2 минуты. Обновление не установлено.");
            try { install(home, stage, version); } finally { lock.release(); }
        }
    }
    public static void main(String[] args) {
        try {
            boolean encoded=args.length==4&&args[0].equals("--encoded-paths");
            Path home = (encoded?decodePath(args[1]):Paths.get(args[0])).toRealPath(), stage = (encoded?decodePath(args[2]):Paths.get(args[1])).toRealPath();
            String version = AppVersion.stable(args[encoded?3:2]);
            if (!stage.getParent().equals(home.resolve("data/updates").toRealPath())) throw new IOException("Unexpected staging directory");
            // Main JVM holds this lock through its shutdown hooks; a new instance cannot start during installation.
            installWhenClosed(home, stage, version, 120000);
            new ProcessBuilder(home.resolve("ttwr-sqlite.exe").toString()).directory(home.toFile()).start();
        } catch (Throwable error) {
            error.printStackTrace();
            JOptionPane.showMessageDialog(null, "Не удалось завершить обновление.\n" + error.getMessage()
                    + "\nРезервная копия и журнал находятся в data/updates.\nЕсли установка уже завершена, запустите ttwr-sqlite.exe вручную.", "Обновление TTWR SQLite", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
