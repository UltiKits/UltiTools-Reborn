package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.PendingFixture;
import com.ultikits.ultitools.manager.ModuleApiLevels;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * {@code /upm install} against the v2 catalogue (plan 22-18): only the versions route carries the
 * API-level filter, the download is written to a name the module loader never reads and is checked
 * byte for byte before it is installed, its {@code plugin.yml} is read again, and a module outside
 * {@code 630..<framework level>} -- or a version the catalogue does not offer for download -- is
 * refused with a reason and nothing left in the modules folder.
 */
@DisplayName("/upm install over /v1/catalogue: levels, integrity and the licence-required outcome")
@PendingFixture
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PluginInstallUtilsApiLevelTest {

    @TempDir
    File serverRoot;

    private File modules;
    private StubCatalogue catalogue;
    private MockedStatic<UltiTools> ultiTools;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = new File(new File(serverRoot, "plugins"), "UltiTools");
        assertThat(dataFolder.mkdirs()).isTrue();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        assertThat(modules.mkdirs()).isTrue();

        UltiTools instance = mock(UltiTools.class);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        ultiTools = mockStatic(UltiTools.class);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        frameworkAt(630);

        catalogue = StubCatalogue.start();
        PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());

        Logger logger = Logger.getLogger(PluginInstallUtils.class.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(PluginInstallUtils.class.getName()).removeHandler(capture);
        PluginInstallUtils.resetBaseUrl();
        catalogue.close();
        ultiTools.close();
    }

    private void frameworkAt(int level) {
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(level);
    }

    private List<String> modulesFolderNames() {
        String[] names = modules.list();
        List<String> list = new ArrayList<>();
        if (names != null) {
            Collections.addAll(list, names);
        }
        Collections.sort(list);
        return list;
    }

    /** The records an operator sees: WARNING or above. */
    private List<String> messages() {
        List<String> messages = new ArrayList<>();
        synchronized (records) {
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    messages.add(record.getMessage());
                }
            }
        }
        return messages;
    }

    private boolean downloadedAnything() {
        for (StubCatalogue.Request request : catalogue.requests()) {
            if (request.path().endsWith("/download")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> queryOf(StubCatalogue.Request request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = request.query();
        if (query == null) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            assertThat(parameters).as("parameter %s repeated in %s", key, request).doesNotContainKey(key);
            parameters.put(key, equals < 0 ? "" : pair.substring(equals + 1));
        }
        return parameters;
    }

    private List<StubCatalogue.Request> versionRequests() {
        List<StubCatalogue.Request> versions = new ArrayList<>();
        for (StubCatalogue.Request request : catalogue.requests()) {
            if (request.path().endsWith("/versions")) {
                versions.add(request);
            }
        }
        return versions;
    }

    private File inModules(String name) {
        // Test-only path under the JUnit @TempDir; every name is a constant of this class.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        return new File(modules, name);
    }

    private byte[] bytesInModules(String name) throws IOException {
        // Test-only read under the JUnit @TempDir; every name is a constant of this class.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        return Files.readAllBytes(inModules(name).toPath());
    }

    @Test
    @DisplayName("a JAR declaring api-version 630 installs; only the versions request carries apiLevel and minApiLevel")
    void compatibleModule_installsAndOnlyTheVersionsRequestCarriesTheLevel() {
        assertThat(PluginInstallUtils.installLatestPlugin("demo")).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        List<StubCatalogue.Request> versions = versionRequests();
        assertThat(versions).isNotEmpty();
        for (StubCatalogue.Request request : versions) {
            assertThat(queryOf(request)).as(request.toString())
                    .containsEntry("apiLevel", "630")
                    .containsEntry("minApiLevel", String.valueOf(ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL));
        }
        for (StubCatalogue.Request request : catalogue.requests()) {
            if (!request.path().endsWith("/versions")) {
                assertThat(queryOf(request)).as(request.toString())
                        .doesNotContainKey("apiLevel")
                        .doesNotContainKey("minApiLevel");
            }
        }
    }

    @Test
    @DisplayName("a JAR declaring api-version 620 is refused after download: nothing in the modules folder, no .part, the reason naming 620 and 630..630")
    void olderModule_isRefusedAndNothingIsLeft() throws IOException {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "620"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "620", 630, 630, 630);
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
        assertThat(PluginInstallUtils.installLatestPlugin("demo")).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a JAR declaring an api-version above the framework's level is refused the same way")
    void newerModule_isRefused() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "640"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "640", 630, 630, 630);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("the upper bound is the framework's own level: on a framework at 640 a 640 module installs and the versions request carries apiLevel=640")
    void frameworkLevelIsTheUpperBound() {
        frameworkAt(640);
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "640"));

        assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        for (StubCatalogue.Request request : versionRequests()) {
            assertThat(queryOf(request)).as(request.toString())
                    .containsEntry("apiLevel", "640")
                    .containsEntry("minApiLevel", String.valueOf(ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL));
        }
    }

    @Test
    @DisplayName("a JAR declaring no api-version is refused, the reason saying it declares none")
    void moduleWithoutApiVersion_isRefused() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", null));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_JAR_NO_LEVEL);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", 630, 630, 630);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a JAR declaring a non-integer api-version (Bukkit style '1.19') is refused, naming the value as written")
    void moduleWithBukkitStyleApiVersion_isRefused() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "'1.19'"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "1.19", 630, 630, 630);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("the level-filtered list empty: refused as 'no compatible version', nothing downloaded")
    void noCompatibleVersion_isRefusedWithoutDownload() {
        catalogue.mode(StubCatalogue.Mode.NO_COMPATIBLE_VERSION);

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_NO_COMPATIBLE_VERSION);
        assertThat(result.getReasonArgs()).containsExactly("demo", 630, 630, 630);
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a stale .part file of the same name is replaced, and an accepted install leaves no .part file")
    void stalePartFile_isReplaced() throws IOException {
        Files.write(inModules("demo-1.1.jar.part").toPath(), "stale".getBytes(StandardCharsets.UTF_8));

        assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        assertThat(bytesInModules("demo-1.1.jar")).isEqualTo(catalogue.jar());
    }

    @Test
    @DisplayName("an accepted install over an existing file of the final name replaces it, as the download always did")
    void existingFinalName_isOverwritten() throws IOException {
        try (OutputStream out = Files.newOutputStream(inModules("demo-1.1.jar").toPath())) {
            out.write("old".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(PluginInstallUtils.installReporting("demo", null).isInstalled()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        assertThat(bytesInModules("demo-1.1.jar")).isEqualTo(catalogue.jar());
    }

    @Test
    @DisplayName("/upm install demo 2.0.0, whose metadata names apiLevel 640: refused as 'this version cannot be"
            + " installed', nothing downloaded")
    void namedVersionAboveTheLevel_isRefusedWithoutDownload() {
        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_VERSION_INCOMPATIBLE);
        assertThat(result.getReasonArgs()).containsExactly("demo", "2.0.0", 630, 630, 630);
        assertThat(PluginInstallUtils.installPlugin("demo", "2.0.0")).isFalse();
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a version whose download is license_required is 'not available', refused before any download, with one English line")
    void licensedVersion_isRefusedWithoutDownloading() {
        frameworkAt(640);

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.isInstalled()).isFalse();
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
        assertThat(messages()).containsExactly(
                "demo 2.0.0 is not available for download (requires a licence); nothing was installed.");
    }

    @Test
    @DisplayName("/upm install demo 1.1 from a catalogue that does not filter levels, the JAR declaring 620: refused after download, nothing left")
    void namedVersionFromUnfilteredCatalogue_isRefusedAfterDownload() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "620"));

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.VERSION);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_JAR_LEVEL_REFUSED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1", "620", 630, 630, 630);
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("/upm versions reads the level-filtered list: the request carries both parameters and an empty answer is an empty list, not a failure")
    void versionsRequestCarriesTheLevel() {
        assertThat(PluginInstallUtils.getPluginVersions("demo")).containsExactly("1.1");

        catalogue.mode(StubCatalogue.Mode.NO_COMPATIBLE_VERSION);
        assertThat(PluginInstallUtils.getPluginVersions("demo")).isEmpty();

        assertThat(versionRequests()).isNotEmpty();
        for (StubCatalogue.Request request : versionRequests()) {
            assertThat(queryOf(request)).as(request.toString())
                    .containsEntry("apiLevel", "630")
                    .containsEntry("minApiLevel", "630");
        }
    }
}
