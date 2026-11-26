package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.ModuleContentLedger;
import com.ultikits.ultitools.utils.ModuleContentLedgers;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

class UltiToolsBootstrapTest {
    @TempDir Path server;

    @AfterEach
    void restoreContentLedger() {
        ModuleContentLedgers.install(ModuleContentLedger.NONE);
    }

    private Path dataFolder() throws IOException {
        return Files.createDirectories(server.resolve("plugins/UltiTools"));
    }

    private BootstrapContext context(Path data) {
        BootstrapContext context = mock(BootstrapContext.class);
        when(context.getDataDirectory()).thenReturn(data);
        when(context.getLogger()).thenReturn(mock(ComponentLogger.class));
        return context;
    }

    @Test
    void bootstrap_decidesTheAdmittedSetBeforeEnable() throws Exception {
        Path data = dataFolder();
        Path modules = ModuleFileTransactions.modulesFolder(data.toFile()).toPath();
        Path current = BootstrapFixtures.jar(modules.resolve("current.jar"),
                BootstrapFixtures.descriptor("Current", null), Collections.emptyMap());
        BootstrapFixtures.jar(modules.resolve("legacy.jar"),
                BootstrapFixtures.descriptor("Legacy", null).replace("700", "630"), Collections.emptyMap());
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context(data));
        BootstrapResult result = bootstrap.result();
        assertThat(result).isNotNull();
        try (ModuleClassLoader loader = result.classLoader()) {
            assertThat(loader.getURLs()).contains(current.toUri().toURL())
                    .doesNotContain(modules.resolve("legacy.jar").toUri().toURL());
            assertThat(result.admission().inspect(current.toFile()).isApiAllowed()).isTrue();
            assertThat(result.classPathFailure()).isNull();
        }
    }

    @Test
    void bootstrap_runsHooksAndHandsFailureIdentityAndEnglishLogsToEnable() throws Exception {
        Path data = dataFolder();
        Path modules = ModuleFileTransactions.modulesFolder(data.toFile()).toPath();
        Path failed = BootstrapFixtures.jar(modules.resolve("bad.jar"), BootstrapFixtures.descriptor("Bad", "fixture.Bad"),
                Collections.singletonMap("fixture.Bad", BootstrapFixtures.hook("fixture.Bad", "java/lang/NoClassDefFoundError")));
        BootstrapFixtures.jar(modules.resolve("good.jar"), BootstrapFixtures.descriptor("Good", "fixture.Good"),
                Collections.singletonMap("fixture.Good", BootstrapFixtures.hook("fixture.Good", null)));
        BootstrapFixtures.jar(modules.resolve("legacy.jar"), BootstrapFixtures.descriptor("Legacy", null).replace("700", "630"),
                Collections.emptyMap());
        ComponentLogger logger =
                mock(ComponentLogger.class);
        BootstrapContext context = context(data);
        when(context.getLogger()).thenReturn(logger);
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context);
        BootstrapResult result = bootstrap.result();
        assertThat(result).isNotNull();
        try (ModuleClassLoader loader = result.classLoader()) {
            assertThat(result.bootstrapsRun()).isEqualTo(2);
            assertThat(result.bootstrapFailures()).containsOnlyKeys(failed);
            assertThat(result.admission().inspect(failed.toFile()).getBootstrapFailure())
                    .isSameAs(result.bootstrapFailures().get(failed));
            assertThat(loader.getURLs()).contains(failed.toUri().toURL());
            verify(logger).info(
                    "[UltiTools] Bootstrap: processed pending module changes; 2 module JAR(s) on the module class path; "
                            + "2 module bootstrap(s) ran, 1 failed.", (Throwable) null);
            verify(logger).error("[UltiTools] Module 'bad.jar' failed in its bootstrap phase "
                    + "(fixture.Bad): java.lang.NoClassDefFoundError: fixture failure -- the module is not loaded "
                    + "at this start. Update rollback is checked during enable; check the enable log before changing any module JARs.",
                    result.bootstrapFailures().get(failed));
        }
    }

    @Test
    void bootstrap_neverThrows_whenClassPathFails() throws Exception {
        UltiToolsBootstrap bootstrap = spy(new UltiToolsBootstrap());
        doThrow(new NoClassDefFoundError("unexpected class-path failure"))
                .when(bootstrap).classPath(any(), any(), any(), any());
        assertThatCode(() -> bootstrap.bootstrap(context(dataFolder()))).doesNotThrowAnyException();
        BootstrapResult result = bootstrap.result();
        assertThat(result).isNotNull();
        try (ModuleClassLoader loader = result.classLoader()) {
            assertThat(loader.getURLs()).isEmpty();
            assertThat(result.classPathFailure()).hasCauseInstanceOf(NoClassDefFoundError.class);
        }
    }

    @Test
    void bootstrapRemovesOnlyStaleFrameworkTemporaries() throws Exception {
        Path data = dataFolder();
        Path root = data.resolve("bootstrap");
        Path packs = Files.createDirectories(root.resolve("datapacks"));
        Path recordTemp = Files.writeString(root.resolve(".contributions-interrupted.tmp"), "partial record");
        Path packTemp = Files.writeString(packs.resolve(".pack-interrupted.tmp"), "partial ZIP");
        Path wrongDirectory = Files.writeString(root.resolve(".pack-not-ours.tmp"), "keep");
        Path wrongSuffix = Files.writeString(packs.resolve(".pack-not-a-temp.txt"), "keep");
        Path operator = Files.writeString(data.resolve(".contributions-operator.tmp"), "keep");
        Path matchingDirectory = Files.createDirectory(packs.resolve(".pack-directory.tmp"));
        Path nested = Files.writeString(matchingDirectory.resolve(".pack-nested.tmp"), "keep");
        Path link = Files.createSymbolicLink(root.resolve(".contributions-link.tmp"), operator);

        var bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context(data));
        var result = bootstrap.result();
        try (var ignored = result.classLoader()) {
            assertThat(result.classPathFailure()).isNull();
            assertAll(
                    () -> assertThat(recordTemp).doesNotExist(),
                    () -> assertThat(packTemp).doesNotExist());
            assertThat(wrongDirectory).hasContent("keep");
            assertThat(wrongSuffix).hasContent("keep");
            assertThat(operator).hasContent("keep");
            assertThat(nested).hasContent("keep");
            assertThat(Files.isSymbolicLink(link)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bootstrap", "bootstrap/datapacks"})
    void bootstrapDoesNotCleanThroughSymlinkedDirectories(String directory) throws Exception {
        Path data = dataFolder();
        Path outside = Files.createDirectory(server.resolve("outside"));
        Path recordTemp = Files.writeString(outside.resolve(".contributions-protected.tmp"), "keep");
        Path packs = Files.createDirectory(outside.resolve("datapacks"));
        Path packTemp = Files.writeString(packs.resolve(".pack-protected.tmp"), "keep");
        Path link = data.resolve(directory);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, directory.equals("bootstrap") ? outside : packs);

        var bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context(data));
        var result = bootstrap.result();
        try (var ignored = result.classLoader()) {
            assertThat(result.classPathFailure()).isNull();
            assertThat(recordTemp).hasContent("keep");
            assertThat(packTemp).hasContent("keep");
        }
    }

    @Test
    void overallPreparationFailureDoesNotInstallHandlers() throws Exception {
        var context = context(dataFolder());
        var bootstrap = spy(new UltiToolsBootstrap());
        doThrow(new IllegalStateException("preparation failed")).when(bootstrap).classPath(any(), any(), any(), any());
        bootstrap.bootstrap(context);
        try (var ignored = bootstrap.result().classLoader()) {
            assertThat(bootstrap.result().contributions().modules()).isEmpty();
            verify(context, never()).getLifecycleManager();
        }
    }

    @Test
    void classPathIoFailure_isCarriedToEnableWithNoModuleUrls() throws Exception {
        UltiToolsBootstrap bootstrap = spy(new UltiToolsBootstrap());
        IOException failure = new IOException("fixture class-path failure");
        doThrow(failure).when(bootstrap).classPath(any(), any(), any(), any());
        bootstrap.bootstrap(context(dataFolder()));
        BootstrapResult result = bootstrap.result();
        assertThat(result).isNotNull();
        try (ModuleClassLoader loader = result.classLoader()) {
            assertThat(result.classPathFailure()).isSameAs(failure);
            assertThat(loader.getURLs()).containsExactly(ModuleClassPath.serverJarOf(Logger.getLogger("UltiTools")));
        }
    }

    @Test
    void transactionDirectoryFailure_isDeferredAndDoesNotAbortOrDiscardModules() throws Exception {
        Path data = dataFolder();
        Path records = ModuleFileTransactions.transactionsFolder(data.toFile()).toPath();
        Files.createDirectories(records.getParent());
        Files.write(records, new byte[] {1}); // A real file cannot be listed as the transactions directory.
        Path module = BootstrapFixtures.jar(ModuleFileTransactions.modulesFolder(data.toFile()).toPath().resolve("ok.jar"),
                BootstrapFixtures.descriptor("Ok", null), Collections.emptyMap());
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        assertThatCode(() -> bootstrap.bootstrap(context(data))).doesNotThrowAnyException();
        BootstrapResult result = bootstrap.result();
        assertThat(result).isNotNull();
        List<LogRecord> reports = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { reports.add(record); }
            @Override public void flush() { /* No buffered output. */ }
            @Override public void close() { /* No owned resource. */ }
        });
        try (ModuleClassLoader loader = result.classLoader()) {
            result.transactions().flushReports(logger, null);
            assertThat(loader.getURLs()).contains(module.toUri().toURL());
            assertThat(result.classPathFailure()).isNull();
            assertThat(reports).anySatisfy(record -> assertThat(record.getMessage())
                    .contains("ultitools:upm.start.records.unlistable", records.toString(), "cannot be listed"));
        }
    }

    @Test
    void invalidServerRoot_neverEscapesBootstrapOrRebuildsTheBrokenTransactions() {
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        assertThatCode(() -> bootstrap.bootstrap(context(Path.of("/UltiTools"))))
                .doesNotThrowAnyException();
        assertThat(bootstrap.result()).isNotNull();
        assertThat(bootstrap.result().classPathFailure()).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void dataDirectoryFailure_isNotLostWhenCreatingThePlugin() throws Exception {
        BootstrapContext context = mock(BootstrapContext.class);
        IllegalStateException failure = new IllegalStateException("data directory unavailable");
        when(context.getDataDirectory()).thenThrow(failure);
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        assertThatCode(() -> bootstrap.bootstrap(context)).doesNotThrowAnyException();
        PluginProviderContext provider = mock(PluginProviderContext.class);
        when(provider.getDataDirectory()).thenReturn(dataFolder());
        List<BootstrapResult> arguments = new ArrayList<>();
        try (MockedConstruction<UltiTools> constructions = mockConstruction(UltiTools.class,
                (mock, construction) -> arguments.add((BootstrapResult) construction.arguments().get(0)))) {
            assertThat(bootstrap.createPlugin(provider)).isSameAs(constructions.constructed().get(0));
            try (ModuleClassLoader loader = arguments.get(0).classLoader()) {
                assertThat(arguments.get(0).classPathFailure()).hasCause(failure);
                assertThat(loader.getURLs()).isEmpty();
            }
        }
    }

    @Test
    void createPlugin_passesTheSameResultOrAnEmptyFallbackToTheExactMainClass() throws Exception {
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        Path data = dataFolder();
        bootstrap.bootstrap(context(data));
        PluginProviderContext provider = mock(PluginProviderContext.class);
        when(provider.getDataDirectory()).thenReturn(data);
        List<BootstrapResult> arguments = new ArrayList<>();
        try (MockedConstruction<UltiTools> constructions = mockConstruction(UltiTools.class,
                (mock, construction) -> arguments.add((BootstrapResult) construction.arguments().get(0)))) {
            assertThat(bootstrap.createPlugin(provider)).isSameAs(constructions.constructed().get(0));
            assertThat(arguments.get(0)).isSameAs(bootstrap.result());
            assertThat(new UltiToolsBootstrap().createPlugin(provider)).isSameAs(constructions.constructed().get(1));
            try (ModuleClassLoader original = arguments.get(0).classLoader();
                    ModuleClassLoader fallback = arguments.get(1).classLoader()) {
                assertThat(original).isNotSameAs(fallback);
                assertThat(fallback.getURLs()).isEmpty();
            }
        }
    }
}
