package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.objenesis.ObjenesisStd;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
import com.ultikits.ultitools.utils.TestHelper;

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
    private String language = "en";

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
    void setUp() {
        Map<String, String> en = new LinkedHashMap<>();
        en.put("config.item.interval", EN_INTERVAL);
        en.put("config.item.multi", EN_MULTI);
        en.put("config.item.lockout", EN_LOCKOUT);
        Map<String, String> zh = new LinkedHashMap<>();
        zh.put("config.item.interval", ZH_INTERVAL);
        zh.put("config.item.multi", ZH_MULTI);
        zh.put("config.item.lockout", ZH_LOCKOUT);
        shipped.put("en", en);
        shipped.put("zh", zh);

        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OwnershipModule");
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        lenient().when(plugin.i18n(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<String, String> current = shipped.get(language);
            return current != null && current.containsKey(key) ? current.get(key) : key;
        });
        lenient().when(plugin.shippedCatalogueTexts(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            List<String> texts = new ArrayList<>();
            for (Map<String, String> catalogue : shipped.values()) {
                if (catalogue.containsKey(key)) {
                    texts.add(catalogue.get(key));
                }
            }
            return texts;
        });
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

        language = "zh";
        new P3Config(PATH).init(plugin);
        assertThat(text()).as("start-up after a language switch").isEqualTo(P3);

        language = "en";
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

        language = "zh";
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
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "item:\n  #Announcement interval in seconds\n  interval: 300\n",
        "item:\n# Announcement interval in seconds\n  interval: 300\n",
        "item:\n    # Announcement interval in seconds\n  interval: 300\n",
        "item:\n  # note\n# Announcement interval in seconds\n  interval: 300\n"})
    @DisplayName("the framework's text in a byte form the framework never writes is the operator's and is kept")
    void frameworkTextInAnotherByteFormIsKept(String operatorForm) throws Exception {
        put(operatorForm);

        language = "zh";
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

        language = "zh";
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
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"depth1-lf", "depth1-crlf", "depth2-lf"})
    @DisplayName("a framework line after a note and a blank line stays at the key's column and follows every switch")
    void frameworkLineAfterABlankLineFollowsEverySwitch(String shape) throws Exception {
        shipped.put("fr", new LinkedHashMap<String, String>());
        shipped.get("fr").put("config.item.interval", "Intervalle des annonces");
        String nl = shape.endsWith("crlf") ? "\r\n" : "\n";
        String indent = shape.startsWith("depth2") ? "    " : "  ";
        String head = shape.startsWith("depth2") ? "deep:" + nl + "  item:" + nl : "item:" + nl;
        put(head + indent + "# my note" + nl + nl + indent + "# " + ZH_INTERVAL + nl + indent + "interval: 300" + nl);
        String[] languages = {"en", "fr", "zh", "en"};
        String[] texts = {EN_INTERVAL, "Intervalle des annonces", ZH_INTERVAL, EN_INTERVAL};
        for (int i = 0; i < languages.length; i++) {
            language = languages[i];
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

    @Test
    @DisplayName("an empty catalogue text identifies nothing: an operator's bare '#' line above the setting is kept")
    void emptyCatalogueTextIdentifiesNoLine() throws Exception {
        shipped.get("en").put("config.item.interval", "");
        shipped.get("zh").put("config.item.interval", "");
        String operatorBlank = "item:\n  # note\n  #\n  interval: 300\n";
        put(operatorBlank);

        language = "zh";
        TokenConfig config = new TokenConfig(PATH);
        config.init(plugin);
        language = "en";
        shipped.get("en").put("config.item.interval", EN_INTERVAL);
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
        language = "zh";
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

    // ------------------------------------------------------------------------------------------------
    // The jar catalogue accessor reads the module's jar only: no language file, hash record or backup is
    // read for it or written by it (the language-provenance writes belong to the language load).

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // same reflective fixture idiom as UltiToolsPluginLanguageFallbackTest
    @DisplayName("shipped catalogues are read from the jar in .json and .yml, and the language folder is untouched")
    void shippedCataloguesAreReadFromTheJarWithoutSideEffects() throws Exception {
        File jarRoot = new File(tempDir.toFile(), "jar-root");
        File classFile = new File(jarRoot, UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName()
                .replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParentFile().toPath());
        Files.write(classFile.toPath(), fixtureClassBytes());
        File jarLang = new File(jarRoot, "lang");
        Files.createDirectories(jarLang.toPath());
        Files.write(new File(jarLang, "en.json").toPath(),
                ("{\"config.item.interval\":\"" + EN_INTERVAL + "\"}").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(jarLang, "zh.yml").toPath(),
                ("config:\n  item:\n    interval: " + ZH_INTERVAL + "\n").getBytes(StandardCharsets.UTF_8));

        File resourceFolder = new File(tempDir.toFile(), "module-folder");
        File diskLang = new File(resourceFolder, "lang");
        Files.createDirectories(diskLang.toPath());
        Files.write(new File(diskLang, "en.json").toPath(),
                "{\"config.item.interval\":\"edited on disk\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(resourceFolder, ".ultitools-resource-hashes.json").toPath(),
                "{}".getBytes(StandardCharsets.UTF_8));

        Logger moduleLogger = Mockito.mock(Logger.class);
        YamlConfiguration frameworkConfig = new YamlConfiguration();
        frameworkConfig.set("language", "en");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getConfig()).thenReturn(frameworkConfig);
            lenient().when(ultiTools.getLogger()).thenReturn(moduleLogger);
        });
        Map<String, String> before = snapshot(tempDir);

        try (URLClassLoader loader = new ChildFirstLoader(new URL[]{jarRoot.toURI().toURL()},
                ConfigFrameworkCommentOwnershipTest.class.getClassLoader())) {
            // The class name is a compile-time constant, never attacker-controllable.
            // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
            Class<?> fixture = Class.forName(UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName(),
                    true, loader);
            UltiToolsPlugin module = (UltiToolsPlugin) new ObjenesisStd().newInstance(fixture);
            Field name = UltiToolsPlugin.class.getDeclaredField("pluginName");
            name.setAccessible(true);
            name.set(module, "FixtureModule");
            Field folder = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
            folder.setAccessible(true);
            folder.set(module, resourceFolder.getAbsolutePath());

            assertThat(module.shippedCatalogueTexts("config.item.interval"))
                    .containsExactlyInAnyOrder(EN_INTERVAL, ZH_INTERVAL);
            assertThat(module.shippedCatalogueTexts("config.item.absent")).isEmpty();
        }

        assertThat(snapshot(tempDir)).as("every file under the module and jar folders, with its SHA-256").isEqualTo(before);
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                    files.put(root.relativize(path).toString(), java.util.Base64.getEncoder().encodeToString(digest));
                } else {
                    files.put(root.relativize(path) + "/", "directory");
                }
            }
        }
        return files;
    }

    private static byte[] fixtureClassBytes() throws IOException {
        String resource = UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream in = ConfigFrameworkCommentOwnershipTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Compiled fixture class not found on the test classpath: " + resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Child-first for the fixture's own class, so its CodeSource is the exploded jar directory built above. */
    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException notShippedByThisJar) {
                        found = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }
}
