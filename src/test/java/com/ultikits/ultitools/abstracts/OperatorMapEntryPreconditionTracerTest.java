package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigEntryPresenceException;
import com.ultikits.ultitools.config.EntryPresence;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * Tracer of plan 17-74 (UltiKits/UltiTools-Reborn#623): an operator's map-entry write made conditional on the entry's
 * presence in the file, end to end through the configuration write gate on a real entity over a temporary folder.
 * <p>
 * Why it cannot overwrite operator content: the condition is decided on the read whose bytes the gate verifies the
 * edit against, so {@code add} never replaces a rule the operator added by hand and {@code setkeyword} never writes back
 * a rule the operator deleted; a failed condition writes nothing (bytes and modification time stay).
 */
@DisplayName("saveOperatorMapEntry(EntryPresence, ...): an operator's map-entry write only under its stated condition")
class OperatorMapEntryPreconditionTracerTest {

    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);
    private static final String RULES = "autoreply.yml";
    private static final String BASE = "# Auto replies\n"
            + "autoreply:\n"
            + "  enabled: true\n"
            + "  # The rules\n"
            + "  rules:\n"
            + "    a:\n"
            + "      keyword: hi\n"
            + "      response: hello\n"
            + "# end\n";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    public static class AutoReply extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.enabled") boolean enabled = true;
        @ConfigEntry(path = "autoreply.rules") Map<String, Map<String, String>> rules = new LinkedHashMap<>();

        public AutoReply(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OperatorModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        Logger.getLogger("com.ultikits").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("com.ultikits").removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    private void put(String text) throws IOException {
        Files.write(tempDir.resolve(RULES), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(tempDir.resolve(RULES), OLD);
    }

    private String read() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(RULES)), StandardCharsets.UTF_8);
    }

    private static Map<String, String> rule(String keyword, String response) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("keyword", keyword);
        rule.put("response", response);
        return rule;
    }

    private AutoReply loaded() throws IOException {
        put(BASE);
        AutoReply config = new AutoReply(RULES);
        config.init(plugin);
        warnings.clear();
        return config;
    }

    @Test
    @DisplayName("MUST_BE_ABSENT, entry absent: the new rule is written and every other line keeps its bytes")
    void absentEntryIsWritten() throws Exception {
        AutoReply config = loaded();

        config.rules.put("b", rule("ip", "play.example.com"));
        config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b");

        assertThat(read()).isEqualTo(BASE.replace("# end\n",
                "    b:\n      keyword: ip\n      response: play.example.com\n# end\n"));
        assertThat(config.isModifiedSinceSnapshot()).as("the written entry is saved").isFalse();
    }

    @Test
    @DisplayName("MUST_BE_ABSENT, a rule of that name hand-added since the load: refused, nothing written, no warning")
    void handAddedEntryRefusesAnAbsentWrite() throws Exception {
        AutoReply config = loaded();
        String handAdded = BASE.replace("# end\n", "    b:\n      keyword: hand\n      response: the operator's\n# end\n");
        put(handAdded);

        config.rules.put("b", rule("ip", "play.example.com"));
        ConfigEntryPresenceException refused = catchThrowableOfType(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b"),
                ConfigEntryPresenceException.class);

        assertThat(refused).as("refused with the dedicated subtype").isNotNull();
        assertThat(refused.getRequired()).isEqualTo(EntryPresence.MUST_BE_ABSENT);
        assertThat(refused.getReason()).contains("autoreply.rules.b").doesNotContain("hand").doesNotContain("play.example");
        assertThat(read()).isEqualTo(handAdded);
        assertThat(Files.getLastModifiedTime(tempDir.resolve(RULES))).isEqualTo(OLD);
        assertThat(warnings).as("the caller reports it; the framework logs no warning").isEmpty();
    }

    @Test
    @DisplayName("MUST_BE_PRESENT, the rule hand-deleted since the load: refused, the file is not given the rule back")
    void handDeletedEntryRefusesAPresentWrite() throws Exception {
        AutoReply config = loaded();
        String handDeleted = "# Auto replies\nautoreply:\n  enabled: true\n  # The rules\n  rules:\n"
                + "    z:\n      keyword: z\n      response: zz\n# end\n";
        put(handDeleted);

        config.rules.get("a").put("keyword", "changed");
        ConfigEntryPresenceException refused = catchThrowableOfType(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "a"),
                ConfigEntryPresenceException.class);

        assertThat(refused).as("refused with the dedicated subtype").isNotNull();
        assertThat(refused.getRequired()).isEqualTo(EntryPresence.MUST_BE_PRESENT);
        assertThat(read()).isEqualTo(handDeleted);
        assertThat(Files.getLastModifiedTime(tempDir.resolve(RULES))).isEqualTo(OLD);
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("MUST_BE_PRESENT, entry present: written exactly as the two-argument overload writes it")
    void presentEntryIsWrittenAsTheExistingOverloadWrites() throws Exception {
        AutoReply twin = loaded();
        twin.rules.get("a").put("keyword", "changed");
        twin.saveOperatorMapEntry("autoreply.rules", "a");
        String expected = read();

        AutoReply config = loaded();
        config.rules.get("a").put("keyword", "changed");
        config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "a");

        assertThat(read()).isEqualTo(expected).isEqualTo(BASE.replace("keyword: hi", "keyword: changed"));
        assertThat(config.isModifiedSinceSnapshot()).isFalse();
    }

    /**
     * The shapes UltiKits/UltiChat#51 names as false refusals of the module's own parser: under the framework's read each
     * holds no rule {@code b}, so {@code add b} writes it, keeping every byte it does not own.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"section-absent", "empty-map", "comment-only", "flat-form"})
    @DisplayName("UltiChat#51 shapes under MUST_BE_ABSENT are written, not refused")
    void ultiChatShapesAreWritten(String shape) throws Exception {
        String onDisk;
        String expected;
        String added = "b:\n      keyword: ip\n      response: here\n";
        switch (shape) {
            case "section-absent":
                onDisk = "# top comment\nother: 1\n";
                expected = onDisk + "autoreply:\n  rules:\n    " + added;
                break;
            case "empty-map":
                onDisk = "autoreply:\n  enabled: true\n  rules: {}\n";
                expected = "autoreply:\n  enabled: true\n  rules: {b: {keyword: ip, response: here}}\n";
                break;
            case "comment-only":
                onDisk = "# only a comment\n# second\n";
                expected = onDisk + "autoreply:\n  rules:\n    " + added;
                break;
            default:
                onDisk = "\"autoreply.rules\":\n  a:\n    keyword: hi\n    response: hello\n";
                expected = onDisk + "  b:\n    keyword: ip\n    response: here\n";
                break;
        }
        AutoReply config = loaded();
        put(onDisk);

        config.rules.put("b", rule("ip", "here"));
        config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b");

        assertThat(read()).isEqualTo(expected);
        assertThat(warnings).isEmpty();
    }
}
