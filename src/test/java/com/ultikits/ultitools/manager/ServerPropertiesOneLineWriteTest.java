package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * A panel edit of one {@code server.properties} key changes exactly the one line that defines it (inventory B4,
 * UltiKits/UltiTools-Reborn#607; maintainer decision of 2026-10-04 "what code may write, by file type": a panel
 * edit writes only the named key). Every other line - comments, key order, separators, other values, a UTF-8
 * {@code motd} - keeps its bytes, the value is encoded so that the server's own reader returns it exactly, an
 * ambiguous definition is refused, and the write is atomic.
 * <p>
 * The server's reader is reproduced by {@link #readLikePaper(byte[])}: measured from the Paper 1.21.11 jar
 * ({@code net.minecraft.server.dedicated.Settings#loadFromFile}): {@code Properties#load(Reader)} over a strict UTF-8
 * decoder, and on a {@link CharacterCodingException} the same load over ISO-8859-1.
 */
class ServerPropertiesOneLineWriteTest {

    private static final String UTF8_FILE = "#Minecraft server properties\n"
            + "#Thu Oct 01 12:00:00 UTC 2026\n"
            + "# operator note: keep pvp on for the event\n"
            + "view-distance=10\n"
            + "motd=§a欢迎\n"
            + "max-players=20\n"
            + "pvp=true\n"
            + "allow-nether=true\n";

    @TempDir
    Path directory;

    private Path file;
    private ServerPropertiesManager manager;
    private UltiPanelWebSocketClient socket;
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
            // Records are kept in memory; there is nothing to flush.
        }

        @Override
        public void close() {
            // No resource is held; there is nothing to close.
        }
    };

    @BeforeEach
    void setUp() {
        file = directory.resolve("server.properties");
        manager = new ServerPropertiesManager(directory.toFile());
        socket = mock(UltiPanelWebSocketClient.class);
        lenient().when(socket.getServerId()).thenReturn("srv-1");
        manager.setWebSocketClient(socket);
        Logger.getLogger(ServerPropertiesManager.class.getName()).addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(ServerPropertiesManager.class.getName()).removeHandler(capture);
    }

    @Test
    void panelSetChangesOnlyTheLineOfTheNamedKeyAndUtf8ValuesStayReadable() throws IOException {
        byte[] original = UTF8_FILE.getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        JsonObject reply = set("max-players", "30");

        assertThat(reply.get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(original, Files.readAllBytes(file), 5, "max-players=30\n");
        assertThat(readLikePaper(Files.readAllBytes(file)).getProperty("max-players")).isEqualTo("30");
        assertThat(readLikePaper(Files.readAllBytes(file)).getProperty("motd")).isEqualTo("§a欢迎");
        assertThat(manager.getSafeProperties().get("motd")).as("the panel reads what the server reads")
                .isEqualTo("§a欢迎");
    }

    @Test
    void separatorsCommentsAndEscapedKeysAsWrittenAreKept() throws IOException {
        String text = "! bang comment\r\n"
                + "max\\-players : 20\r\n"
                + "view-distance\t12\r\n"
                + "   pvp = true\r\n"
                + "motd=last line without a break";
        byte[] original = text.getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        assertThat(set("max-players", "31").get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(original, Files.readAllBytes(file), 1, "max\\-players : 31\r\n");

        byte[] second = Files.readAllBytes(file);
        assertThat(set("view-distance", "8").get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(second, Files.readAllBytes(file), 2, "view-distance\t8\r\n");

        byte[] third = Files.readAllBytes(file);
        assertThat(set("pvp", "false").get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(third, Files.readAllBytes(file), 3, "   pvp = false\r\n");

        byte[] fourth = Files.readAllBytes(file);
        assertThat(set("motd", "new").get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(fourth, Files.readAllBytes(file), 4, "motd=new");
    }

    @Test
    void valuesNeedingEscapesAreReadBackExactlyByTheServer() throws IOException {
        Files.write(file, UTF8_FILE.getBytes(StandardCharsets.UTF_8));
        String value = " lead\\back#hash!bang=eq:colon\ttab §b你好";

        assertThat(set("motd", value).get("success").getAsBoolean()).isTrue();

        assertThat(readLikePaper(Files.readAllBytes(file)).getProperty("motd")).isEqualTo(value);
        assertThat(manager.getSafeProperties().get("motd")).isEqualTo(value);
        assertThat(readLikePaper(Files.readAllBytes(file)).getProperty("max-players")).isEqualTo("20");
    }

    @Test
    void latin1FileKeepsItsBytesAndANewNonLatinValueIsEscaped() throws IOException {
        byte[] original = ("motd=café\n" + "max-players=20\n").getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file, original);

        assertThat(set("max-players", "25").get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(original, Files.readAllBytes(file), 1, "max-players=25\n");
        assertThat(manager.getSafeProperties().get("motd")).isEqualTo("café");

        assertThat(set("motd", "欢迎").get("success").getAsBoolean()).isTrue();
        byte[] after = Files.readAllBytes(file);
        assertThat(readLikePaper(after).getProperty("motd")).isEqualTo("欢迎");
        assertThat(new String(after, StandardCharsets.ISO_8859_1)).startsWith("motd=\\u6B22\\u8FCE\n");
    }

    @Test
    void aKeyDefinedTwiceIsRefusedWithItsReasonAndTheFileIsUnchanged() throws IOException {
        byte[] original = ("max-players=20\n# a note\nmax-players=40\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        JsonObject reply = set("max-players", "30");

        assertThat(reply.get("success").getAsBoolean()).isFalse();
        assertThat(reply.get("reason").getAsString()).contains("more than one line").contains("1").contains("3")
                .doesNotContain("20").doesNotContain("40").doesNotContain("30");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("max-players").contains("more than one line")
                .doesNotContain("=30");
    }

    @Test
    void aDefinitionContinuedOntoTheNextLineIsRefusedAndTheFileIsUnchanged() throws IOException {
        byte[] original = ("motd=hello \\\n    world\nmax-players=20\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        JsonObject reply = set("motd", "plain");

        assertThat(reply.get("success").getAsBoolean()).isFalse();
        assertThat(reply.get("reason").getAsString()).contains("continues onto the next line").contains("1");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);

        // A key inside another key's continued value is not a definition of that key.
        byte[] tricky = ("motd=a \\\nmax-players=5\nmax-players=20\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, tricky);
        assertThat(set("max-players", "30").get("success").getAsBoolean()).isTrue();
        assertOnlyLineChanged(tricky, Files.readAllBytes(file), 2, "max-players=30\n");
    }

    @Test
    void aSetAllBatchChangesOnlyTheNamedLinesAndNamesARefusalReason() throws IOException {
        byte[] original = ("# keep me\nmax-players=20\nview-distance=10\nview-distance=11\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        JsonObject values = new JsonObject();
        values.addProperty("max-players", "30");
        values.addProperty("view-distance", "6");

        ServerPropertiesManager.SetAllResult result = manager.applySetAll(values);

        assertThat(result.getUpdated()).containsExactly("max-players");
        assertThat(result.getFailed()).containsExactly("view-distance");
        assertThat(result.describeFailure()).contains("view-distance").contains("more than one line");
        assertOnlyLineChanged(original, Files.readAllBytes(file), 1, "max-players=30\n");
    }

    @Test
    void aForcedAtomicMoveFailureLeavesTheOriginalOrACompleteFrameworkBackupNeverAHalfFile() throws Exception {
        byte[] original = UTF8_FILE.getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        // Constant framework nested-interface lookup; no user-controlled class name.
        // nosemgrep: java_lang_security_audit_unsafe-reflection_unsafe-reflection, java.lang.security.audit.unsafe-reflection.unsafe-reflection
        Class<?> operations = Class.forName(AtomicConfigWriter.class.getName() + "$FileOperations");
        List<String> seen = new ArrayList<>();
        Object files = mock(operations, invocation -> {
            String name = invocation.getMethod().getName();
            if ("move".equals(name)) {
                Path destination = invocation.getArgument(1);
                if (!destination.getFileName().toString().contains(".ultitools-backup-")) {
                    seen.add("move");
                    throw new AtomicMoveNotSupportedException("source", destination.toString(), "injected");
                }
            }
            if ("openTarget".equals(name)) {
                seen.add("openTarget");
                throw new IOException("injected target-open failure");
            }
            return invocation.callRealMethod();
        });
        Method seam = findStageWithOperations(operations);
        try (MockedStatic<AtomicConfigWriter> writer = Mockito.mockStatic(AtomicConfigWriter.class, invocation -> {
            Method called = invocation.getMethod();
            if ("stage".equals(called.getName()) && called.getParameterCount() == 2
                    && called.getParameterTypes()[1] == byte[].class) {
                return invoke(seam, invocation.getArgument(0), invocation.getArgument(1), files);
            }
            return invocation.callRealMethod();
        })) {
            JsonObject reply = set("max-players", "30");
            assertThat(reply.get("success").getAsBoolean()).isFalse();
        }

        assertThat(seen).as("the write went through the atomic config writer").contains("move", "openTarget");
        byte[] now = Files.readAllBytes(file);
        List<Path> backups;
        try (Stream<Path> listed = Files.list(directory)) {
            backups = listed.filter(path -> path.getFileName().toString()
                    .matches("server\\.properties\\.ultitools-backup-[0-9a-f]{16}")).collect(Collectors.toList());
        }
        boolean originalIntact = java.util.Arrays.equals(now, original);
        boolean completeBackup = backups.size() == 1 && java.util.Arrays.equals(Files.readAllBytes(backups.get(0)), original);
        assertThat(originalIntact || completeBackup).as("original intact or a complete framework backup").isTrue();
        try (Stream<Path> listed = Files.list(directory)) {
            assertThat(listed.map(path -> path.getFileName().toString()).filter(name -> name.contains(".tmp-")))
                    .as("no temporary file left").isEmpty();
        }
    }

    private static Method findStageWithOperations(Class<?> operations) throws NoSuchMethodException {
        Method method = AtomicConfigWriter.class.getDeclaredMethod("stage", Path.class, byte[].class, operations);
        trustAccess(method);
        return method;
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void trustAccess(Method method) {
        // The package-private fault seam of the atomic writer; the test injects a refused move through it.
        method.setAccessible(true);
    }

    private static Object invoke(Method method, Object target, Object data, Object files) throws Throwable {
        try {
            return method.invoke(null, target, data, files);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private JsonObject set(String key, String value) {
        Mockito.clearInvocations(socket);
        JsonObject request = new JsonObject();
        request.addProperty("action", "set");
        request.addProperty("key", key);
        request.addProperty("value", value);
        manager.handleServerProperties(request);
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(socket).sendMessage(captor.capture());
        return captor.getValue();
    }

    /** Paper 1.21.11 {@code Settings#loadFromFile}: strict UTF-8, falling back to ISO-8859-1. */
    static Properties readLikePaper(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        try {
            properties.load(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
            return properties;
        } catch (CharacterCodingException notUtf8) {
            Properties fallback = new Properties();
            fallback.load(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.ISO_8859_1));
            return fallback;
        }
    }

    /** Asserts that {@code after} equals {@code before} line by line, as bytes, except line {@code index} (0-based). */
    private static void assertOnlyLineChanged(byte[] before, byte[] after, int index, String expectedLine) {
        List<byte[]> left = lines(before);
        List<byte[]> right = lines(after);
        assertThat(right).as("line count").hasSize(left.size());
        for (int i = 0; i < left.size(); i++) {
            if (i == index) {
                assertThat(new String(right.get(i), StandardCharsets.ISO_8859_1))
                        .isEqualTo(new String(expectedLine.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
            } else {
                assertThat(right.get(i)).as("line %d keeps its bytes", i + 1).isEqualTo(left.get(i));
            }
        }
    }

    private static List<byte[]> lines(byte[] bytes) {
        List<byte[]> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            boolean crlf = bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n';
            if (crlf) {
                i++;
            }
            if (bytes[i] == '\n' || bytes[i] == '\r') {
                result.add(java.util.Arrays.copyOfRange(bytes, start, i + 1));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            result.add(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return result;
    }
}
