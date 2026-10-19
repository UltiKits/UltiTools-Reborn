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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
import com.ultikits.ultitools.utils.CredentialStore;
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
    private static final String TOKEN_SENTINEL = "SYNTHETIC-TOKEN-SENTINEL-d08";
    private static final String DATA_JSON_CONTENT = "{\"refresh_token\":\"" + TOKEN_SENTINEL + "\"}";
    private static final String ACTION_LOG_SENTINEL = "ACTION-LOG-SENTINEL-d08";

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
    private Path dataJson;
    private Path actionLogFile;

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
        data.addProperty("content", "PAYLOAD"); // read, list and delete ignore it

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
    @ValueSource(strings = {"root/missing/../x.txt", "root/missing/./x.txt", "root/secret.txt/../x.txt"})
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
    @DisplayName("a component under an unsearchable directory is refused as undeterminable")
    void unsearchableDirectoryComponentIsRefused() throws Exception {
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.write(locked.resolve("f.txt"), OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        try {
            Files.setPosixFilePermissions(locked, EnumSet.noneOf(PosixFilePermission.class));
            Assumptions.assumeFalse(Files.exists(locked.resolve("f.txt")),
                    "this user can search a mode-000 directory (running as root?); the row is BLOCKED here");

            Outcome outcome = send(request("read", "root/locked/f.txt"));

            assertThat(outcome.allMessagesJson()).doesNotContain(OUTSIDE_SECRET);
            assertThat(outcome.success()).isFalse();
            assertDeniedEntry(outcome, "read", "root/locked/f.txt");
            assertNonConfigurable(outcome);
        } finally {
            Files.setPosixFilePermissions(locked, original);
        }
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
    // Directory-link deletion, DIRLINK_ANSWER=A (maintainer, 2026-10-18): "A panel delete of a
    // symbolic link that points to a directory removes ONLY the link; the target directory and
    // its contents are untouched (option A, link-only; options B and C are not chosen)."
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] delete root/{0} with recursive={1}")
    @DisplayName("deleting a link to a directory removes only the link (DIRLINK_ANSWER=A)")
    @CsvSource({
        "dlink, absent",
        "dlink, false",
        "dlink, true",
        "olink, absent",
        "olink, false",
        "olink, true"
    })
    void deletingDirectoryLinkRemovesOnlyTheLink(String linkName, String recursive) throws Exception {
        Path realDir = Files.createDirectories(root.resolve("realdir"));
        Path child = realDir.resolve("child.txt");
        Files.write(child, "child".getBytes(StandardCharsets.UTF_8));
        Path outsideDir = Files.createDirectories(outside.resolve("odir"));
        Path sentinel = outsideDir.resolve("sentinel.txt");
        Files.write(sentinel, "sentinel".getBytes(StandardCharsets.UTF_8));
        link(root.resolve("dlink"), realDir);
        link(root.resolve("olink"), outsideDir);
        String path = "root/" + linkName;
        JsonObject data = request("delete", path);
        if (!"absent".equals(recursive)) {
            data.addProperty("recursive", Boolean.parseBoolean(recursive));
        }

        Outcome outcome = send(data);

        assertThat(Files.exists(root.resolve(linkName), LinkOption.NOFOLLOW_LINKS))
                .as("the link entry is removed").isFalse();
        assertThat(child).as("the in-root target's contents are untouched").exists();
        assertThat(readText(child)).isEqualTo("child");
        assertThat(sentinel).as("the outside target's contents are untouched").exists();
        assertThat(readText(sentinel)).isEqualTo("sentinel");
        assertThat(Files.isSymbolicLink(root.resolve("dlink".equals(linkName) ? "olink" : "dlink")))
                .as("the other link is left alone").isTrue();
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", path);
    }

    // ------------------------------------------------------------------------------------------
    // Controls whose behaviour does not change (regression pins; identical before and after).
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control: reading an ordinary in-root file returns its bytes")
    void readOrdinaryInRootFileReturnsItsBytes() throws Exception {
        Outcome outcome = send(request("read", "root/secret.txt"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.lastData().get("content").getAsString()).isEqualTo(DECOY_SECRET);
        assertAllowedEntry(outcome, "read", "root/secret.txt");
    }

    @Test
    @DisplayName("control: reading an in-root link to an in-root file returns the target's bytes")
    void readInRootLinkToInRootFileReturnsTargetBytes() throws Exception {
        link(root.resolve("inlink"), root.resolve("secret.txt"));

        Outcome outcome = send(request("read", "root/inlink"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.lastData().get("content").getAsString()).isEqualTo(DECOY_SECRET);
        assertAllowedEntry(outcome, "read", "root/inlink");
    }

    @Test
    @DisplayName("control: root/sub/../file.txt without links reads root/file.txt")
    void readParentSegmentWithoutLinksReadsInRootFile() throws Exception {
        Files.write(root.resolve("file.txt"), "plain-file".getBytes(StandardCharsets.UTF_8));

        Outcome outcome = send(request("read", "root/sub/../file.txt"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.lastData().get("content").getAsString()).isEqualTo("plain-file");
        assertAllowedEntry(outcome, "read", "root/sub/../file.txt");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("control: a doubled separator reads the same object as the plain form")
    @ValueSource(strings = {"root/sub/file.txt", "root//sub/file.txt"})
    void readWithDoubledSeparatorReachesSameObject(String path) throws Exception {
        Files.write(root.resolve("sub").resolve("file.txt"), "sub-file".getBytes(StandardCharsets.UTF_8));

        Outcome outcome = send(request("read", path));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.lastData().get("content").getAsString()).isEqualTo("sub-file");
        assertAllowedEntry(outcome, "read", path);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("control: a trailing separator lists the same directory as the plain form")
    @ValueSource(strings = {"root/sub", "root/sub/"})
    void listWithTrailingSeparatorReachesSameObject(String path) throws Exception {
        Files.write(root.resolve("sub").resolve("file.txt"), "sub-file".getBytes(StandardCharsets.UTF_8));

        Outcome outcome = send(request("list", path));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.listedNames()).containsExactly("file.txt");
        assertAllowedEntry(outcome, "list", path);
    }

    @Test
    @DisplayName("control: writing a new file into an existing in-root directory creates it there")
    void writeNewFileIntoExistingDirectory() throws Exception {
        JsonObject data = request("write", "root/sub/new.txt");
        data.addProperty("content", "PAYLOAD");

        Outcome outcome = send(data);

        assertThat(outcome.success()).isTrue();
        assertThat(readText(root.resolve("sub").resolve("new.txt"))).isEqualTo("PAYLOAD");
        assertThat(filesNamed("new.txt")).hasSize(1);
        assertAllowedEntry(outcome, "write", "root/sub/new.txt");
    }

    @Test
    @DisplayName("control: writing root/new/deeper/x.txt creates both directories inside the root")
    void writeCreatesMissingDirectoriesInsideRoot() throws Exception {
        JsonObject data = request("write", "root/new/deeper/x.txt");
        data.addProperty("content", "PAYLOAD");

        Outcome outcome = send(data);

        assertThat(outcome.success()).isTrue();
        assertThat(readText(root.resolve("new").resolve("deeper").resolve("x.txt"))).isEqualTo("PAYLOAD");
        assertThat(filesNamed("x.txt")).hasSize(1);
        assertAllowedEntry(outcome, "write", "root/new/deeper/x.txt");
    }

    @Test
    @DisplayName("control: listing an in-root link to an in-root directory lists that directory")
    void listInRootLinkToInRootDirectoryListsTarget() throws Exception {
        Files.write(root.resolve("sub").resolve("file.txt"), "sub-file".getBytes(StandardCharsets.UTF_8));
        link(root.resolve("dirlink"), root.resolve("sub"));

        Outcome outcome = send(request("list", "root/dirlink"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.listedNames()).containsExactly("file.txt");
        assertAllowedEntry(outcome, "list", "root/dirlink");
    }

    @Test
    @DisplayName("control: listing root/sub/.. lists the root")
    void listParentSegmentWithoutLinksListsRoot() throws Exception {
        Outcome outcome = send(request("list", "root/sub/.."));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.listedNames()).contains("secret.txt", "victim.txt", "sub", "link", "esc", "dangling");
        assertAllowedEntry(outcome, "list", "root/sub/..");
    }

    @Test
    @DisplayName("negative control: a sibling prefix directory (root-evil) is refused configurably")
    void siblingPrefixDirectoryIsRefusedConfigurably() throws Exception {
        Path evil = Files.createDirectories(tmp.resolve("root-evil"));
        Files.write(evil.resolve("x"), OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));

        Outcome outcome = send(request("read", "root-evil/x"));

        assertThat(outcome.allMessagesJson()).doesNotContain(OUTSIDE_SECRET);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "read", "root-evil/x");
        assertThat(outcome.entry.getReason()).contains(ROOTS_KEY);
    }

    /**
     * Ordinary failures after an allowed decision keep the allowed decision (D-03): the audit
     * records policy, not I/O success. The last row pins a path that continues below a regular
     * file: nothing can exist there, so it fails like a missing file rather than as a refusal.
     */
    @ParameterizedTest(name = "[{index}] {0} {1}")
    @DisplayName("control: an ordinary failure after an allowed decision is audited ALLOWED")
    @CsvSource({
        "read, root/missing.txt",
        "read, root/sub",
        "write, root/sub/no-content.txt",
        "list, root/secret.txt",
        "delete, root/missing.txt",
        "read, root/secret.txt/x"
    })
    void ordinaryFailureIsAuditedAllowed(String operation, String path) throws Exception {
        Outcome outcome = send(request(operation, path));

        assertThat(outcome.success()).isFalse();
        assertThat(root.resolve("sub").resolve("no-content.txt")).doesNotExist();
        assertAllowedEntry(outcome, operation, path);
    }

    @Test
    @DisplayName("control: deleting an in-root link to an in-root file removes only the link")
    void deletingInRootLinkToInRootFileRemovesOnlyTheLink() throws Exception {
        Path inLink = root.resolve("inlink");
        link(inLink, root.resolve("secret.txt"));

        Outcome outcome = send(request("delete", "root/inlink"));

        assertThat(Files.exists(inLink, LinkOption.NOFOLLOW_LINKS)).as("the link entry is removed").isFalse();
        assertThat(readText(root.resolve("secret.txt"))).as("the target is untouched").isEqualTo(DECOY_SECRET);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "root/inlink");
    }

    @Test
    @DisplayName("control: deleting a link named data.json is refused and leaves link and target")
    void deletingLinkNamedDataJsonIsRefused() throws Exception {
        Path credentialLink = root.resolve("data.json");
        link(credentialLink, root.resolve("secret.txt"));

        Outcome outcome = send(request("delete", "root/data.json"));

        assertThat(Files.isSymbolicLink(credentialLink)).as("the link is left in place").isTrue();
        assertThat(readText(root.resolve("secret.txt"))).isEqualTo(DECOY_SECRET);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", "root/data.json");
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("control: list marking of a protected file and subdirectories is unchanged")
    void listMarkingOfProtectedFileIsUnchanged() throws Exception {
        Path marks = Files.createDirectories(root.resolve("marks"));
        Files.write(marks.resolve("plain.txt"), "plain".getBytes(StandardCharsets.UTF_8));
        Files.write(marks.resolve("data.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(marks.resolve("childdir"));

        Outcome outcome = send(request("list", "root/marks"));

        JsonObject plain = outcome.listedEntry("plain.txt");
        assertThat(plain.get("accessible").getAsBoolean()).isTrue();
        assertThat(plain.has("size")).isTrue();
        assertThat(plain.has("lastModified")).isTrue();
        JsonObject credential = outcome.listedEntry("data.json");
        assertThat(credential.get("accessible").getAsBoolean()).isFalse();
        assertThat(credential.get("reason").getAsString()).isEqualTo("PROTECTED_CREDENTIAL");
        assertThat(credential.has("size")).isFalse();
        assertThat(credential.has("lastModified")).isFalse();
        assertThat(outcome.listedEntry("childdir").get("accessible").getAsBoolean()).isTrue();
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "list", "root/marks");
    }

    // ------------------------------------------------------------------------------------------
    // D-08 adjacent candidate (NOT one of the five frozen cases): an in-root alias whose real
    // target is a protected file or lies in the action-log directory. Fixture: server root = the
    // temporary directory, single editable root = plugins; plugins/UltiTools/data.json holds a
    // synthetic token; plugins/UltiTools/security/action.log holds sentinel bytes;
    // plugins/notes.txt -> data.json, plugins/view.log -> action.log,
    // plugins/linkdir -> plugins/UltiTools.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D-08 adjacent candidate: an in-root alias to the credential file is refused")
    void aliasToProtectedCredentialIsRefused() throws Exception {
        buildAliasFixture();

        Outcome read = send(request("read", "plugins/notes.txt"));

        assertThat(read.allMessagesJson()).doesNotContain(TOKEN_SENTINEL);
        assertThat(read.success()).isFalse();
        assertDeniedEntry(read, "read", "plugins/notes.txt");
        assertNonConfigurable(read);

        resetCollaborators();
        JsonObject write = request("write", "plugins/notes.txt");
        write.addProperty("content", "OVERWRITTEN");
        Outcome written = send(write);

        assertThat(readText(dataJson)).as("the credential file is byte-identical").isEqualTo(DATA_JSON_CONTENT);
        assertThat(written.success()).isFalse();
        assertDeniedEntry(written, "write", "plugins/notes.txt");
    }

    @Test
    @DisplayName("D-08 adjacent candidate: an in-root alias to the action log is refused")
    void aliasToActionLogIsRefused() throws Exception {
        buildAliasFixture();

        Outcome read = send(request("read", "plugins/view.log"));

        assertThat(read.allMessagesJson()).doesNotContain(ACTION_LOG_SENTINEL);
        assertThat(read.success()).isFalse();
        assertDeniedEntry(read, "read", "plugins/view.log");

        resetCollaborators();
        JsonObject write = request("write", "plugins/view.log");
        write.addProperty("content", "OVERWRITTEN");
        Outcome written = send(write);

        assertThat(readText(actionLogFile)).as("the action log is byte-identical").isEqualTo(ACTION_LOG_SENTINEL);
        assertThat(written.success()).isFalse();
        assertDeniedEntry(written, "write", "plugins/view.log");
    }

    @Test
    @DisplayName("D-08 adjacent candidate: an aliased parent path into the action-log directory is refused")
    void aliasedParentPathIntoSecurityDirectoryIsRefused() throws Exception {
        buildAliasFixture();
        String path = "plugins/linkdir/security/action.log";

        Outcome read = send(request("read", path));

        assertThat(read.allMessagesJson()).doesNotContain(ACTION_LOG_SENTINEL);
        assertThat(read.success()).isFalse();
        assertDeniedEntry(read, "read", path);

        resetCollaborators();
        JsonObject write = request("write", path);
        write.addProperty("content", "OVERWRITTEN");
        Outcome written = send(write);

        assertThat(readText(actionLogFile)).as("the action log is byte-identical").isEqualTo(ACTION_LOG_SENTINEL);
        assertThat(written.success()).isFalse();
        assertDeniedEntry(written, "write", path);
    }

    @Test
    @DisplayName("D-08 adjacent candidate: listed aliases to protected targets are marked PROTECTED_CREDENTIAL")
    void aliasChildIsMarkedProtectedInList() throws Exception {
        buildAliasFixture();

        Outcome outcome = send(request("list", "plugins"));

        for (String alias : new String[] {"notes.txt", "view.log"}) {
            JsonObject entry = outcome.listedEntry(alias);
            assertThat(entry.get("accessible").getAsBoolean()).as(alias).isFalse();
            assertThat(entry.get("reason").getAsString()).as(alias).isEqualTo("PROTECTED_CREDENTIAL");
            assertThat(entry.has("size")).as(alias).isFalse();
            assertThat(entry.has("lastModified")).as(alias).isFalse();
        }
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "list", "plugins");
    }

    @Test
    @DisplayName("D-08 adjacent candidate: a recursive delete through an aliased parent of the action-log directory is refused")
    void recursiveDeleteThroughAliasedParentOfSecurityDirectoryIsRefused() throws Exception {
        buildAliasFixture();
        JsonObject data = request("delete", "plugins/linkdir/security");
        data.addProperty("recursive", true);

        Outcome outcome = send(data);

        assertThat(actionLogFile).as("the action log survives").exists();
        assertThat(readText(actionLogFile)).isEqualTo(ACTION_LOG_SENTINEL);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", "plugins/linkdir/security");
    }

    @Test
    @DisplayName("D-08 adjacent candidate: deleting the alias link itself removes only the link")
    void deletingAliasLinkRemovesOnlyTheLink() throws Exception {
        buildAliasFixture();
        Path notes = tmp.resolve("plugins").resolve("notes.txt");

        Outcome outcome = send(request("delete", "plugins/notes.txt"));

        assertThat(Files.exists(notes, LinkOption.NOFOLLOW_LINKS)).as("the alias link is removed").isFalse();
        assertThat(readText(dataJson)).as("the credential file is untouched").isEqualTo(DATA_JSON_CONTENT);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "plugins/notes.txt");
    }

    // ------------------------------------------------------------------------------------------
    // Review-1 finding (gate-1 security review of PR #686): a recursive delete never follows a
    // symbolic link found inside the deleted tree. Fixture: server root = the temporary
    // directory, single editable root = plugins; plugins/victim holds keep.txt and a real
    // subdirectory inner/ holding inner.txt; each test places one link inside that tree. A link
    // inside the tree is removed as a link; its target and the target's contents stay
    // byte-identical; real subdirectories are still emptied and removed.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a recursive delete removes an in-tree link to an outside directory as a link only")
    void recursiveDeleteDoesNotFollowInTreeLinkToOutsideDirectory() throws Exception {
        Path victim = buildRecursiveDeleteVictim();
        Path outsideDir = Files.createDirectories(outside.resolve("odir"));
        Path nested = Files.createDirectories(outsideDir.resolve("nested"));
        Files.write(outsideDir.resolve("sentinel.txt"), OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));
        Files.write(nested.resolve("deep.txt"), OUTSIDE_VICTIM.getBytes(StandardCharsets.UTF_8));
        Map<String, String> before = snapshot(outsideDir);
        link(victim.resolve("olink"), outsideDir);

        Outcome outcome = send(recursiveDelete("plugins/victim"));

        assertThat(Files.exists(victim, LinkOption.NOFOLLOW_LINKS))
                .as("the requested tree, the link inside it included, is removed").isFalse();
        assertThat(snapshot(outsideDir)).as("the outside directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "plugins/victim");
    }

    @Test
    @DisplayName("a recursive delete removes an in-tree link to the action-log directory as a link only")
    void recursiveDeleteDoesNotFollowInTreeLinkToActionLogDirectory() throws Exception {
        Path victim = buildRecursiveDeleteVictim();
        Path security = Files.createDirectories(tmp.resolve("plugins").resolve("UltiTools").resolve("security"));
        Files.write(security.resolve("action.log.0"), ACTION_LOG_SENTINEL.getBytes(StandardCharsets.UTF_8));
        Files.write(security.resolve("action.log.1"), (ACTION_LOG_SENTINEL + "-1").getBytes(StandardCharsets.UTF_8));
        Map<String, String> before = snapshot(security);
        link(victim.resolve("inner").resolve("loglink"), security);

        Outcome outcome = send(recursiveDelete("plugins/victim"));

        assertThat(Files.exists(victim, LinkOption.NOFOLLOW_LINKS))
                .as("the requested tree, the link inside it included, is removed").isFalse();
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "plugins/victim");
    }

    @Test
    @DisplayName("a recursive delete removes an in-tree link to the credential directory as a link only")
    void recursiveDeleteDoesNotFollowInTreeLinkToCredentialDirectory() throws Exception {
        Path victim = buildRecursiveDeleteVictim();
        Path credentialDir = Files.createDirectories(tmp.resolve(".ultikits"));
        Files.write(credentialDir.resolve(CredentialStore.CREDENTIAL_FILE_NAME),
                DATA_JSON_CONTENT.getBytes(StandardCharsets.UTF_8));
        Map<String, String> before = snapshot(credentialDir);
        link(victim.resolve("credlink"), credentialDir);

        Outcome outcome = send(recursiveDelete("plugins/victim"));

        assertThat(Files.exists(victim, LinkOption.NOFOLLOW_LINKS))
                .as("the requested tree, the link inside it included, is removed").isFalse();
        assertThat(snapshot(credentialDir)).as("the credential directory is byte-identical").isEqualTo(before);
        assertThat(outcome.allMessagesJson()).doesNotContain(TOKEN_SENTINEL);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "plugins/victim");
    }

    /** Builds plugins/victim (keep.txt, inner/inner.txt) and points the manager at root = plugins. */
    private Path buildRecursiveDeleteVictim() throws Exception {
        Path plugins = Files.createDirectories(tmp.resolve("plugins"));
        Path victim = Files.createDirectories(plugins.resolve("victim"));
        Path inner = Files.createDirectories(victim.resolve("inner"));
        Files.write(victim.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        Files.write(inner.resolve("inner.txt"), "inner".getBytes(StandardCharsets.UTF_8));
        pointManagerAt(tmp, plugins);
        return victim;
    }

    private static JsonObject recursiveDelete(String path) {
        JsonObject data = request("delete", path);
        data.addProperty("recursive", true);
        return data;
    }

    /** Every entry under {@code dir} (links not followed): relative path to bytes, or a directory marker. */
    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.collect(Collectors.toList())) {
                String key = dir.relativize(p).toString();
                entries.put(key, Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "<dir>" : readText(p));
            }
        }
        return entries;
    }

    private void buildAliasFixture() throws Exception {
        Path plugins = Files.createDirectories(tmp.resolve("plugins"));
        Path ultiTools = Files.createDirectories(plugins.resolve("UltiTools"));
        Path security = Files.createDirectories(ultiTools.resolve("security"));
        dataJson = ultiTools.resolve("data.json");
        actionLogFile = security.resolve("action.log");
        Files.write(dataJson, DATA_JSON_CONTENT.getBytes(StandardCharsets.UTF_8));
        Files.write(actionLogFile, ACTION_LOG_SENTINEL.getBytes(StandardCharsets.UTF_8));
        link(plugins.resolve("notes.txt"), dataJson);
        link(plugins.resolve("view.log"), actionLogFile);
        link(plugins.resolve("linkdir"), ultiTools);
        pointManagerAt(tmp, plugins);
    }

    // ------------------------------------------------------------------------------------------
    // Codex review run 2, finding 1: a link is never followed, whether it is the delete target
    // itself or inside the deleted tree. Every "real directory or link" decision uses one shared
    // predicate: an entry is a real directory only if it is a directory when links are not
    // followed AND its real path equals its parent's real path plus its own name. A Windows
    // junction passes the first test and fails the second; junctions cannot be created on Linux,
    // so the junction case is proven through the predicate with an injected real-path lookup.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control: a recursive delete of a terminal link to an outside directory removes only the link")
    void recursiveDeleteOfTerminalLinkToOutsideDirectoryRemovesOnlyTheLink() throws Exception {
        Map<String, String> before = snapshot(outside);

        Outcome outcome = send(recursiveDelete("root/esc"));

        assertThat(Files.exists(root.resolve("esc"), LinkOption.NOFOLLOW_LINKS))
                .as("the terminal link entry is removed").isFalse();
        assertThat(snapshot(outside)).as("the outside directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "root/esc");
    }

    @Test
    @DisplayName("the shared real-directory predicate refuses an entry whose real path is not its parent's real path plus its name")
    void sharedPredicateRefusesEntryWhoseRealPathIsNotParentPlusName() throws Exception {
        Method predicate = sharedRealDirectoryPredicate();
        assertThat(predicate)
                .as("FileOperationManager has one shared 'real directory or link' predicate "
                        + "isRealDirectory(Path parentReal, Path entry, Function<Path, Path> realPath)")
                .isNotNull();
        Path rootReal = root.toRealPath();
        Path sub = root.resolve("sub");
        Function<Path, Path> filesystem = FileOperationTargetConsistencyTest::realPathOrNull;
        Path junctionTarget = outside.resolve("deep").toRealPath();
        Function<Path, Path> junction = p -> p.equals(sub) ? junctionTarget : realPathOrNull(p);
        assertThat(Files.isDirectory(sub, LinkOption.NOFOLLOW_LINKS))
                .as("precondition: root/sub is a directory when links are not followed").isTrue();

        assertThat(invokePredicate(predicate, rootReal, sub, filesystem))
                .as("control: a real subdirectory is a real directory").isTrue();
        assertThat(invokePredicate(predicate, rootReal, sub, junction))
                .as("a directory entry whose real path is elsewhere (a junction) is a link, not a real directory")
                .isFalse();
        assertThat(invokePredicate(predicate, rootReal, root.resolve("link"), filesystem))
                .as("a symbolic link to a directory is not a real directory").isFalse();
        Path throughLinkedParent = root.resolve("esc").resolve("deep");
        assertThat(Files.isDirectory(throughLinkedParent, LinkOption.NOFOLLOW_LINKS))
                .as("precondition: root/esc/deep is a directory when its own name is not followed").isTrue();
        assertThat(invokePredicate(predicate, rootReal.resolve("esc"), throughLinkedParent, filesystem))
                .as("an entry reached through a linked parent is not a real child of that parent path")
                .isFalse();
    }

    @Test
    @DisplayName("a recursive delete whose terminal entry is a junction-like directory never recurses into it")
    void recursiveDeleteOfJunctionLikeTerminalEntryNeverRecurses() throws Exception {
        Path victim = buildRecursiveDeleteVictim();
        Path junctionTarget = Files.createDirectories(outside.resolve("odir")).toRealPath();
        // A real junction lists its target's children; give the target its own files, with bytes that
        // differ from the victim's copies, so that recursing "through" the junction would be visible.
        Files.write(junctionTarget.resolve("keep.txt"), OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));
        Path targetInner = Files.createDirectories(junctionTarget.resolve("inner"));
        Files.write(targetInner.resolve("inner.txt"), OUTSIDE_VICTIM.getBytes(StandardCharsets.UTF_8));
        Map<String, String> targetBefore = snapshot(junctionTarget);
        Map<String, String> victimBefore = snapshot(victim);
        Map<String, String> outsideBefore = snapshot(outside);
        assertThat(installRealPathLookup(p -> p.equals(victim) ? junctionTarget : realPathOrNull(p)))
                .as("FileOperationManager's shared predicate takes its real paths from one replaceable "
                        + "lookup (field realPathResolver), so a junction can be modelled on Linux")
                .isTrue();

        Outcome outcome = send(recursiveDelete("plugins/victim"));

        assertThat(snapshot(victim))
                .as("an entry whose real path is elsewhere is removed as an entry only, never emptied")
                .isEqualTo(victimBefore);
        assertThat(snapshot(outside)).as("the outside directory is byte-identical").isEqualTo(outsideBefore);
        assertThat(snapshot(junctionTarget))
                .as("the junction target's own files survive byte-identical").isEqualTo(targetBefore);
        assertAllowedEntry(outcome, "delete", "plugins/victim");
    }

    @Test
    @DisplayName("a listed link to an outside directory is marked OUTSIDE_ROOTS without metadata")
    void listedLinkToOutsideDirectoryIsMarkedOutsideRoots() throws Exception {
        Outcome outcome = send(request("list", "root"));

        for (String alias : new String[] {"link", "esc"}) {
            JsonObject entry = outcome.listedEntry(alias);
            assertThat(entry.get("accessible").getAsBoolean()).as(alias).isFalse();
            assertThat(entry.get("reason").getAsString()).as(alias).isEqualTo("OUTSIDE_ROOTS");
            assertThat(entry.has("size")).as(alias).isFalse();
            assertThat(entry.has("lastModified")).as(alias).isFalse();
        }
        assertThat(outcome.listedEntry("sub").get("accessible").getAsBoolean())
                .as("control: a real subdirectory keeps the never-refused rule").isTrue();
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "list", "root");
    }

    // ------------------------------------------------------------------------------------------
    // Codex review run 2, finding 2: protected locations are judged by real path, independent of
    // which editable root matched or the order of the roots. Fixture: server root = the temporary
    // directory; editable roots, in this order, = [plugins/shadow, plugins], where
    // plugins/shadow -> plugins/UltiTools; plugins/UltiTools/security holds action.log.0 and
    // action.log.1 with sentinel bytes.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("overlapping alias root: a recursive delete of plugins/UltiTools is refused and the action log is byte-identical")
    void overlappingAliasRootRecursiveDeleteOfUltiToolsIsRefused() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);

        Outcome outcome = send(recursiveDelete("plugins/UltiTools"));

        assertThat(security).as("the action-log directory survives").isDirectory();
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", "plugins/UltiTools");
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("overlapping alias root: a recursive delete of plugins/shadow/security is refused and the action log is byte-identical")
    void overlappingAliasRootRecursiveDeleteOfSecurityIsRefused() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);

        Outcome outcome = send(recursiveDelete("plugins/shadow/security"));

        assertThat(security).as("the action-log directory survives").isDirectory();
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "delete", "plugins/shadow/security");
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("overlapping alias root: deleting the plugins/shadow link removes only the link and the action log is byte-identical")
    void overlappingAliasRootDeleteOfShadowLinkRemovesOnlyTheLink() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);

        Outcome outcome = send(recursiveDelete("plugins/shadow"));

        assertThat(Files.exists(tmp.resolve("plugins").resolve("shadow"), LinkOption.NOFOLLOW_LINKS))
                .as("the link entry is removed (link-only deletion)").isFalse();
        assertThat(security).as("the action-log directory survives").isDirectory();
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isTrue();
        assertAllowedEntry(outcome, "delete", "plugins/shadow");
    }

    @Test
    @DisplayName("overlapping alias root: reading plugins/shadow/security/action.log.0 is refused")
    void overlappingAliasRootReadOfActionLogIsRefused() throws Exception {
        buildShadowRootFixture();
        String path = "plugins/shadow/security/action.log.0";

        Outcome outcome = send(request("read", path));

        assertThat(outcome.allMessagesJson()).doesNotContain(ACTION_LOG_SENTINEL);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "read", path);
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("overlapping alias root: writing plugins/shadow/security/action.log.0 is refused and the log is byte-identical")
    void overlappingAliasRootWriteOfActionLogIsRefused() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);
        String path = "plugins/shadow/security/action.log.0";
        JsonObject data = request("write", path);
        data.addProperty("content", "OVERWRITTEN");

        Outcome outcome = send(data);

        assertThat(security).as("the action-log directory survives").isDirectory();
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "write", path);
        assertNonConfigurable(outcome);
    }

    @Test
    @DisplayName("overlapping alias root: listing plugins/shadow/security is refused")
    void overlappingAliasRootListOfSecurityIsRefused() throws Exception {
        buildShadowRootFixture();
        String path = "plugins/shadow/security";

        Outcome outcome = send(request("list", path));

        assertThat(outcome.listedNames()).doesNotContain("action.log.0", "action.log.1");
        assertThat(outcome.success()).isFalse();
        assertDeniedEntry(outcome, "list", path);
        assertNonConfigurable(outcome);
    }

    /**
     * Builds plugins/UltiTools/security (action.log.0, action.log.1) and plugins/shadow ->
     * plugins/UltiTools, and points the manager at the roots [plugins/shadow, plugins] in that
     * order: the alias root is matched first.
     */
    private Path buildShadowRootFixture() throws Exception {
        Path plugins = Files.createDirectories(tmp.resolve("plugins"));
        Path ultiTools = Files.createDirectories(plugins.resolve("UltiTools"));
        Path security = Files.createDirectories(ultiTools.resolve("security"));
        Files.write(security.resolve("action.log.0"), ACTION_LOG_SENTINEL.getBytes(StandardCharsets.UTF_8));
        Files.write(security.resolve("action.log.1"), (ACTION_LOG_SENTINEL + "-1").getBytes(StandardCharsets.UTF_8));
        Files.write(ultiTools.resolve("config.yml"), "key: value".getBytes(StandardCharsets.UTF_8));
        link(plugins.resolve("shadow"), ultiTools);
        pointManagerAtRoots(tmp, plugins.resolve("shadow"), plugins);
        return security;
    }

    /** The shared predicate, or {@code null} when the production class does not have it. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the predicate is package-private and looked up by name
    private static Method sharedRealDirectoryPredicate() {
        try {
            Method method = FileOperationManager.class.getDeclaredMethod(
                    "isRealDirectory", Path.class, Path.class, Function.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static boolean invokePredicate(Method predicate, Path parentReal, Path entry,
                                           Function<Path, Path> realPath) throws Exception {
        return (Boolean) predicate.invoke(null, parentReal, entry, realPath);
    }

    /** Replaces the manager's real-path lookup; {@code false} when the production class has none. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the fixture models a junction on Linux
    private boolean installRealPathLookup(Function<Path, Path> lookup) throws IllegalAccessException {
        Field field;
        try {
            field = FileOperationManager.class.getDeclaredField("realPathResolver");
        } catch (NoSuchFieldException e) {
            return false;
        }
        field.setAccessible(true);
        field.set(manager, lookup);
        return true;
    }

    private static Path realPathOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    private void resetCollaborators() {
        org.mockito.Mockito.reset(actionLog, webSocket);
        when(webSocket.isConnected()).thenReturn(true);
        when(webSocket.getServerId()).thenReturn("test-server");
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

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the fixture points the manager at a temp tree
    private void pointManagerAtRoots(Path serverRoot, Path... editableRoots) throws Exception {
        pointManagerAt(serverRoot, editableRoots[0]);
        List<java.io.File> roots = new ArrayList<>();
        for (Path editableRoot : editableRoots) {
            roots.add(editableRoot.toFile());
        }
        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(manager, Collections.unmodifiableList(roots));
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
            return lastData().get("message").getAsString();
        }

        JsonObject lastData() {
            return responses.get(responses.size() - 1);
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
