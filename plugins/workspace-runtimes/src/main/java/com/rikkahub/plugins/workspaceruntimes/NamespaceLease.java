package com.rikkahub.plugins.workspaceruntimes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins a directory chain against rename/deletion. Sharing alone DOES NOT prohibit
 * FILE_WRITE_ATTRIBUTES/reparse mutation. Every child open is therefore native,
 * relative to its pinned parent, and prohibits reparse traversal. No detached
 * pathname is used for I/O through this lease.
 */
final class NamespaceLease implements AutoCloseable {
    private final List<WindowsFile> directories = new ArrayList<>();
    NamespaceLease(Path directory) { this(directory, false); }
    NamespaceLease(Path directory, boolean createDirectories) {
        if (!WindowsOwnership.supported())
            throw new UnsupportedOperationException("Stable filesystem access currently requires Windows");
        Path absolute = directory.toAbsolutePath().normalize();
        try {
            directories.add(WindowsFile.volume(absolute.getRoot()));
            for (Path name : absolute) {
                directories.add(directory().open(name.toString(), WindowsFile.READ,
                        createDirectories ? WindowsFile.OPEN_IF : WindowsFile.OPEN, true, false));
            }
        } catch (RuntimeException e) { close(); throw e; }
    }
    WindowsFile directory() { return directories.get(directories.size() - 1); }
    @Override public void close() {
        for (int i = directories.size() - 1; i >= 0; i--) directories.get(i).close();
        directories.clear();
    }
}
