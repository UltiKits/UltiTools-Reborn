package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        @DisplayName("an empty level-filtered list through /upm's own catalogue: FAILED with 'no compatible newer version', nothing downloaded")
        void latestConflict_stagesNothing() throws IOException {
            com.ultikits.ultitools.stub.StubCatalogue catalogue = com.ultikits.ultitools.stub.StubCatalogue.start();
            catalogue.mode(com.ultikits.ultitools.stub.StubCatalogue.Mode.NO_COMPATIBLE_VERSION);
            java.util.List<String> downloads = new java.util.ArrayList<>();
            try {
                PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());
                java.util.Map<String, String> before = snapshot();

                ModuleFileTransactions.StageResult result = stage(PluginInstallUtils.CATALOGUE,
                        (link, name, folder) -> downloads.add(name));

                assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
                assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_NO_COMPATIBLE_UPDATE);
                assertThat(result.getReasonArgs()).containsExactly("demo", 630, 630, 630);
                assertThat(downloads).isEmpty();
                assertThat(snapshot()).isEqualTo(before);
            } finally {
                catalogue.close();
            }
        }
    }

    /**
     * A staged update becomes the module that loads at the next start, so its download is verified
     * exactly like an install (plan 22-18, D-28).
     */
    @Nested
    @DisplayName("the staged update download is verified like an install")
    class VerifiedStagingDownload {

        @Test
        @DisplayName("a matching JAR is written under its final name and no .part survives")
        void matchingJar_isWritten() throws IOException {
            try (com.ultikits.ultitools.stub.StubCatalogue catalogue =
                         com.ultikits.ultitools.stub.StubCatalogue.start()) {
                PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());
                CatalogueClient.Version version = CatalogueClient.version("demo",
                        com.ultikits.ultitools.stub.StubCatalogue.VERSION);

                PluginInstallUtils.downloadVerified(CatalogueClient.absoluteDownloadUrl(version.downloadUrl()),
                        "demo-1.1.jar", tempDir);

                assertThat(new File(tempDir, "demo-1.1.jar")).exists();
                assertThat(new File(tempDir, "demo-1.1.jar.part")).doesNotExist();
            }
        }

        @Test
        @DisplayName("a tampered JAR throws and leaves nothing behind")
        void tamperedJar_isRefused() throws IOException {
            try (com.ultikits.ultitools.stub.StubCatalogue catalogue =
                         com.ultikits.ultitools.stub.StubCatalogue.start()) {
                catalogue.mode(com.ultikits.ultitools.stub.StubCatalogue.Mode.TAMPER_HASH);
                PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());
                CatalogueClient.Version version = CatalogueClient.version("demo",
                        com.ultikits.ultitools.stub.StubCatalogue.VERSION);
                String url = CatalogueClient.absoluteDownloadUrl(version.downloadUrl());

                assertThatThrownBy(() -> PluginInstallUtils.downloadVerified(url, "demo-1.1.jar", tempDir))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("hash mismatch");

                assertThat(new File(tempDir, "demo-1.1.jar")).doesNotExist();
                assertThat(new File(tempDir, "demo-1.1.jar.part")).doesNotExist();
            }
        }
    }

    /**
     * {@code /upm update} hands the transaction the verifying downloader (plan 22-18 fix round, gap
     * 4). The transaction's own downloader argument is captured here, so what is exercised is the
     * one {@code stageUpdate} actually binds -- not a copy of that line in the test.
     */
    @Nested
    @DisplayName("stageUpdate binds the verifying downloader")
    class VerifiedStageUpdate {

        @Test
        @DisplayName("the bound downloader refuses a tampered JAR and leaves nothing in the staging folder")
        void boundDownloaderIsTheVerifyingOne() throws IOException {
            File serverRoot = new File(tempDir, "server-staged");
            File dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
            com.ultikits.ultitools.abstracts.UltiToolsPlugin loaded =
                    ModuleUpdateFixtures.loadedModule("Demo", "1.0", "demo");
            java.util.concurrent.atomic.AtomicReference<ModuleFileTransactions.Downloader> bound =
                    new java.util.concurrent.atomic.AtomicReference<>();
            File staging = new File(tempDir, "staged-work");
            assertThat(staging.mkdirs()).isTrue();

            try (org.mockito.MockedStatic<com.ultikits.ultitools.UltiTools> ultiTools =
                         org.mockito.Mockito.mockStatic(com.ultikits.ultitools.UltiTools.class);
                 org.mockito.MockedConstruction<ModuleFileTransactions> transactions =
                         org.mockito.Mockito.mockConstruction(ModuleFileTransactions.class,
                                 (mock, context) -> org.mockito.Mockito.when(mock.stageUpdate(
                                         org.mockito.ArgumentMatchers.anyString(),
                                         org.mockito.ArgumentMatchers.any(java.util.function.Supplier.class),
                                         org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                                         org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                                         .thenAnswer(invocation -> {
                                             bound.set(invocation.getArgument(4));
                                             return ModuleFileTransactions.StageResult.of(
                                                     ModuleFileTransactions.StageResult.Outcome.FAILED,
                                                     null, null, null, null);
                                         }));
                 com.ultikits.ultitools.stub.StubCatalogue catalogue =
                         com.ultikits.ultitools.stub.StubCatalogue.start()) {
                com.ultikits.ultitools.UltiTools instance =
                        org.mockito.Mockito.mock(com.ultikits.ultitools.UltiTools.class);
                com.ultikits.ultitools.manager.PluginManager pluginManager =
                        org.mockito.Mockito.mock(com.ultikits.ultitools.manager.PluginManager.class);
                org.mockito.Mockito.when(pluginManager.getPluginList())
                        .thenReturn(java.util.Collections.singletonList(loaded));
                org.mockito.Mockito.when(pluginManager.getModuleJarIndex()).thenReturn(null);
                org.mockito.Mockito.when(instance.getPluginManager()).thenReturn(pluginManager);
                org.mockito.Mockito.when(instance.getDataFolder()).thenReturn(dataFolder);
                ultiTools.when(com.ultikits.ultitools.UltiTools::getInstance).thenReturn(instance);
                ultiTools.when(com.ultikits.ultitools.UltiTools::getPluginVersion).thenReturn(630);
                catalogue.mode(com.ultikits.ultitools.stub.StubCatalogue.Mode.TAMPER_HASH);
                PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());

                ModuleFileTransactions.StageResult result = PluginInstallUtils.stageUpdate("demo");

                assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
                assertThat(bound.get()).as("stageUpdate bound a downloader").isNotNull();
                String link = PluginInstallUtils.getPluginVersionDownloadLink(
                        "demo", com.ultikits.ultitools.stub.StubCatalogue.VERSION);
                assertThatThrownBy(() -> bound.get().download(link, "demo-1.1.jar", staging))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("hash mismatch");
                assertThat(staging.list()).as("the refused download left nothing behind").isEmpty();
            }
        }
    }
}
