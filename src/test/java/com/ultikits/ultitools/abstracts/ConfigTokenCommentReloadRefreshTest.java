package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #594: a config comment written as one {@code {key}} token (#542) follows a language switch applied by
 * {@code /ul reload}. The reload re-reads the module configs before it rebuilds the module's language, so the
 * reload's own comment pass resolved the tokens with the old catalogue. Right after the rebuild, and before the
 * module's own reload hook, the framework refreshes its own comment lines (maintainer decision 2026-10-04,
 * "the framework only refreshes comments on reload"), through the config write gate over a fresh read of the
 * file: the write owns only the comment lines the framework can identify as its own (#604), so a value, a key,
 * an operator's invalid value, a key deleted to reset it and a hand-written comment stay exactly as typed; an
 * anchored or hand-aligned file is refused by the gate with its one warning; a file that failed to read or parse
 * is not touched; a failed write marks nothing for a later save (#597 review F1, F2 and F5 pinned below).
 */
@DisplayName("Framework comments follow a language switch on reload (#594)")
class ConfigTokenCommentReloadRefreshTest {

    private static final String PATH = "config/demo.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);
    private static final String ZH_INTERVAL = "公告间隔（秒）";
    private static final String EN_INTERVAL = "Announcement interval in seconds";
    private static final String ZH_NAME = "大厅名称";
    private static final String EN_NAME = "Lobby name";

    @TempDir
    File frameworkFolder;

    @TempDir
    File moduleFolder;

    private YamlConfiguration runningConfig;
    private ConfigManager configManager;
    private PluginManager pluginManager;
    private final List<LogRecord> warnings = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger frameworkLogger = Logger.getLogger("com.ultikits.ultitools");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // In-memory capture.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

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
        pluginManager = mock(PluginManager.class);
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
                ("{\"config.demo.interval\":\"" + ZH_INTERVAL + "\",\"config.demo.name\":\"" + ZH_NAME + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(langDir, "en.json").toPath(),
                ("{\"config.demo.interval\":\"" + EN_INTERVAL + "\",\"config.demo.name\":\"" + EN_NAME + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        frameworkLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        frameworkLogger.removeHandler(capture);
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
        // The module's jar ships both catalogues (#604: a shipped text is the framework's in every language).
        when(plugin.shippedCatalogueTexts("config.demo.interval")).thenReturn(Arrays.asList(EN_INTERVAL, ZH_INTERVAL));
        when(plugin.shippedCatalogueTexts("config.demo.name")).thenReturn(Arrays.asList(EN_NAME, ZH_NAME));
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
                .containsExactly(ZH_INTERVAL);
        warnings.clear();
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

    private List<String> warningsNamingTheFile() {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (LogRecord record : warnings) {
                if (record.getMessage() != null && record.getMessage().contains(PATH)) {
                    result.add(record.getMessage());
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("after a language switch and a reload, every framework comment is in the new language")
    void tokenCommentsFollowTheLanguageSwitch() throws Exception {
        FixturePlugin plugin = moduleWithConfig();

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(comments("demo.interval")).containsExactly(EN_INTERVAL);
        assertThat(comments("demo.name")).containsExactly(EN_NAME);
        assertThat(comments("demo.literal")).as("a literal comment is written as declared")
                .containsExactly("Literal comment");
    }

    @Test
    @DisplayName("the refresh changes comment lines only: an invalid value and a deleted key stay byte for byte (F5)")
    void valuesAndDeletedKeysAreByteUnchanged() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: 3O0");
        edit("  name: lobby\n", "");
        List<String> typed = nonCommentLines();

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(nonCommentLines()).as("every non-comment line, byte for byte").isEqualTo(typed);
        assertThat(text()).contains("interval: 3O0").doesNotContain("name:");
        assertThat(comments("demo.interval")).containsExactly(EN_INTERVAL);
    }

    @Test
    @DisplayName("a reload without a language switch writes nothing")
    void noLanguageSwitchWritesNothing() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: 3O0");
        Files.setLastModifiedTime(file(), OLD);
        byte[] before = Files.readAllBytes(file());

        plugin.reloadWithReport();

        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
    }

    @Test
    @DisplayName("an anchored file is refused by the gate with one warning and keeps its bytes")
    void anchoredFileIsRefusedByTheGate() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        Files.write(file(), (text() + "templates:\n  base: &base\n    value: 1\n  other: *base\n")
                .getBytes(StandardCharsets.UTF_8));
        plugin.reloadWithReport();
        byte[] before = Files.readAllBytes(file());
        OperatorFileWriterResetBridge.resetAnchorWarnings();
        warnings.clear();

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(text()).contains("&base").contains("*base");
        assertThat(warningsNamingTheFile()).hasSize(1);
        assertThat(warningsNamingTheFile().get(0)).contains("anchors");
    }

    @Test
    @DisplayName("F1: an anchored file reloaded twice after a language switch keeps its bytes")
    void anchoredFileReloadedTwiceAfterALanguageSwitchKeepsItsBytes() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        Files.write(file(), (text() + "templates:\n  base: &base\n    value: 1\n  other: *base\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file(), OLD);
        byte[] before = Files.readAllBytes(file());

        switchServerLanguageTo("en");
        plugin.reloadWithReport();
        plugin.reloadWithReport();

        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(OLD);
    }

    @Test
    @DisplayName("a refresh whose write fails leaves nothing for the shutdown save to write over the operator's values")
    void failedRefreshWriteMarksNothingForShutdown() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: 3O0");
        plugin.reloadWithReport();
        DemoConfig config = configManager.getConfigEntity(plugin, DemoConfig.class);
        File folder = file().getParent().toFile();

        switchServerLanguageTo("en");
        warnings.clear();
        assertThat(folder.setWritable(false)).as("precondition: the config folder can be made read-only").isTrue();
        try {
            plugin.reloadWithReport();
            assertThat(config.isModifiedSinceSnapshot())
                    .as("a shutdown save would write the in-memory default over the operator's 3O0").isFalse();
            assertThat(warningsNamingTheFile()).as("one warning for the failed refresh")
                    .filteredOn(message -> message.contains("comments")).hasSize(1);
        } finally {
            folder.setWritable(true);
        }
        assertThat(text()).contains("interval: 3O0");
    }

    /**
     * 17-64 review round 1 R1-03: a file the gate refuses (here a value the operator spaced by hand) is named once per
     * {@code /ul reload}, not once by the reload's own comment write and again by the refresh.
     */
    @Test
    @DisplayName("a file the gate refuses is warned about once per reload, whichever write was refused")
    void aRefusedFileIsWarnedOncePerReload() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval:  300");
        plugin.reloadWithReport();
        byte[] before = Files.readAllBytes(file());

        switchServerLanguageTo("en");
        for (int reload = 1; reload <= 3; reload++) {
            warnings.clear();
            plugin.reloadWithReport();
            assertThat(warningsNamingTheFile()).as("refusal warnings in reload " + reload)
                    .filteredOn(message -> message.contains("was not written")).hasSize(1);
        }
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
    }

    @Test
    @DisplayName("F2: a failed refresh write followed by a second reload leaves isModifiedSinceSnapshot() false")
    void failedRefreshThenSecondReloadMarksNothing() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("interval: 300", "interval: 3O0");
        plugin.reloadWithReport();
        DemoConfig config = configManager.getConfigEntity(plugin, DemoConfig.class);
        File folder = file().getParent().toFile();
        byte[] before = Files.readAllBytes(file());

        switchServerLanguageTo("en");
        assertThat(folder.setWritable(false)).as("precondition: the config folder can be made read-only").isTrue();
        try {
            plugin.reloadWithReport();
            assertThat(config.isModifiedSinceSnapshot()).as("after the first reload").isFalse();
            plugin.reloadWithReport();
            assertThat(config.isModifiedSinceSnapshot()).as("after the second reload").isFalse();
        } finally {
            folder.setWritable(true);
        }
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

    @Test
    @DisplayName("a hand-written comment above a token setting survives a language-switch reload byte for byte (#604)")
    void handWrittenCommentSurvivesALanguageSwitchReload() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        edit("  # " + ZH_NAME + "\n", "  # my own note on the lobby\n");
        plugin.reloadWithReport();

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(text()).contains("  # my own note on the lobby\n  name: lobby\n");
        assertThat(comments("demo.interval")).containsExactly(EN_INTERVAL);
    }

    @Test
    @DisplayName("an edit saved after the reload read the file is kept; only the framework's comment lines are refreshed")
    void operatorEditAfterTheReloadReadIsKept() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        DemoConfig config = configManager.getConfigEntity(plugin, DemoConfig.class);
        // Runs between the reload's read and the language rebuild: an editor saves the file meanwhile.
        doAnswer(invocation -> {
            edit("interval: 300", "interval: 450");
            return null;
        }).when(pluginManager).applyReloadedConfigBindings(plugin);

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(text()).contains("interval: 450");
        assertThat(comments("demo.interval")).containsExactly(EN_INTERVAL);
        assertThat(config.isFileModifiedSinceSnapshot())
                .as("the entity did not bind the operator's 450, so the file is still a change on disk").isTrue();
    }

    @Test
    @DisplayName("the refresh runs after the language rebuild and before the module's own reload hook")
    void refreshRunsAfterTheLanguageRebuildAndBeforeTheModulesHook() throws Exception {
        FixturePlugin plugin = moduleWithConfig();
        List<List<String>> seenByHook = new ArrayList<>();
        doAnswer(invocation -> {
            seenByHook.add(comments("demo.interval"));
            return null;
        }).when(plugin).onReload(any(ReloadReport.class));

        switchServerLanguageTo("en");
        plugin.reloadWithReport();

        assertThat(seenByHook).as("the module's hook already sees the new language's comment")
                .containsExactly(Collections.singletonList(EN_INTERVAL));
    }

    /** Resets the gate's once-per-run anchored-file warnings (package-private) from this package. */
    private static final class OperatorFileWriterResetBridge {
        @SuppressWarnings("PMD.UnnecessaryConstructor") // Static-only bridge must not expose construction.
        private OperatorFileWriterResetBridge() {
        }

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reaches the gate's package-private test reset
        static void resetAnchorWarnings() throws Exception {
            java.lang.reflect.Method reset = OperatorFileWriter.class.getDeclaredMethod("resetAnchorWarnings");
            reset.setAccessible(true);
            reset.invoke(null);
        }
    }
}
