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
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.manager.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * The three-step panel flow of protocol v2 over one module configuration file: browse the tree, read
 * the typed entries with the version of the file's bytes, then write one key and see the file keep
 * every other byte it had.
 */
class RemoteConfigEndToEndTest {

    /** Operator-written comments and odd spacing that no write may change. */
    private static final String CONFIG = "# Operator note: this line must never change\n"
            + "chat:\n"
            + "  prefix: \"[Chat]\"   # trailing note\n"
            + "  cooldown: 3\n"
            + "  enabled: true\n"
            + "  words:\n"
            + "    - a\n"
            + "    - b\n"
            + "  mode: global\n";

    @TempDir
    Path root;
    private Path file;
    private ChatConfig entity;
    private AgentMessageRouter router;
    private RecordingChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        Path folder = root.resolve("plugins").resolve("UltiTools").resolve("pluginConfig").resolve("Chat");
        Files.createDirectories(folder);
        file = folder.resolve("config.yml");
        Files.write(file, CONFIG.getBytes(StandardCharsets.UTF_8));

        UltiToolsPlugin module = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(module.getPluginName()).thenReturn("Chat");
        lenient().when(module.getResourceFolderPath()).thenReturn(folder.toString());
        ConfigFileStubs.stubConfigFolder(module, folder.toFile());

        entity = new ChatConfig("config.yml");
        ConfigManager configs = new ConfigManager();
        configs.register(module, entity);

        RemoteConfigHandler handler = new RemoteConfigHandler(
                () -> Collections.singletonList(module), configs, Runnable::run, root.toFile());
        router = new AgentMessageRouter(capability -> true, capability -> "denied");
        handler.register(router);
        channel = new RecordingChannel();
    }

    @Test
    void treeListsTheModuleAndItsRegisteredFile() {
        dispatch("config.tree", "01JB2Z8Y3K4M5N6P7Q8R9S0TV1", "{}");

        JsonObject data = onlyFrame("config.tree.result").getAsJsonObject("payload").getAsJsonObject("data");
        JsonArray plugins = data.getAsJsonArray("plugins");
        assertThat(plugins).hasSize(1);
        JsonObject plugin = plugins.get(0).getAsJsonObject();
        assertThat(plugin.get("name").getAsString()).isEqualTo("Chat");
        JsonArray files = plugin.getAsJsonArray("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getAsJsonObject().get("path").getAsString())
                .isEqualTo("plugins/UltiTools/pluginConfig/Chat/config.yml");
        assertThat(files.get(0).getAsJsonObject().get("name").getAsString()).isEqualTo("config.yml");
        assertThat(channel.frames()).noneMatch(frame -> "op.progress".equals(frame.get("type").getAsString()));
    }

    @Test
    void entriesAreTypedWithLabelsCommentsAndTheFilesVersion() throws Exception {
        dispatch("config.entries", "01JB2Z8Y3K4M5N6P7Q8R9S0TV2",
                "{\"path\":\"plugins/UltiTools/pluginConfig/Chat/config.yml\"}");

        JsonObject data = onlyFrame("config.entries.result").getAsJsonObject("payload").getAsJsonObject("data");
        assertThat(data.get("version").getAsString()).isEqualTo(sha256(Files.readAllBytes(file)));
        JsonArray entries = data.getAsJsonArray("entries");
        assertThat(entries).hasSize(5);

        assertThat(entry(entries, "chat.prefix").get("type").getAsString()).isEqualTo("string");
        assertThat(entry(entries, "chat.prefix").get("label").getAsString()).isEqualTo("prefix");
        assertThat(entry(entries, "chat.prefix").get("value").getAsString()).isEqualTo("[Chat]");
        assertThat(entry(entries, "chat.prefix").get("comment").getAsString()).contains("Supports {player}");

        JsonObject cooldown = entry(entries, "chat.cooldown");
        assertThat(cooldown.get("type").getAsString()).isEqualTo("integer");
        assertThat(cooldown.get("value").getAsLong()).isEqualTo(3L);
        assertThat(cooldown.getAsJsonObject("constraints").get("min").getAsLong()).isZero();
        assertThat(cooldown.getAsJsonObject("constraints").get("max").getAsLong()).isEqualTo(60L);

        assertThat(entry(entries, "chat.enabled").get("type").getAsString()).isEqualTo("boolean");
        assertThat(entry(entries, "chat.words").get("type").getAsString()).isEqualTo("string_list");
        assertThat(entry(entries, "chat.words").getAsJsonArray("value")).hasSize(2);
        assertThat(entry(entries, "chat.mode").get("type").getAsString()).isEqualTo("enum");
        assertThat(entry(entries, "chat.mode").getAsJsonObject("constraints").getAsJsonArray("options")).hasSize(2);
    }

    @Test
    void updateWritesOneKeyAndKeepsEveryOtherByte() throws Exception {
        String version = sha256(Files.readAllBytes(file));
        dispatch("config.update", "01JB2Z8Y3K4M5N6P7Q8R9S0TV3", updatePayload(version,
                "[{\"key\":\"chat.prefix\",\"value\":\"[C]\"}]"));

        JsonObject payload = onlyFrame("config.update.result").getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = payload.getAsJsonObject("data");
        JsonArray keys = data.getAsJsonArray("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getAsJsonObject().get("ok").getAsBoolean()).isTrue();
        assertThat(keys.get(0).getAsJsonObject().get("message").isJsonNull()).isTrue();
        assertThat(data.get("version").getAsString()).isEqualTo(sha256(Files.readAllBytes(file)));
        assertThat(data.get("version").getAsString()).isNotEqualTo(version);

        List<String> before = Arrays.asList(CONFIG.split("\n", -1));
        List<String> after = Arrays.asList(read().split("\n", -1));
        assertThat(after).hasSameSizeAs(before);
        for (int line = 0; line < before.size(); line++) {
            if (line == 2) {
                continue;
            }
            assertThat(after.get(line)).as("line " + line).isEqualTo(before.get(line));
        }
        // The written key's own line is the one span the write gate owns: the gate keeps its trailing
        // comment text and may normalize the spacing inside that line (ConfigDocument#render), while
        // every line outside it - the header comment, the other keys, their layout - is byte-identical.
        assertThat(after.get(2)).isEqualTo("  prefix: \"[C]\" # trailing note");
        assertThat(entity.prefix).isEqualTo("[C]");
    }

    private JsonObject entry(JsonArray entries, String key) {
        for (int index = 0; index < entries.size(); index++) {
            JsonObject entry = entries.get(index).getAsJsonObject();
            if (key.equals(entry.get("key").getAsString())) {
                return entry;
            }
        }
        throw new AssertionError("no entry " + key);
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String updatePayload(String version, String changes) {
        return "{\"path\":\"plugins/UltiTools/pluginConfig/Chat/config.yml\",\"baseVersion\":\""
                + version + "\",\"changes\":" + changes + "}";
    }

    private JsonObject onlyFrame(String type) {
        List<JsonObject> matching = new ArrayList<>();
        for (JsonObject frame : channel.frames()) {
            if (type.equals(frame.get("type").getAsString())) {
                matching.add(frame);
            }
        }
        assertThat(matching).as(type).hasSize(1);
        return matching.get(0);
    }

    private void dispatch(String type, String id, String payload) {
        router.dispatch("{\"v\":2,\"id\":\"" + id + "\",\"type\":\"" + type
                + "\",\"ts\":1761274800000,\"payload\":" + payload + "}", channel);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    /** One module configuration with one entry of every type the contract carries. */
    @ConfigEntity("config.yml")
    public static final class ChatConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "chat.prefix", comment = "Supports {player}")
        public String prefix = "[Chat]";
        @ConfigEntry(path = "chat.cooldown")
        @Range(min = 0, max = 60)
        public int cooldown = 3;
        @ConfigEntry(path = "chat.enabled")
        public boolean enabled = true;
        @ConfigEntry(path = "chat.words")
        public List<String> words = new ArrayList<>();
        @ConfigEntry(path = "chat.mode")
        public Mode mode = Mode.global;

        public ChatConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** The enum entry's allowed values. */
    public enum Mode {
        global,
        local
    }
}
