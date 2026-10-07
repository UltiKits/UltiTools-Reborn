package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * Characterisation (plan 17-77 Task 3): what the framework does when an operator deletes a framework-written comment
 * line and keeps the setting. It pins the behaviour measured on the branch (review file, "Measured: deleted framework
 * comment line (17-77)"); it does not drive a change. The documentation states exactly these outcomes.
 * <p>
 * Measured: a deleted framework comment line is written again - at its place above the setting, in the language
 * selected at that moment - at the next start and at the next {@code /ul reload}, with or without a language switch,
 * for a comment written from a shipped catalogue and for one written from a custom language file. Every other byte of
 * the file is unchanged, except other framework comments following a language switch as they always do. Deleting the
 * whole setting brings it back with its default value and its comment, at the end of the file. A comment line the
 * framework cannot identify as its own - even a bare {@code #} - is kept and nothing is written above it.
 * <p>
 * Each start is a real one: the module's own constructor from a real jar, {@code commitLanguageProvenance()}, then a
 * real {@link AbstractConfigEntity#init(UltiToolsPlugin)}. A reload runs the module's reload steps in their order
 * ({@code reload()}, the language rebuild, {@code refreshFrameworkComments()}), with the operator's deletion made
 * while the server runs.
 */
@DisplayName("17-77 characterisation: an operator deletes a framework-written comment line")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the framework singleton a test class leaves behind
class DeletedFrameworkCommentTest {

    private static final String CONFIG_PATH = "config/deleted.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_500_000_000_000L);

    private static final String ZH_FILE = "# 设置 A\na: 1\n# 设置 B\nb: 2\n";
    private static final String MYSERVER_FILE = "# 本服：设置 A\na: 1\n# 设置 B\nb: 2\n";
    private static final String EN_FILE = "# Setting A\na: 1\n# Setting B\nb: 2\n";
    private static final String ZH_COMMENT_DELETED = "a: 1\n# 设置 B\nb: 2\n";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;
    private UltiToolsPlugin plugin;
    private DeletedConfig config;

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(CONFIG_PATH)
    static class DeletedConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "a", comment = "{cfg_a}")
        int a = 1;

        @ConfigEntry(path = "b", comment = "{cfg_b}")
        int b = 2;

        DeletedConfig(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        fixture = BootLanguageFixture.create(tempDir);
        fixture.jarEntry("lang/en.yml", "cfg_a: \"Setting A\"\ncfg_b: \"Setting B\"\n")
                .jarEntry("lang/zh.yml", "cfg_a: \"设置 A\"\ncfg_b: \"设置 B\"\n")
                .onDisk("lang/zh-myserver.yml", "cfg_a: \"本服：设置 A\"\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
        MockBukkitHelper.ensureCleanState();
        clearLeakedUltiToolsInstance();
    }

    /**
     * Clears a mocked {@code UltiTools} instance a test class leaves behind (17-74 gate-1 F1): a later class would
     * otherwise log through a mock whose logger is {@code null}.
     */
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** A server start with {@code language} set to {@code code}. */
    private void start(String code) throws Exception {
        fixture.language(code);
        plugin = fixture.construct("6.3.0");
        plugin.commitLanguageProvenance();
        config = new DeletedConfig(CONFIG_PATH);
        config.init(plugin);
    }

    /** {@code /ul reload} with {@code language} set to {@code code}: the module's reload steps, in their order. */
    private void reload(String code) throws Exception {
        fixture.language(code);
        config.reload();
        plugin.commitLanguageProvenance();
        config.refreshFrameworkComments();
    }

    private Path configFile() {
        return fixture.disk(CONFIG_PATH).toPath();
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
        writtenThenCommentDeleted("zh-myserver", MYSERVER_FILE);
        start("zh-myserver");
        assertThat(text()).as("(e-a) next start").isEqualTo(MYSERVER_FILE);

        operatorSaves(ZH_COMMENT_DELETED);
        reload("zh-myserver");
        assertThat(text()).as("(e-b) /ul reload").isEqualTo(MYSERVER_FILE);

        operatorSaves(ZH_COMMENT_DELETED);
        start("en");
        assertThat(text()).as("(e-c) switch to en, start").isEqualTo(EN_FILE);

        start("zh-myserver");
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
