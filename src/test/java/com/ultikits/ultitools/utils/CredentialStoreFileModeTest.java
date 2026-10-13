package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The credential file holds the UltiCloud access and refresh tokens. Every write replaces it with a temporary file
 * moved over it, so the file's mode is the temporary file's mode: on a POSIX file system that file must be created
 * owner-only ({@code rw-------}), or a token refresh widens a file the operator restricted to {@code 600} to the
 * process umask (Phase 19 D19-060).
 */
@DisplayName("CredentialStore keeps the credential file owner-only on POSIX file systems")
class CredentialStoreFileModeTest {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path tempDir;

    private Path file;

    @BeforeEach
    void setUp() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX file permissions are not supported here; the non-POSIX path is unchanged");
        file = tempDir.resolve("credentials.json");
        CredentialStore.setTargetPathForTesting(file);
    }

    @AfterEach
    void tearDown() {
        CredentialStore.clearTargetPathForTesting();
    }

    @Test
    @DisplayName("an update of a 600 file leaves it at 600")
    void updateKeepsOwnerOnly() throws Exception {
        CredentialStore.write(Collections.<String, Object>singletonMap("uuid", "server-id"));
        Files.setPosixFilePermissions(file, OWNER_ONLY);

        CredentialStore.update(data -> {
            data.put("access_token", "refreshed");
            return data;
        });

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("a first write creates the file owner-only")
    void firstWriteIsOwnerOnly() throws Exception {
        CredentialStore.write(Collections.<String, Object>singletonMap("uuid", "server-id"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }
}
