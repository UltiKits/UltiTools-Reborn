package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Tracer of follow-up 3 batch 4 (#615 item 3; maintainer decision 2026-10-06, row 01:07): the placeholder guard that
 * protects an operator's custom language file compares the {@code String.format} conversion of each argument, not
 * only how many arguments a value needs. A custom value that uses {@code %d} where the official text has {@code %s}
 * (or the other way round, or a different argument index) is not used for that key: the official text is, with one
 * WARNING naming the file and the key, and formatting the message no longer throws
 * {@code IllegalFormatConversionException}. The comparison runs wherever the count guard runs -- a module's custom
 * file and the framework's own custom file.
 */
@DisplayName("#615 item 3 tracer: a custom value whose %s/%d conversions differ from the official text uses the official text, with one warning")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // drives UltiTools' private language step; clears its singleton
class PlaceholderConversionTracerTest {

    private static final String CUSTOM_PATH = "lang/zh-myserver.yml";
    private static final String FRAMEWORK_KEY = "Module '%s' reloaded.";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        clearLeakedUltiToolsInstance();
        fixture = BootLanguageFixture.create(tempDir);
        fixture.jarEntry("lang/en.yml", "msg: \"%s items\"\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
        clearLeakedUltiToolsInstance();
    }

    /**
     * Clears the mocked {@code UltiTools} instance a test class leaves behind (17-74 gate-1 F1): a later class would
     * otherwise log through a mock whose logger is {@code null}.
     */
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private UltiToolsPlugin startModule(String officialZh, String customZh) throws Exception {
        fixture.jarEntry("lang/zh.yml", officialZh).onDisk(CUSTOM_PATH, customZh).language("zh-myserver");
        UltiToolsPlugin plugin = fixture.construct("6.3.0");
        plugin.commitLanguageProvenance();
        return plugin;
    }

    @Test
    @DisplayName("module: %d in zh-myserver where official zh has %s renders the official text, does not throw, and warns once naming file and key")
    void moduleConversionMismatchUsesTheOfficialText() throws Exception {
        UltiToolsPlugin plugin = startModule("msg: \"共 %s 个\"\n", "msg: \"本服：共 %d 个\"\n");
        File customFile = fixture.disk(CUSTOM_PATH);

        assertThatCode(() -> String.format(plugin.i18n("msg"), "七")).doesNotThrowAnyException();
        assertThat(String.format(plugin.i18n("msg"), "七")).isEqualTo("共 七 个");
        verify(fixture.logger(), times(1)).warning(anyString());
        verify(fixture.logger()).warning(argThat((String message) -> message.contains("'msg'")
                && message.contains(customFile.getPath()) && !message.contains("%d") && !message.contains("%s")));
    }

    @Test
    @DisplayName("module: %s in zh-myserver where official zh has %d also uses the official text (the conversions must be the same)")
    void moduleReverseConversionMismatchUsesTheOfficialText() throws Exception {
        UltiToolsPlugin plugin = startModule("msg: \"共 %d 个\"\n", "msg: \"本服：共 %s 个\"\n");

        assertThat(plugin.i18n("msg")).isEqualTo("共 %d 个");
        verify(fixture.logger(), times(1)).warning(argThat((String message) -> message.contains("'msg'")));
    }

    @Test
    @DisplayName("module: the same conversions in a different order with explicit indices are the operator's text (control)")
    void reorderedExplicitIndicesAreKept() throws Exception {
        UltiToolsPlugin plugin = startModule("msg: \"%1$d 个来自 %2$s\"\n", "msg: \"本服：来自 %2$s 的 %1$d 个\"\n");

        assertThat(plugin.i18n("msg")).isEqualTo("本服：来自 %2$s 的 %1$d 个");
        assertThat(String.format(plugin.i18n("msg"), 3, "Steve")).isEqualTo("本服：来自 Steve 的 3 个");
        verify(fixture.logger(), never()).warning(anyString());
    }

    @Test
    @DisplayName("module: a matching value is the operator's text, and %% is not a conversion (control)")
    void matchingValueAndLiteralPercentAreKept() throws Exception {
        UltiToolsPlugin plugin = startModule("msg: \"完成 %s%%\"\n", "msg: \"本服：已完成 %s%%\"\n");

        assertThat(plugin.i18n("msg")).isEqualTo("本服：已完成 %s%%");
        verify(fixture.logger(), never()).warning(anyString());
    }

    @Test
    @DisplayName("module: an extra or a missing conversion keeps today's outcome, the count reason")
    void countMismatchKeepsTheCountReason() throws Exception {
        UltiToolsPlugin plugin = startModule("msg: \"共 %s 个\"\nmore: \"%s 和 %s\"\n",
                "msg: \"本服：共 %s 个，%s\"\nmore: \"本服：%s\"\n");

        assertThat(plugin.i18n("msg")).isEqualTo("共 %s 个");
        assertThat(plugin.i18n("more")).isEqualTo("%s 和 %s");
        verify(fixture.logger(), times(2)).warning(argThat((String message) ->
                message.contains("different placeholder count")));
    }

    @Test
    @DisplayName("pure comparison: implicit positions are compared in order; a different explicit index is a mismatch")
    void pureComparisonCases() {
        Map<String, String> official = new LinkedHashMap<>();
        official.put("implicit", "%s then %d");
        official.put("swapped", "%s then %d");
        official.put("index", "%1$s and %1$s");
        official.put("same", "%s then %d");
        Map<String, String> own = new LinkedHashMap<>();
        own.put("implicit", "first %s, then %d");
        own.put("swapped", "%d then %s");
        own.put("index", "%1$s and %2$s");
        own.put("same", "%1$s then %2$d");
        List<String> reported = new ArrayList<>();

        Map<String, String> resolved = UltiToolsPlugin.overrideLostPlaceholders(own, official,
                (key, reason) -> reported.add(key));

        assertThat(resolved.get("implicit")).isEqualTo("first %s, then %d");
        assertThat(resolved.get("swapped")).isEqualTo("%s then %d");
        assertThat(resolved.get("index")).isEqualTo("%1$s and %1$s");
        assertThat(resolved.get("same")).isEqualTo("%1$s then %2$d");
        assertThat(reported).containsExactly("swapped", "index");
    }

    @Test
    @DisplayName("framework: %d in lang/zh-myserver.json where official zh has %s uses the official zh text and warns once")
    void frameworkConversionMismatchUsesTheOfficialText() throws Exception {
        File dataFolder = new File(tempDir, "UltiTools");
        // Test-only path under the JUnit @TempDir; the name is a constant.
        // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
        File custom = new File(dataFolder, "lang" + File.separator + "zh-myserver.json");
        Files.createDirectories(custom.getParentFile().toPath());
        Files.write(custom.toPath(), "{\"Module '%s' reloaded.\":\"本服：模块 '%d' 已重载\"}"
                .getBytes(StandardCharsets.UTF_8));
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

        String officialZh = shippedFrameworkZh().get(FRAMEWORK_KEY);
        assertThat(officialZh).isNotNull().contains("%s");
        String text = language.getLocalizedText(FRAMEWORK_KEY);
        assertThatCode(() -> String.format(text, "UltiChat")).doesNotThrowAnyException();
        assertThat(text).isEqualTo(officialZh);
        verify(logger, times(1)).warning(anyString());
        verify(logger).warning(argThat((String message) -> message.contains("'" + FRAMEWORK_KEY + "'")
                && message.contains(custom.getPath()) && !message.contains("%d")));
    }

    private static Map<String, String> shippedFrameworkZh() throws IOException {
        try (Reader reader = new InputStreamReader(
                UltiToolsPlugin.class.getResourceAsStream("/lang/zh.json"), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
        }
    }
}
