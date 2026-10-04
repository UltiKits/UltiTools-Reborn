package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * UltiKits/UltiTools-Reborn#542, maintainer answer of 2026-09-29 ("rewrite in the current language
 * on every save"): on every framework write, a one-token comment is written in the server's current
 * language - keys already in the file included - and an upgraded server's existing file follows the
 * server's language after one start even when no value changed. Since the maintainer decision of
 * 2026-10-04 ("what code may write, by file type"), the comment-only write at start-up goes through the
 * config write gate: every byte outside the rewritten comment lines stays, or nothing is written.
 * A start with nothing semantically changed writes nothing (bytes and modification time unchanged).
 */
@DisplayName("AbstractConfigEntity - one-token comments follow the server language on an existing file (#542)")
class ConfigCommentTokenUpgradeTest {

    private static final String PATH = "config/upgrade.yml";

    /** "Maximum number of items" in Chinese, as the module's zh catalogue holds it. */
    private static final String ZH_LIMIT = "最大物品数";
    /** The old hard-coded Chinese comment an older build of the module wrote. */
    private static final String OLD_ZH_COMMENT = "最大物品数量";
    private static final String EN_LIMIT = "Maximum number of items";

    /**
     * What an older build wrote, then an operator edited: header, values, own comments, a blank line.
     * The blank line is a true blank line: a line of spaces would be normalized by the renderer, so under
     * the 2026-10-04 decision the gate refuses the comment write on such a file
     * ({@link #whitespaceOnlyBlankLineBlocksTheCommentRewrite}).
     */
    private static final String UPGRADED_FILE = "# Operator header line\n"
            + "\n"
            + "demo:\n"
            + "  # " + OLD_ZH_COMMENT + "\n"
            + "  limit: 25\n"
            + "  # My own note on the name\n"
            + "  name: Custom\n"
            + "\n"
            + "  # operator note on other\n"
            + "  other: 3\n";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder
    static class UpgradeConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "demo.limit", comment = "{config.demo.limit}")
        int limit = 10;

        @ConfigEntry(path = "demo.name", comment = "Display name")
        String name = "Default";

        @ConfigEntry(path = "demo.other")
        int other = 1;

        public UpgradeConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("UpgradeModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        languageEnglish();
    }

    private void languageEnglish() {
        lenient().when(plugin.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plugin.i18n("config.demo.limit")).thenReturn(EN_LIMIT);
    }

    private void languageChinese() {
        when(plugin.i18n("config.demo.limit")).thenReturn(ZH_LIMIT);
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void writeFile(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }

    private String readFile() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    /** Pins the file's modification time in the past, so any write is visible. */
    private long pinModificationTime() {
        long pinned = 1_000_000_000_000L;
        assertThat(file().toFile().setLastModified(pinned)).isTrue();
        return file().toFile().lastModified();
    }

    private YamlConfiguration parse() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.loadFromString(readFile());
        return configuration;
    }

    // The token comment line is the only line the comment-only write owns.
    private void assertPreserved(String limitComment) throws Exception {
        YamlConfiguration parsed = parse();
        assertThat(parsed.getInt("demo.limit")).isEqualTo(25);
        assertThat(parsed.getString("demo.name")).isEqualTo("Custom");
        assertThat(parsed.getInt("demo.other")).isEqualTo(3);
        assertThat(parsed.getComments("demo.limit")).containsExactly(limitComment);
        assertThat(parsed.getComments("demo.name")).containsExactly("My own note on the name");
        assertThat(parsed.getComments("demo.other")).contains("operator note on other");
        assertThat(readFile()).contains("# Operator header line");
        assertThat(readFile().indexOf("limit:")).isLessThan(readFile().indexOf("name:"));
        assertThat(readFile().indexOf("name:")).isLessThan(readFile().indexOf("other:"));
    }

    @Test
    @DisplayName("upgrade: the first start under language en rewrites only the token-keyed comment line")
    void upgradeRewritesOnlyTheTokenCommentLine() throws Exception {
        writeFile(UPGRADED_FILE);

        UpgradeConfig config = new UpgradeConfig(PATH);
        config.init(plugin);

        assertPreserved(EN_LIMIT);
        assertThat(config.limit).isEqualTo(25);
        assertThat(config.name).isEqualTo("Custom");
        assertThat(config.other).isEqualTo(3);
        assertThat(config.isModifiedSinceSnapshot()).as("the shutdown save sees no change").isFalse();
    }

    @Test
    @DisplayName("a line of spaces outside the comment: the comment write is refused and the file keeps every byte")
    void whitespaceOnlyBlankLineBlocksTheCommentRewrite() throws Exception {
        String spaced = UPGRADED_FILE.replace("Custom\n\n", "Custom\n  \n");
        writeFile(spaced);
        long mtime = pinModificationTime();

        UpgradeConfig config = new UpgradeConfig(PATH);
        config.init(plugin);

        assertThat(readFile()).isEqualTo(spaced);
        assertThat(file().toFile().lastModified()).isEqualTo(mtime);
        assertThat(config.limit).isEqualTo(25);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("no churn: a start whose resolved comments already match writes nothing (bytes and mtime unchanged)")
    void secondStartWritesNothing() throws Exception {
        writeFile(UPGRADED_FILE);
        new UpgradeConfig(PATH).init(plugin);
        byte[] afterFirst = Files.readAllBytes(file());
        long mtime = pinModificationTime();

        UpgradeConfig second = new UpgradeConfig(PATH);
        second.init(plugin);

        assertThat(Files.readAllBytes(file())).isEqualTo(afterFirst);
        assertThat(file().toFile().lastModified()).isEqualTo(mtime);
        assertThat(second.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("language switch: the first start under zh rewrites the comment once, the next writes nothing")
    void languageSwitchRewritesOnce() throws Exception {
        writeFile(UPGRADED_FILE);
        new UpgradeConfig(PATH).init(plugin);

        languageChinese();
        new UpgradeConfig(PATH).init(plugin);
        assertPreserved(ZH_LIMIT);

        byte[] afterSwitch = Files.readAllBytes(file());
        long mtime = pinModificationTime();
        new UpgradeConfig(PATH).init(plugin);
        assertThat(Files.readAllBytes(file())).isEqualTo(afterSwitch);
        assertThat(file().toFile().lastModified()).isEqualTo(mtime);
    }

    @Test
    @DisplayName("an operator's hand-written comment on a token-keyed entry is replaced; one on a literal entry is kept")
    void handWrittenCommentOnTokenEntryIsReplaced() throws Exception {
        writeFile("demo:\n  # my own explanation\n  limit: 25\n  # my note\n  name: Custom\n  other: 3\n");

        new UpgradeConfig(PATH).init(plugin);

        YamlConfiguration parsed = parse();
        assertThat(parsed.getComments("demo.limit")).containsExactly(EN_LIMIT);
        assertThat(parsed.getComments("demo.name")).containsExactly("my note");
        assertThat(parsed.getInt("demo.limit")).isEqualTo(25);
    }

    @Test
    @DisplayName("every write carries the resolved comment: an explicit save() and a panel write")
    void explicitSaveAndPanelWriteCarryTheComment() throws Exception {
        writeFile(UPGRADED_FILE);
        UpgradeConfig config = new UpgradeConfig(PATH);
        config.init(plugin);

        writeFile(readFile().replace("# " + EN_LIMIT, "# tampered in memory"));
        config.save();
        assertThat(parse().getComments("demo.limit")).containsExactly(EN_LIMIT);

        writeFile(readFile().replace("# " + EN_LIMIT, "# tampered again"));
        JsonObject payload = new JsonObject();
        payload.addProperty("demo.other", 4);
        config.updateProperties(payload);
        YamlConfiguration afterPanel = parse();
        assertThat(afterPanel.getComments("demo.limit")).containsExactly(EN_LIMIT);
        assertThat(afterPanel.getInt("demo.other")).isEqualTo(4);
        assertThat(afterPanel.getComments("demo.name")).containsExactly("My own note on the name");
    }
    @Test
    void bundledLayoutPreservesContentCommentsAndStyleWhenTokenChanges() throws Exception {
        String text = "\uFEFF# Operator header\r\n\r\ndemo:\r\n"
                + "    # old token text\r\n    limit: 25 # inline limit\r\n"
                + "    # Literal operator note\r\n    name: 'Custom'\r\n    other: 3\r\n"
                + "extra:\r\n    items:\r\n        - 'quoted item' # inline list\r\n        - second";
        writeFile(text);
        UpgradeConfig config = new UpgradeConfig(PATH);
        config.init(plugin);
        String written = readFile();
        assertThat(written).startsWith("\uFEFF").doesNotEndWith("\n");
        assertThat(written.replace("\r\n", "")).doesNotContain("\n", "\r");
        assertThat(written).contains("    limit:", "# inline limit", "# inline list", "# Operator header");
        YamlConfiguration parsed = parse();
        assertThat(parsed.getComments("demo.limit")).containsExactly(EN_LIMIT);
        assertThat(parsed.getComments("demo.name")).containsExactly("Literal operator note");
        assertThat(parsed.getStringList("extra.items")).containsExactly("quoted item", "second");
        assertThat(parsed.getInt("demo.limit")).isEqualTo(25);
        byte[] before = Files.readAllBytes(file());
        long mtime = pinModificationTime();
        config.reload();
        config.save();
        assertThat(Files.readAllBytes(file())).isEqualTo(before);
        assertThat(file().toFile().lastModified()).isEqualTo(mtime);
    }

}
