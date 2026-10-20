package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #610 item 2 (plan 17-75 Task 2): a section written as an explicit empty value - {@code {}}, {@code ~} or {@code null} -
 * is the operator's own value, which the framework never reinterprets as "left empty" (#620 expands only a section line
 * with no value at all). An insert under it is refused, and the refusal now tells the operator what to change: it names
 * the file, the section and its line, says the section is written as an explicit empty value the framework does not
 * change, and offers the two ways out (delete the value so the line ends after the colon, or add the keys by hand) -
 * never printing the value. Before the fix the reasons were "a key this write owns shares line N with a key it does not
 * own" ({@code {}}) and "the write would change keys it does not own" ({@code ~}, {@code null}).
 */
class EmptySectionRefusalWordingTest {

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
        @ConfigEntry(path = "messages.mail-received", comment = "Notice shown when a new mail is received")
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
        lenient().when(plugin.getPluginName()).thenReturn("WordingModule");
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

    private List<String> warningsNaming(String file) {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (String warning : warnings) {
                if (warning.contains(file)) {
                    result.add(warning);
                }
            }
        }
        return result;
    }

    @ParameterizedTest(name = "messages: {0}")
    @ValueSource(strings = {"{}", "~", "null"})
    void aStartUpInsertUnderAnExplicitlyEmptySectionIsRefusedWithWhatToChange(String empty) throws Exception {
        Path file = tempDir.resolve("mail.yml");
        byte[] before = ("other: 1\nmessages: " + empty + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);

        new Mail("mail.yml").init(plugin);

        assertThat(Files.readAllBytes(file)).as("the operator's value is kept").isEqualTo(before);
        List<String> named = warningsNaming(file.toAbsolutePath().toString());
        assertThat(named).as("one warning per start").hasSize(1);
        String warning = named.get(0);
        assertThat(warning).contains("was not written: the section messages (line 2) is written as an explicit empty"
                + " value, which the framework does not change; to have keys added below it, delete that value so the line"
                + " ends after the colon, or add the keys by hand")
                .contains("Keys this write would have changed: messages.mail-received");
        String reason = warning.substring(warning.indexOf("was not written:"), warning.indexOf(". Keys this write"));
        assertThat(reason).as("the value itself is never printed").doesNotContain(empty);

        new Mail("mail.yml").init(plugin);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(warningsNaming(file.toAbsolutePath().toString())).as("one warning at the next start").hasSize(2);
    }

    @Test
    void anOperatorsMapEntryCommandUnderAnExplicitlyEmptyRulesValueIsRefusedWithWhatToChange() throws Exception {
        Path file = tempDir.resolve("autoreply.yml");
        byte[] before = "autoreply:\n  enabled: true\n  rules: ~\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        Rules config = new Rules("autoreply.yml");
        config.init(plugin);

        config.rules = new LinkedHashMap<>();
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("keyword", "hi");
        rule.put("response", "hello");
        config.rules.put("greeting", rule);

        assertThatThrownBy(() -> config.saveOperatorMapEntry("autoreply.rules", "greeting"))
                .isInstanceOf(ConfigWriteRefusedException.class)
                .hasMessageContaining("the section autoreply.rules (line 3) is written as an explicit empty value");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void aSectionLeftWithNoValueIsNotRefused() throws Exception {
        Path file = tempDir.resolve("mail.yml");
        Files.write(file, "other: 1\nmessages:\n".getBytes(StandardCharsets.UTF_8));

        new Mail("mail.yml").init(plugin);

        assertThat(warningsNaming(file.toAbsolutePath().toString())).as("control: the #620 shape is expanded").isEmpty();
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("  mail-received: ");
    }
}
