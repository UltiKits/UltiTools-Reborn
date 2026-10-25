package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Pattern;
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
            + "enabled: true\n"
            + "mode: fast\n"
            + "code: abc\n";

    @TempDir
    Path root;
    private Path folder;
    private Path file;
    private PatchConfig entity;
    private UltiToolsPlugin module;
    private ConfigManager configs;
    private AgentMessageRouter router;
    private RecordingChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        folder = root.resolve("plugins").resolve("UltiTools").resolve("pluginConfig").resolve("Patch");
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

    @Test
    void aWriteTheModuleCouldNotReloadIsAnsweredOkSayingSo() throws Exception {
        // The operator hand-edited another key of this file to a value its @Range refuses and did not
        // reload: the write is correct, but the module cannot take the new values until the file is fixed.
        Files.write(file, CONFIG.replace("cooldown: 3", "cooldown: 99").getBytes(StandardCharsets.UTF_8));
        String readVersion = version();

        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWM", readVersion,
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.get("message").getAsString())
                .as("the panel is told the module kept its previous values, naming the file and the cause")
                .contains("config.yml").contains("ConfigurationException")
                .doesNotContain("99").doesNotContain("[C]");
        assertThat(payload.getAsJsonObject("data").getAsJsonArray("keys").get(0).getAsJsonObject()
                .get("ok").getAsBoolean()).isTrue();
        assertThat(payload.getAsJsonObject("data").get("version").getAsString()).isEqualTo(version());
        assertThat(read()).contains("prefix: \"[C]\"").contains("cooldown: 99");
        assertThat(entity.prefix).as("the module keeps running its previous values").isEqualTo("[Chat]");
    }

    @Test
    void onlyTheEditedFilesEntityIsReloaded() throws Exception {
        Path other = folder.resolve("other.yml");
        Files.write(other, "value: ok\n".getBytes(StandardCharsets.UTF_8));
        configs.register(module, new OtherConfig("other.yml"));
        // A file of the same module this write never touches is broken on disk: reloading the whole
        // module would stop at it and report a failure the edited file does not have.
        Files.write(other, "value: [unclosed\n  - x\n".getBytes(StandardCharsets.UTF_8));

        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWN", version(),
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.has("message")).as("an unrelated broken file is not a reload failure").isFalse();
        assertThat(entity.prefix).isEqualTo("[C]");
        assertThat(read()).contains("prefix: \"[C]\"");
    }

    @Test
    void aValueOfTheWrongTypeOrOutsideTheEnumIsRefusedAtTheHandler() throws Exception {
        byte[] before = Files.readAllBytes(file);

        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWP", version(),
                "[{\"key\":\"cooldown\",\"value\":\"three\"},{\"key\":\"mode\",\"value\":\"turbo\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("partial");
        JsonArray keys = payload.getAsJsonObject("data").getAsJsonArray("keys");
        assertThat(keys.get(0).getAsJsonObject().get("message").getAsString()).isEqualTo("expected a whole number");
        assertThat(keys.get(1).getAsJsonObject().get("message").getAsString()).isEqualTo("expected one of: fast, slow");
        assertThat(keys.get(0).getAsJsonObject().get("ok").getAsBoolean()).isFalse();
        assertThat(keys.get(1).getAsJsonObject().get("ok").getAsBoolean()).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(entity.mode).isEqualTo(Mode.fast);
    }

    @Test
    void aValidatorOnlyRefusalIsValueFreeAndLeavesTheFileAndTheModule() throws Exception {
        byte[] before = Files.readAllBytes(file);

        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWR", version(),
                "[{\"key\":\"code\",\"value\":\"ABC\"}]");

        JsonObject refused = payload("config.update.result").getAsJsonObject("data")
                .getAsJsonArray("keys").get(0).getAsJsonObject();
        assertThat(refused.get("ok").getAsBoolean()).isFalse();
        assertThat(refused.get("message").getAsString())
                .as("the rule, never the value it echoed and never load-time wording")
                .isNotEmpty().doesNotContain("ABC").doesNotContain("refused to load").doesNotContain("restart");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(entity.code).isEqualTo("abc");
    }

    @Test
    void aLayoutTheWriteGateCannotKeepNamesTheLineToFix() throws Exception {
        // A trailing space on a line this write does not own is a layout the 6.3.0 gate refuses rather
        // than normalises; the operator needs the reason, not a sentence that reads like a permission error.
        Files.write(file, (CONFIG + "note: x   \n").getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(file);

        dispatchUpdate("01JB2Z8Y3K4M5N6P7Q8R9S0TWS", version(),
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]");

        JsonObject payload = payload("config.update.result");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("write_denied");
        assertThat(payload.get("message").getAsString())
                .as("the gate's own value-free reason, naming the line")
                .contains("layout").contains("(line ").doesNotContain("[C]");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(entity.prefix).isEqualTo("[Chat]");
    }

    @Test
    void fileWorkRunsOnlyOnTheInjectedExecutor() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        RemoteConfigHandler handler = new RemoteConfigHandler(
                () -> Collections.singletonList(module), configs, queued::add, root.toFile());
        AgentMessageRouter ownRouter = new AgentMessageRouter(capability -> true, capability -> "denied");
        handler.register(ownRouter);
        RecordingChannel ownChannel = new RecordingChannel();

        ownRouter.dispatch(updateFrame("01JB2Z8Y3K4M5N6P7Q8R9S0TWT", version(),
                "[{\"key\":\"prefix\",\"value\":\"[C]\"}]"), ownChannel);
        assertThat(ownChannel.frames()).as("no reply before the server thread runs the task").isEmpty();
        assertThat(read()).as("no write before the server thread runs the task").isEqualTo(CONFIG);
        assertThat(queued).hasSize(1);

        ownRouter.dispatch("{\"v\":2,\"id\":\"01JB2Z8Y3K4M5N6P7Q8R9S0TWV\",\"type\":\"config.update\","
                + "\"ts\":1761274800000,\"payload\":{\"path\":\"" + PATH + "\","
                + "\"changes\":[{\"key\":\"prefix\",\"value\":\"[C]\"}]}}", ownChannel);
        assertThat(ownChannel.frames()).as("a malformed payload is answered on the dispatching thread")
                .hasSize(1);
        assertThat(ownChannel.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("invalid_message");
        assertThat(queued).hasSize(1);

        ownChannel.clear();
        queued.get(0).run();
        assertThat(entity.prefix).isEqualTo("[C]");
        assertThat(ownChannel.frames().get(0).getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo("ok");
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

    /** One module configuration whose settable entries cover a text, a bounded number, an enum and a pattern. */
    @ConfigEntity("config.yml")
    public static final class PatchConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "prefix")
        public String prefix = "[Chat]";
        @ConfigEntry(path = "cooldown")
        @Range(min = 0, max = 60)
        public int cooldown = 3;
        @ConfigEntry(path = "enabled")
        public boolean enabled = true;
        @ConfigEntry(path = "mode")
        public Mode mode = Mode.fast;
        @ConfigEntry(path = "code")
        @Pattern(regex = "[a-z]+")
        public String code = "abc";

        public PatchConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A second configuration of the same module, for the reload scope. */
    @ConfigEntity("other.yml")
    public static final class OtherConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "value")
        public String value = "ok";

        public OtherConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** The enum entry's allowed values. */
    public enum Mode {
        fast,
        slow
    }
}
