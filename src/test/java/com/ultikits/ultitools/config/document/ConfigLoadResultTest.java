package com.ultikits.ultitools.config.document;

import static com.ultikits.ultitools.config.document.ConfigDocumentWriteTest.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 17-56 Task 3: reading a config file yields one of four states and never throws, so an unreadable or
 * unparseable file can be told apart from a missing one and is never mistaken for "no settings" (#511 part 2,
 * #470); a key ending in {@code .} is an ordinary key (#575).
 */
@DisplayName("ConfigDocument#load - four states, never a throw")
class ConfigLoadResultTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a missing file is ABSENT")
    void missingFileIsAbsent() {
        Path file = tempDir.resolve("missing.yml");

        ConfigLoadResult result = ConfigDocument.load(file);

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.ABSENT);
        assertThat(result.document()).isNull();
        assertThat(result.file()).isEqualTo(file);
    }

    @Test
    @DisplayName("a read that throws IOException is UNREADABLE with that cause")
    void failingReadIsUnreadable() {
        Path file = tempDir.resolve("config.yml");
        IOException failure = new IOException("injected read failure");
        AtomicConfigWriter.FileOperations failingRead = new AtomicConfigWriter.FileOperations() {
            @Override
            public byte[] read(Path target) throws IOException {
                throw failure;
            }
        };

        ConfigLoadResult result = ConfigDocument.load(file, failingRead);

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNREADABLE);
        assertThat(result.cause()).isSameAs(failure);
        assertThat(result.document()).isNull();
    }

    @Test
    @DisplayName("a file the process may not read (chmod 000) is UNREADABLE")
    void permissionDeniedIsUnreadable() throws IOException {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"), "POSIX file system");
        assumeTrue(!"root".equals(System.getProperty("user.name")), "not running as root");
        Path file = tempDir.resolve("config.yml");
        Files.write(file, "a: 1\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(file, Collections.<PosixFilePermission>emptySet());
        try {
            ConfigLoadResult result = ConfigDocument.load(file);

            assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNREADABLE);
            assertThat(result.cause()).isInstanceOf(AccessDeniedException.class);
        } finally {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    @DisplayName("a directory where the file should be is UNREADABLE")
    void directoryIsUnreadable() throws IOException {
        Path file = Files.createDirectory(tempDir.resolve("config.yml"));

        ConfigLoadResult result = ConfigDocument.load(file);

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNREADABLE);
        assertThat(result.cause()).isNotNull();
    }

    @Test
    @DisplayName("a YAML syntax error is UNPARSEABLE with SnakeYAML's message")
    void syntaxErrorIsUnparseable() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write("a: [unclosed\n"));

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
        assertThat(result.parserMessage()).contains("flow sequence").contains("line 1");
        assertThat(result.document()).isNull();
    }

    @Test
    @DisplayName("a global tag such as !!java.util.UUID is UNPARSEABLE (no class is ever constructed)")
    void globalTagIsUnparseable() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write("owner: !!java.util.UUID '00000000-0000-0000-0000-000000000001'\n"));

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
        assertThat(result.parserMessage()).contains("java.util.UUID");
    }

    @Test
    @DisplayName("nesting deeper than 100 is UNPARSEABLE; 100 levels load")
    void nestingDepthLimit() throws IOException {
        ConfigLoadResult deep = ConfigDocument.load(write(nested(101)));
        ConfigLoadResult limit = ConfigDocument.load(write(nested(100)));

        assertThat(deep.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
        assertThat(deep.parserMessage()).containsIgnoringCase("nesting depth");
        assertThat(limit.state()).isEqualTo(ConfigLoadResult.State.LOADED);
    }

    @Test
    @DisplayName("a top level that is not a mapping is UNPARSEABLE")
    void topLevelListIsUnparseable() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write("- a\n- b\n"));

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
        assertThat(result.parserMessage()).contains("not a map");
    }

    @Test
    @DisplayName("wave.: z is LOADED with the key 'wave.' (#575)")
    void trailingDotKeyLoads() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write("emojis:\n  wave.: z\n"));

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(result.document().get(path("emojis", "wave."))).isEqualTo("z");
    }

    @Test
    @DisplayName("an empty file is LOADED with an empty document, as Bukkit reads it")
    void emptyFileLoads() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write(""));

        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.LOADED);
        assertThat(result.document().toPlain()).isEmpty();
    }

    @Test
    @DisplayName("LOADED carries the SHA-256 fingerprint of the bytes read")
    void loadedCarriesAFingerprint() throws IOException {
        ConfigLoadResult first = ConfigDocument.load(write("a: 1\n"));
        ConfigLoadResult same = ConfigDocument.load(write("a: 1\n"));
        ConfigLoadResult other = ConfigDocument.load(write("a: 2\n"));

        assertThat(first.fingerprint()).hasSize(64).isEqualTo(same.fingerprint()).isNotEqualTo(other.fingerprint());
        assertThat(first.fingerprint()).isEqualTo(sha256("a: 1\n"));
    }

    private Path write(String text) throws IOException {
        Path file = Files.createTempFile(tempDir, "config", ".yml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String nested(int depth) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            for (int j = 0; j < i; j++) {
                text.append("  ");
            }
            text.append('k').append(i).append(i == depth - 1 ? ": leaf\n" : ":\n");
        }
        return text.toString();
    }

    private static String sha256(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
