package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.context.ConditionalRegistrationEvaluator;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/**
 * A declared setting path written as a flat dotted key ({@code features.chat: false}) is that setting, for the entity and
 * for {@code @ConditionalOnConfig} alike; a setting written in two forms is refused at load; writes address the line the
 * setting is held on and never add a nested copy; keys inside a map value stay whole (#612, Follow-up 16).
 */
class ConfigDottedSettingPathTest {
    private static final String PATH = "config/dotted-setting.yml";
    private static final String COMPLETE_FLAT = "features.chat: false\nlimits.max: 7\na.b:\n  c: 3\nchat.emoji:\n  o.O: wink\n";
    @TempDir Path tempDir;
    private UltiToolsPlugin plugin;
    private SimpleContainer container;

    static class Features extends AbstractConfigEntity {
        @ConfigEntry(path = "features.chat") boolean chat = true;
        @ConfigEntry(path = "limits.max") int max = 5;
        @ConfigEntry(path = "a.b.c") int deep = 1;
        @ConfigEntry(path = "chat.emoji") Map<String, String> emoji = new LinkedHashMap<>();
        public Features(String path) { super(path); }
    }

    /** Registered only when {@code features.chat} reads {@code true}. */
    @ConditionalOnConfig(value = PATH, path = "features.chat")
    static class ChatListener {
    }

    @BeforeEach void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DottedSettingModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        container = new SimpleContainer();
        container.registerType(UltiToolsPlugin.class, plugin);
    }

    @AfterEach void tearDown() {
        ConditionalRegistrationEvaluator.clear(plugin);
        container.close();
    }

    private Path file() { return tempDir.resolve(PATH); }

    private void write(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private boolean chatListenerRegistered() {
        return ConditionalRegistrationEvaluator.shouldRegister(ChatListener.class, container);
    }

    @Test void aFlatDottedKeyIsTheSettingAndTheFileIsNotChanged() throws IOException {
        write(COMPLETE_FLAT);
        byte[] before = Files.readAllBytes(file());
        Features config = new Features(PATH);
        config.init(plugin);
        assertThat(config.chat).isFalse();
        assertThat(config.max).isEqualTo(7);
        assertThat(config.deep).isEqualTo(3);
        assertThat(config.emoji).containsExactly(entry("o.O", "wink"));
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
        assertThat(config.isPresentInFile("features.chat")).isTrue();
        assertThat(config.isPresentInFile("a.b.c")).isTrue();
    }

    @Test void theNestedFormStillWorks() throws IOException {
        write("features:\n  chat: false\nlimits:\n  max: 7\na:\n  b:\n    c: 3\nchat:\n  emoji: {}\n");
        byte[] before = Files.readAllBytes(file());
        Features config = new Features(PATH);
        config.init(plugin);
        assertThat(config.chat).isFalse();
        assertThat(config.max).isEqualTo(7);
        assertThat(config.deep).isEqualTo(3);
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(chatListenerRegistered()).isFalse();
    }

    @Test void theMissingKeyInsertNeverAddsANestedCopyOfAFlatSetting() throws IOException {
        write("features.chat: false\n");
        Features config = new Features(PATH);
        config.init(plugin);
        assertThat(config.chat).isFalse();
        String after = text();
        assertThat(after).startsWith("features.chat: false\n");
        assertThat(after).doesNotContain("features:").doesNotContain("  chat:");
        assertThat(after).contains("limits:").contains("max: 5");
        Features second = new Features(PATH);
        second.init(plugin);
        assertThat(second.chat).isFalse();
        assertThat(text()).isEqualTo(after);
    }

    @Test void conditionalReadsTheSameValueBeforeAndAfterTheEntityStarts() throws IOException {
        write("features.chat: false\n");
        assertThat(chatListenerRegistered()).isFalse();
        new Features(PATH).init(plugin);
        assertThat(chatListenerRegistered()).isFalse();
        assertThat(ConditionalRegistrationEvaluator.reportDrift(plugin)).isEmpty();
        new Features(PATH).init(plugin);
        assertThat(chatListenerRegistered()).isFalse();
    }

    @Test void conditionalReadsAFlatTrueAsTrue() throws IOException {
        write("features.chat: true\n");
        assertThat(chatListenerRegistered()).isTrue();
    }

    @Test void bothFormsWithDifferentValuesRefuseTheModuleNamingFileAndSetting() throws IOException {
        write("features.chat: false\nfeatures:\n  chat: true\nlimits:\n  max: 77\nlimits.max: 99\n");
        byte[] before = Files.readAllBytes(file());
        assertThatThrownBy(() -> new Features(PATH).init(plugin))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(PATH).hasMessageContaining("'features.chat'").hasMessageContaining("'limits.max'")
                .hasMessageContaining("two forms")
                .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("77").doesNotContain("99")
                        .doesNotContain("fix the value"));
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test void bothFormsWithEqualValuesAreRefusedToo() throws IOException {
        write("features.chat: false\nfeatures:\n  chat: false\n");
        byte[] before = Files.readAllBytes(file());
        assertThatThrownBy(() -> new Features(PATH).init(plugin))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(PATH).hasMessageContaining("'features.chat'");
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test void aSecondFormAddedBeforeAReloadRefusesTheReloadAndKeepsTheRunningValue() throws IOException {
        write(COMPLETE_FLAT);
        Features config = new Features(PATH);
        config.init(plugin);
        write(COMPLETE_FLAT + "features:\n  chat: true\n");
        assertThatThrownBy(config::reload).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'features.chat'");
        assertThat(config.chat).isFalse();
    }

    @Test void conditionalRefusesASettingWrittenInTwoForms() throws IOException {
        write("features.chat: false\nfeatures:\n  chat: true\n");
        assertThatThrownBy(this::chatListenerRegistered).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(PATH).hasMessageContaining("'features.chat'").hasMessageContaining("two forms");
    }

    @Test void aModuleSaveOfAFlatHeldSettingWritesTheFlatLineOnly() throws IOException {
        write(COMPLETE_FLAT);
        Features config = new Features(PATH);
        config.init(plugin);
        config.chat = true;
        config.deep = 4;
        config.save();
        assertThat(text()).isEqualTo("features.chat: true\nlimits.max: 7\na.b:\n  c: 4\nchat.emoji:\n  o.O: wink\n");
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test void anOperatorChangeOfAFlatHeldSettingWritesTheFlatLineOnly() throws IOException {
        write(COMPLETE_FLAT);
        Features config = new Features(PATH);
        config.init(plugin);
        config.max = 12;
        config.emoji.put("x.D", "laugh");
        config.saveOperatorChange("limits.max");
        config.saveOperatorMapEntry("chat.emoji", "x.D");
        assertThat(text()).isEqualTo("features.chat: false\nlimits.max: 12\na.b:\n  c: 3\nchat.emoji:\n  o.O: wink\n"
                + "  x.D: laugh\n");
    }

    @Test void anOperatorChangeIsRefusedWhenTheFileNowHoldsTheSettingTwice() throws IOException {
        write(COMPLETE_FLAT);
        Features config = new Features(PATH);
        config.init(plugin);
        write(COMPLETE_FLAT + "limits:\n  max: 8\n");
        byte[] before = Files.readAllBytes(file());
        config.max = 12;
        assertThatThrownBy(() -> config.saveOperatorChange("limits.max"))
                .isInstanceOf(ConfigWriteRefusedException.class).hasMessageContaining("'limits.max'");
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test void aPanelEditOfAFlatHeldSettingAddressesTheSameLine() throws IOException {
        write(COMPLETE_FLAT);
        Features config = new Features(PATH);
        config.init(plugin);
        JsonObject shown = config.toJsonObject();
        assertThat(shown.get("features.chat").getAsBoolean()).isFalse();
        assertThat(shown.has("chat.emoji.o.O")).isTrue();
        JsonObject edit = new JsonObject();
        edit.addProperty("features.chat", true);
        edit.addProperty("chat.emoji.o.O", "blink");
        config.updateProperties(edit);
        assertThat(config.chat).isTrue();
        assertThat(config.emoji).containsExactly(entry("o.O", "blink"));
        assertThat(text()).isEqualTo("features.chat: true\nlimits.max: 7\na.b:\n  c: 3\nchat.emoji:\n  o.O: blink\n");
    }

    @Test void dottedKeysInsideAMapValueKeepTheirLiteralMeaning() throws IOException {
        write("features:\n  chat: true\nlimits:\n  max: 5\na:\n  b:\n    c: 1\nchat:\n  emoji:\n    o.O: wink\n    o:\n      O: split\n");
        byte[] before = Files.readAllBytes(file());
        Features config = new Features(PATH);
        try (ConfigWarningCapture warnings = ConfigWarningCapture.install()) {
            config.init(plugin);
            assertThat(warnings.messages()).noneMatch(message -> message.contains("written twice"));
        }
        assertThat(config.emoji).containsEntry("o.O", "wink").doesNotContainKey("o");
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
