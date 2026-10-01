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

    @Test
    void nonUtf8FilesAreUnparseableAndUnchanged() throws IOException {
        byte[][] inputs = {"name: \u6d4b\u8bd5\n".getBytes(java.nio.charset.Charset.forName("GBK")),
                new byte[]{'k', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n'}};
        for (byte[] bytes : inputs) {
            Path file = Files.createTempFile(tempDir, "non-utf8", ".yml");
            Files.write(file, bytes);
            ConfigLoadResult result = ConfigDocument.load(file);
            assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
            assertThat(result.document()).isNull();
            assertThat(result.parserMessage()).contains("UTF-8");
            assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        }
    }

    @Test
    void cleanupRuntimeFailureNeverEscapesLoad() throws IOException {
        Path file = write("a: 1\n");
        try (org.mockito.MockedStatic<Files> mocked = org.mockito.Mockito.mockStatic(Files.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.newDirectoryStream(org.mockito.ArgumentMatchers.any(Path.class),
                    org.mockito.ArgumentMatchers.any( java.nio.file.DirectoryStream.Filter.class)))
                    .thenThrow(new java.nio.file.DirectoryIteratorException(new IOException("iteration failed")));
            assertThat(ConfigDocument.load(file).state()).isEqualTo(ConfigLoadResult.State.LOADED);
        }
    }

    @Test
    void deepAliasChainIsUnparseableRatherThanStackOverflow() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write(aliasChain(12000)));
        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
        assertThat(result.parserMessage()).containsIgnoringCase("depth");
    }

    @Test
    void aliasExpansionObeysHundredLevelLimit() throws IOException {
        ConfigLoadResult result = ConfigDocument.load(write(aliasChain(101)));
        assertThat(result.state()).isEqualTo(ConfigLoadResult.State.UNPARSEABLE);
        assertThat(result.parserMessage()).contains("100");
        assertThat(ConfigDocument.load(write(aliasChain(99))).state()).isEqualTo(ConfigLoadResult.State.LOADED);
    }

    @Test
    void eligibleMoveFollowedByTemporaryReadFailureLogsExactlyOnceWithoutBackup() throws IOException {
        Path target = write("a: 1\n");
        java.util.List<java.util.logging.LogRecord> warnings = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(AtomicConfigWriter.class.getName());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) { warnings.add(record); }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        AtomicConfigWriter.FileOperations files = new AtomicConfigWriter.FileOperations() {
            @Override public void move(Path source, Path destination, java.nio.file.CopyOption... options) throws IOException {
                throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected refusal");
            }
            @Override public byte[] read(Path file) throws IOException {
                if (file.getFileName().toString().contains(".tmp-")) {
                    throw new IOException("injected temporary read failure");
                }
                return AtomicConfigWriter.FileOperations.super.read(file);
            }
        };
        logger.addHandler(capture);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> AtomicConfigWriter.write(target, "a: 2\n", files))
                    .isInstanceOf(IOException.class).hasMessageContaining("temporary read failure");
            assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).isEqualTo("a: 1\n");
            assertThat(Files.exists(target.resolveSibling(target.getFileName() + ".bak"))).isFalse();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getMessage()).contains(target.toString(), "in-place", "failed", "injected refusal");
        } finally {
            logger.removeHandler(capture);
        }
    }

    private static String aliasChain(int levels) {
        StringBuilder text = new StringBuilder("a0: &a0 [leaf]\n");
        for (int i = 1; i < levels; i++) {
            text.append('a').append(i).append(": &a").append(i).append(" [*a").append(i - 1).append("]\n");
        }
        return text.toString();
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
