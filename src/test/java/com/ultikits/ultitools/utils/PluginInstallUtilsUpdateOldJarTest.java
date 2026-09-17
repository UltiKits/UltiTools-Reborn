package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * #505: {@link PluginInstallUtils#updatePlugin(String)} downloaded the new module jar, then
 * called {@code oldJar.delete()}, ignored its result and returned {@code true} either way. When
 * the delete failed, {@code /upm update} reported success while the old jar stayed next to the new
 * one, so the next start found two jars for the same module.
 * <p>
 * The catalogue is a local {@link HttpServer}; the download is real. To make only the old jar's
 * delete fail, the new jar's file is created before the plugins folder is made read-only: writing
 * an existing file needs only the file's own write permission, while deleting a directory entry
 * needs the directory's.
 */
@DisplayName("PluginInstallUtils#updatePlugin reports a failed old-jar delete (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsUpdateOldJarTest {

    private static final String IDENTIFY_STRING = "fixture-module";
    private static final byte[] NEW_JAR_BYTES = "new-version-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File dataFolder;

    private HttpServer server;
    private File pluginsFolder;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        pluginsFolder = new File(dataFolder, "plugins");
        assertThat(pluginsFolder.mkdirs()).isTrue();
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getDataFolder()).thenReturn(dataFolder));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/plugin/get", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":{\"id\":7,\"identifyString\":\"" + IDENTIFY_STRING + "\"}}"));
        server.createContext("/plugin/7/latest", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"2.0.0\"}"));
        server.createContext("/plugin/7/2.0.0/download", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"" + origin + "/artifact.jar\"}"));
        server.createContext("/artifact.jar", exchange -> {
            exchange.sendResponseHeaders(200, NEW_JAR_BYTES.length);
            exchange.getResponseBody().write(NEW_JAR_BYTES);
            exchange.close();
        });
        server.start();
        PluginInstallUtils.setBaseUrlForTesting(origin);
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        server.stop(0);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("control: a writable folder updates, deletes the old jar and reports success")
    void writableFolder_deletesOldJarAndReportsSuccess() throws IOException {
        File oldJar = writeOldJar();

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar")).hasBinaryContent(NEW_JAR_BYTES);
    }

    @Test
    @DisplayName("an old jar that cannot be deleted is reported as a failure naming that jar, never as success")
    void undeletableOldJar_isReportedAsFailureNamingTheJar() throws IOException {
        Path folder = pluginsFolder.toPath();
        Assumptions.assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"),
                "needs POSIX permissions to make the delete fail");
        File oldJar = writeOldJar();
        File newJar = new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar");
        assertThat(newJar.createNewFile()).isTrue();

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder),
                    "running as a user that can write a read-only directory (e.g. root); the delete cannot be made to fail");

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

            assertThat(newJar)
                    .as("control: the download itself must have happened, so only the old jar's delete failed")
                    .hasBinaryContent(NEW_JAR_BYTES);
            assertThat(thrown)
                    .as("a failed old-jar delete must not be reported as a successful update (#505)")
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(FileSystemException.class);
            assertThat(((FileSystemException) thrown.getCause()).getFile()).isEqualTo(oldJar.getAbsolutePath());
            assertThat(oldJar).exists();
        } finally {
            Files.setPosixFilePermissions(folder, original);
        }
    }

    private File writeOldJar() throws IOException {
        File jar = new File(pluginsFolder, IDENTIFY_STRING + "-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: 1.0.0\nidentify-string: " + IDENTIFY_STRING + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
