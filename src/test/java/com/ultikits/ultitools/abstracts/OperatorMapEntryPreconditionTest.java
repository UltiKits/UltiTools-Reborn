package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigEntryPresenceException;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.config.EntryPresence;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * The edges of {@code saveOperatorMapEntry(EntryPresence, ...)} (UltiKits/UltiTools-Reborn#623) beyond the tracer: the
 * argument checks it shares with the two-argument overload, what "present" means at each shape the plan decides (an
 * entry holding {@code null}, an empty map, a deeper key, a missing file), and the order of refusals - a gate refusal
 * stays a plain {@link ConfigWriteRefusedException} when the condition holds, and a setting written in two forms is
 * refused before presence is consulted.
 * <p>
 * Why it cannot overwrite operator content: every case below either writes exactly the named entry or writes nothing.
 */
@DisplayName("saveOperatorMapEntry(EntryPresence, ...): argument checks, presence semantics and refusal order")
class OperatorMapEntryPreconditionTest {

    private static final FileTime OLD = FileTime.fromMillis(1_577_836_800_000L);
    private static final String RULES = "autoreply.yml";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;

    public static class AutoReply extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply.enabled") boolean enabled = true;
        @ConfigEntry(path = "autoreply.rules") Map<String, Map<String, String>> rules = new LinkedHashMap<>();
        @ConfigEntry(path = "autoreply.lists") Map<String, List<String>> lists = new LinkedHashMap<>();

        public AutoReply(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("OperatorModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    /**
     * Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (gate-1 F1): with one,
     * the framework logs through the mock's {@code getLogger()}, which is {@code null}, instead of its own logger.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private void put(String text) throws IOException {
        Files.write(tempDir.resolve(RULES), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(tempDir.resolve(RULES), OLD);
    }

    private String read() throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(RULES)), StandardCharsets.UTF_8);
    }

    private AutoReply loaded(String text) throws IOException {
        put(text);
        AutoReply config = new AutoReply(RULES);
        config.init(plugin);
        put(read());
        return config;
    }

    private static Map<String, String> rule(String keyword, String response) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("keyword", keyword);
        rule.put("response", response);
        return rule;
    }

    private static final String BASE = "autoreply:\n  enabled: true\n  rules:\n    a:\n      keyword: hi\n      response: hello\n"
            + "  lists:\n    l: [x]\n";

    @Test
    @DisplayName("a null condition is an IllegalArgumentException; nothing is written")
    void nullConditionIsRejected() throws Exception {
        AutoReply config = loaded(BASE);
        config.rules.put("b", rule("ip", "here"));

        Throwable thrown = catchThrowable(() -> config.saveOperatorMapEntry((EntryPresence) null, "autoreply.rules", "b"));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autoreply.rules");
        assertThat(read()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("the two-argument overload's argument checks apply unchanged under either condition")
    void argumentChecksAreTheExistingOverloads() throws Exception {
        AutoReply config = loaded(BASE);

        for (EntryPresence required : EntryPresence.values()) {
            assertThat(catchThrowable(() -> config.saveOperatorMapEntry(required, "autoreply.lists", "l", "0")))
                    .as("keys reaching inside a list entry").isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reach inside an entry that is not a map");
            assertThat(catchThrowable(() -> config.saveOperatorMapEntry(required, "autoreply.enabled", "x")))
                    .as("not a map setting").isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> config.saveOperatorMapEntry(required, "autoreply.rules")))
                    .as("no map key").isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> config.saveOperatorMapEntry(required, "nope", "x")))
                    .as("not a declared entry").isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(read()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("an entry holding null is present: MUST_BE_ABSENT refuses it, MUST_BE_PRESENT writes it")
    void nullEntryIsPresent() throws Exception {
        AutoReply config = loaded(BASE);
        String withNull = BASE.replace("  lists:", "    b: ~\n  lists:");
        put(withNull);
        config.rules.put("b", rule("ip", "here"));

        ConfigEntryPresenceException refused = (ConfigEntryPresenceException) catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b"));
        assertThat(refused.getRequired()).isEqualTo(EntryPresence.MUST_BE_ABSENT);
        assertThat(read()).isEqualTo(withNull);

        config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "b");
        assertThat(read()).isEqualTo(BASE.replace("  lists:", "    b:\n      keyword: ip\n      response: here\n  lists:"));
    }

    @Test
    @DisplayName("an empty map holds no entry: MUST_BE_PRESENT is refused there")
    void emptyMapHoldsNoEntry() throws Exception {
        AutoReply config = loaded(BASE);
        String empty = "autoreply:\n  enabled: true\n  rules: {}\n  lists:\n    l: [x]\n";
        put(empty);
        config.rules.get("a").put("keyword", "changed");

        Throwable thrown = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "a"));

        assertThat(thrown).isInstanceOf(ConfigEntryPresenceException.class);
        assertThat(((ConfigEntryPresenceException) thrown).getRequired()).isEqualTo(EntryPresence.MUST_BE_PRESENT);
        assertThat(read()).isEqualTo(empty);
    }

    @Test
    @DisplayName("a deeper key is present only when its whole path is: a rule without that field is refused")
    void deeperKeyNeedsItsWholePath() throws Exception {
        AutoReply config = loaded(BASE);
        String noKeyword = BASE.replace("      keyword: hi\n", "");
        put(noKeyword);
        config.rules.get("a").put("keyword", "changed");

        Throwable thrown = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "a", "keyword"));
        assertThat(thrown).isInstanceOf(ConfigEntryPresenceException.class);
        assertThat(((ConfigEntryPresenceException) thrown).getReason()).contains("autoreply.rules.a.keyword");
        assertThat(read()).isEqualTo(noKeyword);

        config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "a", "keyword");
        assertThat(read()).as("only the field is written; the rule's response stays")
                .isEqualTo(BASE.replace("      keyword: hi\n      response: hello\n",
                        "      response: hello\n      keyword: changed\n"));
    }

    @Test
    @DisplayName("a missing file holds no entry: MUST_BE_PRESENT is refused and creates nothing")
    void missingFileHoldsNoEntry() throws Exception {
        AutoReply config = loaded(BASE);
        Files.delete(tempDir.resolve(RULES));
        config.rules.get("a").put("keyword", "changed");

        Throwable thrown = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "a"));

        assertThat(thrown).isInstanceOf(ConfigEntryPresenceException.class);
        assertThat(Files.exists(tempDir.resolve(RULES))).isFalse();
    }

    @Test
    @DisplayName("a refused layout under MUST_BE_PRESENT stays the gate's refusal, not a presence refusal")
    void refusedLayoutIsTheGatesRefusal() throws Exception {
        AutoReply config = loaded(BASE);
        String aligned = BASE.replace("  enabled: true\n", "  enabled: true    # aligned note\n");
        put(aligned);
        config.rules.get("a").put("keyword", "changed");

        Throwable thrown = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_PRESENT, "autoreply.rules", "a"));

        assertThat(thrown).isInstanceOf(ConfigWriteRefusedException.class)
                .isNotInstanceOf(ConfigEntryPresenceException.class);
        assertThat(((ConfigWriteRefusedException) thrown).getReason()).contains("line 2");
        assertThat(read()).isEqualTo(aligned);
    }

    @Test
    @DisplayName("a setting written in two forms is refused first (#612); presence is not consulted")
    void twoFormsAreRefusedBeforePresence() throws Exception {
        AutoReply config = loaded(BASE);
        String twice = BASE + "\"autoreply.rules\":\n  z:\n    keyword: z\n    response: z\n";
        put(twice);
        config.rules.put("b", rule("ip", "here"));

        Throwable thrown = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b"));

        assertThat(thrown).isInstanceOf(ConfigWriteRefusedException.class)
                .isNotInstanceOf(ConfigEntryPresenceException.class);
        assertThat(((ConfigWriteRefusedException) thrown).getReason()).contains("two forms");
        assertThat(read()).isEqualTo(twice);
    }

    @Test
    @DisplayName("the flat dotted form is read as the framework reads it: an entry there is present")
    void flatFormEntryIsPresent() throws Exception {
        AutoReply config = loaded(BASE);
        String flat = "autoreply:\n  enabled: true\n  lists:\n    l: [x]\n\"autoreply.rules\":\n  b:\n    keyword: hand\n"
                + "    response: the operator's\n";
        put(flat);
        config.rules.put("b", rule("ip", "here"));

        Throwable thrown = catchThrowable(
                () -> config.saveOperatorMapEntry(EntryPresence.MUST_BE_ABSENT, "autoreply.rules", "b"));

        assertThat(thrown).isInstanceOf(ConfigEntryPresenceException.class);
        assertThat(read()).isEqualTo(flat);
        List<String> lines = new ArrayList<>(Arrays.asList(read().split("\n")));
        assertThat(lines).contains("    keyword: hand");
    }
}
