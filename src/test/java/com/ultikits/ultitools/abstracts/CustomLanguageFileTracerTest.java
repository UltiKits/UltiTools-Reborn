package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Tracer for #608 (plan 17-69): the maintainer's "copy, rename, select" customisation of language
 * files works end to end, for a module and for the framework itself.
 * <p>
 * Maintainer decisions of 2026-10-04: official language files are framework-owned; an operator
 * customises by copying an official file under a new name and selecting that name as {@code
 * language} in {@code plugins/UltiTools/config.yml}; keys missing from the custom file are filled
 * from the official file of the language it was copied from, identified by the name prefix
 * ({@code zh-myserver} is based on {@code zh}).
 */
@DisplayName("#608 tracer: a custom-named language file is selected, and its gaps are filled from its official base")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework case drives UltiTools' private language load
class CustomLanguageFileTracerTest {

    private static final String EN = "greeting: \"Hello\"\nfarewell: \"Goodbye\"\n";
    private static final String ZH = "greeting: \"你好\"\nfarewell: \"再见\"\n";
    /** The operator's copy of {@code zh.yml}: one key changed, {@code farewell} deleted. */
    private static final String ZH_MYSERVER = "greeting: \"你好，本服欢迎你\"\n";

    /** A framework catalogue key both shipped catalogues translate. */
    private static final String FRAMEWORK_CHANGED_KEY = "Module '%s' reloaded.";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = BootLanguageFixture.create(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    @Test
    @DisplayName("module: language zh-myserver renders the custom text, fills the missing key from official zh, and reports zh as its language")
    void moduleSelectsTheCustomFileAndFillsGapsFromItsOfficialBase() throws Exception {
        fixture.jarEntry("lang/en.yml", EN).jarEntry("lang/zh.yml", ZH)
                .onDisk("lang/zh-myserver.yml", ZH_MYSERVER).language("zh-myserver");

        UltiToolsPlugin plugin = fixture.construct("6.3.0");
        plugin.commitLanguageProvenance();

        assertThat(plugin.i18n("greeting")).isEqualTo("你好，本服欢迎你");
        assertThat(plugin.i18n("farewell")).isEqualTo("再见");
        assertThat(plugin.getLanguageCode()).isEqualTo("zh");
    }

    @Test
    @DisplayName("framework: plugins/UltiTools/lang/zh-myserver.json renders the custom text and fills the missing key from official zh")
    void frameworkSelectsTheCustomFileAndFillsGapsFromItsOfficialBase() throws Exception {
        File dataFolder = new File(tempDir, "UltiTools");
        File custom = new File(dataFolder, "lang" + File.separator + "zh-myserver.json");
        Files.createDirectories(custom.getParentFile().toPath());
        Files.write(custom.toPath(),
                "{\"Module '%s' reloaded.\":\"本服：模块 '%s' 已重载\"}".getBytes(StandardCharsets.UTF_8));
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "zh-myserver");
        Logger logger = Mockito.mock(Logger.class);
        UltiTools ultiTools = TestHelper.mockUltiToolsInstance(mock -> {
            Mockito.lenient().when(mock.getConfig()).thenReturn(config);
            Mockito.lenient().when(mock.getDataFolder()).thenReturn(dataFolder);
            Mockito.lenient().when(mock.getLogger()).thenReturn(logger);
        });

        Method initLanguage = UltiTools.class.getDeclaredMethod("initLanguage");
        initLanguage.setAccessible(true);
        initLanguage.invoke(ultiTools);
        Field languageField = UltiTools.class.getDeclaredField("language");
        languageField.setAccessible(true);
        Language language = (Language) languageField.get(ultiTools);

        String officialZh = shippedFrameworkZh().get(UltiToolsPlugin.LANGUAGE_CHANGE_PENDING_KEY);
        assertThat(officialZh).isNotNull();
        assertThat(language.getLocalizedText(FRAMEWORK_CHANGED_KEY)).isEqualTo("本服：模块 '%s' 已重载");
        assertThat(language.getLocalizedText(UltiToolsPlugin.LANGUAGE_CHANGE_PENDING_KEY)).isEqualTo(officialZh);
    }

    private static Map<String, String> shippedFrameworkZh() throws IOException {
        try (Reader reader = new InputStreamReader(
                UltiToolsPlugin.class.getResourceAsStream("/lang/zh.json"), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
        }
    }
}
