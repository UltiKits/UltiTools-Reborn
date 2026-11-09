package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Target-consistency guarantees of the remote file API (issue #672, Phase 20.2), ported in 6.4.0
 * from the removed protocol v1 handler onto the protocol 2.1.0 {@code files.read},
 * {@code files.list}, {@code files.write} and {@code files.delete} requests: the object that is
 * authorized is the object the I/O reaches, through symbolic links, path aliases, link loops and
 * overlapping editable roots.
 *
 * <p>Requests go through {@link AgentMessageRouter} into {@link RemoteFileOperations}, exactly as
 * the live connection dispatches them; every assertion is a real filesystem effect, the reply the
 * panel receives (schema-checked by {@link RecordingChannel}) or the single action-log entry.</p>
 *
 * <p>Protocol 2.1.0 differs from v1 in four ways that shape the ported rows:</p>
 * <ul>
 *   <li>a {@code ServerPath} cannot carry {@code .} or {@code ..} segments, empty segments or a
 *       leading or trailing separator, so those request shapes are answered {@code invalid_message}
 *       before any filesystem access; the resolver's own refusal of them is still pinned directly
 *       on {@link FileOperationManager#resolveTarget} as defence in depth;</li>
 *   <li>a listing hides a refused child instead of marking it;</li>
 *   <li>{@code files.write} never creates a file and needs the current {@code baseVersion} (the
 *       rows below send the correct version, so only the guard can refuse);</li>
 *   <li>{@code files.delete} has no recursive form; a directory answers {@code is_directory}.</li>
 * </ul>
 *
 * <p>Standard fixture (built before every test): server root = the temporary directory, single
 * editable root = {@code root}; {@code outside/deep} (directory), {@code outside/secret.txt} and
 * {@code outside/victim.txt} carry sentinel bytes; in-root decoys {@code root/secret.txt} and
 * {@code root/victim.txt} carry different bytes; {@code root/sub} is a directory;
 * {@code root/link -> outside/deep}, {@code root/esc -> outside},
 * {@code root/dangling -> outside/created.txt} (absent).</p>
 */
@DisplayName("UltiCloud v2 files.*: the authorized object is the object the I/O reaches")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the fixture points the manager at a temp tree
class FileOperationTargetConsistencyTest {

    private static final String ROOTS_KEY = "ultipanel.files.editable-roots";
    private static final String NON_CONFIGURABLE_MARKER = "cannot be changed through configuration";
    private static final String NOT_ALLOWED = "path_not_allowed";

    private static final String OUTSIDE_SECRET = "OUTSIDE-SECRET-SENTINEL-7f3a";
    private static final String OUTSIDE_VICTIM = "OUTSIDE-VICTIM-SENTINEL-91c2";
    private static final String DECOY_SECRET = "in-root decoy secret";
    private static final String DECOY_VICTIM = "in-root decoy victim";
    private static final String TOKEN_SENTINEL = "SYNTHETIC-TOKEN-SENTINEL-d08";
    private static final String DATA_JSON_CONTENT = "{\"refresh_token\":\"" + TOKEN_SENTINEL + "\"}";
    private static final String ACTION_LOG_SENTINEL = "ACTION-LOG-SENTINEL-d08";
    /** A well-formed version that matches no fixture file. */
    private static final String ANY_VERSION = repeat('0', 64);

    @TempDir
    Path tmp;

    private FileOperationManager files;
    private AgentMessageRouter router;
    private RecordingChannel channel;
    private List<RemoteActionLog.Entry> audit;

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

        Logger logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
        });

        buildStandardFixture();
        files = new FileOperationManager();
        pointManagerAt(tmp, root);
        audit = Collections.synchronizedList(new ArrayList<RemoteActionLog.Entry>());
        RemoteFileOperations handler = new RemoteFileOperations(files, tmp.toFile(), audit::add, () -> true);
        router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("files.list", handler);
        router.register("files.read", handler);
        router.register("files.write", handler);
        router.register("files.delete", handler);
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------------------------------
    // Request shapes that relied on '..', '.', doubled or trailing separators. Protocol 2.1.0
    // refuses them at the contract layer; the guard still refuses the unsafe ones on its own.
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @DisplayName("a '..', '.', doubled or trailing separator path is invalid_message and touches nothing")
    @CsvSource({
        "files.read, root/link/../secret.txt",
        "files.write, root/link/../new.txt",
        "files.list, root/link/..",
        "files.delete, root/link/../victim.txt",
        "files.write, root/missing/../x.txt",
        "files.write, root/missing/./x.txt",
        "files.write, root/secret.txt/../x.txt",
        "files.delete, root/sub/..",
        "files.delete, /",
        "files.delete, .",
        "files.delete, root/sub/.",
        "files.read, root/sub/../secret.txt",
        "files.read, root//sub/file.txt",
        "files.list, root/sub/",
        "files.list, root/sub/.."
    })
    void unsafePathShapesAreRefusedBeforeAnyFilesystemAccess(String type, String path) throws IOException {
        Map<String, String> before = snapshot(tmp);

        dispatch(requestFor(type, path, "PAYLOAD", ANY_VERSION));

        JsonObject frame = only();
        assertThat(frame.get("type").getAsString()).isEqualTo("error");
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        assertThat(frame.toString()).doesNotContain(OUTSIDE_SECRET).doesNotContain(DECOY_SECRET);
        assertThat(snapshot(tmp)).as("nothing anywhere is created, changed or removed").isEqualTo(before);
        assertThat(audit).as("a request refused before a decision records nothing").isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0} {1} -> configurable={2}")
    @DisplayName("the guard itself still refuses a link followed by '..' and every unsafe '.' or '..' shape")
    @CsvSource({
        "FOLLOW, root/link/../secret.txt, true",
        "FOLLOW, root/link/../new.txt, true",
        "FOLLOW, root/link/.., true",
        "ENTRY, root/link/../victim.txt, true",
        "FOLLOW, root/missing/../x.txt, false",
        "FOLLOW, root/missing/./x.txt, false",
        "FOLLOW, root/secret.txt/../x.txt, false",
        "ENTRY, root/sub/.., false"
    })
    void guardRefusesUnsafeShapes(FileOperationManager.ResolveMode mode, String path, boolean configurable) {
        FileOperationManager.ResolvedTarget target = files.resolveTarget(path, mode);

        assertThat(target.getDecision().isAllowed()).isFalse();
        assertThat(target.getOperand()).isNull();
        assertThat(target.getDecision().isConfigurable()).isEqualTo(configurable);
        if (configurable) {
            assertThat(target.getDecision().getMessage()).contains(ROOTS_KEY);
        } else {
            assertThat(target.getDecision().getMessage()).contains(NON_CONFIGURABLE_MARKER).doesNotContain(ROOTS_KEY);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("the guard refuses a delete that names no ordinary entry ('/', '.', 'sub/.') non-configurably")
    @ValueSource(strings = {"/", ".", "sub/."})
    void guardRefusesDeleteWithoutOrdinaryTerminalName(String path) throws Exception {
        Path srv = Files.createDirectories(tmp.resolve("srv"));
        Files.createDirectories(srv.resolve("sub"));
        pointManagerAt(srv, srv);

        FileOperationManager.ResolvedTarget target =
                files.resolveTarget(path, FileOperationManager.ResolveMode.ENTRY);

        assertThat(target.getDecision().isAllowed()).isFalse();
        assertThat(target.getDecision().getMessage()).contains(NON_CONFIGURABLE_MARKER).doesNotContain(ROOTS_KEY);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @DisplayName("control: the guard resolves harmless '..', doubled and trailing separators to the plain object")
    @CsvSource({
        "root/sub/../file.txt, file.txt",
        "root//sub/inner.txt, sub/inner.txt",
        "root/sub/, sub",
        "root/sub/.., ''"
    })
    void guardResolvesHarmlessShapesToThePlainObject(String path, String expectedInRoot) throws IOException {
        Files.write(root.resolve("file.txt"), "plain-file".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("sub").resolve("inner.txt"), "inner".getBytes(StandardCharsets.UTF_8));

        FileOperationManager.ResolvedTarget target =
                files.resolveTarget(path, FileOperationManager.ResolveMode.FOLLOW);

        assertThat(target.getDecision().isAllowed()).isTrue();
        Path expected = expectedInRoot.isEmpty() ? root.toRealPath() : root.toRealPath().resolve(expectedInRoot);
        assertThat(target.getOperand().toPath()).isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------------------
    // The checked path and the used path must not differ (dangling link, real-path refusal).
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a write to a dangling link does not create the link's target and is refused non-configurably")
    void writeToDanglingLinkDoesNotCreateLinkTarget() throws IOException {
        dispatch(writeRequest("root/dangling", "PAYLOAD", ANY_VERSION));

        assertThat(outside.resolve("created.txt")).as("the dangling link's target must not be created")
                .doesNotExist();
        assertThat(Files.isSymbolicLink(root.resolve("dangling"))).as("the link itself is left alone").isTrue();
        assertDenied(Capability.FILE_WRITE, "files.write", "root/dangling", NOT_ALLOWED);
        assertNonConfigurable();
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @DisplayName("a refusal reached during real-path resolution is audited as exactly one DENIED entry")
    @CsvSource({
        "files.read, root/esc/secret.txt",
        "files.write, root/esc/secret.txt",
        "files.write, root/esc/new.txt",
        "files.list, root/esc",
        "files.delete, root/esc/secret.txt"
    })
    void realPathRefusalIsAuditedAsDenied(String type, String path) throws IOException {
        String version = RemoteFileOperations.sha256Hex(OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));

        dispatch(requestFor(type, path, "PAYLOAD", version));

        assertThat(readText(outsideSecret)).isEqualTo(OUTSIDE_SECRET);
        assertThat(readText(outsideVictim)).isEqualTo(OUTSIDE_VICTIM);
        assertThat(outside.resolve("new.txt")).doesNotExist();
        assertThat(only().toString()).doesNotContain(OUTSIDE_SECRET);
        assertDenied(capabilityOf(type), type, path, NOT_ALLOWED);
        assertThat(audit.get(0).getReason()).as("an out-of-root refusal names the editable-roots key")
                .contains(ROOTS_KEY);
    }

    @Test
    @DisplayName("a component under an unsearchable directory is refused as undeterminable")
    void unsearchableDirectoryComponentIsRefused() throws IOException {
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.write(locked.resolve("f.txt"), OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        try {
            Files.setPosixFilePermissions(locked, EnumSet.noneOf(PosixFilePermission.class));
            Assumptions.assumeFalse(Files.exists(locked.resolve("f.txt")),
                    "this user can search a mode-000 directory (running as root?); the row is BLOCKED here");

            dispatch(readRequest("root/locked/f.txt"));

            assertThat(only().toString()).doesNotContain(OUTSIDE_SECRET);
            assertDenied(Capability.FILE_READ, "files.read", "root/locked/f.txt", NOT_ALLOWED);
            assertNonConfigurable();
        } finally {
            Files.setPosixFilePermissions(locked, original);
        }
    }

    @Test
    @DisplayName("a self-referencing link is refused as unresolvable")
    void linkLoopIsRefusedAsUnresolvable() throws IOException {
        Path loop = root.resolve("loop");
        link(loop, loop);

        dispatch(readRequest("root/loop"));

        assertThat(Files.isSymbolicLink(loop)).isTrue();
        assertDenied(Capability.FILE_READ, "files.read", "root/loop", NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("negative control: a sibling prefix directory (root-evil) is refused configurably")
    void siblingPrefixDirectoryIsRefusedConfigurably() throws IOException {
        Path evil = Files.createDirectories(tmp.resolve("root-evil"));
        Files.write(evil.resolve("x"), OUTSIDE_SECRET.getBytes(StandardCharsets.UTF_8));

        dispatch(readRequest("root-evil/x"));

        assertThat(only().toString()).doesNotContain(OUTSIDE_SECRET);
        assertDenied(Capability.FILE_READ, "files.read", "root-evil/x", NOT_ALLOWED);
        assertThat(audit.get(0).getReason()).contains(ROOTS_KEY);
    }

    @Test
    @DisplayName("control: a path continuing below a regular file is not_found, not a refusal")
    void pathBelowARegularFileIsNotFound() {
        dispatch(readRequest("root/secret.txt/x"));

        JsonObject payload = only().getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("not_found");
        assertThat(payload.get("message").getAsString()).doesNotContain(NON_CONFIGURABLE_MARKER)
                .doesNotContain(ROOTS_KEY);
    }

    // ------------------------------------------------------------------------------------------
    // Links: deletion removes only the link; in-root links reach their in-root targets.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("deleting a dangling link removes the link and creates nothing outside")
    void deletingDanglingLinkRemovesTheLink() {
        dispatch(deleteRequest("root/dangling"));

        assertThat(okPayload()).isNotNull();
        assertThat(Files.exists(root.resolve("dangling"), LinkOption.NOFOLLOW_LINKS))
                .as("the dangling link is removed").isFalse();
        assertThat(outside.resolve("created.txt")).as("nothing is created outside").doesNotExist();
        assertAllowed(Capability.FILE_DELETE, "files.delete", "root/dangling");
    }

    /**
     * DIRLINK_ANSWER=A (maintainer, 2026-10-18): a panel delete of a symbolic link that points to
     * a directory removes only the link; the target directory and its contents are untouched.
     */
    @ParameterizedTest(name = "[{index}] delete root/{0}")
    @DisplayName("deleting a link to a directory removes only the link (DIRLINK_ANSWER=A)")
    @ValueSource(strings = {"dlink", "olink", "esc"})
    void deletingDirectoryLinkRemovesOnlyTheLink(String linkName) throws IOException {
        Path realDir = Files.createDirectories(root.resolve("realdir"));
        Path child = realDir.resolve("child.txt");
        Files.write(child, "child".getBytes(StandardCharsets.UTF_8));
        Path outsideDir = Files.createDirectories(outside.resolve("odir"));
        Path sentinel = outsideDir.resolve("sentinel.txt");
        Files.write(sentinel, "sentinel".getBytes(StandardCharsets.UTF_8));
        link(root.resolve("dlink"), realDir);
        link(root.resolve("olink"), outsideDir);
        Map<String, String> outsideBefore = snapshot(outside);
        String path = "root/" + linkName;

        dispatch(deleteRequest(path));

        assertThat(okPayload()).isNotNull();
        assertThat(Files.exists(root.resolve(linkName), LinkOption.NOFOLLOW_LINKS))
                .as("the link entry is removed").isFalse();
        assertThat(readText(child)).as("the in-root target's contents are untouched").isEqualTo("child");
        assertThat(snapshot(outside)).as("the outside tree is byte-identical").isEqualTo(outsideBefore);
        assertAllowed(Capability.FILE_DELETE, "files.delete", path);
    }

    @Test
    @DisplayName("control: reading an in-root link to an in-root file returns the target's bytes")
    void readInRootLinkToInRootFileReturnsTargetBytes() throws IOException {
        link(root.resolve("inlink"), root.resolve("secret.txt"));

        dispatch(readRequest("root/inlink"));

        assertThat(okPayload().getAsJsonObject("data").get("content").getAsString()).isEqualTo(DECOY_SECRET);
        assertAllowed(Capability.FILE_READ, "files.read", "root/inlink");
    }

    @Test
    @DisplayName("control: listing an in-root link to an in-root directory lists that directory")
    void listInRootLinkToInRootDirectoryListsTarget() throws IOException {
        Files.write(root.resolve("sub").resolve("file.txt"), "sub-file".getBytes(StandardCharsets.UTF_8));
        link(root.resolve("dirlink"), root.resolve("sub"));

        dispatch(listRequest("root/dirlink"));

        assertThat(listedNames()).containsExactly("file.txt");
        assertAllowed(Capability.FILE_READ, "files.list", "root/dirlink");
    }

    @Test
    @DisplayName("a listing hides links to outside directories and dangling links, and keeps real subdirectories")
    void listedLinksToOutsideAreHidden() {
        dispatch(listRequest("root"));

        assertThat(listedNames()).containsExactlyInAnyOrder("secret.txt", "victim.txt", "sub");
        assertAllowed(Capability.FILE_READ, "files.list", "root");
    }

    @Test
    @DisplayName("a listing hides an in-root alias to an outside file")
    void aliasToOutsideFileIsHiddenFromList() throws IOException {
        Path aliasDir = Files.createDirectories(root.resolve("aliasdir"));
        Files.write(aliasDir.resolve("plain.txt"), "plain".getBytes(StandardCharsets.UTF_8));
        link(aliasDir.resolve("alias.txt"), outsideSecret);

        dispatch(listRequest("root/aliasdir"));

        assertThat(listedNames()).containsExactly("plain.txt");
        assertThat(only().toString()).doesNotContain(OUTSIDE_SECRET);
        assertAllowed(Capability.FILE_READ, "files.list", "root/aliasdir");
    }

    @Test
    @DisplayName("deleting a link named data.json is refused and leaves link and target")
    void deletingLinkNamedDataJsonIsRefused() throws IOException {
        Path credentialLink = root.resolve("data.json");
        link(credentialLink, root.resolve("secret.txt"));

        dispatch(deleteRequest("root/data.json"));

        assertThat(Files.isSymbolicLink(credentialLink)).as("the link is left in place").isTrue();
        assertThat(readText(root.resolve("secret.txt"))).isEqualTo(DECOY_SECRET);
        assertDenied(Capability.FILE_DELETE, "files.delete", "root/data.json", NOT_ALLOWED);
        assertNonConfigurable();
    }

    // ------------------------------------------------------------------------------------------
    // D-08: an in-root alias whose real target is a protected file or lies in the action-log
    // directory. Fixture: editable root = plugins; plugins/UltiTools/data.json holds a synthetic
    // token; plugins/UltiTools/security/action.log holds sentinel bytes;
    // plugins/notes.txt -> data.json, plugins/view.log -> action.log, plugins/linkdir -> plugins/UltiTools.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D-08: an in-root alias to the credential file is refused for read and write")
    void aliasToProtectedCredentialIsRefused() throws Exception {
        buildAliasFixture();

        dispatch(readRequest("plugins/notes.txt"));
        assertThat(only().toString()).doesNotContain(TOKEN_SENTINEL);
        assertDenied(Capability.FILE_READ, "files.read", "plugins/notes.txt", NOT_ALLOWED);
        assertNonConfigurable();

        dispatch(writeRequest("plugins/notes.txt", "OVERWRITTEN", versionOf(dataJson)));
        assertThat(readText(dataJson)).as("the credential file is byte-identical").isEqualTo(DATA_JSON_CONTENT);
        assertDenied(Capability.FILE_WRITE, "files.write", "plugins/notes.txt", NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("D-08: an in-root alias to the action log is refused for read and write")
    void aliasToActionLogIsRefused() throws Exception {
        buildAliasFixture();

        dispatch(readRequest("plugins/view.log"));
        assertThat(only().toString()).doesNotContain(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_READ, "files.read", "plugins/view.log", NOT_ALLOWED);
        assertNonConfigurable();

        dispatch(writeRequest("plugins/view.log", "OVERWRITTEN", versionOf(actionLogFile)));
        assertThat(readText(actionLogFile)).as("the action log is byte-identical").isEqualTo(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_WRITE, "files.write", "plugins/view.log", NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("D-08: an aliased parent path into the action-log directory is refused for read and write")
    void aliasedParentPathIntoSecurityDirectoryIsRefused() throws Exception {
        buildAliasFixture();
        String path = "plugins/linkdir/security/action.log";

        dispatch(readRequest(path));
        assertThat(only().toString()).doesNotContain(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_READ, "files.read", path, NOT_ALLOWED);
        assertNonConfigurable();

        dispatch(writeRequest(path, "OVERWRITTEN", versionOf(actionLogFile)));
        assertThat(readText(actionLogFile)).as("the action log is byte-identical").isEqualTo(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_WRITE, "files.write", path, NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("D-08: a listing hides aliases to protected targets and keeps the aliased directory")
    void aliasesToProtectedTargetsAreHiddenFromList() throws Exception {
        buildAliasFixture();

        dispatch(listRequest("plugins"));

        assertThat(listedNames()).containsExactlyInAnyOrder("UltiTools", "linkdir");
        assertThat(only().toString()).doesNotContain(TOKEN_SENTINEL).doesNotContain(ACTION_LOG_SENTINEL);
        assertAllowed(Capability.FILE_READ, "files.list", "plugins");
    }

    @Test
    @DisplayName("D-08: a delete through an aliased parent of the action-log directory is refused")
    void deleteThroughAliasedParentOfSecurityDirectoryIsRefused() throws Exception {
        buildAliasFixture();

        dispatch(deleteRequest("plugins/linkdir/security"));
        assertThat(readText(actionLogFile)).isEqualTo(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_DELETE, "files.delete", "plugins/linkdir/security", NOT_ALLOWED);
        assertNonConfigurable();

        dispatch(deleteRequest("plugins/linkdir/security/action.log"));
        assertThat(readText(actionLogFile)).as("the action log survives").isEqualTo(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_DELETE, "files.delete", "plugins/linkdir/security/action.log", NOT_ALLOWED);
        assertNonConfigurable();
    }

    // ------------------------------------------------------------------------------------------
    // Protected locations are judged by real path, independent of which editable root matched or
    // the order of the roots. Fixture: editable roots, in this order, = [plugins/shadow, plugins],
    // where plugins/shadow -> plugins/UltiTools; plugins/UltiTools/security holds action.log.0 and
    // action.log.1 with sentinel bytes.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("overlapping alias root: deleting plugins/UltiTools is refused and the action log is byte-identical")
    void overlappingAliasRootDeleteOfUltiToolsLeavesActionLog() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);

        dispatch(deleteRequest("plugins/UltiTools"));

        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertDenied(Capability.FILE_DELETE, "files.delete", "plugins/UltiTools", "is_directory");
    }

    @Test
    @DisplayName("overlapping alias root: deleting plugins/shadow/security or a log inside it is refused")
    void overlappingAliasRootDeleteOfSecurityIsRefused() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);

        dispatch(deleteRequest("plugins/shadow/security"));
        assertThat(snapshot(security)).isEqualTo(before);
        assertDenied(Capability.FILE_DELETE, "files.delete", "plugins/shadow/security", NOT_ALLOWED);
        assertNonConfigurable();

        dispatch(deleteRequest("plugins/shadow/security/action.log.0"));
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertDenied(Capability.FILE_DELETE, "files.delete", "plugins/shadow/security/action.log.0", NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("overlapping alias root: deleting the plugins/shadow link removes only the link")
    void overlappingAliasRootDeleteOfShadowLinkRemovesOnlyTheLink() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);

        dispatch(deleteRequest("plugins/shadow"));

        assertThat(okPayload()).isNotNull();
        assertThat(Files.exists(tmp.resolve("plugins").resolve("shadow"), LinkOption.NOFOLLOW_LINKS))
                .as("the link entry is removed (link-only deletion)").isFalse();
        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertAllowed(Capability.FILE_DELETE, "files.delete", "plugins/shadow");
    }

    @Test
    @DisplayName("overlapping alias root: reading plugins/shadow/security/action.log.0 is refused")
    void overlappingAliasRootReadOfActionLogIsRefused() throws Exception {
        buildShadowRootFixture();
        String path = "plugins/shadow/security/action.log.0";

        dispatch(readRequest(path));

        assertThat(only().toString()).doesNotContain(ACTION_LOG_SENTINEL);
        assertDenied(Capability.FILE_READ, "files.read", path, NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("overlapping alias root: writing plugins/shadow/security/action.log.0 is refused and the log is byte-identical")
    void overlappingAliasRootWriteOfActionLogIsRefused() throws Exception {
        Path security = buildShadowRootFixture();
        Map<String, String> before = snapshot(security);
        String path = "plugins/shadow/security/action.log.0";

        dispatch(writeRequest(path, "OVERWRITTEN", versionOf(security.resolve("action.log.0"))));

        assertThat(snapshot(security)).as("the action-log directory is byte-identical").isEqualTo(before);
        assertDenied(Capability.FILE_WRITE, "files.write", path, NOT_ALLOWED);
        assertNonConfigurable();
    }

    @Test
    @DisplayName("overlapping alias root: listing plugins/shadow/security is refused")
    void overlappingAliasRootListOfSecurityIsRefused() throws Exception {
        buildShadowRootFixture();
        String path = "plugins/shadow/security";

        dispatch(listRequest(path));

        assertThat(only().toString()).doesNotContain("action.log.0").doesNotContain("action.log.1");
        assertDenied(Capability.FILE_READ, "files.list", path, NOT_ALLOWED);
        assertNonConfigurable();
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures.
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
        pointManagerAt(tmp, plugins.resolve("shadow"), plugins);
        return security;
    }

    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic link creation is refused for this user/filesystem: " + e.getMessage());
        }
    }

    private void pointManagerAt(Path serverRoot, Path... editableRoots) throws Exception {
        Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
        serverRootField.setAccessible(true);
        serverRootField.set(files, serverRoot.toFile());

        List<File> roots = new ArrayList<>();
        for (Path editableRoot : editableRoots) {
            roots.add(editableRoot.toFile());
        }
        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(files, Collections.unmodifiableList(roots));
    }

    // ------------------------------------------------------------------------------------------
    // Requests and assertions.
    // ------------------------------------------------------------------------------------------

    /** Clears the previous reply and audit, then dispatches one request frame. */
    private void dispatch(JsonObject frame) {
        channel.clear();
        audit.clear();
        router.dispatch(frame.toString(), channel);
    }

    private static JsonObject requestFor(String type, String path, String content, String baseVersion) {
        switch (type) {
            case "files.read":
                return readRequest(path);
            case "files.list":
                return listRequest(path);
            case "files.write":
                return writeRequest(path, content, baseVersion);
            case "files.delete":
                return deleteRequest(path);
            default:
                throw new IllegalArgumentException(type);
        }
    }

    private static JsonObject readRequest(String path) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.read/valid/config.json");
        frame.getAsJsonObject("payload").addProperty("path", path);
        return frame;
    }

    private static JsonObject listRequest(String path) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.list/valid/plugins-dir.json");
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

    private static JsonObject deleteRequest(String path) {
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.delete/valid/file.json");
        frame.getAsJsonObject("payload").addProperty("path", path);
        return frame;
    }

    private static Capability capabilityOf(String type) {
        if ("files.write".equals(type)) {
            return Capability.FILE_WRITE;
        }
        if ("files.delete".equals(type)) {
            return Capability.FILE_DELETE;
        }
        return Capability.FILE_READ;
    }

    private JsonObject only() {
        assertThat(channel.frames()).hasSize(1);
        return channel.frames().get(0);
    }

    private JsonObject okPayload() {
        JsonObject payload = only().getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).as(payload.toString()).isEqualTo("ok");
        return payload;
    }

    private List<String> listedNames() {
        JsonArray entries = okPayload().getAsJsonObject("data").getAsJsonArray("entries");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            names.add(entries.get(i).getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    /** One error reply with {@code code}, and exactly one DENIED entry carrying the text the panel received. */
    private void assertDenied(Capability capability, String action, String path, String code) {
        JsonObject payload = only().getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).as(payload.toString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).as(payload.toString()).isEqualTo(code);
        assertThat(audit).hasSize(1);
        RemoteActionLog.Entry entry = audit.get(0);
        assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(entry.getCapability()).isEqualTo(capability.name());
        assertThat(entry.getAction()).isEqualTo(action);
        assertThat(entry.getTarget()).isEqualTo(path);
        assertThat(entry.getActor()).isEqualTo("panel");
        assertThat(entry.getReason()).as("the audited reason is the message the panel received")
                .isNotBlank().isEqualTo(payload.get("message").getAsString());
    }

    private void assertAllowed(Capability capability, String action, String path) {
        assertThat(audit).hasSize(1);
        RemoteActionLog.Entry entry = audit.get(0);
        assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(entry.getCapability()).isEqualTo(capability.name());
        assertThat(entry.getAction()).isEqualTo(action);
        assertThat(entry.getTarget()).isEqualTo(path);
        assertThat(entry.getActor()).isEqualTo("panel");
    }

    private void assertNonConfigurable() {
        assertThat(audit.get(0).getReason()).contains(NON_CONFIGURABLE_MARKER).doesNotContain(ROOTS_KEY);
    }

    private static String versionOf(Path file) throws IOException {
        return RemoteFileOperations.sha256Hex(Files.readAllBytes(file));
    }

    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Every entry under {@code dir}, links not followed: relative path to bytes, a directory
     * marker, or a link marker naming the link's target.
     */
    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.collect(Collectors.toList())) {
                String key = dir.relativize(p).toString();
                String value;
                if (Files.isSymbolicLink(p)) {
                    value = "<link> " + Files.readSymbolicLink(p);
                } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    value = "<dir>";
                } else {
                    value = readText(p);
                }
                entries.put(key, value);
            }
        }
        return entries;
    }

    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(character);
        }
        return text.toString();
    }
}
