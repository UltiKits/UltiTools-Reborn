package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * The optimistic concurrency rule of {@code config.update}: a write happens only while the file still
 * holds the bytes the panel read, a refused key never reaches the file, and a whole-request failure
 * answers one of the contract's error codes instead of pretending nothing happened.
 */
class RemoteConfigUpdateTest {

    private static final String PATH = "plugins/UltiTools/pluginConfig/Patch/config.yml";
    private static final String CONFIG = "# Operator note\n"
            + "prefix: \"[Chat]\"\n"
            + "cooldown: 3\n"
            + "enabled: true\n";

    @TempDir
    Path root;
    private Path file;
    private PatchConfig entity;
    private UltiToolsPlugin module;
    private ConfigManager configs;
    private AgentMessageRouter router;
    private RecordingChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        Path folder = root.resolve("plugins").resolve("UltiTools").resolve("pluginConfig").resolve("Patch");
        Files.createDirectories(folder);
        file = folder.resolve("config.yml");
        Files.write(file, CONFIG.getBytes(StandardCharsets.UTF_8));

        module = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(module.getPluginName()).thenReturn("Patch");
        lenient().when(module.getResourceFolderPath()).thenReturn(folder.toString());
        ConfigFileStubs.stubConfigFolder(module, folder.toFile());

        entity = new PatchConfig("config.yml");
        configs = new ConfigManager();
        configs.register(module, entity);

        RemoteConfigHandler handler = new RemoteConfigHandler(
                () -> Collections.singletonList(module), configs, Runnable::run, root.toFile());
        router = new AgentMessageRouter(capability -> true, capability -> "denied");
        handler.register(router);
        channel = new RecordingChannel();
    }

    @Test
    void anOperatorEditSinceTheReadIsNeverOverwritten() throws Exception {
        String readVersion = version();
        Files.write(file, CONFIG.replace("cooldown: 3", "cooldown: 7").getBytes(StandardCharsets.UTF_8));
        byte[] operator = Files.readAllBytes(file);

        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TW1", readVersion,
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("file_changed");
        assertThat(Files.readAllBytes(file)).isEqualTo(operator);
        assertThat(entity.prefix).isEqualTo("[Chat]");
    }

    @Test
    void twoWritesRaceOnTheSameVersionAndOnlyOneLands() throws Exception {
        String readVersion = version();
        // The handler runs its file work inline on each calling thread, so both writes are inside the
        // per-file lock at once: whoever reads second sees the first write's bytes and is refused.
        RemoteConfigHandler racing = new RemoteConfigHandler(
                () -> Collections.singletonList(module), configs, Runnable::run, root.toFile());
        AgentMessageRouter racingRouter = new AgentMessageRouter(capability -> true, capability -> "denied");
        racing.register(racingRouter);
        final CountDownLatch start = new CountDownLatch(1);
        final RecordingChannel[] channels = new RecordingChannel[] {new RecordingChannel(), new RecordingChannel()};
        Thread[] writers = new Thread[2];
        for (int index = 0; index < 2; index++) {
            final int slot = index;
            final String value = index == 0 ? "[A]" : "[B]";
            writers[index] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                racingRouter.dispatch(updateFrame("01JB2Z8Y3K4M5N6P7Q8R9S0TW" + (4 + slot), readVersion,
                        "[{\"key\":\"prefix\",\"value\":\"" + value + "\"}]"), channels[slot]);
            });
            writers[index].start();
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join(TimeUnit.SECONDS.toMillis(30));
        }
        List<String> outcomes = new ArrayList<>();
        for (RecordingChannel own : channels) {
            assertThat(own.frames()).hasSize(1);
            JsonObject payload = own.frames().get(0).getAsJsonObject("payload");
            outcomes.add("ok".equals(payload.get("status").getAsString())
                    ? "ok" : payload.get("code").getAsString());
        }
        assertThat(outcomes).containsExactlyInAnyOrder("ok", "file_changed");
        // Exactly the winner's value is on disk; the refused write left no trace.
        String written = read();
        assertThat(written.contains("prefix: \"[A]\"") ^ written.contains("prefix: \"[B]\"")).isTrue();
        assertThat(written).doesNotContain("prefix: \"[Chat]\"");
    }

    @Test
    void oneRefusedKeyDoesNotStopTheAcceptedOne() throws Exception {
        String readVersion = version();
        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TW6", readVersion,
                "[{\"key\":\"prefix\",\"value\":\"[C]\"},{\"key\":\"cooldown\",\"value\":99}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("partial");
        JsonObject data = payload.getAsJsonObject("data");
        assertThat(data.get("version").getAsString()).isEqualTo(version());
        assertThat(data.get("version").getAsString()).isNotEqualTo(readVersion);
        JsonObject keys = data.getAsJsonArray("keys").get(0).getAsJsonObject();
        JsonObject refused = data.getAsJsonArray("keys").get(1).getAsJsonObject();
        assertThat(keys.get("ok").getAsBoolean()).isTrue();
        assertThat(refused.get("ok").getAsBoolean()).isFalse();
        assertThat(refused.get("message").getAsString()).isNotEmpty();
        assertThat(read()).contains("prefix: \"[C]\"").contains("cooldown: 3");
    }

    @Test
    void whenEveryKeyIsRefusedTheFileAndVersionStayAsTheyWere() throws Exception {
        String readVersion = version();
        byte[] before = Files.readAllBytes(file);
        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TW7", readVersion,
                "[{\"key\":\"cooldown\",\"value\":99},{\"key\":\"unknown\",\"value\":\"x\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("partial");
        JsonObject data = payload.getAsJsonObject("data");
        assertThat(data.get("version").getAsString()).isEqualTo(readVersion);
        assertThat(data.getAsJsonArray("keys")).hasSize(2);
        for (int index = 0; index < 2; index++) {
            assertThat(data.getAsJsonArray("keys").get(index).getAsJsonObject().get("ok").getAsBoolean()).isFalse();
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void aPathOutsideTheRegisteredFilesIsRefused() throws Exception {
        dispatchUpdateOn("server.properties", version(), "[{\"key\":\"prefix\",\"value\":\"x\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("code").getAsString()).isEqualTo("path_not_allowed");
        assertThat(read()).isEqualTo(CONFIG);
    }

    @Test
    void aRegisteredFileDeletedFromDiskIsNotFound() throws Exception {
        String readVersion = version();
        Files.delete(file);
        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TW8", readVersion,
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");

        assertThat(payload("config.update.result").get("code").getAsString()).isEqualTo("not_found");
    }

    @Test
    void aFileWithBrokenYamlIsConfigInvalidForBothRequests() throws Exception {
        Files.write(file, "prefix: [unclosed\n  - x\n".getBytes(StandardCharsets.UTF_8));
        dispatch("config.entries", "01JB2Z8Y3K4M5N6P7Q8R9S0TW9", "{\"path\":\"" + PATH + "\"}");
        assertThat(payload("config.entries.result").get("code").getAsString()).isEqualTo("config_invalid");

        channel.clear();
        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWJ", version(),
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");
        assertThat(payload("config.update.result").get("code").getAsString()).isEqualTo("config_invalid");
    }

    @Test
    void aReadOnlyFileIsWriteDenied() throws Exception {
        String readVersion = version();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        try {
            dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWK", readVersion,
                    "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");
            assertThat(payload("config.update.result").get("code").getAsString()).isEqualTo("write_denied");
            assertThat(read()).isEqualTo(CONFIG);
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void theInvalidUpdateFixturesAreAnsweredWithInvalidMessage() {
        for (ContractFixtures.Fixture fixture : ContractFixtures.invalidFrames("config.update")) {
            channel.clear();
            router.dispatch(fixture.frame().toString(), channel);
            List<JsonObject> errors = framesOfType("error");
            assertThat(errors).as(fixture.path()).hasSize(1);
            JsonObject payload = errors.get(0).getAsJsonObject("payload");
            assertThat(payload.get("code").getAsString()).as(fixture.path()).isEqualTo("invalid_message");
            assertThat(errors.get(0).get("replyTo").getAsString())
                    .isEqualTo(fixture.frame().get("id").getAsString());
        }
    }

    private JsonObject payload(String type) {
        List<JsonObject> frames = framesOfType(type);
        assertThat(frames).as(type).hasSize(1);
        return frames.get(0).getAsJsonObject("payload");
    }

    private List<JsonObject> framesOfType(String type) {
        List<JsonObject> matching = new ArrayList<>();
        for (JsonObject frame : channel.frames()) {
            if (type.equals(frame.get("type").getAsString())) {
                matching.add(frame);
            }
        }
        return matching;
    }

    private String read() {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String version() throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private void dispatchUpdate(String id, String version, String changes) {
        dispatchUpdateOn(PATH, version, changes);
    }

    private void dispatchUpdateOn(String path, String version, String changes) {
        dispatch("config.update", "01JB2Z8Y3K4M5N6P7Q8R9S0TVW", updateBody(path, version, changes));
    }

    private static String updateBody(String path, String version, String changes) {
        return "{\"path\":\"" + path + "\",\"baseVersion\":\"" + version + "\",\"changes\":" + changes + "}";
    }

    private void dispatch(String type, String id, String payload) {
        router.dispatch(frame(type, id, payload), channel);
    }

    private static String updateFrame(String id, String version, String changes) {
        return frame("config.update", id, updateBody(PATH, version, changes));
    }

    private static String frame(String type, String id, String payload) {
        return "{\"v\":2,\"id\":\"" + id + "\",\"type\":\"" + type
                + "\",\"ts\":1761274800000,\"payload\":" + payload + "}";
    }

    /** One module configuration whose two settable entries are a text and a bounded number. */
    @ConfigEntity("config.yml")
    public static final class PatchConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "prefix")
        public String prefix = "[Chat]";
        @ConfigEntry(path = "cooldown")
        @Range(min = 0, max = 60)
        public int cooldown = 3;
        @ConfigEntry(path = "enabled")
        public boolean enabled = true;

        public PatchConfig(String configFilePath) {
            super(configFilePath);
        }
    }
}
