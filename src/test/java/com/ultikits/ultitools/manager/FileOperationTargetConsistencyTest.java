package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Target-consistency reproductions for the remote file API (issue #672, Phase 20.2 Plan 01).
 * <p>
 * Every request goes through the public {@link FileOperationManager#handleFileOperation} entry and
 * every assertion checks a real filesystem effect, the captured panel response, or the single
 * action-log entry. The class reaches production only through the public constructor,
 * {@code setWebSocketClient}, {@code handleFileOperation} and reflective writes to the
 * {@code serverRoot} and {@code editableRoots} fields, so it compiles against the pre-fix
 * production bytes and can be run there as a baseline observation.
 * <p>
 * All methods are top-level: Surefire's method filter does not descend into nested classes.
 * <p>
 * Standard fixture (built before every test): server root = the temporary directory, single
 * editable root = {@code root}; {@code outside/deep} (directory), {@code outside/secret.txt} and
 * {@code outside/victim.txt} carry sentinel bytes; in-root decoys {@code root/secret.txt} and
 * {@code root/victim.txt} carry different bytes; {@code root/sub} is a directory;
 * {@code root/link -> outside/deep}, {@code root/esc -> outside},
 * {@code root/dangling -> outside/created.txt} (absent).
 */
@DisplayName("FileOperationManager: the authorized object is the object the I/O reaches")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FileOperationTargetConsistencyTest {

    private static final String ACTOR = "target-consistency-admin";
    private static final String ROOTS_KEY = "ultipanel.files.editable-roots";
    private static final String NON_CONFIGURABLE_MARKER = "cannot be changed through configuration";

    private static final String OUTSIDE_SECRET = "OUTSIDE-SECRET-SENTINEL-7f3a";
    private static final String OUTSIDE_VICTIM = "OUTSIDE-VICTIM-SENTINEL-91c2";
    private static final String DECOY_SECRET = "in-root decoy secret";
    private static final String DECOY_VICTIM = "in-root decoy victim";

    @TempDir
    Path tmp;

    private Logger mockLogger;
    private RemoteActionLog actionLog;
    private UltiPanelWebSocketClient webSocket;
    private FileOperationManager manager;

    private Path root;
    private Path outside;
    private Path outsideSecret;
    private Path outsideVictim;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        mockLogger = mock(Logger.class);
        actionLog = mock(RemoteActionLog.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.getRemoteActionLog()).thenReturn(actionLog);
        });

        webSocket = mock(UltiPanelWebSocketClient.class);
        when(webSocket.isConnected()).thenReturn(true);
        when(webSocket.getServerId()).thenReturn("test-server");

        manager = new FileOperationManager();
        manager.setWebSocketClient(webSocket);

        buildStandardFixture();
        pointManagerAt(tmp, root);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------------------------------
    // Case fb720d5cbe578bb7ea7cffa20163b74b810033f5d71a62c70c0c655e25551347:
    // the checked path and the used path differ (symbolic link followed by '..', dangling link).
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("read root/link/../secret.txt does not read the outside object")
    void readThroughLinkParentSegmentDoesNotReadOutsideObject() throws Exception {
        String path = "root/link/../secret.txt";

        Outcome outcome = send(request("read", path));

        assertThat(outcome.allMessagesJson())
                .as("no panel message may carry the outside file's bytes")
                .doesNotContain(OUTSIDE_SECRET);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "read", path);
    }

    @Test
    @DisplayName("write root/link/../new.txt does not create a file outside the root")
    void writeThroughLinkParentSegmentDoesNotCreateOutsideFile() throws Exception {
        String path = "root/link/../new.txt";
        JsonObject data = request("write", path);
        data.addProperty("content", "PAYLOAD");

        Outcome outcome = send(data);

        assertThat(outside.resolve("new.txt")).as("outside/new.txt must not be created").doesNotExist();
        assertThat(root.resolve("new.txt")).as("a refused write creates nothing in the root").doesNotExist();
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "write", path);
    }

    @Test
    @DisplayName("list root/link/.. does not list the outside directory")
    void listThroughLinkParentSegmentDoesNotListOutsideDirectory() throws Exception {
        String path = "root/link/..";

        Outcome outcome = send(request("list", path));

        assertThat(outcome.listedNames())
                .as("no listed entry may come from the outside directory")
                .doesNotContain("deep", "secret.txt");
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "list", path);
    }

    @Test
    @DisplayName("delete root/link/../victim.txt does not remove the outside file")
    void deleteThroughLinkParentSegmentDoesNotRemoveOutsideFile() throws Exception {
        String path = "root/link/../victim.txt";

        Outcome outcome = send(request("delete", path));

        assertThat(outsideVictim).as("outside/victim.txt must not be removed").exists();
        assertThat(readText(outsideVictim)).as("outside/victim.txt must be untouched").isEqualTo(OUTSIDE_VICTIM);
        assertThat(readText(root.resolve("victim.txt"))).as("the in-root decoy must be untouched")
                .isEqualTo(DECOY_VICTIM);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", path);
    }

    @Test
    @DisplayName("write to a dangling link does not create the link's target")
    void writeToDanglingLinkDoesNotCreateLinkTarget() throws Exception {
        String path = "root/dangling";
        JsonObject data = request("write", path);
        data.addProperty("content", "PAYLOAD");

        Outcome outcome = send(data);

        assertThat(outside.resolve("created.txt")).as("the dangling link's target must not be created")
                .doesNotExist();
        assertThat(Files.isSymbolicLink(root.resolve("dangling"))).as("the link itself is left alone").isTrue();
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "write", path);
    }

    // ------------------------------------------------------------------------------------------
    // Case bfdffbeb4827bf3a7a613d559fcc44a0cafa47318e08cb6dc2c49997579fd4ff:
    // a refusal reached during real-path resolution must be the decision that is audited.
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @DisplayName("a real-path refusal is audited as exactly one DENIED entry")
    @CsvSource({
        "read, root/esc/secret.txt",
        "write, root/esc/new.txt",
        "list, root/esc",
        "delete, root/esc/secret.txt"
    })
    void realPathRefusalIsAuditedAsDenied(String operation, String path) throws Exception {
        JsonObject data = request(operation, path);
        if ("write".equals(operation)) {
            data.addProperty("content", "PAYLOAD");
        }

        Outcome outcome = send(data);

        assertThat(outsideSecret).exists();
        assertThat(readText(outsideSecret)).isEqualTo(OUTSIDE_SECRET);
        assertThat(readText(outsideVictim)).isEqualTo(OUTSIDE_VICTIM);
        assertThat(outside.resolve("new.txt")).doesNotExist();
        assertThat(outcome.allMessagesJson()).doesNotContain(OUTSIDE_SECRET);
        assertThat(outcome.listedNames()).isEmpty();
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, operation, path);
        assertThat(outcome.entry.getReason()).as("an out-of-root refusal names the editable-roots key")
                .contains(ROOTS_KEY);
    }

    // ------------------------------------------------------------------------------------------
    // Behaviour-change rows (baseline observation, not frozen cases).
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a listed alias to an outside file is marked OUTSIDE_ROOTS without metadata")
    void aliasToOutsideFileIsMarkedRefusedInList() throws Exception {
        Path aliasDir = Files.createDirectories(root.resolve("aliasdir"));
        Files.write(aliasDir.resolve("plain.txt"), "plain".getBytes(StandardCharsets.UTF_8));
        link(aliasDir.resolve("alias.txt"), outsideSecret);

        Outcome outcome = send(request("list", "root/aliasdir"));

        JsonObject alias = outcome.listedEntry("alias.txt");
        assertThat(alias.get("accessible").getAsBoolean()).isFalse();
        assertThat(alias.get("reason").getAsString()).isEqualTo("OUTSIDE_ROOTS");
        assertThat(alias.has("size")).isFalse();
        assertThat(alias.has("lastModified")).isFalse();
        assertThat(alias.has("readable")).isFalse();
        assertThat(alias.has("writable")).isFalse();
        assertThat(outcome.listedEntry("plain.txt").get("accessible").getAsBoolean()).isTrue();
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "list", "root/aliasdir");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("a write whose missing part contains '..' or '.' is refused non-configurably")
    @ValueSource(strings = {"root/missing/../x.txt", "root/missing/./x.txt"})
    void writeAfterMissingComponentParentSegmentIsRefused(String path) throws Exception {
        JsonObject data = request("write", path);
        data.addProperty("content", "PAYLOAD");

        Outcome outcome = send(data);

        assertThat(filesNamed("x.txt")).as("no x.txt may be created anywhere").isEmpty();
        assertThat(root.resolve("missing")).as("no directory may be created for a refused write").doesNotExist();
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "write", path);
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("a self-referencing link is refused as unresolvable")
    void linkLoopIsRefusedAsUnresolvable() throws Exception {
        Path loop = root.resolve("loop");
        link(loop, loop);

        Outcome outcome = send(request("read", "root/loop"));

        assertThat(Files.isSymbolicLink(loop)).isTrue();
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "read", "root/loop");
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("deleting an in-root link to an outside file removes only the link")
    void deletingLinkToOutsideFileRemovesOnlyTheLink() throws Exception {
        Path outLink = root.resolve("outlink");
        link(outLink, outsideSecret);

        Outcome outcome = send(request("delete", "root/outlink"));

        assertThat(Files.exists(outLink, LinkOption.NOFOLLOW_LINKS)).as("the link entry is removed").isFalse();
        assertThat(readText(outsideSecret)).as("the link's target is untouched").isEqualTo(OUTSIDE_SECRET);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "root/outlink");
    }

    @Test
    @DisplayName("deleting a dangling link removes the link")
    void deletingDanglingLinkRemovesTheLink() throws Exception {
        Path dangling = root.resolve("dangling");

        Outcome outcome = send(request("delete", "root/dangling"));

        assertThat(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS)).as("the dangling link is removed").isFalse();
        assertThat(outside.resolve("created.txt")).as("nothing is created outside").doesNotExist();
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "root/dangling");
    }

    @Test
    @DisplayName("deleting root/sub/.. is refused non-configurably and removes nothing")
    void deletingParentSegmentEntryIsRefused() throws Exception {
        List<String> before = rootEntries();
        JsonObject data = request("delete", "root/sub/..");
        data.addProperty("recursive", true);

        Outcome outcome = send(data);

        assertThat(rootEntries()).as("every root entry is intact").isEqualTo(before);
        assertThat(readText(root.resolve("secret.txt"))).isEqualTo(DECOY_SECRET);
        assertThat(readText(outsideSecret)).isEqualTo(OUTSIDE_SECRET);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", "root/sub/..");
        assertNonConfigurable(outcome);
    }

    /**
     * Additional behaviour-change row for the entry-mode rule "the terminal name must be an
     * ordinary name": with the server root itself granted as the editable root, a delete of
     * {@code /}, {@code .} or {@code sub/.} names no ordinary entry and is refused instead of
     * emptying the directory it lexically collapses to.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("a delete naming no ordinary entry ('/', '.', 'sub/.') is refused and removes nothing")
    @ValueSource(strings = {"/", ".", "sub/."})
    void deletingEntryWithoutOrdinaryTerminalNameIsRefused(String path) throws Exception {
        Path srv = Files.createDirectories(tmp.resolve("srv"));
        Path sub = Files.createDirectories(srv.resolve("sub"));
        Files.write(srv.resolve("top.txt"), "top".getBytes(StandardCharsets.UTF_8));
        Files.write(sub.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        pointManagerAt(srv, srv);
        JsonObject data = request("delete", path);
        data.addProperty("recursive", true);

        Outcome outcome = send(data);

        assertThat(srv.resolve("top.txt")).exists();
        assertThat(sub.resolve("keep.txt")).exists();
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", path);
        assertNonConfigurable(outcome);
    }

    // ------------------------------------------------------------------------------------------
    // Fixture and request helpers.
    // ------------------------------------------------------------------------------------------

    private void buildStandardFixture() throws IOException {
        root = Files.createDirectories(tmp.resolve("root"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Path deep = Files.createDirectories(outside.resolve("deep"));
        outsideSecret = outside.resolve("secret.txt");
        outsideVictim = outside.resolve("victim.txt");
        Files.write(outsideSecret, OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));
        Files.write(outsideVictim, OUTSIDE_VICTIM.getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("secret.txt"), DECOY_SECRET.getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("victim.txt"), DECOY_VICTIM.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("sub"));
        link(root.resolve("link"), deep);
        link(root.resolve("esc"), outside);
        link(root.resolve("dangling"), outside.resolve("created.txt"));
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic link creation is refused for this user/filesystem: " + e.getMessage());
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the fixture points the manager at a temp tree
    private void pointManagerAt(Path serverRoot, Path editableRoot) throws Exception {
        Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
        serverRootField.setAccessible(true);
        serverRootField.set(manager, serverRoot.toFile());

        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(manager, Collections.singletonList(editableRoot.toFile()));
    }

    private static JsonObject request(String operation, String path) {
        JsonObject data = new JsonObject();
        data.addProperty("operation", operation);
        data.addProperty("path", path);
        data.addProperty("operationId", "tc-" + operation);
        data.addProperty("executor", ACTOR);
        return data;
    }

    private Outcome send(JsonObject data) {
        manager.handleFileOperation(data);

        ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(actionLog, timeout(5000).times(1)).record(entryCaptor.capture());

        ArgumentCaptor<JsonObject> messageCaptor = ArgumentCaptor.forClass(JsonObject.class);
        verify(webSocket, atLeastOnce()).sendMessage(messageCaptor.capture());
        List<JsonObject> responses = new ArrayList<>();
        for (JsonObject message : messageCaptor.getAllValues()) {
            responses.add(message.getAsJsonObject("data"));
        }
        return new Outcome(entryCaptor.getValue(), responses);
    }

    private static void assertDeniedEntry(Outcome outcome, String operation, String path) {
        RemoteActionLog.Entry entry = outcome.entry;
        assertThat(entry.getVerdict()).as("verdict").isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(entry.getCapability())
                .isEqualTo(PluginInitiationUtils.resolveFileOperationCapability(operation).name());
        assertThat(entry.getAction()).isEqualTo("file_operation:" + operation);
        assertThat(entry.getTarget()).isEqualTo(path);
        assertThat(entry.getActor()).isEqualTo(ACTOR);
        assertThat(entry.getReason()).isNotBlank();
        assertThat(entry.getReason()).as("the audited reason is the message the panel received")
                .isEqualTo(outcome.lastMessage());
    }

    private static void assertAllowedEntry(Outcome outcome, String operation, String path) {
        RemoteActionLog.Entry entry = outcome.entry;
        assertThat(entry.getVerdict()).as("verdict").isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(entry.getCapability())
                .isEqualTo(PluginInitiationUtils.resolveFileOperationCapability(operation).name());
        assertThat(entry.getAction()).isEqualTo("file_operation:" + operation);
        assertThat(entry.getTarget()).isEqualTo(path);
        assertThat(entry.getActor()).isEqualTo(ACTOR);
    }

    private static void assertNonConfigurable(Outcome outcome) {
        assertThat(outcome.entry.getReason())
                .contains(NON_CONFIGURABLE_MARKER)
                .doesNotContain(ROOTS_KEY);
    }

    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<Path> filesNamed(String name) throws IOException {
        try (Stream<Path> walk = Files.walk(tmp)) {
            return walk.filter(p -> p.getFileName() != null && name.equals(p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    private List<String> rootEntries() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.map(p -> root.relativize(p).toString()).sorted().collect(Collectors.toList());
        }
    }

    /** One handled request: the single action-log entry and every panel response's data object. */
    private static final class Outcome {
        private final RemoteActionLog.Entry entry;
        private final List<JsonObject> responses;

        Outcome(RemoteActionLog.Entry entry, List<JsonObject> responses) {
            this.entry = entry;
            this.responses = responses;
        }

        boolean success() {
            return responses.get(responses.size() - 1).get("success").getAsBoolean();
        }

        String lastMessage() {
            return responses.get(responses.size() - 1).get("message").getAsString();
        }

        String allMessagesJson() {
            return responses.toString();
        }

        List<String> listedNames() {
            List<String> names = new ArrayList<>();
            for (JsonObject response : responses) {
                JsonArray files = response.getAsJsonArray("files");
                if (files == null) {
                    continue;
                }
                for (JsonElement file : files) {
                    names.add(file.getAsJsonObject().get("name").getAsString());
                }
            }
            return names;
        }

        JsonObject listedEntry(String name) {
            for (JsonObject response : responses) {
                JsonArray files = response.getAsJsonArray("files");
                if (files == null) {
                    continue;
                }
                for (JsonElement file : files) {
                    if (name.equals(file.getAsJsonObject().get("name").getAsString())) {
                        return file.getAsJsonObject();
                    }
                }
            }
            throw new AssertionError("no listed entry named '" + name + "' in " + responses);
        }
    }
}
