package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * End-to-end reproduction of one panel file read over UltiCloud protocol 2.0.0: the frozen
 * fixture frame goes through {@link AgentMessageRouter} to {@link RemoteFileOperations}, which
 * resolves the path through {@link FileOperationManager#resolveTarget} exactly as the v1 handlers
 * do, and answers with {@code files.read.result}. Every reply is schema-checked by
 * {@link RecordingChannel}, so a frame that does not match the vendored contract fails here.
 * <p>
 * The single editable root is the server root itself, so a request for a name the unconditional
 * deny layer refuses ({@code .ultikits/}, {@code server.properties}, {@code *.jar}) is refused by
 * that layer rather than by the editable-root layer — the refusal this test is about.
 */
@DisplayName("UltiCloud v2 files.read: fixture frame, guard, content version")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the fixture points the manager at a temp tree
class RemoteFilesEndToEndTest {

    private static final String REQUEST_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";

    @TempDir
    Path tmp;

    private FileOperationManager files;
    private RemoteFileOperations handler;
    private AgentMessageRouter router;
    private RecordingChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        Logger logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
        });

        files = new FileOperationManager();
        pointManagerAt(files, tmp, tmp);
        handler = new RemoteFileOperations(files, tmp.toFile());
        router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("files.read", handler);
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("a panel read answers the content, its size and the SHA-256 of the raw bytes")
    void panelReadAnswersContentSizeAndVersion() throws IOException {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\nmotd: hi\n");
        byte[] bytes = Files.readAllBytes(config);

        router.dispatch(readRequest("plugins/UltiTools/config.yml").toString(), channel);

        JsonObject frame = singleFrame();
        assertThat(frame.get("type").getAsString()).isEqualTo("files.read.result");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(REQUEST_ID);
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = payload.getAsJsonObject("data");
        assertThat(data.get("path").getAsString()).isEqualTo("plugins/UltiTools/config.yml");
        assertThat(data.get("content").getAsString()).isEqualTo("enabled: true\nmotd: hi\n");
        assertThat(data.get("sizeBytes").getAsLong()).isEqualTo(bytes.length);
        assertThat(data.get("version").getAsString()).isEqualTo(sha256(bytes));
    }

    @Test
    @DisplayName("non-ASCII content round-trips and the version still covers the raw bytes")
    void nonAsciiContentRoundTrips() throws IOException {
        Path config = write("logs/latest.log", "欢迎回来\n");
        byte[] bytes = Files.readAllBytes(config);

        JsonObject frame = ContractFixtures.object("fixtures/agent/files.read/valid/non-ascii.json");
        frame.getAsJsonObject("payload").addProperty("path", "logs/latest.log");
        router.dispatch(frame.toString(), channel);

        JsonObject data = singleFrame().getAsJsonObject("payload").getAsJsonObject("data");
        assertThat(data.get("content").getAsString()).isEqualTo("欢迎回来\n");
        assertThat(data.get("sizeBytes").getAsLong()).isEqualTo(bytes.length);
        assertThat(data.get("version").getAsString()).isEqualTo(sha256(bytes));
    }

    @Test
    @DisplayName("the credential directory, server.properties and a JAR are refused with the guard's own message")
    void guardedNamesAreRefusedWithTheGuardMessage() throws IOException {
        write(".ultikits/credentials.json", "{\"refresh_token\":\"x\"}\n");
        write("server.properties", "motd=A Minecraft Server\n");
        write("plugins/UltiTools/module.jar", "not really a jar");

        assertRefused(".ultikits/credentials.json");
        assertRefused("server.properties");
        assertRefused("plugins/UltiTools/module.jar");
    }

    @Test
    @DisplayName("a missing file is not_found and a directory is is_directory")
    void missingFileAndDirectoryAnswerTheirOwnCodes() throws IOException {
        Files.createDirectories(tmp.resolve("plugins/UltiTools/lang"));

        assertError("plugins/UltiTools/absent.yml", "not_found");
        assertError("plugins/UltiTools/lang", "is_directory");
    }

    @Test
    @DisplayName("a file over the negotiated limit, invalid UTF-8 or a control character is file_not_editable")
    void fileContentRuleIsEnforcedOnTheRawBytes() throws IOException {
        RecordingChannel small = new RecordingChannel();
        com.ultikits.ultitools.websocket.CloudLimits tiny = com.ultikits.ultitools.websocket.CloudLimits.fromWelcome(
                welcome(1024));
        small.setLimits(tiny);
        write("plugins/UltiTools/large.yml", repeat('a', 2048));

        router.dispatch(readRequest("plugins/UltiTools/large.yml").toString(), small);

        JsonObject frame = only(small);
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("file_not_editable");

        Files.write(tmp.resolve("plugins/UltiTools/latin1.yml"), new byte[]{'a', (byte) 0xE9, '\n'});
        assertError("plugins/UltiTools/latin1.yml", "file_not_editable");

        Files.write(tmp.resolve("plugins/UltiTools/control.yml"), new byte[]{'a', 0x00, 'b'});
        assertError("plugins/UltiTools/control.yml", "file_not_editable");
    }

    @Test
    @DisplayName("every invalid files.read fixture is answered invalid_message and nothing is read")
    void invalidFixturesAreRejectedBeforeAnyFilesystemAccess() throws IOException {
        write("secret.txt", "OUTSIDE-SENTINEL\n");
        List<ContractFixtures.Fixture> invalid = ContractFixtures.invalidFrames("files.read");
        assertThat(invalid).isNotEmpty();

        for (ContractFixtures.Fixture fixture : invalid) {
            channel.clear();
            router.dispatch(fixture.frame().toString(), channel);
            JsonObject frame = only(channel);
            assertThat(frame.get("type").getAsString()).as(fixture.path()).isEqualTo("error");
            assertThat(frame.getAsJsonObject("payload").get("code").getAsString())
                    .as(fixture.path()).isEqualTo("invalid_message");
            assertThat(frame.get("replyTo").getAsString())
                    .as(fixture.path()).isEqualTo(fixture.frame().get("id").getAsString());
        }
    }

    private void assertRefused(String path) {
        channel.clear();
        router.dispatch(readRequest(path).toString(), channel);
        JsonObject frame = only(channel);
        assertThat(frame.get("type").getAsString()).isEqualTo("files.read.result");
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("path_not_allowed");
        assertThat(payload.get("message").getAsString())
                .isEqualTo(files.isPathAllowed(path).getMessage());
    }

    private void assertError(String path, String code) {
        channel.clear();
        router.dispatch(readRequest(path).toString(), channel);
        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo(code);
    }

    private JsonObject singleFrame() {
        return only(channel);
    }

    private static JsonObject only(RecordingChannel recorded) {
        assertThat(recorded.frames()).hasSize(1);
        return recorded.frames().get(0);
    }

    private static JsonObject readRequest(String path) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.read/valid/config.json");
        frame.getAsJsonObject("payload").addProperty("path", path);
        return frame;
    }

    private static JsonObject welcome(int fileMaxBytes) {
        JsonObject limits = new JsonObject();
        limits.addProperty("maxFrameBytes", 1048576);
        JsonObject log = new JsonObject();
        log.addProperty("ratePerSec", 50);
        log.addProperty("burst", 200);
        log.addProperty("lineMaxBytes", 8192);
        log.addProperty("batchMaxLines", 100);
        limits.add("log", log);
        limits.addProperty("outboxMax", 1000);
        limits.addProperty("commandDedupeMax", 1000);
        limits.addProperty("commandDedupeTtlSeconds", 86400);
        limits.addProperty("commandOutputMaxBytes", 65536);
        limits.addProperty("fileMaxBytes", fileMaxBytes);
        return limits;
    }

    private Path write(String relative, String content) throws IOException {
        Path target = tmp.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(character);
        }
        return text.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unattainable) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", unattainable);
        }
    }

    private static void pointManagerAt(FileOperationManager manager, Path serverRoot, Path editableRoot)
            throws IllegalAccessException, NoSuchFieldException {
        Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
        serverRootField.setAccessible(true);
        serverRootField.set(manager, serverRoot.toFile());

        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(manager, Collections.singletonList(editableRoot.toFile()));
    }
}
