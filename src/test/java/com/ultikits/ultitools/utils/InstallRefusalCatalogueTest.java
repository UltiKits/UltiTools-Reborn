package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.contract.PendingFixture;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * Plan 22-22 Task 3 (S-62, checker B-W5): the two {@code /upm} refusal reasons of 22-18 -- an integrity failure and a
 * version that needs a licence -- are catalogue keys the command shows its sender, in English with
 * {@code language: en} and in Chinese with {@code language: zh}, instead of a generic "install failed" and an English
 * log line.
 */
@DisplayName("/upm refusal reasons of the v2 catalogue are catalogue text")
@PendingFixture
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InstallRefusalCatalogueTest {

    @TempDir
    File serverRoot;

    private StubCatalogue catalogue;
    private MockedStatic<UltiTools> ultiTools;

    @BeforeEach
    void setUp() throws IOException {
        File dataFolder = new File(new File(serverRoot, "plugins"), "UltiTools");
        assertThat(dataFolder.mkdirs()).isTrue();
        assertThat(ModuleFileTransactions.modulesFolder(dataFolder).mkdirs()).isTrue();
        UltiTools instance = mock(UltiTools.class);
        when(instance.getDataFolder()).thenReturn(dataFolder);
        ultiTools = mockStatic(UltiTools.class);
        ultiTools.when(UltiTools::getInstance).thenReturn(instance);
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(630);
        catalogue = StubCatalogue.start();
        PluginInstallUtils.setBaseUrlForTesting(catalogue.baseUrl());
        Logger logger = Logger.getLogger(PluginInstallUtils.class.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
    }

    @AfterEach
    void tearDown() {
        Logger logger = Logger.getLogger(PluginInstallUtils.class.getName());
        logger.setLevel(null);
        logger.setUseParentHandlers(true);
        PluginInstallUtils.resetBaseUrl();
        catalogue.close();
        ultiTools.close();
    }

    @ParameterizedTest
    @EnumSource(value = StubCatalogue.Mode.class, names = {"TAMPER_HASH", "TAMPER_SIZE", "WRONG_HEADER"})
    @DisplayName("every integrity failure is refused with the integrity reason, naming module and version")
    void integrityFailureIsRefusedWithItsReason(StubCatalogue.Mode mode) {
        catalogue.mode(mode);

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo", "1.1");

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_INTEGRITY_FAILED);
        assertThat(result.getReasonArgs()).containsExactly("demo", "1.1");
    }

    @Test
    @DisplayName("a version that needs a licence is refused with the licence reason before any download")
    void licensedVersionIsRefusedWithItsReason() {
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(640);

        PluginInstallUtils.InstallResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.isInstalled()).isFalse();
        assertThat(result.getReasonKey()).isEqualTo(PluginInstallUtils.InstallResult.Keys.REASON_LICENCE_REQUIRED);
        assertThat(result.getReasonArgs()).containsExactly("demo", StubCatalogue.LICENSED_VERSION);
        for (StubCatalogue.Request request : catalogue.requests()) {
            assertThat(request.path()).as("nothing downloaded").doesNotEndWith("/download");
        }
    }

    @Test
    @DisplayName("the operator reads English with language en and Chinese with language zh, same placeholders")
    void bothReasonsAreTranslated() throws IOException {
        JsonObject english = catalogue("en");
        JsonObject chinese = catalogue("zh");
        for (String key : new String[] {PluginInstallUtils.InstallResult.Keys.REASON_INTEGRITY_FAILED,
                PluginInstallUtils.InstallResult.Keys.REASON_LICENCE_REQUIRED}) {
            assertThat(english.has(key)).as(key).isTrue();
            assertThat(chinese.has(key)).as(key).isTrue();
            String en = String.format(english.get(key).getAsString(), "demo", "1.1");
            String zh = String.format(chinese.get(key).getAsString(), "demo", "1.1");
            assertThat(en).contains("demo 1.1").doesNotContainPattern("[\\u3000-\\u9fff\\uff00-\\uffef]");
            assertThat(zh).contains("demo 1.1").containsPattern("[\\u4e00-\\u9fff]");
        }
    }

    @Test
    @DisplayName("the English literals of 22-18 for these two reasons are gone from PluginInstallUtils")
    void englishLiteralsAreGone() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/ultikits/ultitools/utils/PluginInstallUtils.java")), StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("failed its integrity check").doesNotContain("requires a licence")
                .doesNotContain("carries no usable hash or size");
    }

    private static JsonObject catalogue(String language) throws IOException {
        try (InputStream in = InstallRefusalCatalogueTest.class.getClassLoader()
                .getResourceAsStream("lang/" + language + ".json")) {
            assertThat(in).isNotNull();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
