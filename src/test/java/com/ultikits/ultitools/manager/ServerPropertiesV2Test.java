package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * The protocol 2.0.0 {@code properties.get} / {@code properties.update} surface over the existing
 * line-preserving {@link ServerPropertiesManager} rewrite.
 *
 * <p>Two things are pinned here beyond the happy path. The optimistically concurrent write: an
 * update carries the version the panel read, and a file whose bytes no longer hash to it is
 * answered {@code file_changed} with every byte intact. And the maintainer's decision that
 * {@code online-mode} and {@code white-list} are never remotely writable: rejected by the strict
 * payload shape at the router and, independently, refused by the manager's own write path if a
 * future caller bypasses it.</p>
 */
@DisplayName("UltiCloud v2 properties.get / properties.update")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServerPropertiesV2Test {

    private static final String SAMPLE = "motd=A Minecraft Server\n"
            + "max-players=20\n"
            + "difficulty=normal\n"
            + "gamemode=survival\n"
            + "spawn-protection=16\n"
            + "pvp=true\n"
            + "view-distance=10\n"
            + "online-mode=true\n"
            + "white-list=false\n"
            + "spawn-animals=true\n"
            + "rcon.password=secret123\n"
            + "server-port=25565\n";

    @TempDir
    Path tmp;

    private Path propsFile;
    private ServerPropertiesManager properties;
    private RemotePropertiesHandler handler;
    private AgentMessageRouter router;
    private RecordingChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        Logger logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
        });

        propsFile = tmp.resolve("server.properties");
        Files.write(propsFile, SAMPLE.getBytes(StandardCharsets.UTF_8));
        properties = new ServerPropertiesManager(tmp.toFile());
        handler = new RemotePropertiesHandler(properties);
        router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("properties.get", handler);
        router.register("properties.update", handler);
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // --------------------------------------------------------------------------------------
    // properties.get
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("get answers the present readable keys typed, the 12 writable keys and the raw-bytes version")
    void getAnswersTypedValuesAndTheWritableSet() throws IOException {
        JsonObject frame = ContractFixtures.object("fixtures/agent/properties.get/valid/all.json");
        router.dispatch(frame.toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = payload.getAsJsonObject("data");
        JsonObject values = data.getAsJsonObject("values");

        assertThat(values.get("motd").getAsString()).isEqualTo("A Minecraft Server");
        assertThat(values.get("max-players").getAsInt()).isEqualTo(20);
        assertThat(values.get("difficulty").getAsString()).isEqualTo("normal");
        assertThat(values.get("gamemode").getAsString()).isEqualTo("survival");
        assertThat(values.get("spawn-protection").getAsInt()).isEqualTo(16);
        assertThat(values.get("view-distance").getAsInt()).isEqualTo(10);
        assertThat(values.get("pvp").getAsBoolean()).isTrue();
        assertThat(values.get("online-mode").getAsBoolean()).isTrue();
        assertThat(values.get("white-list").getAsBoolean()).isFalse();
        assertThat(values.has("allow-flight")).isFalse();
        assertThat(values.has("rcon.password")).isFalse();
        assertThat(values.has("server-port")).isFalse();

        assertThat(strings(data.getAsJsonArray("writable"))).containsExactly(
                "motd", "max-players", "difficulty", "gamemode", "spawn-protection", "view-distance",
                "simulation-distance", "pvp", "allow-flight", "spawn-monsters", "allow-nether",
                "enable-command-block");
        assertThat(data.get("version").getAsString())
                .isEqualTo(RemoteFilesEndToEndTest.sha256(Files.readAllBytes(propsFile)));
    }

    @Test
    @DisplayName("a value that does not parse to its key's type is left out, not reported wrongly")
    void unparseableValuesAreOmitted() throws IOException {
        Files.write(propsFile, ("motd=Hi\nmax-players=lots\ndifficulty=weird\nview-distance=999\n"
                + "pvp=maybe\ngamemode=Survival\n").getBytes(StandardCharsets.UTF_8));

        router.dispatch(ContractFixtures.object("fixtures/agent/properties.get/valid/all.json").toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject values = payload.getAsJsonObject("data").getAsJsonObject("values");
        assertThat(values.has("motd")).isTrue();
        assertThat(values.has("max-players")).isFalse();
        assertThat(values.has("difficulty")).isFalse();
        assertThat(values.has("view-distance")).isFalse();
        assertThat(values.has("pvp")).isFalse();
        assertThat(values.get("gamemode").getAsString()).isEqualTo("survival");
    }

    @Test
    @DisplayName("a missing server.properties is not_found and a non-UTF-8 one is file_not_editable")
    void getRefusesAbsentAndNonEditableFiles() throws IOException {
        Files.delete(propsFile);
        assertGetError("not_found");

        Files.write(propsFile, new byte[]{'m', 'o', 't', 'd', '=', (byte) 0xE9, '\n'});
        assertGetError("file_not_editable");
    }

    // --------------------------------------------------------------------------------------
    // properties.update
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("an update with the current version rewrites only the named lines and answers the new version")
    void updateWithCurrentVersionRewritesOnlyNamedLines() throws IOException {
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(propsFile));
        JsonObject frame = ContractFixtures.object("fixtures/agent/properties.update/valid/motd-and-view-distance.json");
        frame.getAsJsonObject("payload").addProperty("baseVersion", baseVersion);
        router.dispatch(frame.toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = payload.getAsJsonObject("data");
        assertThat(keyOutcomes(data)).containsExactly("motd=true", "view-distance=true", "pvp=true");

        String expected = SAMPLE.replace("motd=A Minecraft Server", "motd=Welcome")
                .replace("view-distance=10", "view-distance=12")
                .replace("pvp=true", "pvp=false");
        assertThat(new String(Files.readAllBytes(propsFile), StandardCharsets.UTF_8)).isEqualTo(expected);
        assertThat(data.get("version").getAsString())
                .isEqualTo(RemoteFilesEndToEndTest.sha256(Files.readAllBytes(propsFile)));
        assertThat(data.get("version").getAsString()).isNotEqualTo(baseVersion);
    }

    @Test
    @DisplayName("a stale version is file_changed and every byte of the operator's file survives")
    void staleVersionLeavesTheFileUntouched() throws IOException {
        String stale = RemoteFilesEndToEndTest.sha256(new byte[0]);
        byte[] before = Files.readAllBytes(propsFile);
        JsonObject frame = ContractFixtures.object("fixtures/agent/properties.update/valid/motd-and-view-distance.json");
        frame.getAsJsonObject("payload").addProperty("baseVersion", stale);
        router.dispatch(frame.toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("file_changed");
        assertThat(Files.readAllBytes(propsFile)).isEqualTo(before);
    }

    @Test
    @DisplayName("a key the file does not define fails on its own while the others are applied")
    void absentKeyMakesTheReplyPartial() throws IOException {
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(propsFile));
        JsonObject changes = new JsonObject();
        changes.addProperty("motd", "Partial");
        changes.addProperty("allow-flight", true);
        router.dispatch(updateRequest(baseVersion, changes).toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("partial");
        assertThat(keyOutcomes(payload.getAsJsonObject("data")))
                .containsExactly("motd=true", "allow-flight=false");
        JsonArray keys = payload.getAsJsonObject("data").getAsJsonArray("keys");
        assertThat(keys.get(1).getAsJsonObject().get("message").getAsString()).isNotEmpty();
        assertThat(new String(Files.readAllBytes(propsFile), StandardCharsets.UTF_8))
                .isEqualTo(SAMPLE.replace("motd=A Minecraft Server", "motd=Partial"));
    }

    @Test
    @DisplayName("every invalid properties.update fixture is invalid_message and the file is untouched")
    void invalidUpdateFixturesNeverTouchTheFile() throws IOException {
        byte[] before = Files.readAllBytes(propsFile);
        for (ContractFixtures.Fixture fixture : ContractFixtures.invalidFrames("properties.update")) {
            channel.clear();
            router.dispatch(fixture.frame().toString(), channel);
            JsonObject frame = only(channel);
            assertThat(frame.get("type").getAsString()).as(fixture.path()).isEqualTo("error");
            assertThat(frame.getAsJsonObject("payload").get("code").getAsString())
                    .as(fixture.path()).isEqualTo("invalid_message");
        }
        assertThat(Files.readAllBytes(propsFile)).isEqualTo(before);
    }

    @Test
    @DisplayName("the manager refuses online-mode, white-list and any key outside the 12 even when called directly")
    void directUpdateRefusesTheTwoSecurityKeys() throws IOException {
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(propsFile));
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("online-mode", "false");
        changes.put("white-list", "true");
        changes.put("spawn-animals", "false");
        changes.put("motd", "Still Applied");

        ServerPropertiesManager.PropertiesUpdateResult result =
                properties.updateWithBaseVersion(baseVersion, changes);

        assertThat(result.getOutcome()).isEqualTo(ServerPropertiesManager.PropertiesUpdateResult.Outcome.PARTIAL);
        assertThat(result.getKeys()).hasSize(4);
        for (ServerPropertiesManager.PropertyKeyResult key : result.getKeys()) {
            assertThat(key.isOk()).as(key.getKey()).isEqualTo("motd".equals(key.getKey()));
        }
        String text = new String(Files.readAllBytes(propsFile), StandardCharsets.UTF_8);
        assertThat(text).contains("online-mode=true\nwhite-list=false\nspawn-animals=true\n");
        assertThat(text).contains("motd=Still Applied");
    }

    @Test
    @DisplayName("properties.get rejects any payload field with invalid_message")
    void getRejectsParameters() {
        ContractFixtures.Fixture fixture = ContractFixtures.invalidFrames("properties.get").get(0);
        channel.clear();
        router.dispatch(fixture.frame().toString(), channel);
        assertThat(only(channel).getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
    }

    // --------------------------------------------------------------------------------------
    // helpers
    // --------------------------------------------------------------------------------------

    private void assertGetError(String code) {
        channel.clear();
        router.dispatch(ContractFixtures.object("fixtures/agent/properties.get/valid/all.json").toString(), channel);
        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo(code);
    }

    private static JsonObject updateRequest(String baseVersion, JsonObject changes) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/properties.update/valid/motd-and-view-distance.json");
        JsonObject payload = new JsonObject();
        payload.addProperty("baseVersion", baseVersion);
        payload.add("changes", changes);
        frame.add("payload", payload);
        return frame;
    }

    private static String[] keyOutcomes(JsonObject data) {
        JsonArray keys = data.getAsJsonArray("keys");
        String[] outcomes = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            JsonObject entry = keys.get(i).getAsJsonObject();
            outcomes[i] = entry.get("key").getAsString() + "=" + entry.get("ok").getAsBoolean();
        }
        return outcomes;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static JsonObject only(RecordingChannel recorded) {
        assertThat(recorded.frames()).hasSize(1);
        return recorded.frames().get(0);
    }
}
