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

    /**
     * {@code /upm update} staging and the API level (plan 18-40): a download whose
     * {@code api-version} lies outside {@code 630..<framework level>} -- served by a catalogue that
     * does not filter -- is refused with the staging discarded and nothing recorded, and a catalogue
     * answering {@code latest} with 409 stages nothing.
     */
    @Nested
    @DisplayName("update staging refuses a download outside the accepted API levels (18-40)")
    class ApiLevelStaging {

        private File serverRoot;
        private File dataFolder;
        private File oldJar;
        private com.ultikits.ultitools.abstracts.UltiToolsPlugin loaded;
        private org.mockito.MockedStatic<com.ultikits.ultitools.UltiTools> ultiTools;

        @BeforeEach
        void setUpServer() throws IOException {
            serverRoot = new File(tempDir, "server");
            dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
            oldJar = ModuleUpdateFixtures.moduleJar(new File(ModuleFileTransactions.modulesFolder(dataFolder),
                    "demo-1.0.jar"), "Demo", "1.0", "demo", "630");
            loaded = ModuleUpdateFixtures.loadedModule("Demo", "1.0", "demo");
            ultiTools = org.mockito.Mockito.mockStatic(com.ultikits.ultitools.UltiTools.class);
            ultiTools.when(com.ultikits.ultitools.UltiTools::getPluginVersion).thenReturn(630);
        }

        @AfterEach
        void closeStatic() {
            ultiTools.close();
        }

        private ModuleFileTransactions.StageResult stage(ModuleFileTransactions.Catalogue catalogue,
                                                         ModuleFileTransactions.Downloader downloader) {
            return new ModuleFileTransactions(dataFolder).stageUpdate("demo", java.util.Collections.singletonList(loaded),
                    new ModuleUpdateFixtures.CodeSources().with(loaded, oldJar), catalogue, downloader);
        }

        private java.util.Map<String, String> snapshot() throws IOException {
            java.util.Map<String, String> files = new java.util.TreeMap<>();
            for (String path : ModuleUpdateFixtures.treeOf(serverRoot)) {
                // The path comes from listing this test's own temporary folder; nothing is external input.
                // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
                files.put(path, new String(java.nio.file.Files.readAllBytes(new File(serverRoot, path).toPath()),
                        java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            return files;
        }

        @Test
        @DisplayName("a staged download declaring api-version 620 is refused: FAILED, the reason naming 620 and 630..630, nothing recorded")
        void olderDownload_isRefused() throws IOException {
            java.util.Map<String, String> before = snapshot();

            ModuleFileTransactions.StageResult result = stage(ModuleUpdateFixtures.catalogue("demo", "1.1"),
                    ModuleUpdateFixtures.downloading("Demo", "1.1", "demo", "620"));

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_INCOMPATIBLE_API_LEVEL);
            assertThat(result.getReasonArgs()).containsExactly("demo-1.1.jar", "620", 630, 630, 630);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("a staged download declaring api-version 640 on a 630 framework is refused the same way")
        void newerDownload_isRefused() throws IOException {
            java.util.Map<String, String> before = snapshot();

            ModuleFileTransactions.StageResult result = stage(ModuleUpdateFixtures.catalogue("demo", "1.1"),
                    ModuleUpdateFixtures.downloading("Demo", "1.1", "demo", "640"));

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_INCOMPATIBLE_API_LEVEL);
            assertThat(result.getReasonArgs()).containsExactly("demo-1.1.jar", "640", 630, 630, 630);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("a staged download declaring no api-version is refused, the reason saying it declares none")
        void downloadWithoutLevel_isRefused() throws IOException {
            java.util.Map<String, String> before = snapshot();

            ModuleFileTransactions.StageResult result = stage(ModuleUpdateFixtures.catalogue("demo", "1.1"),
                    ModuleUpdateFixtures.downloading("Demo", "1.1", "demo", null));

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_NO_API_LEVEL);
            assertThat(result.getReasonArgs()).containsExactly("demo-1.1.jar", 630, 630, 630);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("control: a staged download declaring api-version 630 is staged")
        void compatibleDownload_isStaged() {
            ModuleFileTransactions.StageResult result = stage(ModuleUpdateFixtures.catalogue("demo", "1.1"),
                    ModuleUpdateFixtures.downloading("Demo", "1.1", "demo", "630"));

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        }

        @Test
        @DisplayName("the catalogue answering latest with 409 through /upm's own catalogue: FAILED with 'no compatible newer version', nothing downloaded")
        void latestConflict_stagesNothing() throws IOException {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                int status = "/plugin/7/latest".equals(path) ? 409 : 200;
                String body = "/plugin/get".equals(path)
                        ? "{\"code\":\"200\",\"msg\":\"Success\",\"data\":{\"id\":7,\"identifyString\":\"demo\"}}"
                        : "{\"code\":\"" + status + "\",\"msg\":\"No version of this module is compatible\"}";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            java.util.List<String> downloads = new java.util.ArrayList<>();
            try {
                PluginInstallUtils.setBaseUrlForTesting("http://127.0.0.1:" + server.getAddress().getPort());
                java.util.Map<String, String> before = snapshot();

                ModuleFileTransactions.StageResult result = stage(PluginInstallUtils.CATALOGUE,
                        (link, name, folder) -> downloads.add(name));

                assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
                assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_NO_COMPATIBLE_UPDATE);
                assertThat(result.getReasonArgs()).containsExactly("demo", 630, 630, 630);
                assertThat(downloads).isEmpty();
                assertThat(snapshot()).isEqualTo(before);
            } finally {
                server.stop(0);
            }
        }
    }
}
