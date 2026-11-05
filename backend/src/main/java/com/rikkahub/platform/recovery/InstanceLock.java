package com.rikkahub.platform.recovery;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import com.rikkahub.platform.storage.StoragePaths;

/** An OS-held lock; the file remains after close so a new inode cannot bypass a live lock. */
public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private InstanceLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    public static InstanceLock acquire(StoragePaths paths) throws IOException {
        Path file = paths.dataDirectory().resolve(".lock");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException held) { throw new IOException("Another RikkaHub instance is already using this data directory", held); }
            if (lock == null) { throw new IOException("Another RikkaHub instance is already using this data directory"); }
            return new InstanceLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            channel.close(); throw failure;
        }
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) { lock.release(); } }
        finally { channel.close(); }
    }
}
