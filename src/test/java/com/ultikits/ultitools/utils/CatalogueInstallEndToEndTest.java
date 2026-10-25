package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * {@code /upm install} against the catalogue of protocol v2, end to end (plan 22-18, D-27, D-28).
 * A JAR whose byte count and SHA-256 match the version's metadata is installed; a JAR one byte
 * away from the hash its metadata names is discarded before anything reaches the modules folder,
 * and the operator is told once, without the installed file ever existing.
 */
@DisplayName("/upm install against the v2 catalogue: a matching JAR installs, a tampered one is discarded")
@PendingFixture
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CatalogueInstallEndToEndTest {

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
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(630);

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

    private List<String> modulesFolderNames() {
        String[] names = modules.list();
        List<String> list = new ArrayList<>();
        if (names != null) {
            Collections.addAll(list, names);
        }
        Collections.sort(list);
        return list;
    }

    /** What an operator sees: the records at WARNING or above. */
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

    @Test
    @DisplayName("a JAR whose hash and byte count match the version is installed, byte for byte")
    void matchingJar_isInstalled() throws IOException {
        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

        assertThat(result.isInstalled()).isTrue();
        assertThat(result.getFileName()).isEqualTo("demo-1.1.jar");
        assertThat(modulesFolderNames()).containsExactly("demo-1.1.jar");
        assertThat(Files.readAllBytes(new File(modules, "demo-1.1.jar").toPath()))
                .isEqualTo(catalogue.jar());
    }

    @Test
    @DisplayName("one byte away from the metadata's hash: nothing installed, no .part left, one integrity line")
    void tamperedJar_isDiscardedBeforeStaging() {
        catalogue.mode(StubCatalogue.Mode.TAMPER_HASH);

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getFileName()).isNull();
        assertThat(modulesFolderNames()).isEmpty();
        assertThat(messages()).containsExactly(
                "Downloaded demo 1.1 failed its integrity check (hash mismatch); nothing was installed.");
    }

    @Test
    @DisplayName("only /v1/catalogue/* is requested, no request carries Authorization, no /plugin/* is reached")
    void onlyV2CataloguePathsAreRequested() {
        assertThat(PluginInstallUtils.installReporting("demo", "1.1").isInstalled()).isTrue();

        List<String> paths = catalogue.paths();
        assertThat(paths).isNotEmpty();
        for (StubCatalogue.Request request : catalogue.requests()) {
            assertThat(request.path()).as(request.toString()).startsWith("/v1/catalogue/");
            assertThat(request.authorization()).as(request.toString()).isNull();
        }
        assertThat(paths).noneMatch(path -> path.startsWith("/plugin/"));
    }
}
