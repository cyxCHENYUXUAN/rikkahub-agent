package com.rikkahub.platform.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import com.rikkahub.platform.storage.StoragePaths;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/** A persistent local secret. It is never exposed through a body, getter or log. */
public final class LocalTokenService {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTokenService.class);
    public static final String HEADER_NAME = "X-RikkaHub-Token";
    public static final String COOKIE_NAME = "RikkaHub-Local-Session";
    private final String token;

    public LocalTokenService(StoragePaths paths) throws IOException {
        Path config = paths.configDirectory();
        requireRegularDirectory(config);
        restrictDirectory(config);
        Path file = config.resolve("local-token");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            byte[] value = Base64.getUrlEncoder().withoutPadding().encode(random);
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), privateAttribute(config))) {
                ByteBuffer buffer = ByteBuffer.wrap(value);
                while (buffer.hasRemaining()) { channel.write(buffer); }
            } catch (FileAlreadyExistsException concurrentCreation) {
                // Another initializer won; validate and use the existing file below.
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 43) {
            throw new IOException("Local token must be a regular private token file");
        }
        restrictFile(file);
        token = Files.readString(file, StandardCharsets.US_ASCII);
        if (!token.matches("[A-Za-z0-9_-]{43}")) { throw new IOException("Invalid local token file"); }
    }

    public boolean matches(String candidate) {
        return candidate != null && candidate.length() == 43 && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.UTF_8));
    }

    public void establishSession(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).secure(secure).sameSite("Strict").path("/api/").build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static void requireRegularDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local token configuration directory must be a regular directory");
        }
    }

    /**
     * Directory hardening is best effort: it guards against replacing or listing the token, but a
     * data directory restored from a backup or created by another account (for example a sandbox)
     * often cannot be re-ACLed. The token file itself is still restricted below, and that is what
     * keeps the secret readable by the current account only.
     */
    private static void restrictDirectory(Path directory) throws IOException {
        try {
            restrict(directory);
        } catch (AccessDeniedException notOwner) {
            LOG.warn("Cannot restrict access to {} because it is owned by {}; continuing with an "
                            + "owner-only token file. Delete the directory to let RikkaHub recreate it "
                            + "with a private ACL.", directory, ownerName(directory));
        }
    }

    private static void restrictFile(Path file) throws IOException {
        try {
            restrict(file);
        } catch (AccessDeniedException notOwner) {
            throw new IOException("Local token file " + file + " is owned by " + ownerName(file)
                    + " and cannot be restricted to the current account. Delete it and restart; "
                    + "RikkaHub will create a new token with owner-only access.", notOwner);
        }
    }

    private static String ownerName(Path path) {
        try {
            return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
        } catch (IOException | UnsupportedOperationException unavailable) {
            return "another account";
        }
    }

    private static FileAttribute<?> privateAttribute(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        }
        if (Files.getFileStore(directory).supportsFileAttributeView(AclFileAttributeView.class)) {
            List<AclEntry> acl = ownerAcl(Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS));
            return new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return acl; }
            };
        }
        throw new IOException("Owner-only local token permissions are not supported by this filesystem");
    }

    private static void restrict(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) { throw new IOException("Local token paths cannot be symbolic links"); }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> mode = PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------");
            posix.setPermissions(mode);
            if (!posix.readAttributes().permissions().equals(mode)) { throw new IOException("Cannot secure local token permissions"); }
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) { throw new IOException("Owner-only local token permissions are not supported"); }
        UserPrincipal owner = view.getOwner();
        view.setAcl(ownerAcl(owner));
        if (view.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner))) {
            throw new IOException("Cannot secure local token permissions");
        }
    }

    private static List<AclEntry> ownerAcl(UserPrincipal owner) {
        return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
    }
}
