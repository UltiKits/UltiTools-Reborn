package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.commands.UltiToolsCommands;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #502: {@code language} is one setting for the whole server, in the framework's
 * {@code config.yml}. A per-module reload does not apply a changed value to one module -- it keeps
 * the language the framework runs with, so the module stays consistent with the framework and
 * every other module -- and since 6.3.0 it says so: the reload is reported as partial, naming the
 * change and that a full {@code /ul reload} applies it. Before the fix the module kept its old
 * language silently, under a comment claiming the reload re-initialised it "in case language
 * setting changed".
 */
@DisplayName("A per-module reload says a changed language needs a full /ul reload (#502)")
class UltiToolsPluginLanguageChangeReloadTest {

    @TempDir
    File frameworkFolder;

    @TempDir
    File moduleFolder;

    private YamlConfiguration runningConfig;
    private PluginManager pluginManager;

    /** Bare fixture. */
    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() throws IOException {
        runningConfig = new YamlConfiguration();
        runningConfig.set("language", "zh");
        pluginManager = mock(PluginManager.class);
        ConfigManager configManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getConfigManager()).thenReturn(configManager);
            when(ultiTools.getDataFolder()).thenReturn(frameworkFolder);
            when(ultiTools.getConfig()).thenReturn(runningConfig);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        File langDir = new File(moduleFolder, "lang");
        Files.createDirectories(langDir.toPath());
        Files.write(new File(langDir, "zh.json").toPath(), "{\"greeting\":\"你好\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(langDir, "en.json").toPath(), "{\"greeting\":\"Hello\"}".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFrameworkConfigOnDisk(String content) throws IOException {
        Files.write(new File(frameworkFolder, "config.yml").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same idiom as UltiToolsPluginLanguageFallbackTest
    private FixturePlugin module() throws Exception {
        FixturePlugin plugin = mock(FixturePlugin.class);
        when(plugin.getPluginName()).thenReturn("LangModule");
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, moduleFolder.getAbsolutePath());
        doCallRealMethod().when(plugin).reloadSelf();
        doCallRealMethod().when(plugin).reloadWithReport();
        doCallRealMethod().when(plugin).onReload(any(ReloadReport.class));
        doCallRealMethod().when(plugin).getLanguageCode();
        doCallRealMethod().when(plugin).getConfiguredLanguage();
        doCallRealMethod().when(plugin).getLanguage();
        return plugin;
    }

    @Test
    @DisplayName("a changed language on disk makes a per-module reload partial, naming the change, and the module keeps the running language")
    void changedLanguageIsReportedAndNotAppliedToOneModule() throws Exception {
        writeFrameworkConfigOnDisk("language: en\n");
        FixturePlugin plugin = module();

        ReloadReport report = plugin.reloadWithReport();

        assertThat(report.getPartialReasons())
                .as("the reload says the change was not applied and how to apply it")
                .hasSize(1)
                .allSatisfy(reason -> assertThat(reason).contains("zh").contains("en").contains("/ul reload")
                        .doesNotContain("%s"));
        Language language = plugin.getLanguage();
        assertThat(language.getLocalizedText("greeting"))
                .as("the module keeps the language the framework runs with")
                .isEqualTo("你好");
    }

    @Test
    @DisplayName("an unchanged language adds nothing to the reload")
    void unchangedLanguageAddsNothing() throws Exception {
        writeFrameworkConfigOnDisk("language: zh\n");
        FixturePlugin plugin = module();

        ReloadReport report = plugin.reloadWithReport();

        assertThat(report.isPartial()).isFalse();
    }

    @Test
    @DisplayName("an unreadable or missing framework config adds nothing to the reload")
    void missingOrBrokenConfigAddsNothing() throws Exception {
        FixturePlugin plugin = module();

        assertThat(plugin.reloadWithReport().isPartial()).as("no config.yml").isFalse();

        writeFrameworkConfigOnDisk("language: [unclosed\n");
        assertThat(plugin.reloadWithReport().isPartial()).as("a config.yml that does not parse").isFalse();
    }

    @Test
    @DisplayName("once the framework runs with the new language, as after a full /ul reload, the module applies it")
    void runningLanguageChangeIsApplied() throws Exception {
        writeFrameworkConfigOnDisk("language: en\n");
        runningConfig.set("language", "en");
        FixturePlugin plugin = module();

        ReloadReport report = plugin.reloadWithReport();

        assertThat(report.isPartial()).isFalse();
        assertThat(plugin.getLanguage().getLocalizedText("greeting")).isEqualTo("Hello");
    }

    @Test
    @DisplayName("/ul reload <module> tells the sender a changed language needs a full /ul reload")
    void commandTellsTheSender() throws Exception {
        writeFrameworkConfigOnDisk("language: en\n");
        FixturePlugin plugin = module();
        when(pluginManager.getPluginList()).thenReturn(Collections.singletonList(plugin));
        CommandSender sender = mock(CommandSender.class);

        new UltiToolsCommands().reloadPlugin(sender, "LangModule");

        verify(sender).sendMessage(argThat((String message) -> message.contains("LangModule")
                && message.contains("/ul reload") && message.contains("en")));
        verify(sender, never()).sendMessage("模块 LangModule 已重载");
    }
}
