package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #592, measured on UltiMail's {@code mail.yml}: the documented way to reset a setting is to delete its key and
 * restart. For eleven of UltiMail's settings the framework re-inserts the key at the end of its section - right after
 * a multi-line block scalar - with its comment at the key's column. Before the fix SnakeYAML read that comment as the
 * block scalar's own, so every later gated write to the file was refused (start-up comment refresh, the module's save,
 * the language switch). The entity below declares UltiMail's settings in UltiMail's order with its English comments;
 * the fixture is the file a fresh install writes.
 */
class ConfigBlockScalarDeleteToResetTest {

    private static final String PATH = "mail.yml";
    private static final String FIXTURE = "/config-592/ultimail-mail.yml";
    private static final String SWITCHED = " (second language)";
    private static final Map<String, String> COMMENTS = new LinkedHashMap<>();

    static {
        COMMENTS.put("max-items", "Maximum number of items attached to one mail");
        COMMENTS.put("notify-on-join", "Notify players of unread mail when they log in");
        COMMENTS.put("notify-delay", "Delay before the login notification (seconds)");
        COMMENTS.put("max-subject-length", "Maximum length of a mail subject");
        COMMENTS.put("max-content-length", "Maximum length of a mail's content");
        COMMENTS.put("send-cooldown", "Cooldown between sending mails (seconds)");
        COMMENTS.put("messages.mail-received", "Notice shown when a new mail is received");
        COMMENTS.put("recall.server-name", "Server name shown in recall mails");
        COMMENTS.put("recall.subject", "Subject of the in-game recall mail");
        COMMENTS.put("recall.content", "Content of the in-game recall mail");
        COMMENTS.put("email.enabled", "Whether real email sending is enabled");
        COMMENTS.put("email.smtp-host", "SMTP server address");
        COMMENTS.put("email.smtp-port", "SMTP port");
        COMMENTS.put("email.smtp-username", "SMTP username");
        COMMENTS.put("email.smtp-password", "SMTP password");
        COMMENTS.put("email.smtp-from-email", "Sender email address");
        COMMENTS.put("email.smtp-ssl", "Whether to use SSL encryption");
        COMMENTS.put("email.smtp-starttls", "Whether to use STARTTLS encryption");
        COMMENTS.put("email.recall-subject", "Subject of the recall email");
        COMMENTS.put("email.recall-content", "Content of the recall email");
    }

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private boolean switched;
    private final List<LogRecord> warnings = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Logger frameworkLogger = Logger.getLogger("com.ultikits.ultitools");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
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

    /** UltiMail's {@code MailConfig} settings, in its declared order, with English defaults. */
    @ConfigEntity(PATH)
    public static class Mail extends AbstractConfigEntity {
        @ConfigEntry(path = "max-items", comment = "{max-items}")
        int maxItems = 27;
        @ConfigEntry(path = "notify-on-join", comment = "{notify-on-join}")
        boolean notifyOnJoin = true;
        @ConfigEntry(path = "notify-delay", comment = "{notify-delay}")
        int notifyDelay = 3;
        @ConfigEntry(path = "max-subject-length", comment = "{max-subject-length}")
        int maxSubjectLength = 50;
        @ConfigEntry(path = "max-content-length", comment = "{max-content-length}")
        int maxContentLength = 500;
        @ConfigEntry(path = "send-cooldown", comment = "{send-cooldown}")
        int sendCooldown = 10;
        @ConfigEntry(path = "messages.mail-received", comment = "{messages.mail-received}")
        String mailReceived = "&e[Mail] &fYou received a new mail from &a{SENDER}&f!";
        @ConfigEntry(path = "recall.server-name", comment = "{recall.server-name}")
        String serverName = "Minecraft Server";
        @ConfigEntry(path = "recall.subject", comment = "{recall.subject}")
        String recallSubject = "[{SERVER}] Come back to us";
        @ConfigEntry(path = "recall.content", comment = "{recall.content}")
        String recallContent = "Dear player, {SERVER} misses you!\n\nCome back and take a look, we look forward to seeing you again!"
                + "\n\nSender: {SENDER}";
        @ConfigEntry(path = "email.enabled", comment = "{email.enabled}")
        boolean emailEnabled = false;
        @ConfigEntry(path = "email.smtp-host", comment = "{email.smtp-host}")
        String smtpHost = "smtp.example.com";
        @ConfigEntry(path = "email.smtp-port", comment = "{email.smtp-port}")
        int smtpPort = 587;
        @ConfigEntry(path = "email.smtp-username", comment = "{email.smtp-username}")
        String smtpUsername = "";
        @ConfigEntry(path = "email.smtp-password", comment = "{email.smtp-password}")
        String smtpPassword = "";
        @ConfigEntry(path = "email.smtp-from-email", comment = "{email.smtp-from-email}")
        String smtpFromEmail = "noreply@example.com";
        @ConfigEntry(path = "email.smtp-ssl", comment = "{email.smtp-ssl}")
        boolean smtpSsl = false;
        @ConfigEntry(path = "email.smtp-starttls", comment = "{email.smtp-starttls}")
        boolean smtpStartTls = true;
        @ConfigEntry(path = "email.recall-subject", comment = "{email.recall-subject}")
        String recallEmailSubject = "[{SERVER}] We miss you!";
        @ConfigEntry(path = "email.recall-content", comment = "{email.recall-content}")
        String recallEmailContent = "Dear {PLAYER},\n\nThe {SERVER} server misses you! Come back and take a look, we look forward to "
                + "seeing you again!\n\nSender: {SENDER}";

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
        lenient().when(plugin.i18n(anyString())).thenAnswer(i -> {
            String english = COMMENTS.get(i.<String>getArgument(0));
            return english == null ? i.getArgument(0) : english + (switched ? SWITCHED : "");
        });
        // The module's jar ships both catalogues, so a comment in either language is the framework's (#604).
        lenient().when(plugin.shippedCatalogueTexts(anyString())).thenAnswer(i -> {
            String english = COMMENTS.get(i.<String>getArgument(0));
            return english == null ? Collections.emptyList() : Arrays.asList(english, english + SWITCHED);
        });
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

    private static String fixture() throws IOException {
        try (InputStream in = ConfigBlockScalarDeleteToResetTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture " + FIXTURE).isNotNull();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The fixture without {@code dottedPath}'s key line and its comment line (the operator's documented reset). */
    private static String withoutKey(String text, String dottedPath) {
        String key = dottedPath.substring(dottedPath.lastIndexOf('.') + 1);
        String indent = dottedPath.contains(".") ? "  " : "";
        String removed = text.replace(indent + "# " + COMMENTS.get(dottedPath) + "\n", "");
        Matcher line = Pattern.compile("(?m)^" + Pattern.quote(indent + key + ":") + " .*\n").matcher(removed);
        assertThat(line.find()).as("key line of " + dottedPath).isTrue();
        return removed.substring(0, line.start()) + removed.substring(line.end());
    }

    private static int count(String text, String line) {
        int found = 0;
        for (String each : text.split("\n", -1)) {
            if (each.equals(line)) {
                found++;
            }
        }
        return found;
    }

    private List<String> warningsNamingTheFile() {
        List<String> result = new ArrayList<>();
        synchronized (warnings) {
            for (LogRecord record : warnings) {
                if (record.getMessage() != null && record.getMessage().contains(PATH)) {
                    result.add(record.getMessage());
                }
            }
        }
        return result;
    }

    @Test
    void freshInstallWritesTheFixture() throws Exception {
        new Mail(PATH).init(plugin);

        assertThat(text()).isEqualTo(fixture());
        assertThat(warningsNamingTheFile()).isEmpty();
    }

    /**
     * The eleven settings whose key is re-inserted right after a block scalar (measurement of 2026-10-05,
     * {@code 17-592-layout-measurement.md}). Each step is one of the gated writes the measurement found refused.
     */
    @ParameterizedTest(name = "delete {0}, restart")
    @ValueSource(strings = {"recall.server-name", "recall.subject", "email.enabled", "email.smtp-host", "email.smtp-port",
            "email.smtp-username", "email.smtp-password", "email.smtp-from-email", "email.smtp-ssl", "email.smtp-starttls",
            "email.recall-subject"})
    void deleteToResetAfterABlockScalarLeavesTheFileWritable(String deleted) throws Exception {
        String indent = deleted.contains(".") ? "  " : "";
        String comment = indent + "# " + COMMENTS.get(deleted);
        Files.write(file(), withoutKey(fixture(), deleted).getBytes(StandardCharsets.UTF_8));

        Mail first = new Mail(PATH);
        first.init(plugin);
        String reset = text();
        assertThat(count(reset, comment)).as("start 1 writes the comment once").isEqualTo(1);
        assertThat(warningsNamingTheFile()).as("start 1").isEmpty();

        Mail second = new Mail(PATH);
        second.init(plugin);
        assertThat(text()).as("start 2 writes nothing").isEqualTo(reset);
        assertThat(warningsNamingTheFile()).as("start 2").isEmpty();

        second.maxItems = 30;
        second.save();
        assertThat(text()).as("the module's save is written").contains("\nmax-items: 30\n");
        assertThat(count(text(), comment)).isEqualTo(1);
        assertThat(warningsNamingTheFile()).as("the save").isEmpty();

        switched = true;
        second.reload();
        String afterSwitch = text();
        assertThat(count(afterSwitch, comment)).as("the old-language comment is gone").isZero();
        assertThat(count(afterSwitch, comment + SWITCHED)).as("the language switch rewrites it once").isEqualTo(1);
        for (Map.Entry<String, String> entry : COMMENTS.entrySet()) {
            String each = (entry.getKey().contains(".") ? "  # " : "# ") + entry.getValue() + SWITCHED;
            assertThat(count(afterSwitch, each)).as(entry.getKey()).isEqualTo(1);
        }
        assertThat(warningsNamingTheFile()).as("the language switch").isEmpty();

        Mail third = new Mail(PATH);
        third.init(plugin);
        assertThat(text()).as("a start after the switch writes nothing").isEqualTo(afterSwitch);
        third.smtpPort = 2525;
        third.save();
        assertThat(text()).as("a save after the switch is written").contains("\n  smtp-port: 2525\n");
        assertThat(warningsNamingTheFile()).as("after the switch").isEmpty();
    }
}
