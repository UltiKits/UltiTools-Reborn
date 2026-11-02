package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Pins the plugin-side file guard to the panel's protected server-file list (UltiCloud
 * {@code packages/contracts/src/rest/server-files.ts}, {@code PROTECTED_SERVER_FILES}). The panel
 * refuses these paths first; the plugin refuses them again as defence in depth, so a remote
 * {@code files.read} or {@code files.write} of any listed file is answered with the unchanged
 * {@code path_not_allowed} refusal and the file's bytes are never read or replaced.
 */
@DisplayName("protected server files match the panel's protected list")
class ProtectedServerFilesParityTest {

    @TempDir
    Path tmp;

    private FileOperationManager files;
    private String originalUserDir = System.getProperty("user.dir");
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
            // Editable roots: the server root itself and plugins/, resolved against user.dir below.
            YamlConfiguration config = new YamlConfiguration();
            config.set("ultipanel.files.editable-roots", Arrays.asList("", "plugins"));
            lenient().when(ultiTools.getConfig()).thenReturn(config);
        });

        Files.createDirectories(tmp.resolve("plugins"));
        // The manager takes its server root from user.dir at construction, so no field access is needed.
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmp.toString());
        files = new FileOperationManager();
        System.setProperty("user.dir", originalUserDir);
        RemoteFileOperations handler = new RemoteFileOperations(files, tmp.toFile(), entry -> { }, () -> true);
        router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("files.read", handler);
        router.register("files.write", handler);
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
        MockBukkitHelper.safeUnmock();
    }

    /** Names and extensions the guard lacked before; a file of each is placed at the server root. */
    @ParameterizedTest
    @ValueSource(strings = {
        "commands.yml", "permissions.yml", "paper-world.yml", "purpur.yml", "help.yml",
        "start.cmd", "start.ps1", "start.command",
        "COMMANDS.YML", "world/paper-world.yml", "plugins/run.command"
    })
    @DisplayName("a newly protected file is refused for read and write and keeps its bytes")
    void newlyProtectedFileIsRefusedForReadAndWrite(String relative) throws IOException {
        Path target = write(relative, "operator: bytes\n");
        byte[] before = Files.readAllBytes(target);

        AccessDecision decision = files.isPathAllowed(relative);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.isConfigurable()).isFalse();

        assertError(readRequest(relative), "path_not_allowed");
        String current = RemoteFilesEndToEndTest.sha256(before);
        assertError(writeRequest(relative, "panel: write\n", current), "path_not_allowed");
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    /** The panel strips trailing dots and spaces from each segment before matching; so does the guard. */
    @ParameterizedTest
    @ValueSource(strings = {"server.properties.", "purpur.yml ", "start.command. ", "run.jar.."})
    @DisplayName("trailing dots and spaces do not hide a protected name")
    void trailingDotsAndSpacesDoNotHideAProtectedName(String path) {
        AccessDecision decision = files.isPathAllowed(path);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.isConfigurable()).isFalse();
    }

    /** Every fixed name and extension on the panel's list is refused by the plugin. */
    @ParameterizedTest
    @ValueSource(strings = {
        "server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json",
        "eula.txt", "usercache.json", "bukkit.yml", "spigot.yml", "paper.yml", "paper-global.yml",
        "paper-world-defaults.yml", "commands.yml", "permissions.yml", "paper-world.yml", "purpur.yml",
        "help.yml", "a.jar", "a.sh", "a.bat", "a.cmd", "a.ps1", "a.exe", "a.class", "a.command",
        "data.json", "credentials.json", "secring.gpg", "access_key.txt", ".dev.vars",
        ".ultikits/x.yml", "plugins/UltiTools/security/log.jsonl"
    })
    @DisplayName("every entry of the panel's protected list is refused")
    void everyPanelEntryIsRefused(String path) {
        assertThat(files.isPathAllowed(path).isAllowed()).isFalse();
    }

    @Test
    @DisplayName("an ordinary plugin config next to them is still read and written")
    void ordinaryConfigIsStillAllowed() throws IOException {
        Path config = write("plugins/Example/config.yml", "enabled: true\n");
        assertThat(files.isPathAllowed("plugins/Example/config.yml").isAllowed()).isTrue();

        router.dispatch(readRequest("plugins/Example/config.yml").toString(), channel);
        assertThat(only().getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");

        channel.clear();
        String current = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(config));
        router.dispatch(writeRequest("plugins/Example/config.yml", "enabled: false\n", current).toString(), channel);
        assertThat(only().getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).isEqualTo("enabled: false\n");
    }

    private void assertError(JsonObject request, String code) {
        channel.clear();
        router.dispatch(request.toString(), channel);
        JsonObject payload = only().getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo(code);
    }

    private JsonObject only() {
        assertThat(channel.frames()).hasSize(1);
        return channel.frames().get(0);
    }

    private static JsonObject readRequest(String path) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.read/valid/config.json");
        frame.getAsJsonObject("payload").addProperty("path", path);
        return frame;
    }

    private static JsonObject writeRequest(String path, String content, String baseVersion) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.write/valid/with-base-version.json");
        JsonObject payload = frame.getAsJsonObject("payload");
        payload.addProperty("path", path);
        payload.addProperty("content", content);
        payload.addProperty("baseVersion", baseVersion);
        return frame;
    }

    private Path write(String relative, String content) throws IOException {
        Path target = tmp.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return target;
    }
}
