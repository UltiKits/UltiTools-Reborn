package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * Characterisation (plan 17-77 Task 3): what the framework does when an operator deletes a framework-written comment
 * line and keeps the setting. It pins the behaviour measured on the branch (review file, "Measured: deleted framework
 * comment line (17-77)"); it does not drive a change. The documentation states exactly these outcomes.
 * <p>
 * Measured: a deleted framework comment line is written again - at its place above the setting, in the language
 * selected at that moment - at the next start and at the next {@code /ul reload}, with or without a language switch,
 * for a comment written from a shipped catalogue and for one written from a message override file. Every other byte of
 * the file is unchanged, except other framework comments following a language switch as they always do. Deleting the
 * whole setting brings it back with its default value and its comment, at the end of the file. A comment line the
 * framework cannot identify as its own - even a bare {@code #} - is kept and nothing is written above it.
 * <p>
 * Each start is a real one: the module's own constructor from a real jar, {@code publishMessageCatalogue()}, then a
 * config registration through {@link ConfigManager}. A reload calls {@link UltiToolsPlugin#reloadWithReport()},
 * with the server language already applied as by a full reload and the operator's deletion made while the server runs.
 */
@DisplayName("17-77 characterisation: an operator deletes a framework-written comment line")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DeletedFrameworkCommentTest {

    private static final String CONFIG_PATH = "config/deleted.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_500_000_000_000L);

    private static final String ZH_FILE = "# 设置 A\na: 1\n# 设置 B\nb: 2\n";
    private static final String MYSERVER_FILE = "# 本服：设置 A\na: 1\n# 设置 B\nb: 2\n";
    private static final String EN_FILE = "# Setting A\na: 1\n# Setting B\nb: 2\n";
    private static final String ZH_COMMENT_DELETED = "a: 1\n# 设置 B\nb: 2\n";

    @TempDir
    File tempDir;

    private ConfigMessageModuleFixture fixture;
    private UltiToolsPlugin plugin;
    private ConfigManager configManager;

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(CONFIG_PATH)
    static class DeletedConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "a", comment = "{config.a}")
        int a = 1;

        @ConfigEntry(path = "b", comment = "{config.b}")
        int b = 2;

        DeletedConfig(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        configManager = new ConfigManager();
        fixture = new ConfigMessageModuleFixture(tempDir.toPath(), configManager, null);
        fixture.shipped("en", "config.a: '<green>Setting A</green>'\nconfig.b: 'Setting B'\n")
                .shipped("zh", "config.a: '<gold>设置 A</gold>'\nconfig.b: '设置 B'\n");
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    /** A server start with the selected fallback locale. */
    private void start(String code) throws Exception {
        fixture.fallback(code);
        plugin = fixture.construct();
        plugin.publishMessageCatalogue();
        configManager.register(plugin, new DeletedConfig(CONFIG_PATH));
    }

    /** The full reload has applied the fallback; drive the real module reload entry point. */
    private void reload(String code) throws Exception {
        fixture.fallback(code);
        assertThat(plugin.reloadWithReport().isPartial()).isFalse();
    }

    private Path configFile() {
        return fixture.disk(CONFIG_PATH);
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(configFile()), StandardCharsets.UTF_8);
    }

    /** The operator's edit, saved with an old modification time so an unchanged file is visible. */
    private void operatorSaves(String text) throws IOException {
        Files.write(configFile(), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(configFile(), OLD);
    }

    /** The framework writes the file under {@code code}, the operator deletes {@code a}'s comment line. */
    private void writtenThenCommentDeleted(String code, String expectedWritten) throws Exception {
        start(code);
        assertThat(text()).as("written").isEqualTo(expectedWritten);
        operatorSaves(ZH_COMMENT_DELETED);
    }

    @Test
    @DisplayName("reload rebuilds a deleted comment from the jar without recreating a missing reference")
    void reloadDoesNotRecreateAMissingMessageReference() throws Exception {
        start("zh");
        operatorSaves(ZH_COMMENT_DELETED);
        Path englishReference = fixture.disk("messages/reference/en.yml");
        Files.delete(englishReference);
        Path chineseReference = fixture.disk("messages/reference/zh.yml");
        byte[] shippedReference = Files.readAllBytes(chineseReference);

        reload("en");

        assertThat(text()).isEqualTo(EN_FILE);
        assertThat(englishReference).doesNotExist();
        assertThat(chineseReference).hasBinaryContent(shippedReference);
        assertThat(fixture.disk("messages/en.yml")).doesNotExist();
    }

    @Test
    @DisplayName("(a) shipped-catalogue comment deleted: the next start writes it again in the same language")
    void shippedCommentNextStart() throws Exception {
        writtenThenCommentDeleted("zh", ZH_FILE);

        start("zh");

        assertThat(text()).isEqualTo(ZH_FILE);
    }

    @Test
    @DisplayName("(b) shipped-catalogue comment deleted while running: /ul reload writes it again")
    void shippedCommentReload() throws Exception {
        start("zh");
        operatorSaves(ZH_COMMENT_DELETED);

        reload("zh");

        assertThat(text()).isEqualTo(ZH_FILE);
    }

    @Test
    @DisplayName("(c) shipped-catalogue comment deleted, switch to en, start: written again in en")
    void shippedCommentSwitchThenStart() throws Exception {
        writtenThenCommentDeleted("zh", ZH_FILE);

        start("en");

        assertThat(text()).isEqualTo(EN_FILE);
    }

    @Test
    @DisplayName("(d) shipped-catalogue comment deleted while running, switch to en, /ul reload: written again in en")
    void shippedCommentSwitchThenReload() throws Exception {
        start("zh");
        operatorSaves(ZH_COMMENT_DELETED);

        reload("en");

        assertThat(text()).isEqualTo(EN_FILE);
    }

    @Test
    @DisplayName("(e) custom-catalogue comment deleted: start and /ul reload write it again in the custom text; after a switch to en, in en")
    void customCommentDeleted() throws Exception {
        fixture.onDisk("messages/zh.yml", "config.a: '<gold>本服：设置 A</gold>'\n");
        writtenThenCommentDeleted("zh", MYSERVER_FILE);
        start("zh");
        assertThat(text()).as("(e-a) next start").isEqualTo(MYSERVER_FILE);

        operatorSaves(ZH_COMMENT_DELETED);
        reload("zh");
        assertThat(text()).as("(e-b) /ul reload").isEqualTo(MYSERVER_FILE);

        operatorSaves(ZH_COMMENT_DELETED);
        start("en");
        assertThat(text()).as("(e-c) switch to en, start").isEqualTo(EN_FILE);

        start("zh");
        operatorSaves(ZH_COMMENT_DELETED);
        reload("en");
        assertThat(text()).as("(e-d) switch to en, /ul reload").isEqualTo(EN_FILE);
    }

    @Test
    @DisplayName("(f) control: the whole setting deleted comes back with its default and its comment, at the end of the file")
    void wholeSettingDeletedComesBackAtTheEnd() throws Exception {
        writtenThenCommentDeleted("zh", ZH_FILE);
        operatorSaves("# 设置 B\nb: 2\n");

        start("zh");

        assertThat(text()).isEqualTo("# 设置 B\nb: 2\n# 设置 A\na: 1\n");
    }

    @Test
    @DisplayName("(g) the comment line replaced by a bare #: kept, nothing written above the setting, through a start and a switching reload")
    void bareHashLineIsKept() throws Exception {
        start("zh");
        operatorSaves("#\na: 1\n# 设置 B\nb: 2\n");

        start("zh");
        assertThat(text()).isEqualTo("#\na: 1\n# 设置 B\nb: 2\n");
        assertThat(Files.getLastModifiedTime(configFile())).isEqualTo(OLD);

        reload("en");
        assertThat(text()).isEqualTo("#\na: 1\n# Setting B\nb: 2\n");
    }
}
