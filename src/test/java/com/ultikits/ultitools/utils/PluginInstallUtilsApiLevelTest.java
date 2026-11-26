package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.manager.ModuleApiLevels;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * {@code /upm install} against the v2 catalogue (plan 22-18): only the versions route carries the
 * API-level filter, the download is written to a name the module loader never reads and is checked
 * byte for byte before it is installed, its {@code plugin.yml} is read again, and a module outside
 * {@code 700..<framework level>} -- or a version the catalogue does not offer for download -- is
 * refused with a reason and nothing left in the modules folder.
 */
@DisplayName("/upm install over /v1/catalogue: levels, integrity and the licence-required outcome")
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
        frameworkAt(700);

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
    @DisplayName("a JAR declaring api-version 700 installs; only the versions request carries apiLevel and minApiLevel")
    void compatibleModule_installsAndOnlyTheVersionsRequestCarriesTheLevel() throws IOException {
        assertThat(PluginInstallUtils.installLatestPlugin("demo")).isTrue();

        assertThat(modulesFolderNames()).isEmpty();
        assertThat(PendingTestValues.stagedJar(modules, "demo-1.1.0.jar")).exists();
        List<StubCatalogue.Request> versions = versionRequests();
        assertThat(versions).isNotEmpty();
        for (StubCatalogue.Request request : versions) {
            assertThat(queryOf(request)).as(request.toString())
                    .containsEntry("apiLevel", "700")
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
    @DisplayName("a JAR declaring api-version 620 is refused after download: nothing in the modules folder, no .part, the reason naming 620 and 700..700")
    void olderModule_isRefusedAndNothingIsLeft() throws IOException {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "620"));

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_JAR_LEVEL_REFUSED);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", "1.1.0", "620", 700, 700, 700);
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
        assertThat(PluginInstallUtils.installLatestPlugin("demo")).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a JAR declaring an api-version above the framework's level is refused the same way")
    void newerModule_isRefused() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "710"));

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_JAR_LEVEL_REFUSED);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", "1.1.0", "710", 700, 700, 700);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("the upper bound is the framework's own level: on a framework at 710 a 710 module installs and the versions request carries apiLevel=710")
    void frameworkLevelIsTheUpperBound() throws IOException {
        frameworkAt(710);
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "710"));

        assertThat(PluginInstallUtils.installReporting("demo", null).queued()).isTrue();

        assertThat(modulesFolderNames()).isEmpty();
        assertThat(PendingTestValues.stagedJar(modules, "demo-1.1.0.jar")).exists();
        for (StubCatalogue.Request request : versionRequests()) {
            assertThat(queryOf(request)).as(request.toString())
                    .containsEntry("apiLevel", "710")
                    .containsEntry("minApiLevel", String.valueOf(ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL));
        }
    }

    @Test
    @DisplayName("a JAR declaring no api-version is refused, the reason saying it declares none")
    void moduleWithoutApiVersion_isRefused() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", null));

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_JAR_NO_LEVEL);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", "1.1.0", 700, 700, 700);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a JAR declaring a non-integer api-version (Bukkit style '1.19') is refused, naming the value as written")
    void moduleWithBukkitStyleApiVersion_isRefused() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "'1.19'"));

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_JAR_LEVEL_REFUSED);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", "1.1.0", "1.19", 700, 700, 700);
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("the level-filtered list empty: refused as 'no compatible version', nothing downloaded")
    void noCompatibleVersion_isRefusedWithoutDownload() {
        catalogue.mode(StubCatalogue.Mode.NO_COMPATIBLE_VERSION);

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_NO_COMPATIBLE_VERSION);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", 700, 700, 700);
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("a stale .part in the modules directory is untouched and the new staging uses a separate folder")
    void stalePartFile_isUntouched() throws IOException {
        Files.write(inModules("demo-1.1.0.jar.part").toPath(), "stale".getBytes(StandardCharsets.UTF_8));

        assertThat(PluginInstallUtils.installReporting("demo", null).queued()).isTrue();

        assertThat(modulesFolderNames()).containsExactly("demo-1.1.0.jar.part");
        assertThat(bytesInModules("demo-1.1.0.jar.part")).isEqualTo("stale".getBytes(StandardCharsets.UTF_8));
        File staged = PendingTestValues.stagedJar(modules, "demo-1.1.0.jar");
        assertThat(staged.getParentFile().list()).containsExactly(staged.getName());
        assertThat(Files.readAllBytes(staged.toPath())).isEqualTo(catalogue.jar());
    }

    @Test
    @DisplayName("a same-name file refuses a new install without downloading or touching that file")
    void existingFinalName_isNotOverwritten() throws IOException {
        String name = "demo-1.1.0.jar";
        byte[] original = "old".getBytes(StandardCharsets.UTF_8);
        Files.write(inModules(name).toPath(), original);

        var result = PluginInstallUtils.installReporting("demo", null);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.key().path()).isEqualTo("upm.install.refused.target_exists");
            assertThat(reply.args()).containsExactly(Arg.string("file", name));
        });
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).containsExactly(name);
        assertThat(bytesInModules(name)).isEqualTo(original);
        assertThat(PluginInstallUtils.pendingChanges().list()).isEmpty();
        assertThat(ModuleUpdateFixtures.treeOf(ModuleFileTransactions.transactionsFolder(modules.getParentFile()))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "1.1.0,other,wrong_identity",
        "1.0.0,demo,wrong_version"
    })
    void aNewInstallRejectsAValidDownloadWhoseDescriptorDisagreesWithTheCatalogue(
            String version, String identity, String reason) throws IOException {
        catalogue.jar(StubCatalogue.moduleJar("Demo", version, identity, "700"));

        var result = PluginInstallUtils.installReporting("demo", StubCatalogue.VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.key().path()).isEqualTo("upm.install.not_staged");
            assertThat(reply.args().getLast()).isInstanceOfSatisfying(Arg.Localized.class,
                    arg -> assertThat(arg.value().toString()).contains("upm.reason." + reason));
        });
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
        assertThat(PluginInstallUtils.pendingChanges().list()).isEmpty();
        assertThat(ModuleUpdateFixtures.treeOf(ModuleFileTransactions.transactionsFolder(modules.getParentFile()))).isEmpty();
    }

    @Test
    void aDeniedInstallNamesTheReasonInTheOperatorsLanguage() throws IOException {
        var manager = mock(PluginManager.class);
        when(UltiTools.getInstance().getPluginManager()).thenReturn(manager);
        when(manager.getPluginList()).thenThrow(new SecurityException("fixture access failure"));

        var result = PluginInstallUtils.installReporting("demo", StubCatalogue.VERSION);

        assertThat(result.queued()).isFalse();
        try (var text = new CommandMessageTestSupport("en")) {
            assertThat(result.replies()).singleElement().satisfies(reply -> {
                assertThat(reply.key().path()).isEqualTo("upm.install.not_staged");
                var args = reply.args().toArray(Arg[]::new);
                assertThat(CommandMessageTestSupport.plain(text.messages().render(reply.key(), Locale.CHINESE, args)))
                        .contains("访问", "fixture access failure");
                assertThat(CommandMessageTestSupport.plain(text.messages().render(reply.key(), Locale.ENGLISH, args)))
                        .contains("access", "fixture access failure");
            });
        }
        assertThat(downloadedAnything()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCancelledDownloadNamesTheCancellationAndAnUnknownFailureKeepsTheFallback(boolean cancelled) {
        var transactions = mock(ModuleFileTransactions.class);
        when(UltiTools.getInstance().getModuleFileTransactions()).thenReturn(transactions);
        var result = cancelled ? ModuleFileTransactions.StageResult.failed(null, UpmKeys.REASON_CANCELLED_WHILE_DOWNLOADING)
                : ModuleFileTransactions.StageResult.of(ModuleFileTransactions.StageResult.Outcome.FAILED, null, null, null, null);
        when(transactions.stageChange(eq("demo"), eq(StubCatalogue.VERSION), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        var install = PluginInstallUtils.installReporting("demo", StubCatalogue.VERSION);

        assertThat(install.queued()).isFalse();
        assertThat(install.replies()).singleElement().satisfies(reply -> {
            if (cancelled) {
                assertThat(reply.key().path()).isEqualTo("upm.install.not_staged");
                assertThat(reply.args().getLast()).isInstanceOfSatisfying(Arg.Localized.class,
                        arg -> assertThat(arg.value().toString()).contains(UpmKeys.REASON_CANCELLED_WHILE_DOWNLOADING.path()));
            } else {
                assertThat(reply.key()).isEqualTo(UpmKeys.INSTALL_FAILED);
            }
        });
    }

    @Test
    @DisplayName("/upm install demo 2.0.0, whose metadata names an apiLevel above the framework's: refused as 'this version cannot be"
            + " installed', nothing downloaded")
    void namedVersionAboveTheLevel_isRefusedWithoutDownload() {
        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_VERSION_INCOMPATIBLE);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", "2.0.0", 700, 700, 700);
        assertThat(PluginInstallUtils.installPlugin("demo", "2.0.0")).isFalse();
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("on a 7.1.0 framework (level 710) the stub's 2.0.0 is still above the level: the UAT stub rows stay"
            + " 'cannot be installed' as the framework's own level rises")
    void stubLicensedVersion_staysAboveA710Framework() {
        frameworkAt(710);

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_VERSION_INCOMPATIBLE);
        assertThat(downloadedAnything()).isFalse();
    }

    @Test
    @DisplayName("a version whose download is license_required is 'not available', refused before any download, with one catalogue line")
    void licensedVersion_isRefusedWithoutDownloading() {
        frameworkAt(StubCatalogue.LICENSED_API_LEVEL);

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(downloadedAnything()).isFalse();
        assertThat(modulesFolderNames()).isEmpty();
        // The catalogue text of plan 22-22; the mocked framework has no language loaded, so the key itself is shown.
        assertThat(messages()).containsExactly(String.format(
                "无法安装 %s %s：该版本需要许可证，不提供公开下载。模块目录没有任何改动。", "demo", StubCatalogue.LICENSED_VERSION));
    }

    @Test
    @DisplayName("/upm install demo 1.1.0 from a catalogue that does not filter levels, the JAR declaring 620: refused after download, nothing left")
    void namedVersionFromUnfilteredCatalogue_isRefusedAfterDownload() {
        catalogue.jar(StubCatalogue.moduleJar("Demo", StubCatalogue.VERSION, "demo", "620"));

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_JAR_LEVEL_REFUSED);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", "1.1.0", "620", 700, 700, 700);
        assertThat(downloadedAnything()).isTrue();
        assertThat(modulesFolderNames()).isEmpty();
    }

    @Test
    @DisplayName("/upm versions reads the level-filtered list: the request carries both parameters and an empty answer is an empty list, not a failure")
    void versionsRequestCarriesTheLevel() {
        assertThat(PluginInstallUtils.getPluginVersions("demo")).containsExactly("1.1.0");

        catalogue.mode(StubCatalogue.Mode.NO_COMPATIBLE_VERSION);
        assertThat(PluginInstallUtils.getPluginVersions("demo")).isEmpty();

        assertThat(versionRequests()).isNotEmpty();
        for (StubCatalogue.Request request : versionRequests()) {
            assertThat(queryOf(request)).as(request.toString())
                    .containsEntry("apiLevel", "700")
                    .containsEntry("minApiLevel", "700");
        }
    }
}
