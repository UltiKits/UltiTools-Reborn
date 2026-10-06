package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #620 beyond the start-up insert (plan 17-75 Task 2): the expansion of a section line the operator left with no value
 * applies to every gated insert - an operator's map-entry command ({@code /uchat autoreply add} into an emptied
 * {@code rules:}, UltiChat#51), an operator change of a named setting, and a panel edit - and keeps every other line
 * byte-identical. Also the shapes of the same deletion the start-up tracer does not cover: a child commented out rather
 * than deleted (its comment line stays where it is, under the section), and a section line with a trailing comment
 * written whole.
 */
class NullSectionExpansionTest {

    private static final String NOTICE = "Notice shown when a new mail is received";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
    private final Logger frameworkLogger = Logger.getLogger("com.ultikits.ultitools");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                warnings.add(record.getMessage());
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

    @ConfigEntity("mail.yml")
    public static class Mail extends AbstractConfigEntity {
        @ConfigEntry(path = "other", comment = "Other setting")
        public int other = 1;
        @ConfigEntry(path = "messages.mail-received", comment = NOTICE)
        public String mailReceived = "You received a new mail";

        public Mail(String path) {
            super(path);
        }
    }

    @ConfigEntity("autoreply.yml")
    public static class Rules extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.enabled", comment = "Whether auto-reply is on")
        public boolean enabled = true;
        @ConfigEntry(path = "autoreply.rules")
        public Map<String, Map<String, String>> rules = new LinkedHashMap<>();

        public Rules(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger moduleLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(u -> Mockito.when(u.getLogger()).thenReturn(moduleLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("ExpansionModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        lenient().when(plugin.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        frameworkLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        frameworkLogger.removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    private void write(String name, String text) throws IOException {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }

    private List<String> refusals() {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (String warning : warnings) {
                if (warning.contains("was not written")) {
                    result.add(warning);
                }
            }
        }
        return result;
    }

    private static Map<String, String> rule(String keyword, String response) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("keyword", keyword);
        rule.put("response", response);
        return rule;
    }

    /** The only lines that differ are {@code added}, one block directly below line {@code sectionLine} (0-based). */
    private static void onlyInserted(String before, String after, int sectionLine, String... added) {
        LineDiff diff = LineDiff.of(before, after);
        assertThat(diff.removed).as("no operator line is removed or changed").isEmpty();
        assertThat(diff.added).containsExactly(added);
        assertThat(diff.addedIsContiguous()).isTrue();
        assertThat(diff.addedAt.get(0)).as("directly below the section line").isEqualTo(sectionLine + 1);
    }

    @Nested
    class ExplicitWrites {

        @Test
        void anOperatorsMapEntryCommandWritesIntoARulesLineLeftEmpty() throws Exception {
            String emptied = "# Auto-reply (written by hand)\nautoreply:\n  enabled: true\n  rules:\n\n# end note\n";
            write("autoreply.yml", emptied);
            Rules config = new Rules("autoreply.yml");
            config.init(plugin);
            assertThat(read("autoreply.yml")).as("nothing to insert at start: the setting is present").isEqualTo(emptied);

            config.rules = new LinkedHashMap<>();
            config.rules.put("greeting", rule("hi", "hello"));
            config.saveOperatorMapEntry("autoreply.rules", "greeting");

            onlyInserted(emptied, read("autoreply.yml"), 3,
                    "    greeting:\n", "      keyword: hi\n", "      response: hello\n");
            assertThat(refusals()).isEmpty();
        }

        @Test
        void anOperatorChangeOfASettingWhoseSectionWasEmptiedSinceTheLoadInsertsIt() throws Exception {
            write("mail.yml", "other: 1\nmessages:\n  mail-received: old\n");
            Mail config = new Mail("mail.yml");
            config.init(plugin);
            String emptied = "other: 1\nmessages:\n# kept by hand\n";
            write("mail.yml", emptied);

            config.mailReceived = "from a command";
            config.saveOperatorChange("messages.mail-received");

            onlyInserted(emptied, read("mail.yml"), 1, "  # " + NOTICE + "\n", "  mail-received: from a command\n");
            assertThat(refusals()).isEmpty();
        }

        @Test
        void aPanelEditOfASettingWhoseSectionWasEmptiedSinceTheLoadInsertsIt() throws Exception {
            write("mail.yml", "other: 1\nmessages:\n  mail-received: old\n");
            Mail config = new Mail("mail.yml");
            config.init(plugin);
            String emptied = "other: 1\nmessages:   # emptied by hand\n";
            write("mail.yml", emptied);

            JsonObject edit = new JsonObject();
            edit.addProperty("messages.mail-received", "from the panel");
            config.updateProperties(edit);

            String after = read("mail.yml");
            List<String> lines = OperatorFileWriter.lines(after);
            assertThat(lines).containsExactly("other: 1\n", lines.get(1), "  # " + NOTICE + "\n",
                    "  mail-received: from the panel\n");
            assertThat(lines.get(1)).startsWith("messages:").endsWith("# emptied by hand\n");
            assertThat(config.mailReceived).isEqualTo("from the panel");
            assertThat(refusals()).isEmpty();
        }
    }

    @Nested
    class SameDeletionOtherShapes {

        @Test
        void aCommentedOutChildStaysUnderItsSectionBelowTheRestoredKey() throws Exception {
            String commentedOut = "other: 1\nmessages:\n  # mail-received: my old text\nlast: 2\n";
            write("mail.yml", commentedOut);

            new Mail("mail.yml").init(plugin);

            String restored = read("mail.yml");
            onlyInserted(commentedOut, restored, 1, "  # " + NOTICE + "\n", "  mail-received: You received a new mail\n");
            assertThat(refusals()).isEmpty();
            new Mail("mail.yml").init(plugin);
            assertThat(read("mail.yml")).as("a second start changes nothing").isEqualTo(restored);
            assertThat(refusals()).isEmpty();
        }

        @Test
        void aCommentedOutChildAtTheEndOfTheFileStaysUnderItsSection() throws Exception {
            String commentedOut = "other: 1\nmessages:\n  # mail-received: my old text\n";
            write("mail.yml", commentedOut);

            new Mail("mail.yml").init(plugin);

            onlyInserted(commentedOut, read("mail.yml"), 1, "  # " + NOTICE + "\n", "  mail-received: You received a new mail\n");
            assertThat(refusals()).isEmpty();
        }

        @Test
        void aWholeMapWrittenOverASectionLineWithATrailingCommentKeepsTheComment() throws Exception {
            String emptied = "autoreply:\n  enabled: true\n  rules:   # none yet\n";
            write("autoreply.yml", emptied);
            Rules config = new Rules("autoreply.yml");
            config.init(plugin);

            config.rules = new LinkedHashMap<>();
            config.rules.put("greeting", rule("hi", "hello"));
            config.saveOperatorChange("autoreply.rules");

            List<String> lines = OperatorFileWriter.lines(read("autoreply.yml"));
            assertThat(lines).hasSize(6);
            assertThat(lines.subList(0, 2)).containsExactly("autoreply:\n", "  enabled: true\n");
            assertThat(lines.get(2)).startsWith("  rules:").endsWith("# none yet\n");
            assertThat(lines.subList(3, 6)).containsExactly("    greeting:\n", "      keyword: hi\n", "      response: hello\n");
            assertThat(refusals()).isEmpty();
        }
    }
}
