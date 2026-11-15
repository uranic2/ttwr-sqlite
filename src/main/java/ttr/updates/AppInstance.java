package ttr.updates;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** A running application and the installer must never own the installation together. */
public final class AppInstance {
    private static FileChannel channel;
    private static FileLock lock;
    private AppInstance() {}
    public static synchronized void acquire(Path home) throws IOException {
        if (lock != null) return;
        Files.createDirectories(home.resolve("data"));
        channel = FileChannel.open(home.resolve("data/application.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) { channel.close(); channel = null; throw new IOException("Программа уже запущена или выполняется её обновление."); }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (AppInstance.class) {
                try { if (lock != null) lock.release(); if (channel != null) channel.close(); } catch (IOException ignored) {}
            }
        }, "TTWR-instance-unlock"));
    }
}
