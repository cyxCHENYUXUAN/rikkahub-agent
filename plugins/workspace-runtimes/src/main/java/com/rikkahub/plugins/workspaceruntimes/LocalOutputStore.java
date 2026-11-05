package com.rikkahub.plugins.workspaceruntimes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Host-owned spill namespace. Files survive close; deletion/retention belongs to the host. */
public final class LocalOutputStore {
    private final LocalPaths paths;
    private final RuntimeLimits limits;
    private final String namespace = UUID.randomUUID().toString();
    private final Set<String> references = new HashSet<>();
    private long retained;

    public LocalOutputStore(Path root, RuntimeLimits limits) { this.paths = new LocalPaths(root); this.limits = limits; }

    synchronized String create() throws IOException {
        if (retained >= limits.maxTotalSpillBytes() || references.size() >= 10000) return null;
        String ref = namespace + "-" + UUID.randomUUID();
        try (var lease = paths.lease(paths.root);
             var file = lease.directory().open(ref, WindowsFile.WRITE, WindowsFile.CREATE, false, false)) {
            references.add(ref); return ref;
        }
    }
    synchronized int append(String ref, byte[] bytes, int offset, int length, long streamRetained) throws IOException {
        int allowed = (int) Math.min(length, Math.min(limits.outputSpillBytes() - streamRetained, limits.maxTotalSpillBytes() - retained));
        if (allowed <= 0) return 0;
        if (!references.contains(ref)) throw new IllegalArgumentException("unknown output reference");
        try (var lease = paths.lease(paths.root);
             var file = lease.directory().open(ref, WindowsFile.WRITE, WindowsFile.OPEN, false, false)) {
            file.appendPosition();
            int written = 0;
            while (written < allowed) {
                int count = file.writeChunk(bytes, offset + written, allowed - written);
                written += count; retained += count; // charge partial writes before any next failure
            }
            return written;
        }
    }
    synchronized byte[] read(String ref, long offset, int maxBytes) {
        if (offset < 0 || maxBytes < 0 || maxBytes > limits.maxFileBytes()) throw new IllegalArgumentException("invalid output range");
        if (!references.contains(ref)) throw new IllegalArgumentException("unknown output reference");
        try (var lease = paths.lease(paths.root);
             var file = lease.directory().open(ref, WindowsFile.READ, WindowsFile.OPEN, false, false)) {
            return file.read(offset, maxBytes);
        }
    }
}
