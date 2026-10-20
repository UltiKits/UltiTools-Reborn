package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #676 case 26: the contract of the one code-source resolver the language subsystem shares --
 * escaped locations decode to the file they name, and a location that is not a usable {@code file:}
 * URI falls back to the raw-path rule instead of throwing.
 */
@DisplayName("#676 case 26: CodeSourceLocations.toFile")
class CodeSourceLocationsTest {

    @TempDir
    File tempDir;

    private File jarUnder(String folderName) throws IOException {
        File folder = new File(tempDir, folderName);
        Files.createDirectories(folder.toPath());
        File jar = new File(folder, "module.jar");
        Files.write(jar.toPath(), new byte[]{0});
        return jar;
    }

    @Test
    @DisplayName("a folder name with a space, a plus sign and a hash sign round-trips to the same file")
    void escapedLocationRoundTripsToTheSameFile() throws IOException {
        File jar = jarUnder("server install+test#1");
        URL location = jar.toURI().toURL();
        assertThat(location.getPath()).contains("%20").contains("%23").contains("+");

        assertThat(CodeSourceLocations.toFile(location)).isEqualTo(jar.getAbsoluteFile());
    }

    @Test
    @DisplayName("the same with a non-ASCII character (UTF-8 file names only)")
    void nonAsciiLocationRoundTripsToTheSameFile() throws IOException {
        Assumptions.assumeTrue("UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding")),
                "the host's file-name encoding is not UTF-8, so a non-ASCII folder name is not portable here");
        File jar = jarUnder("server install+test#1 服务器");

        assertThat(CodeSourceLocations.toFile(jar.toURI().toURL())).isEqualTo(jar.getAbsoluteFile());
    }

    @Test
    @DisplayName("a plain location without escapes round-trips unchanged")
    void plainLocationRoundTrips() throws IOException {
        File jar = jarUnder("plain");

        assertThat(CodeSourceLocations.toFile(jar.toURI().toURL())).isEqualTo(jar.getAbsoluteFile());
    }

    @Test
    @DisplayName("a directory code source resolves to that directory")
    void directoryLocationRoundTrips() throws IOException {
        File classes = new File(tempDir, "exploded classes");
        Files.createDirectories(classes.toPath());

        File resolved = CodeSourceLocations.toFile(classes.toURI().toURL());

        assertThat(resolved).isEqualTo(classes.getAbsoluteFile());
        assertThat(resolved).isDirectory();
    }

    @Test
    @DisplayName("an unescaped space (not a valid URI) falls back to the raw path")
    void invalidUriFallsBackToTheRawPath() throws IOException {
        File jar = jarUnder("raw space");
        URL location = new URL("file:" + jar.getAbsolutePath());

        assertThat(CodeSourceLocations.toFile(location)).isEqualTo(new File(jar.getAbsolutePath()));
    }

    @Test
    @DisplayName("an authority-bearing file URL falls back to the raw path without throwing")
    void authorityBearingUrlFallsBackWithoutThrowing() throws IOException {
        URL location = new URL("file://fileserver/share/module.jar");

        assertThat(CodeSourceLocations.toFile(location)).isEqualTo(new File("/share/module.jar"));
    }

    @Test
    @DisplayName("a file URL with an empty path falls back without throwing")
    void emptyPathFallsBackWithoutThrowing() throws IOException {
        assertThat(CodeSourceLocations.toFile(new URL("file:"))).isEqualTo(new File(""));
    }
}
