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
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #620 tracer (plan 17-75 Task 1): the operator resets a setting by deleting its key, and the key was the only one of
 * its section, so the section key is left with no value ({@code messages:}). Maintainer decision 2026-10-06 (row 01:05):
 * deleting every child is most likely accidental, so the framework puts the key back under that line on the next start.
 * The proof obligation: every line outside the section line and the inserted key's own lines stays byte-identical
 * (terminators included), and a second start writes nothing.
 */
class NullSectionExpansionTracerTest {

    private static final String PATH = "mail.yml";
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

    /** UltiMail's shape: a section holding one setting, and a top-level setting beside it. */
    @ConfigEntity(PATH)
    public static class Mail extends AbstractConfigEntity {
        @ConfigEntry(path = "other", comment = "Other setting")
        public int other = 1;
        @ConfigEntry(path = "messages.mail-received", comment = NOTICE)
        public String mailReceived = "You received a new mail";

        public Mail(String path) {
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
        lenient().when(plugin.getPluginName()).thenReturn("MailModule");
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

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private List<String> warningsNamingTheFile() {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (String warning : warnings) {
                if (warning.contains(PATH)) {
                    result.add(warning);
                }
            }
        }
        return result;
    }

    @Test
    void theDeletedOnlyKeyIsRestoredUnderTheEmptySectionLineAndNothingElseMoves() throws Exception {
        String deleted = "# Mail settings for the lobby (written by hand)\n"
                + "\n"
                + "# Other setting\n"
                + "other: 1\n"
                + "\n"
                + "messages:\n"
                + "\n"
                + "# A note the operator keeps at the end\n";
        Files.write(file(), deleted.getBytes(StandardCharsets.UTF_8));

        Mail first = new Mail(PATH);
        first.init(plugin);

        String restored = text();
        assertThat(restored).isEqualTo("# Mail settings for the lobby (written by hand)\n"
                + "\n"
                + "# Other setting\n"
                + "other: 1\n"
                + "\n"
                + "messages:\n"
                + "  # " + NOTICE + "\n"
                + "  mail-received: You received a new mail\n"
                + "\n"
                + "# A note the operator keeps at the end\n");
        LineDiff diff = LineDiff.of(deleted, restored);
        assertThat(diff.removed).as("no operator line is removed or changed").isEmpty();
        assertThat(diff.added).containsExactly("  # " + NOTICE + "\n", "  mail-received: You received a new mail\n");
        assertThat(diff.addedAt).as("the inserted lines sit directly below the section line").containsExactly(6, 7);
        assertThat(warningsNamingTheFile()).as("no refusal").isEmpty();
        assertThat(first.mailReceived).isEqualTo("You received a new mail");

        Mail second = new Mail(PATH);
        second.init(plugin);
        assertThat(text()).as("a second start changes nothing").isEqualTo(restored);
        assertThat(warningsNamingTheFile()).as("no refusal on the second start").isEmpty();
    }

    @Test
    void aTrailingCommentOnTheSectionLineIsKeptAndOnlyThatLineMayChange() throws Exception {
        String deleted = "other: 1\r\n"
                + "messages:   # reset to default\r\n"
                + "# end note\r\n";
        Files.write(file(), deleted.getBytes(StandardCharsets.UTF_8));

        new Mail(PATH).init(plugin);

        String restored = text();
        List<String> before = OperatorFileWriter.lines(deleted);
        List<String> after = OperatorFileWriter.lines(restored);
        assertThat(after).hasSize(5);
        assertThat(after.get(0)).isEqualTo(before.get(0));
        assertThat(after.get(1)).as("the key text and the operator's comment are kept on the section line")
                .startsWith("messages:").endsWith("# reset to default\r\n");
        assertThat(after.get(2)).isEqualTo("  # " + NOTICE + "\r\n");
        assertThat(after.get(3)).isEqualTo("  mail-received: You received a new mail\r\n");
        assertThat(after.get(4)).isEqualTo(before.get(2));
        assertThat(warningsNamingTheFile()).as("no refusal").isEmpty();

        new Mail(PATH).init(plugin);
        assertThat(text()).as("a second start changes nothing").isEqualTo(restored);
        assertThat(warningsNamingTheFile()).isEmpty();
    }
}
