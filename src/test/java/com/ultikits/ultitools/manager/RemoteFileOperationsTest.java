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
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * The three mutating and listing operations of the protocol 2.0.0 file surface, driven through
 * {@link AgentMessageRouter} into {@link RemoteFileOperations}.
 *
 * <p>Every assertion is a real filesystem effect plus the reply the panel receives; every reply is
 * schema-checked by {@link RecordingChannel}. The optimistic-concurrency assertions are the point
 * of the class: a write only lands when its {@code baseVersion} still equals the SHA-256 of the
 * bytes on disk, so a file the operator edited after the panel read it is never overwritten.</p>
 */
@DisplayName("UltiCloud v2 files.list / files.write / files.delete")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the fixture points the manager at a temp tree
class RemoteFileOperationsTest {

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

        Files.createDirectories(tmp.resolve("plugins"));
        Files.createDirectories(tmp.resolve("logs"));
        files = new FileOperationManager();
        pointManagerAt(files, tmp, tmp.resolve("plugins"), tmp.resolve("logs"));
        handler = new RemoteFileOperations(files, tmp.toFile());
        router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("files.list", handler);
        router.register("files.write", handler);
        router.register("files.delete", handler);
        channel = new RecordingChannel();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // --------------------------------------------------------------------------------------
    // files.list
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("a directory listing reports type, size and modified time, and hides refused children")
    void listReportsEntriesAndHidesRefusedChildren() throws IOException {
        write("plugins/config.yml", "a: 1\n");
        Files.createDirectories(tmp.resolve("plugins/UltiTools"));
        write("plugins/latest.log", "line\n");
        Files.write(tmp.resolve("plugins/blob.jar"), new byte[]{'x'});
        Files.write(tmp.resolve("plugins/credentials.json"), new byte[]{'{', '}'});

        JsonObject frame = ContractFixtures.object("fixtures/agent/files.list/valid/plugins-dir.json");
        frame.getAsJsonObject("payload").addProperty("path", "plugins");
        router.dispatch(frame.toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = payload.getAsJsonObject("data");
        assertThat(data.get("path").getAsString()).isEqualTo("plugins");
        assertThat(data.get("truncated").getAsBoolean()).isFalse();
        assertThat(names(data.getAsJsonArray("entries")))
                .containsExactly("UltiTools", "config.yml", "latest.log");

        JsonObject dir = entry(data.getAsJsonArray("entries"), "UltiTools");
        assertThat(dir.get("type").getAsString()).isEqualTo("dir");
        assertThat(dir.get("sizeBytes").isJsonNull()).isTrue();
        assertThat(dir.get("modifiedAt").isJsonNull()).isTrue();

        JsonObject file = entry(data.getAsJsonArray("entries"), "config.yml");
        assertThat(file.get("type").getAsString()).isEqualTo("file");
        assertThat(file.get("sizeBytes").getAsLong()).isEqualTo(5L);
        assertThat(file.get("modifiedAt").getAsLong()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("the empty path lists only the editable roots")
    void emptyPathListsOnlyTheEditableRoots() throws IOException {
        write("world/level.dat", "binary");
        JsonObject frame = ContractFixtures.object("fixtures/agent/files.list/valid/root.json");
        router.dispatch(frame.toString(), channel);

        JsonObject data = only(channel).getAsJsonObject("payload").getAsJsonObject("data");
        assertThat(data.get("path").getAsString()).isEmpty();
        assertThat(names(data.getAsJsonArray("entries")))
                .containsExactly("logs", "plugins");
        assertThat(entry(data.getAsJsonArray("entries"), "plugins").get("type").getAsString()).isEqualTo("dir");
    }

    @Test
    @DisplayName("a listing of more than 2000 entries is truncated at 2000 and flagged")
    void largeListingIsTruncated() throws IOException {
        Path many = tmp.resolve("plugins/many");
        Files.createDirectories(many);
        for (int i = 0; i < 2001; i++) {
            Files.write(many.resolve(String.format("f%04d.yml", i)), new byte[]{'x'});
        }

        router.dispatch(listRequest("plugins/many").toString(), channel);

        JsonObject data = only(channel).getAsJsonObject("payload").getAsJsonObject("data");
        assertThat(data.get("truncated").getAsBoolean()).isTrue();
        assertThat(data.getAsJsonArray("entries")).hasSize(2000);
    }

    @Test
    @DisplayName("listing a file is not_a_directory and an unknown path is not_found")
    void listingNonDirectoriesAnswersItsOwnCodes() throws IOException {
        write("plugins/config.yml", "a: 1\n");

        assertError(listRequest("plugins/config.yml"), "not_a_directory");
        assertError(listRequest("plugins/absent"), "not_found");
    }

    // --------------------------------------------------------------------------------------
    // files.write
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("a write with the current version replaces the content and answers the new version")
    void writeWithCurrentVersionReplacesContent() throws IOException {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(config));
        String content = "enabled: false\n";

        JsonObject frame = ContractFixtures.object("fixtures/agent/files.write/valid/with-base-version.json");
        frame.getAsJsonObject("payload").addProperty("path", "plugins/UltiTools/config.yml");
        frame.getAsJsonObject("payload").addProperty("content", content);
        frame.getAsJsonObject("payload").addProperty("baseVersion", baseVersion);
        router.dispatch(frame.toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        JsonObject data = payload.getAsJsonObject("data");
        assertThat(data.get("path").getAsString()).isEqualTo("plugins/UltiTools/config.yml");
        assertThat(data.get("sizeBytes").getAsLong()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(data.get("version").getAsString())
                .isEqualTo(RemoteFilesEndToEndTest.sha256(content.getBytes(StandardCharsets.UTF_8)));
        assertThat(new String(Files.readAllBytes(config), StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("a write keeps the file's POSIX permissions")
    void writeKeepsPosixPermissions() throws IOException {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");
        Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);
        try {
            Files.setPosixFilePermissions(config, permissions);
        } catch (UnsupportedOperationException noPosix) {
            return;
        }
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(config));

        router.dispatch(writeRequest("plugins/UltiTools/config.yml", "enabled: false\n", baseVersion).toString(),
                channel);

        assertThat(only(channel).getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(Files.getPosixFilePermissions(config)).isEqualTo(permissions);
    }

    @Test
    @DisplayName("a stale version is file_changed and the operator's bytes survive")
    void staleVersionLeavesTheFileUntouched() throws IOException {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(config));
        write("plugins/UltiTools/config.yml", "operator: edit\n");
        byte[] operatorBytes = Files.readAllBytes(config);

        router.dispatch(writeRequest("plugins/UltiTools/config.yml", "panel: write\n", baseVersion).toString(),
                channel);

        assertThat(only(channel).getAsJsonObject("payload").get("code").getAsString()).isEqualTo("file_changed");
        assertThat(Files.readAllBytes(config)).isEqualTo(operatorBytes);
    }

    @Test
    @DisplayName("a write never creates a file and never accepts content outside the file-content rule")
    void writeRefusesMissingTargetsAndUneditableContent() throws IOException {
        String any = RemoteFilesEndToEndTest.sha256(new byte[0]);
        assertError(writeRequest("plugins/UltiTools/absent.yml", "a: 1\n", any), "not_found");
        assertThat(tmp.resolve("plugins/UltiTools/absent.yml")).doesNotExist();

        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");
        byte[] untouched = Files.readAllBytes(config);
        String current = RemoteFilesEndToEndTest.sha256(untouched);
        assertError(writeRequest("plugins/UltiTools/config.yml", "a\u0000b", current), "file_not_editable");
        assertThat(Files.readAllBytes(config)).isEqualTo(untouched);
    }

    @Test
    @DisplayName("an operator-pinned read-only file is write_denied and stays byte-identical")
    void readOnlyFileIsWriteDenied() throws IOException {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");
        try {
            Files.setPosixFilePermissions(config, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException noPosix) {
            return;
        }
        byte[] untouched = Files.readAllBytes(config);
        String baseVersion = RemoteFilesEndToEndTest.sha256(untouched);

        router.dispatch(writeRequest("plugins/UltiTools/config.yml", "enabled: false\n", baseVersion).toString(),
                channel);

        assertThat(only(channel).getAsJsonObject("payload").get("code").getAsString()).isEqualTo("write_denied");
        assertThat(Files.readAllBytes(config)).isEqualTo(untouched);
    }

    @Test
    @DisplayName("two concurrent writes with the same version: exactly one wins, the other is file_changed")
    void concurrentWritesWithTheSameVersionHaveOneWinner() throws Exception {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");
        String baseVersion = RemoteFilesEndToEndTest.sha256(Files.readAllBytes(config));

        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        handler.setWriteGate(() -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        RecordingChannel first = new RecordingChannel();
        RecordingChannel second = new RecordingChannel();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread one = new Thread(() -> dispatchQuietly(
                writeRequest("plugins/UltiTools/config.yml", "winner: one\n", baseVersion), first, failure));
        Thread two = new Thread(() -> dispatchQuietly(
                writeRequest("plugins/UltiTools/config.yml", "winner: two\n", baseVersion), second, failure));

        one.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        two.start();
        Thread.sleep(150);
        release.countDown();
        one.join(TimeUnit.SECONDS.toMillis(20));
        two.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(failure.get()).isNull();
        List<String> outcomes = new ArrayList<>();
        outcomes.add(statusOf(first));
        outcomes.add(statusOf(second));
        assertThat(outcomes).containsExactlyInAnyOrder("ok", "error");
        String loserCode = "ok".equals(statusOf(first))
                ? payloadOf(second).get("code").getAsString()
                : payloadOf(first).get("code").getAsString();
        assertThat(loserCode).isEqualTo("file_changed");
        String winner = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        assertThat(winner).isIn("winner: one\n", "winner: two\n");
    }

    // --------------------------------------------------------------------------------------
    // files.delete
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("a file is deleted and answers an empty data object")
    void deleteRemovesTheFile() throws IOException {
        Path config = write("plugins/UltiTools/config.yml", "enabled: true\n");

        JsonObject frame = ContractFixtures.object("fixtures/agent/files.delete/valid/file.json");
        frame.getAsJsonObject("payload").addProperty("path", "plugins/UltiTools/config.yml");
        router.dispatch(frame.toString(), channel);

        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").entrySet()).isEmpty();
        assertThat(config).doesNotExist();
    }

    @Test
    @DisplayName("a directory is is_directory, an absent path is not_found, the v1 recursive shape is invalid")
    void deleteRefusesDirectoriesAndV1Shapes() throws IOException {
        Files.createDirectories(tmp.resolve("plugins/UltiTools"));
        assertError(deleteRequest("plugins/UltiTools"), "is_directory");
        assertThat(tmp.resolve("plugins/UltiTools")).exists();
        assertError(deleteRequest("plugins/absent.yml"), "not_found");

        ContractFixtures.Fixture recursive = ContractFixtures.invalidFrames("files.delete").get(0);
        assertThat(recursive.path()).contains("v1-recursive");
        channel.clear();
        router.dispatch(recursive.frame().toString(), channel);
        JsonObject frame = only(channel);
        assertThat(frame.get("type").getAsString()).isEqualTo("error");
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
    }

    // --------------------------------------------------------------------------------------
    // helpers
    // --------------------------------------------------------------------------------------

    private void dispatchQuietly(JsonObject frame, RecordingChannel target, AtomicReference<Throwable> failure) {
        try {
            router.dispatch(frame.toString(), target);
        } catch (RuntimeException broken) {
            failure.compareAndSet(null, broken);
        }
    }

    private static String statusOf(RecordingChannel recorded) {
        return payloadOf(recorded).get("status").getAsString();
    }

    private static JsonObject payloadOf(RecordingChannel recorded) {
        assertThat(recorded.frames()).hasSize(1);
        return recorded.frames().get(0).getAsJsonObject("payload");
    }

    private static List<String> names(JsonArray entries) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            names.add(entries.get(i).getAsJsonObject().get("name").getAsString());
        }
        Collections.sort(names);
        return names;
    }

    private static JsonObject entry(JsonArray entries, String name) {
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            if (name.equals(entry.get("name").getAsString())) {
                return entry;
            }
        }
        throw new AssertionError("No entry named " + name);
    }

    private void assertError(JsonObject request, String code) {
        channel.clear();
        router.dispatch(request.toString(), channel);
        JsonObject payload = only(channel).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo(code);
    }

    private static JsonObject only(RecordingChannel recorded) {
        assertThat(recorded.frames()).hasSize(1);
        return recorded.frames().get(0);
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

    private Path write(String relative, String content) throws IOException {
        Path target = tmp.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private static void pointManagerAt(FileOperationManager manager, Path serverRoot, Path... editableRoots)
            throws IllegalAccessException, NoSuchFieldException {
        Field serverRootField = FileOperationManager.class.getDeclaredField("serverRoot");
        serverRootField.setAccessible(true);
        serverRootField.set(manager, serverRoot.toFile());

        List<File> roots = new ArrayList<>();
        for (Path editableRoot : editableRoots) {
            roots.add(editableRoot.toFile());
        }
        Field editableRootsField = FileOperationManager.class.getDeclaredField("editableRoots");
        editableRootsField.setAccessible(true);
        editableRootsField.set(manager, Collections.unmodifiableList(roots));
    }
}
