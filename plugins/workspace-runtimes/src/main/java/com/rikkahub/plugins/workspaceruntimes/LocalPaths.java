package com.rikkahub.plugins.workspaceruntimes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;

/** Lexical policy plus bound native directory traversal; does not sandbox processes. */
final class LocalPaths {
    final Path root;
    LocalPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
        for (Path name : this.root) validateName(name.toString());
        try (var lease = new NamespaceLease(this.root, true)) { /* provision through bound parents */ }
    }
    Path resolve(Path input) {
        if (input == null) throw new NullPointerException("path");
        for (Path name : input) {
            if (name.toString().equals("..")) throw denied();
            validateName(name.toString());
        }
        Path path = root.resolve(input).normalize();
        if (!path.startsWith(root)) throw denied();
        return path;
    }
    NamespaceLease lease(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) throw denied();
        return new NamespaceLease(absolute);
    }
    NamespaceLease parentLease(Path file, boolean create) {
        if (file.equals(root) || !file.startsWith(root)) throw denied();
        return new NamespaceLease(file.getParent(), create);
    }
    static void validateName(String name) {
        if (name.isEmpty() || name.equals(".")) return;
        if (name.equals("..") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('/') >= 0 || name.endsWith(".") || name.endsWith(" ")) throw denied();
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")
                || stem.equals("CONIN$") || stem.equals("CONOUT$") || stem.matches("(COM|LPT)[0-9¹²³]")) throw denied();
    }
    static IllegalArgumentException denied() { return new IllegalArgumentException("workspace path denied"); }
    static UncheckedIOException io(IOException e) { return new UncheckedIOException("local I/O failed", e); }
}
