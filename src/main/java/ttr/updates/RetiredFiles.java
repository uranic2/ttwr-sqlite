package ttr.updates;

import java.io.IOException;
import java.nio.file.*;

/** Targeted migration for a retired program document, including upgrades by older helpers. */
public final class RetiredFiles {
    private RetiredFiles() {}
    public static Path cleanup(Path home) throws IOException {
        Path root=home.toRealPath();
        Path document=UpdateInstaller.safe(root,"docs/source-provenance.md");
        if(!Files.exists(document,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(document,LinkOption.NOFOLLOW_LINKS))throw new IOException("Retired document is not a regular file");
        String digest=ReleaseClient.sha256(document);
        Path directory=UpdateInstaller.safe(root,"data/updates/retired-files");
        Files.createDirectories(directory);
        Path backup=UpdateInstaller.safe(root,"data/updates/retired-files/"+digest+".md");
        if(!Files.exists(backup,LinkOption.NOFOLLOW_LINKS))Files.copy(document,backup);
        if(!Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS)||!ReleaseClient.sha256(backup).equals(digest))throw new IOException("Retired document backup differs from source");
        if(!ReleaseClient.sha256(document).equals(digest))throw new IOException("Retired document changed during backup; it was retained");
        Files.delete(document);
        return backup;
    }
}
