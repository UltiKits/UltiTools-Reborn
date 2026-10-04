package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #594: a config comment written as one {@code {key}} token (#542) follows a language switch on
 * reload. {@code /ul reload} re-reads the module configs before it rebuilds the module's language,
 * so the reload's own comment pass resolved the tokens with the old catalogue. After the rebuild
 * the framework refreshes the token comment lines through the comment-only write {@code load()}
 * uses, never touching a value or a key: an operator's invalid value and a key deleted to reset it
 * stay exactly as typed, and a file that failed to read or parse is not touched.
 */
@DisplayName("Token config comments follow a language switch on reload (#594)")
class ConfigTokenCommentReloadRefreshTest {

    private static final String PATH = "config/demo.yml";

    @TempDir
    File frameworkFolder;

    @TempDir
    File moduleFolder;

    private YamlConfiguration runningConfig;
    private ConfigManager configManager;

    /** Bare fixture. */
    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class DemoConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "demo.interval", comment = "{config.demo.interval}")
        int interval = 300;

        @ConfigEntry(path = "demo.name", comment = "{config.demo.name}")
        String name = "lobby";

        @ConfigEntry(path = "demo.literal", comment = "Literal comment")
        int literal = 1;

        public DemoConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        runningConfig = new YamlConfiguration();
        runningConfig.set("language", "zh");
        configManager = new ConfigManager();
        PluginManager pluginManager = mock(PluginManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getConfigManager()).thenReturn(configManager);
            when(ultiTools.getDataFolder()).thenReturn(frameworkFolder);
            when(ultiTools.getConfig()).thenReturn(runningConfig);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        writeFrameworkConfigOnDisk("language: zh\n");
        File langDir = new File(moduleFolder, "lang");
        Files.createDirectories(langDir.toPath());
        Files.write(new File(langDir, "zh.json").toPath(),
                "{\"config.demo.interval\":\"公告间隔（秒）\",\"config.demo.name\":\"大厅名称\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(langDir, "en.json").toPath(),
                "{\"config.demo.interval\":\"Announcement interval in seconds\",\"config.demo.name\":\"Lobby name\"}"
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void writeFrameworkConfigOnDisk(String content) throws IOException {
        Files.write(new File(frameworkFolder, "config.yml").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** The operator switches the server language and runs a full {@code /ul reload}. */
    private void switchServerLanguageTo(String code) throws IOException {
        writeFrameworkConfigOnDisk("language: " + code + "\n");
        runningConfig.set("language", code);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same idiom as UltiToolsPluginLanguageChangeReloadTest
    private FixturePlugin module() throws Exception {
        FixturePlugin plugin = mock(FixturePlugin.class);
        when(plugin.getPluginName()).thenReturn("DemoModule");
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(moduleFolder, invocation.<String>getArgument(0)));
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, moduleFolder.getAbsolutePath());
        doCallRealMethod().when(plugin).getResourceFolderPath();
        doCallRealMethod().when(plugin).reloadSelf();
        doCallRealMethod().when(plugin).reloadWithReport();
        doCallRealMethod().when(plugin).onReload(any(ReloadReport.class));
        doCallRealMethod().when(plugin).getLanguageCode();
        doCallRealMethod().when(plugin).getLanguage();
        doCallRealMethod().when(plugin).i18n(anyString());
        return plugin;
    }

    /** A module running in Chinese with its config written by init in Chinese. */
    private FixturePlugin moduleWithConfig() throws Exception {
        FixturePlugin plugin = module();
        // Builds the module's catalogue in the running language, as the module's load does.
        plugin.reloadWithReport();
        configManager.register(plugin, new DemoConfig(PATH));
        assertThat(comments("demo.interval")).as("precondition: init wrote the token in Chinese")
                .containsExactly("公告间隔（秒）");
        return plugin;
    }

    private Path file() {
        return new File(moduleFolder, PATH).toPath();
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private void edit(String from, String to) throws IOException {
        String before = text();
        assertThat(before).as("edit target present").contains(from);
        Files.write(file(), before.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }

    private List<String> comments(String path) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.load(file().toFile());
        return configuration.getComments(path);
    }

    /** Every line of the file that is not a comment line, in order. */
    private List<String> nonCommentLines() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : text().split("\n", -1)) {
            if (!line.trim().startsWith("#")) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    @DisplayName("after a language switch and a reload, every token comment is in the new language")
    void tokenCommentsFollowTheLanguageSwitch() throws Exception {
        FixturePlugin plugin = moduleWithConfig();

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(comments("demo.interval")).containsExactly("Announcement interval in seconds");
        assertThat(comments("demo.name")).containsExactly("Lobby name");
        assertThat(comments("demo.literal")).as("a literal comment is written as declared")
                .containsExactly("Literal comment");
    }

    @Test
    @DisplayName("the refresh changes comment lines only: an invalid value and a deleted key stay exactly as typed")
    void valuesAndDeletedKeysAreByteUnchanged() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: 3O0");
        edit("  name: lobby\n", "");
        List<String> typed = nonCommentLines();

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(nonCommentLines()).as("every non-comment line, byte for byte").isEqualTo(typed);
        assertThat(text()).contains("interval: 3O0").doesNotContain("name:");
        assertThat(comments("demo.interval")).containsExactly("Announcement interval in seconds");
    }

    @Test
    @DisplayName("a reload without a language switch writes nothing")
    void noLanguageSwitchWritesNothing() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: 3O0");
        byte[] before = Files.readAllBytes(file());

        plugin.reloadWithReport();

        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("a file that cannot be parsed is not refreshed: it stays byte for byte")
    @SuppressWarnings("PMD.EmptyCatchBlock") // either outcome of the reload is fine; the file is what is asserted
    void unparseableFileIsNotRefreshed() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: [unclosed");
        byte[] before = Files.readAllBytes(file());

        switchServerLanguageTo("en");
        try {
            plugin.reloadWithReport();
        } catch (ConfigurationException refusedSince589) {
            // #589 makes this reload throw; before it, the file is protected and the reload returns.
        }

        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }
}
