package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.util.jar.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for PluginInstallUtils.findPluginJar() and updatePlugin().
 * Focuses on findPluginJar() since updatePlugin() requires complex mocking
 * (static methods + file system + network).
 * <br>
 * 测试 PluginInstallUtils.findPluginJar() 和 updatePlugin()。
 * 主要测试 findPluginJar()，因为 updatePlugin() 需要复杂的静态方法+文件系统+网络模拟。
 */
@DisplayName("PluginInstallUtils Update Tests")
class PluginInstallUtilsUpdateTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        // Set a dummy base URL to prevent NullPointerException if any code
        // accidentally calls getBaseUrl() (which uses UltiTools.getEnv())
        PluginInstallUtils.setBaseUrlForTesting("http://localhost:9999");
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
    }

    /**
     * Helper: create a minimal JAR in tempDir with a plugin.yml containing identify-string.
     */
    private File createPluginJar(String jarName, String identifyString) throws IOException {
        File jarFile = new File(tempDir, jarName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            // Add plugin.yml entry
            JarEntry entry = new JarEntry("plugin.yml");
            jos.putNextEntry(entry);
            String yaml = "name: TestPlugin\nversion: 1.0.0\n";
            if (identifyString != null) {
                yaml += "identify-string: " + identifyString + "\n";
            }
            jos.write(yaml.getBytes());
            jos.closeEntry();
        }
        return jarFile;
    }

    @Test
    @DisplayName("findPluginJar should find JAR by identifyString")
    void shouldFindPluginJar() throws IOException {
        createPluginJar("test-plugin-1.0.0.jar", "test-plugin");
        createPluginJar("other-plugin-1.0.0.jar", "other-plugin");

        File found = PluginInstallUtils.findPluginJar(tempDir, "test-plugin");

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("test-plugin-1.0.0.jar");
    }

    @Test
    @DisplayName("findPluginJar should return null when not found")
    void shouldReturnNullWhenNotFound() throws IOException {
        createPluginJar("test-plugin-1.0.0.jar", "test-plugin");

        File found = PluginInstallUtils.findPluginJar(tempDir, "nonexistent");

        assertThat(found).isNull();
    }

    @Test
    @DisplayName("findPluginJar should handle JARs without identify-string")
    void shouldHandleJarsWithoutIdentifyString() throws IOException {
        createPluginJar("no-id-plugin.jar", null);

        File found = PluginInstallUtils.findPluginJar(tempDir, "test-plugin");

        assertThat(found).isNull();
    }

    @Test
    @DisplayName("findPluginJar should handle empty directory")
    void shouldHandleEmptyDirectory() {
        File found = PluginInstallUtils.findPluginJar(tempDir, "test-plugin");

        assertThat(found).isNull();
    }

    @Test
    @DisplayName("findPluginJar should return null for null folder")
    void shouldReturnNullForNullFolder() {
        File found = PluginInstallUtils.findPluginJar(null, "test-plugin");

        assertThat(found).isNull();
    }

    @Test
    @DisplayName("findPluginJar should return null for non-directory file")
    void shouldReturnNullForNonDirectory() throws IOException {
        File regularFile = new File(tempDir, "not-a-dir.txt");
        regularFile.createNewFile();

        File found = PluginInstallUtils.findPluginJar(regularFile, "test-plugin");

        assertThat(found).isNull();
    }

    @Test
    @DisplayName("findPluginJar should skip non-JAR files")
    void shouldSkipNonJarFiles() throws IOException {
        // Create a non-JAR file in the directory
        File textFile = new File(tempDir, "readme.txt");
        textFile.createNewFile();
        createPluginJar("test-plugin-1.0.0.jar", "test-plugin");

        File found = PluginInstallUtils.findPluginJar(tempDir, "test-plugin");

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("test-plugin-1.0.0.jar");
    }
}
