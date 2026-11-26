package com.ultikits.ultitools.utils;

import org.junit.jupiter.params.provider.ValueSource;
import java.util.Locale;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
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

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * Plan 22-22 Task 3 (S-62, checker B-W5): the two {@code /upm} refusal reasons of 22-18 -- an integrity failure and a
 * version that needs a licence -- are catalogue keys the command shows its sender, in English with
 * {@code language: en} and in Chinese with {@code language: zh}, instead of a generic "install failed" and an English
 * log line.
 */
@DisplayName("/upm refusal reasons of the v2 catalogue are catalogue text")
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
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(700);
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

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo", StubCatalogue.VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_INTEGRITY_FAILED);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", StubCatalogue.VERSION);
    }

    @Test
    @DisplayName("a version that needs a licence is refused with the licence reason before any download")
    void licensedVersionIsRefusedWithItsReason() {
        ultiTools.when(UltiTools::getPluginVersion).thenReturn(StubCatalogue.LICENSED_API_LEVEL);

        PluginInstallUtils.PendingResult result = PluginInstallUtils.installReporting("demo",
                StubCatalogue.LICENSED_VERSION);

        assertThat(result.queued()).isFalse();
        assertThat(result.replies().getLast().key()).isEqualTo(UpmKeys.INSTALL_REFUSED_LICENCE_REQUIRED);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("demo", StubCatalogue.LICENSED_VERSION);
        for (StubCatalogue.Request request : catalogue.requests()) {
            assertThat(request.path()).as("nothing downloaded").doesNotEndWith("/download");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    @DisplayName("the sender sees each refusal in the selected locale with the original wording")
    void bothReasonsAreTranslated(String language) {
        Locale locale = Locale.forLanguageTag(language);
        Arg[] args = {Arg.string("module", "demo"), Arg.string("version", "1.1")};
        try (var text = new CommandMessageTestSupport(language)) {
            String integrity = "en".equals(language)
                    ? "Refused to install demo 1.1: the file did not pass its integrity check against the catalogue (its SHA-256 or size does not match, or the catalogue gave no usable hash or size). Nothing was written to the modules folder."
                    : "已拒绝安装 demo 1.1：文件没有通过云端目录的完整性校验（SHA-256 或大小与目录不符，或目录没有给出可用的校验值）。模块目录没有任何改动。";
            String licence = "en".equals(language)
                    ? "Cannot install demo 1.1: this version requires a licence and is not offered for public download. Nothing was written to the modules folder."
                    : "无法安装 demo 1.1：该版本需要许可证，不提供公开下载。模块目录没有任何改动。";
            assertThat(CommandMessageTestSupport.plain(text.messages().render(UpmKeys.INSTALL_REFUSED_INTEGRITY_FAILED,
                    locale, args))).isEqualTo(integrity);
            assertThat(CommandMessageTestSupport.plain(text.messages().render(UpmKeys.INSTALL_REFUSED_LICENCE_REQUIRED,
                    locale, args))).isEqualTo(licence);
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
}
