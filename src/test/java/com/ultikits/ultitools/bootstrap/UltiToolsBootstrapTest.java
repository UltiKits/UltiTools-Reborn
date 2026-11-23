package com.ultikits.ultitools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import com.ultikits.ultitools.ModuleClassLoader;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;

class UltiToolsBootstrapTest {
    @TempDir Path server;

    private Path dataFolder() throws IOException {
        return Files.createDirectories(server.resolve("plugins/UltiTools"));
    }

    private BootstrapContext context(Path data) {
        BootstrapContext context = mock(BootstrapContext.class);
        when(context.getDataDirectory()).thenReturn(data);
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
            result.transactions().flushReports(logger, text -> text);
            assertThat(loader.getURLs()).contains(module.toUri().toURL());
            assertThat(result.classPathFailure()).isNull();
            assertThat(reports).anySatisfy(record -> assertThat(record.getMessage())
                    .contains(records.toString(), "无法列出"));
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
