package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * {@code /upm update} staging treats the catalogue's {@code download.url} as an opaque address (plan
 * 22-26 fix round, verification gap 1).
 *
 * <p>The catalogue contract says download URLs must not be built or parsed and their shape is
 * descriptive only. Staging therefore verifies the download against the version it has just read
 * through the contract's version route (identifier and version), never against a route derived from
 * the URL. Each case serves a download URL of another, contract-allowed shape and expects the update
 * to stage and verify; the usual shape is the control.
 *
 * <p>The catalogue and downloader exercised are the ones {@link PluginInstallUtils#stageUpdate(String)}
 * itself binds: they are captured from that call, then run through a real {@link ModuleFileTransactions}.
 */
@DisplayName("update staging does not parse the download URL (22-26 fix, gap 1)")
class StagingDownloadUrlShapeTest {

    @TempDir
    File tempDir;

    private StubCatalogue stub;

    @BeforeEach
    void startStub() throws IOException {
        stub = StubCatalogue.start();
        PluginInstallUtils.setBaseUrlForTesting(stub.baseUrl());
    }

    @AfterEach
    void stopStub() {
        PluginInstallUtils.resetBaseUrl();
        stub.close();
    }

    @ParameterizedTest(name = "download URL path {0}")
    @ValueSource(strings = {
            "/v1/catalogue/modules/demo/versions/1.1.0/download",
            "/v1/catalogue/downloads/01J9ZQ7W5X3TOKEN",
            "/v1/catalogue/modules/demo/versions/1.1.0/download?sig=abc123",
            "/v1/catalogue/files/demo/1.1.0/Demo-1.1.0.jar"
    })
    @DisplayName("a catalogue whose download URL has another allowed shape still stages and verifies")
    void anyDownloadUrlShape_stagesAndVerifies(String pathAndQuery) throws IOException {
        stub.downloadPath(pathAndQuery);
        File serverRoot = new File(tempDir, "server");
        File dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        File oldJar = ModuleUpdateFixtures.moduleJar(new File(ModuleFileTransactions.modulesFolder(dataFolder),
                "demo-1.0.jar"), StubCatalogue.MODULE_NAME, "1.0", StubCatalogue.MODULE, "630");
        UltiToolsPlugin loaded = ModuleUpdateFixtures.loadedModule(StubCatalogue.MODULE_NAME, "1.0",
                StubCatalogue.MODULE);

        try (MockedStatic<UltiTools> ultiTools = mockStatic(UltiTools.class)) {
            ultiTools.when(UltiTools::getPluginVersion).thenReturn(630);
            Bound bound = boundByStageUpdate(ultiTools, dataFolder, loaded);

            ModuleFileTransactions.StageResult result = new ModuleFileTransactions(dataFolder).stageUpdate(
                    StubCatalogue.MODULE, Collections.singletonList(loaded),
                    new ModuleUpdateFixtures.CodeSources().with(loaded, oldJar), bound.catalogue, bound.downloader);

            assertThat(result.getOutcome()).as("reason %s %s", result.getReasonKey(),
                    java.util.Arrays.toString(result.getReasonArgs()))
                    .isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
            File staged = stagedJar(serverRoot);
            assertThat(staged).as("the staged JAR").isNotNull();
            assertThat(Files.readAllBytes(staged.toPath())).isEqualTo(stub.jar());
        }
        String downloadPath = pathAndQuery.split("\\?", 2)[0];
        assertThat(stub.paths()).as("the download was fetched at the URL the catalogue named").contains(downloadPath);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a link the staging catalogue did not just hand out is not downloaded")
    void foreignLink_isNotDownloaded() throws IOException {
        PluginInstallUtils.StagingCatalogue staging = new PluginInstallUtils.StagingCatalogue();
        String other = stub.baseUrl() + "/v1/catalogue/modules/demo/versions/" + StubCatalogue.VERSION + "/download";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> staging.download(other, "demo-1.1.0.jar", tempDir))
                .as("nothing looked up yet").isInstanceOf(IOException.class);
        String link = staging.downloadLink(StubCatalogue.MODULE, StubCatalogue.VERSION);
        assertThat(link).isEqualTo(other);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> staging.download(other + "?another", "demo-1.1.0.jar", tempDir))
                .as("another link than the one handed out").isInstanceOf(IOException.class);

        assertThat(stub.paths()).as("no download was requested").doesNotContain(
                "/v1/catalogue/modules/demo/versions/" + StubCatalogue.VERSION + "/download");
        assertThat(tempDir.list()).isEmpty();
    }

    /** The catalogue and downloader one {@code stageUpdate} call bound. */
    private static final class Bound {
        private final ModuleFileTransactions.Catalogue catalogue;
        private final ModuleFileTransactions.Downloader downloader;

        private Bound(ModuleFileTransactions.Catalogue catalogue, ModuleFileTransactions.Downloader downloader) {
            this.catalogue = catalogue;
            this.downloader = downloader;
        }
    }

    @SuppressWarnings("unchecked")
    private static Bound boundByStageUpdate(MockedStatic<UltiTools> ultiTools, File dataFolder,
                                            UltiToolsPlugin loaded) {
        AtomicReference<Bound> bound = new AtomicReference<>();
        UltiTools instance = mock(UltiTools.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.getPluginList()).thenReturn(Collections.singletonList(loaded));
        when(pluginManager.getModuleJarIndex()).thenReturn(null);
        when(instance.getPluginManager()).thenReturn(pluginManager);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        try (MockedConstruction<ModuleFileTransactions> ignored = mockConstruction(ModuleFileTransactions.class,
                (mock, context) -> when(mock.stageUpdate(anyString(), any(Supplier.class), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            bound.set(new Bound(invocation.getArgument(3), invocation.getArgument(4)));
                            return ModuleFileTransactions.StageResult.of(
                                    ModuleFileTransactions.StageResult.Outcome.FAILED, null, null, null, null);
                        }))) {
            PluginInstallUtils.stageUpdate(StubCatalogue.MODULE);
        }
        assertThat(bound.get()).as("stageUpdate bound a catalogue and a downloader").isNotNull();
        return bound.get();
    }

    /** The staged JAR: records and staged files live under the server root, not the data folder. */
    private static File stagedJar(File serverRoot) throws IOException {
        File[] found = {null};
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(serverRoot.toPath())) {
            files.filter(path -> path.getFileName().toString().equals("demo-" + StubCatalogue.VERSION + ".jar"))
                    .findFirst().ifPresent(path -> found[0] = path.toFile());
        }
        return found[0];
    }
}
