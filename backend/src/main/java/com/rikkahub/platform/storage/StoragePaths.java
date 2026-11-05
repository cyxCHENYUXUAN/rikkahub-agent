package com.rikkahub.platform.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.rikkahub.Main;
import org.springframework.boot.system.ApplicationHome;

/**
 * Resolves and manages the application's physical data directories.
 * Supports:
 * - Explicit configured path via --rikkahub.storage.data-directory
 * - Environment variable RIKKAHUB_DATA_DIR
 * - Home-relative expansion (~/...)
 * - Automatic detection: dev workspace -> ./data; standalone distribution -> standard OS user data directory
 *   (%LOCALAPPDATA%/RikkaHub on Windows, ~/.rikkahub on Unix/macOS)
 */
public record StoragePaths(Path dataDirectory, boolean devWorkspace) {

    public static StoragePaths initialize(String configuredDirectory) throws IOException {
        Path home = new ApplicationHome(Main.class).getDir().toPath().toAbsolutePath().normalize();
        Path devBase = null;
        for (Path candidate = home; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("backend"))) {
                devBase = candidate;
                break;
            }
        }
        boolean isDev = devBase != null;

        String rawPath = configuredDirectory;
        if (rawPath == null || rawPath.isBlank()) {
            rawPath = System.getenv("RIKKAHUB_DATA_DIR");
        }

        Path resolvedDataDir;
        if (rawPath != null && !rawPath.isBlank()) {
            String trimmed = rawPath.trim();
            if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
                String userHome = System.getProperty("user.home");
                resolvedDataDir = Path.of(userHome, trimmed.substring(2)).toAbsolutePath().normalize();
            } else if ("~".equals(trimmed)) {
                resolvedDataDir = Path.of(System.getProperty("user.home"), ".rikkahub").toAbsolutePath().normalize();
            } else {
                Path candidate = Path.of(trimmed);
                if (candidate.isAbsolute()) {
                    resolvedDataDir = candidate.normalize();
                } else if (isDev) {
                    resolvedDataDir = devBase.resolve(candidate).toAbsolutePath().normalize();
                } else {
                    resolvedDataDir = home.resolve(candidate).toAbsolutePath().normalize();
                }
            }
        } else if (isDev) {
            resolvedDataDir = devBase.resolve("data").toAbsolutePath().normalize();
        } else {
            resolvedDataDir = resolveDefaultOsUserDataDir();
        }

        StoragePaths paths = new StoragePaths(resolvedDataDir, isDev);
        paths.ensureDirectoriesAndWritable();
        return paths;
    }

    private static Path resolveDefaultOsUserDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "RikkaHub").toAbsolutePath().normalize();
            }
            return Path.of(System.getProperty("user.home"), "AppData", "Local", "RikkaHub").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".rikkahub").toAbsolutePath().normalize();
    }

    private void ensureDirectoriesAndWritable() throws IOException {
        Files.createDirectories(dataDirectory);
        if (!Files.isWritable(dataDirectory)) {
            throw new IllegalStateException("RikkaHub data directory is not writable: " + dataDirectory
                    + ". Please grant write permissions or set RIKKAHUB_DATA_DIR environment variable.");
        }
        for (String directory : new String[]{"database", "config", "logs", "files", "memory", "skills", "workspace", "tool_outputs", "backups"}) {
            Files.createDirectories(dataDirectory.resolve(directory));
        }
    }

    public Path databaseFile() {
        return dataDirectory.resolve("database/rikkahub.db");
    }

    public Path backupsDirectory() {
        return dataDirectory.resolve("backups");
    }

    public Path configDirectory() {
        return dataDirectory.resolve("config");
    }

    public Path logsDirectory() {
        return dataDirectory.resolve("logs");
    }

    public Path memoryDirectory() {
        return dataDirectory.resolve("memory");
    }

    public Path skillsDirectory() {
        return dataDirectory.resolve("skills");
    }

    public Path workspaceDirectory() {
        return dataDirectory.resolve("workspace");
    }

    public Path toolOutputsDirectory() {
        return dataDirectory.resolve("tool_outputs");
    }
}
