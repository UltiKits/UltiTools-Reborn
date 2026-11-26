package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
import com.ultikits.ultitools.text.ModuleCatalogueFixture;
import java.util.Base64;

/**
 * #604 (inventory A2/A5, probe P3), maintainer decision of 2026-10-04 "hand-written comments on token-commented
 * settings": the framework rewrites only the comment lines it can identify as its own. A comment line above a
 * {@code @ConfigEntry(comment = "{key}")} setting is the framework's only when the setting's comment, as a whole
 * or as its trailing run of lines, equals the framework's rendering of that token in a catalogue the module's jar
 * ships, of the text the module currently resolves, or of the bare token. Everything else - an operator's note,
 * a framework comment the operator edited, a literal comment - stays byte for byte, permanently; the framework's
 * own lines still follow a language switch. Supersedes the 2026-09-29 answer that such a comment is replaced.
 */
@DisplayName("Config comments: only the framework's own comment lines are rewritten (#604)")
class ConfigFrameworkCommentOwnershipTest {

    private static final String PATH = "config/ownership.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);

    private static final String EN_INTERVAL = "Announcement interval in seconds";
    private static final String ZH_INTERVAL = "公告间隔（秒）";
    private static final String EN_MULTI = "First line\nSecond line";
    private static final String ZH_MULTI = "第一行\n第二行";
    private static final String UNKNOWN_TOKEN = "{config.item.unknown}";
    private static final String PREVIOUS_LOCKOUT = "封禁类型：IP / UUID / BOTH";
    private static final String ZH_LOCKOUT = "封禁类型：IP / UUID / BOTH。失败次数按与封禁相同的键计数";
    private static final String EN_LOCKOUT = "Lockout type: IP / UUID / BOTH. Failures are counted per the same key";

    /** The P3 fixture of the overwrite inventory (probe {@code p3_tokenCommentReplacesHandComment}). */
    private static final String P3 = "item:\n  # my own note: keep 300 for event weekends\n  interval: 300\n"
            + "  warn-times: [60, 30, 10]\n  message: hello\n  added: new\nemojis: {}\n";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;
    /** The catalogues the module's jar ships: code -> key -> text. */
    private final Map<String, Map<String, String>> shipped = new TreeMap<>();
    private ModuleCatalogueFixture catalogue;

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class P3Config extends AbstractConfigEntity {
        @ConfigEntry(path = "item.interval", comment = "{config.item.interval}")
        int interval = 300;

        @ConfigEntry(path = "item.message")
        String message = "hello";

        public P3Config(String path) {
            super(path);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class TokenConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "item.interval", comment = "{config.item.interval}")
        int interval = 300;

        public TokenConfig(String path) {
            super(path);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class DeepConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "deep.item.interval", comment = "{config.item.interval}")
        int interval = 300;

        public DeepConfig(String path) {
            super(path);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class SeparatorConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "a.s")
        String s = "x";

        @ConfigEntry(path = "a.b.k", comment = "{config.item.interval}")
        int k = 1;

        @ConfigEntry(path = "a.b.m")
        int m = 2;

        public SeparatorConfig(String path) {
            super(path);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class UnknownConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "item.unknown", comment = UNKNOWN_TOKEN)
        boolean unknown = true;

        public UnknownConfig(String path) {
            super(path);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class MultiConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "item.multi", comment = "{config.item.multi}")
        int multi = 2;

        public MultiConfig(String path) {
            super(path);
        }
    }

    /** UltiLogin's {@code security.lockout-type} shape: an older module version wrote a literal comment. */
    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class PreviousConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "item.lockout", comment = "{config.item.lockout}", previousComments = {PREVIOUS_LOCKOUT})
        String lockout = "IP";

        @ConfigEntry(path = "item.kept", comment = "Literal comment", previousComments = {"Old literal comment"})
        int kept = 1;

        public PreviousConfig(String path) {
            super(path);
        }
    }

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(PATH)
    static class LiteralConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "item.kept", comment = "Literal comment")
        int kept = 1;

        @ConfigEntry(path = "item.inserted", comment = "Inserted literal comment")
        int inserted = 2;

        public LiteralConfig(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<String, String> en = new LinkedHashMap<>();
        en.put("config.item.interval", "<green>" + EN_INTERVAL + "</green>");
        en.put("config.item.multi", EN_MULTI);
        en.put("config.item.lockout", EN_LOCKOUT);
        Map<String, String> zh = new LinkedHashMap<>();
        zh.put("config.item.interval", "<gold>" + ZH_INTERVAL + "</gold>");
        zh.put("config.item.multi", ZH_MULTI);
        zh.put("config.item.lockout", ZH_LOCKOUT);
        shipped.put("en", en);
        shipped.put("zh", zh);

        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OwnershipModule");
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        catalogue = new ModuleCatalogueFixture(tempDir);
        refreshPreparedCatalogue();
    }

    private void refreshPreparedCatalogue() throws IOException {
        for (var entry : shipped.entrySet()) {
            catalogue.shipped(entry.getKey(), entry.getValue());
        }
        lenient().when(plugin.moduleTexts()).thenReturn(Optional.of(catalogue.preparedTexts()));
    }

    @AfterEach
    void closeCatalogue() {
        catalogue.close();
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void put(String text) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file(), OLD);
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private FileTime mtime() throws IOException {
        return Files.getLastModifiedTime(file());
    }

    private List<String> comments(String path) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.loadFromString(text());
        return configuration.getComments(path);
    }

    @Test
    @DisplayName("P3: a hand-written note above a token setting survives start-up, a language switch and a reload, byte for byte")
    void handWrittenNoteSurvivesStartLanguageSwitchAndReload() throws Exception {
        put(P3);

        P3Config config = new P3Config(PATH);
        config.init(plugin);
        assertThat(text()).as("start-up").isEqualTo(P3);
        assertThat(mtime()).isEqualTo(OLD);

        catalogue.fallback("zh");
        new P3Config(PATH).init(plugin);
        assertThat(text()).as("start-up after a language switch").isEqualTo(P3);

        catalogue.fallback("en");
        config.reload();
        assertThat(text()).as("reload after a further switch").isEqualTo(P3);
        assertThat(mtime()).isEqualTo(OLD);
        assertThat(config.interval).isEqualTo(300);
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    @DisplayName("a framework comment written in Chinese becomes English after a switch to en; the next start writes nothing")
    void frameworkCommentFollowsALanguageSwitchAndTheNextStartIsANoOp() throws Exception {
        put("item:\n  # " + ZH_INTERVAL + "\n  interval: 300\n");

        new TokenConfig(PATH).init(plugin);
        assertThat(text()).isEqualTo("item:\n  # " + EN_INTERVAL + "\n  interval: 300\n");

        Files.setLastModifiedTime(file(), OLD);
        byte[] after = Files.readAllBytes(file());
        new TokenConfig(PATH).init(plugin);
        assertThat(Files.readAllBytes(file())).isEqualTo(after);
        assertThat(mtime()).isEqualTo(OLD);
    }

    @Test
    @DisplayName("an operator note above the framework's line: only the framework line changes language")
    void onlyTheFrameworkLineBelowAnOperatorNoteChangesLanguage() throws Exception {
        put("item:\n  # operator note: raised for events\n  # " + ZH_INTERVAL + "\n  interval: 300\n");

        TokenConfig config = new TokenConfig(PATH);
        config.init(plugin);

        assertThat(text()).isEqualTo("item:\n  # operator note: raised for events\n  # " + EN_INTERVAL + "\n  interval: 300\n");
        assertThat(config.isModifiedSinceSnapshot()).isFalse();

        catalogue.fallback("zh");
        config.reload();
        assertThat(text()).isEqualTo("item:\n  # operator note: raised for events\n  # " + ZH_INTERVAL + "\n  interval: 300\n");
    }

    @Test
    @DisplayName("a framework comment the operator edited by one word is kept after a language switch")
    void frameworkCommentEditedByOneWordIsKept() throws Exception {
        String edited = "item:\n  # 公告间隔（分钟）\n  interval: 300\n";
        put(edited);

        new TokenConfig(PATH).init(plugin);

        assertThat(text()).isEqualTo(edited);
        assertThat(mtime()).isEqualTo(OLD);
    }

    @Test
    @DisplayName("a bare token with no catalogue entry stays; once an entry appears only the token line is replaced")
    void bareTokenIsTheFrameworksUntilItsCatalogueEntryAppears() throws Exception {
        String noEntry = "item:\n  # my note on this switch\n  # " + UNKNOWN_TOKEN + "\n  unknown: true\n";
        put(noEntry);

        new UnknownConfig(PATH).init(plugin);
        assertThat(text()).as("no catalogue entry in any language").isEqualTo(noEntry);
        assertThat(mtime()).isEqualTo(OLD);

        shipped.get("en").put("config.item.unknown", "Whether the unknown feature runs");
        refreshPreparedCatalogue();
        new UnknownConfig(PATH).init(plugin);
        assertThat(text()).isEqualTo("item:\n  # my note on this switch\n  # Whether the unknown feature runs\n  unknown: true\n");
    }

    @Test
    @DisplayName("a two-line catalogue text is recognised and rewritten as a whole; a CRLF file keeps CRLF")
    void twoLineCatalogueTextIsRewrittenAsAWholeInACrlfFile() throws Exception {
        put("item:\r\n  # kept note\r\n  # 第一行\r\n  # 第二行\r\n  multi: 2\r\n");

        new MultiConfig(PATH).init(plugin);

        assertThat(text()).isEqualTo("item:\r\n  # kept note\r\n  # First line\r\n  # Second line\r\n  multi: 2\r\n");
        assertThat(comments("item.multi")).containsExactly("kept note", "First line", "Second line");
    }

    /**
     * 17-64 review round 1 R1-02 (comment-identification revision 1): only the framework's exact written form is its
     * own - the key's own indentation, then {@code "# "} and the text. The same text without the space after
     * {@code #}, or at another column, was written by the operator and is kept byte for byte.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "item:\n  #Announcement interval in seconds\n  interval: 300\n",
        "item:\n# Announcement interval in seconds\n  interval: 300\n",
        "item:\n    # Announcement interval in seconds\n  interval: 300\n",
        "item:\n  # note\n# Announcement interval in seconds\n  interval: 300\n"})
    @DisplayName("the framework's text in a byte form the framework never writes is the operator's and is kept")
    void frameworkTextInAnotherByteFormIsKept(String operatorForm) throws Exception {
        put(operatorForm);

        catalogue.fallback("zh");
        new TokenConfig(PATH).init(plugin);

        assertThat(text()).isEqualTo(operatorForm);
        assertThat(mtime()).isEqualTo(OLD);
    }

    /**
     * Maintainer decision 2026-10-04 ("register the old texts as framework-owned"): a comment equal, in the exact
     * written form, to a text an earlier module version shipped for the setting ({@code @ConfigEntry#previousComments})
     * is the framework's - replaced by the current catalogue text and following the language from then on.
     */
    @Test
    @DisplayName("a registered previous comment text is the framework's: replaced, then it follows the language")
    void registeredPreviousCommentIsReplacedAndFollowsTheLanguage() throws Exception {
        put("item:\n  # operator note\n  # " + PREVIOUS_LOCKOUT + "\n  lockout: UUID\n");

        PreviousConfig config = new PreviousConfig(PATH);
        config.init(plugin);
        assertThat(text()).isEqualTo("item:\n  # operator note\n  # " + EN_LOCKOUT + "\n  lockout: UUID\n"
                + "  # Literal comment\n  kept: 1\n");

        catalogue.fallback("zh");
        config.reload();
        assertThat(text()).isEqualTo("item:\n  # operator note\n  # " + ZH_LOCKOUT + "\n  lockout: UUID\n"
                + "  # Literal comment\n  kept: 1\n");

        Files.setLastModifiedTime(file(), OLD);
        new PreviousConfig(PATH).init(plugin);
        assertThat(mtime()).as("the next start writes nothing").isEqualTo(OLD);
    }

    @Test
    @DisplayName("a comment one character away from a registered previous text is the operator's and is kept")
    void commentOneCharacterAwayFromAPreviousTextIsKept() throws Exception {
        String edited = "item:\n  # " + PREVIOUS_LOCKOUT + "!\n  lockout: UUID\n  # Old literal comment\n  kept: 1\n";
        put(edited);

        new PreviousConfig(PATH).init(plugin);

        assertThat(text()).as("one character more, and a previous text on a literal entry, are both kept").isEqualTo(edited);
        assertThat(mtime()).isEqualTo(OLD);
    }

    /**
     * 17-64 review round 1 R1-01 and route change (i): the framework's line after an operator note and a blank line
     * stays at the key's column, so it stays identifiable and keeps following the language across en, fr, zh, en - at
     * depth 1 and depth 2, LF and CRLF.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"depth1-lf", "depth1-crlf", "depth2-lf"})
    @DisplayName("a framework line after a note and a blank line stays at the key's column and follows every switch")
    void frameworkLineAfterABlankLineFollowsEverySwitch(String shape) throws Exception {
        shipped.put("fr", new LinkedHashMap<>());
        shipped.get("fr").put("config.item.interval", "Intervalle des annonces");
        refreshPreparedCatalogue();
        String nl = shape.endsWith("crlf") ? "\r\n" : "\n";
        String indent = shape.startsWith("depth2") ? "    " : "  ";
        String head = shape.startsWith("depth2") ? "deep:" + nl + "  item:" + nl : "item:" + nl;
        put(head + indent + "# my note" + nl + nl + indent + "# " + ZH_INTERVAL + nl + indent + "interval: 300" + nl);
        String[] languages = {"en", "fr", "zh", "en"};
        String[] texts = {EN_INTERVAL, "Intervalle des annonces", ZH_INTERVAL, EN_INTERVAL};
        for (int i = 0; i < languages.length; i++) {
            catalogue.fallback(languages[i]);
            if (shape.startsWith("depth2")) {
                new DeepConfig(PATH).init(plugin);
            } else {
                new TokenConfig(PATH).init(plugin);
            }
            assertThat(text()).as("after the switch to " + languages[i]).isEqualTo(head + indent + "# my note" + nl + nl
                    + indent + "# " + texts[i] + nl + indent + "interval: 300" + nl);
        }
    }

    /**
     * 17-64 review round 2 R2-01: an operator value holding {@code #} and private-use characters is never changed - not
     * by the gated comment write next to it, not by a module save.
     */
    @Test
    @DisplayName("a value holding '#' and private-use characters survives the comment write and a save byte for byte")
    void valueWithPrivateUseCharactersSurvivesCommentWriteAndSave() throws Exception {
        String value = "a#\uE000\uE001\uE002b";
        put("item:\n  # note\n\n  # " + ZH_INTERVAL + "\n  interval: 300\n  message: " + value + "\n");

        P3Config config = new P3Config(PATH);
        config.init(plugin);
        assertThat(text()).isEqualTo("item:\n  # note\n\n  # " + EN_INTERVAL + "\n  interval: 300\n  message: " + value + "\n");

        config.interval = 450;
        config.save();
        assertThat(text()).isEqualTo("item:\n  # note\n\n  # " + EN_INTERVAL + "\n  interval: 450\n  message: " + value + "\n");
        assertThat(config.message).isEqualTo(value);
    }

    private static String separatorTail(String framework) {
        return "  b:\n    # note\n\n    # " + framework + "\n    k: 1\n    # tail\n\n        # operator deep\n    m: 2\n";
    }

    /**
     * 17-64 review round 3 R3-01: a value holding one or four U+2028 does not shift the comments after it - through a
     * gated language switch the framework line stays at the key's column and follows en and zh; the operator's deeper
     * line keeps its column.
     */
    @ParameterizedTest(name = "{0} x U+2028")
    @ValueSource(ints = {1, 4})
    @DisplayName("a value holding U+2028: the framework line follows en and zh at the key's column through gated writes")
    void lineSeparatorValueThroughGatedLanguageSwitches(int count) throws Exception {
        StringBuilder separators = new StringBuilder();
        for (int i = 0; i < count; i++) {
            separators.append('\u2028');
        }
        String head = "a:\n  s: |-\n    x" + separators + "    y\n";
        put(head + separatorTail(ZH_INTERVAL));

        catalogue.fallback("en");
        new SeparatorConfig(PATH).init(plugin);
        assertThat(text()).isEqualTo(head + separatorTail(EN_INTERVAL));

        catalogue.fallback("zh");
        new SeparatorConfig(PATH).init(plugin);
        assertThat(text()).isEqualTo(head + separatorTail(ZH_INTERVAL));
    }

    @Test
    @DisplayName("a module save of a value holding four U+2028 keeps every comment line at its column")
    void saveOfALineSeparatorValueKeepsCommentColumns() throws Exception {
        catalogue.fallback("en");
        put("a:\n  s: x\n" + separatorTail(EN_INTERVAL));
        SeparatorConfig config = new SeparatorConfig(PATH);
        config.init(plugin);

        config.s = "x\u2028y\u2028z\u2028w\u2028v";
        config.save();

        assertThat(text()).endsWith(separatorTail(EN_INTERVAL));
        SeparatorConfig reread = new SeparatorConfig(PATH);
        reread.init(plugin);
        assertThat(reread.s).isEqualTo("x\u2028y\u2028z\u2028w\u2028v");
    }

    @Test
    @DisplayName("an empty catalogue text identifies nothing: an operator's bare '#' line above the setting is kept")
    void emptyCatalogueTextIdentifiesNoLine() throws Exception {
        shipped.get("en").put("config.item.interval", "");
        shipped.get("zh").put("config.item.interval", "");
        refreshPreparedCatalogue();
        String operatorBlank = "item:\n  # note\n  #\n  interval: 300\n";
        put(operatorBlank);

        catalogue.fallback("zh");
        TokenConfig config = new TokenConfig(PATH);
        config.init(plugin);
        catalogue.fallback("en");
        shipped.get("en").put("config.item.interval", EN_INTERVAL);
        refreshPreparedCatalogue();
        config.reload();

        assertThat(text()).as("the framework never writes an empty comment, so '#' is not its line").isEqualTo(operatorBlank);
        assertThat(mtime()).isEqualTo(OLD);
    }

    @Test
    @DisplayName("a literal comment is never rewritten; it is written only when its key is inserted")
    void literalCommentIsWrittenOnlyWhenItsKeyIsInserted() throws Exception {
        put("item:\n  # the operator's own words\n  kept: 5\n");

        LiteralConfig config = new LiteralConfig(PATH);
        config.init(plugin);
        catalogue.fallback("zh");
        config.reload();
        config.save();

        assertThat(text()).isEqualTo("item:\n  # the operator's own words\n  kept: 5\n"
                + "  # Inserted literal comment\n  inserted: 2\n");
    }

    @Test
    @DisplayName("the gate owns only the framework's trailing run: an edit of the operator line above it is refused")
    void gateOwnsOnlyTheTrailingFrameworkRun() throws Exception {
        String original = "item:\n  # operator note\n  # " + ZH_INTERVAL + "\n  interval: 300\n";
        put(original);
        List<String> path = Arrays.asList("item", "interval");
        OwnedPaths run = OwnedPaths.builder().frameworkComment(path, 1).build();

        OperatorFileWriter.Result refused = OperatorFileWriter.write(file(), run, null,
                document -> document.setFrameworkComment(path, Collections.singletonList(EN_INTERVAL)));
        assertThat(refused.outcome()).isEqualTo(OperatorFileWriter.Outcome.REFUSED);
        assertThat(text()).isEqualTo(original);

        OperatorFileWriter.Result written = OperatorFileWriter.write(file(), run, null,
                document -> document.replaceFrameworkComment(path, 1, Collections.singletonList(EN_INTERVAL)));
        assertThat(written.outcome()).isEqualTo(OperatorFileWriter.Outcome.WRITTEN);
        assertThat(text()).isEqualTo("item:\n  # operator note\n  # " + EN_INTERVAL + "\n  interval: 300\n");
    }

    @Test
    @DisplayName("shipped templates come from every module catalogue and leave the operator files alone")
    void shippedCataloguesAreReadFromTheCodeSourceWithoutSideEffects() throws Exception {
        Path operator = Files.createDirectories(tempDir.resolve("overrides"));
        Files.writeString(operator.resolve("en.yml"), "config.item.interval: edited on disk\n");
        Map<String, String> before = snapshot(tempDir);

        var texts = catalogue.preparedTexts();
        assertThat(texts.shippedTemplates("config.item.interval")).containsExactlyInAnyOrder(EN_INTERVAL, ZH_INTERVAL);
        assertThat(texts.shippedTemplates("config.item.absent")).isEmpty();
        assertThat(snapshot(tempDir)).as("each file and its SHA-256 under the fixture root").isEqualTo(before);
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                    files.put(root.relativize(path).toString(), Base64.getEncoder().encodeToString(digest));
                } else {
                    files.put(root.relativize(path) + "/", "directory");
                }
            }
        }
        return files;
    }

}
