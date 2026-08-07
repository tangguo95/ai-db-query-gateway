package com.tangguo.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Applies the strongest simple owner-only file mode supported by the host file system. */
public final class FileSecurity {
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private FileSecurity() {}

    public static void setOwnerOnlyDirectory(Path path) throws IOException {
        setPosixPermissions(path, OWNER_ONLY_DIRECTORY);
    }

    public static void setOwnerOnlyFile(Path path) throws IOException {
        setPosixPermissions(path, OWNER_ONLY_FILE);
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows uses inherited per-user ACLs and DPAPI for secrets; POSIX modes do not exist there.
        }
    }
}
