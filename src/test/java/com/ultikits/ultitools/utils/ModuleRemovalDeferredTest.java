package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * An uninstall whose JAR cannot be deleted now -- Windows holding it open through the shared module
 * class loader, or a folder that is not writable -- records the deletion and performs it at the next
 * start, before any module loads (#518). The reply says exactly that instead of asking the operator
 * to delete a file the running server prevents them from deleting.
 *
 * <p>The delete failure is produced the way POSIX produces one, with a modules folder that is not
 * writable; Windows' sharing violation reaches the same {@code Files.deleteIfExists} failure.
 */
@DisplayName("Uninstall: a JAR that cannot be deleted now is deleted at the next start (#518)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleRemovalDeferredTest {

    private static final String MODULE = "RemovalFixture";

    @TempDir
    File serverRoot;

    /** {@code <server root>/plugins/UltiTools}, as on a real server; the records live under the server root. */
    private File dataFolder;

    private File modules;
    private File jar;

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        Assumptions.assumeTrue(Files.getFileStore(dataFolder.toPath()).supportsFileAttributeView("posix"));
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        jar = moduleJar(new File(modules, "removal-fixture-1.0.jar"), MODULE, "1.0", "removal-fixture");
        AtomicReference<PluginManager> manager = new AtomicReference<>();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(dataFolder);
            when(ultiTools.getPluginManager()).thenAnswer(invocation -> manager.get());
            when(ultiTools.getCommandManager()).thenReturn(mock(CommandManager.class));
            when(ultiTools.getListenerManager()).thenReturn(mock(ListenerManager.class));
        });
        manager.set(new PluginManager());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("rwx------"));
        // A JAR a test made unreadable is given its permissions back, so the TempDir can be removed.
        for (File file : modules.listFiles()) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        }
        MockBukkitHelper.safeUnmock();
    }

    /**
     * Takes read permission away from a module JAR, the way a transient read failure looks at a
     * start: its size and modification time still match the record, only its content cannot be read.
     */
    private void unreadable(File file) throws IOException {
        long size = file.length();
        long lastModified = file.lastModified();
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("-w-------"));
        assertThat(file.length()).isEqualTo(size);
        assertThat(file.lastModified()).isEqualTo(lastModified);
        Assumptions.assumeFalse(Files.isReadable(file.toPath()), "running as a user that ignores permissions");
    }

    private void readable(File file) throws IOException {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
    }

    /** The single removal record in the transactions folder. */
    private File onlyRecord() throws IOException {
        File folder = ModuleFileTransactions.transactionsFolder(dataFolder);
        List<String> tree = treeOf(folder);
        assertThat(tree).hasSize(1).allMatch(p -> p.startsWith("remove-") && p.endsWith(".json"));
        return new File(folder, tree.get(0));
    }

    private static JsonObject jsonOf(File record) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static JsonObject removalNamed(JsonObject record, String name) {
        for (JsonElement removal : record.getAsJsonArray("removals")) {
            if (name.equals(removal.getAsJsonObject().get("name").getAsString())) {
                return removal.getAsJsonObject();
            }
        }
        throw new AssertionError("no removal entry named " + name + " in " + record);
    }

    private void readOnlyModulesFolder() throws IOException {
        Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(modules.toPath()), "running as a user that ignores permissions");
    }

    private void writableModulesFolder() throws IOException {
        Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("rwx------"));
    }

    private PluginInstallUtils.RemovalDeferredException uninstallThatCannotDelete() throws IOException {
        readOnlyModulesFolder();
        Throwable thrown = catchThrowable(() -> PluginInstallUtils.uninstallPluginReporting(MODULE));
        assertThat(thrown).isInstanceOf(PluginInstallUtils.RemovalDeferredException.class);
        return (PluginInstallUtils.RemovalDeferredException) thrown;
    }

    @Test
    @DisplayName("a JAR that cannot be deleted is recorded for the next start, and the failure names it and why")
    void undeletableJar_isRecordedForTheNextStart() throws IOException {
        PluginInstallUtils.RemovalDeferredException deferred = uninstallThatCannotDelete();

        assertThat(deferred.deferredFiles()).containsExactly(jar.getAbsolutePath());
        assertThat(deferred.getReason()).contains("AccessDeniedException");
        assertThat(jar).exists();
        List<String> tree = treeOf(ModuleFileTransactions.transactionsFolder(dataFolder));
        assertThat(tree).hasSize(1).allMatch(p -> p.startsWith("remove-") && p.endsWith(".json"));
    }

    @Test
    @DisplayName("the next start deletes the recorded JAR before the modules load, and says so")
    void nextStart_deletesTheRecordedJar() throws IOException {
        uninstallThatCannotDelete();
        writableModulesFolder();

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(jar).doesNotExist();
        assertThat(treeOf(ModuleFileTransactions.transactionsFolder(dataFolder))).isEmpty();
        List<ModuleFileTransactions.Report> reports = start.pendingReports();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(reports.get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.REMOVED);
    }

    @Test
    @DisplayName("a deletion that still fails at the next start is reported SEVERE and kept for the start after")
    void stillUndeletable_isReportedAndKept() throws IOException {
        uninstallThatCannotDelete();

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(jar).exists();
        assertThat(start.pendingReports()).hasSize(1);
        assertThat(start.pendingReports().get(0).getLevel()).isEqualTo(Level.SEVERE);
        assertThat(start.pendingReports().get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.REMOVAL_FAILED);
        assertThat(treeOf(ModuleFileTransactions.transactionsFolder(dataFolder))).hasSize(1);
    }

    @Test
    @DisplayName("a JAR replaced since the uninstall is never deleted by the recorded deletion")
    void replacedJar_isNotDeleted() throws IOException {
        uninstallThatCannotDelete();
        writableModulesFolder();
        byte[] replacement = "a newer install under the same name".getBytes(StandardCharsets.UTF_8);
        Files.write(jar.toPath(), replacement);

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(Files.readAllBytes(jar.toPath())).isEqualTo(replacement);
        assertThat(start.pendingReports()).hasSize(1);
        assertThat(start.pendingReports().get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.REMOVAL_SKIPPED);
        assertThat(namesIn(modules)).containsExactly("removal-fixture-1.0.jar");
        assertThat(treeOf(ModuleFileTransactions.transactionsFolder(dataFolder))).isEmpty();
    }

    @Test
    @DisplayName("ultitools.boot.deferred-removal.unreadable: a recorded JAR that cannot be read at a start stays recorded, and a later start deletes it")
    void unreadableRecordedJar_staysRecordedAndIsDeletedByALaterStart() throws IOException {
        uninstallThatCannotDelete();
        writableModulesFolder();
        File record = onlyRecord();
        byte[] recordBefore = Files.readAllBytes(record.toPath());
        unreadable(jar);

        ModuleFileTransactions failingStart = new ModuleFileTransactions(dataFolder);
        failingStart.applyBeforeLoad();

        // The uninstall the operator asked for is still recorded, exactly as it was written.
        assertThat(jar).exists();
        assertThat(treeOf(ModuleFileTransactions.transactionsFolder(dataFolder))).containsExactly(record.getName());
        assertThat(Files.readAllBytes(record.toPath())).isEqualTo(recordBefore);
        List<ModuleFileTransactions.Report> reports = failingStart.pendingReports();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getLevel()).isEqualTo(Level.SEVERE);
        assertThat(reports.get(0).getKey()).isNotEqualTo(ModuleFileTransactions.Keys.REMOVAL_SKIPPED);
        assertThat(Arrays.asList(reports.get(0).getArgs())).contains(jar.getAbsolutePath());
        assertThat(reports.get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.REMOVAL_UNREADABLE);
        assertThat(reports.get(0).getArgs()).hasSize(2);
        assertThat(reports.get(0).getArgs()[1].toString()).contains("AccessDeniedException");

        readable(jar);
        ModuleFileTransactions laterStart = new ModuleFileTransactions(dataFolder);
        laterStart.applyBeforeLoad();

        assertThat(jar).doesNotExist();
        assertThat(treeOf(ModuleFileTransactions.transactionsFolder(dataFolder))).isEmpty();
        assertThat(laterStart.pendingReports()).hasSize(1);
        assertThat(laterStart.pendingReports().get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(laterStart.pendingReports().get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.REMOVED);
    }

    @Test
    @DisplayName("of two recorded JARs, the readable one is deleted and the unreadable one stays recorded with its original fields")
    void oneUnreadableOfTwoRecordedJars_staysRecordedWithItsOriginalFields() throws IOException {
        File other = moduleJar(new File(modules, "removal-fixture-extra-1.0.jar"), "RemovalFixtureExtra", "1.0",
                "removal-fixture-extra");
        new ModuleFileTransactions(dataFolder).recordDeferredRemoval(MODULE, Arrays.asList(jar, other));
        JsonObject recordedBefore = jsonOf(onlyRecord());
        JsonObject otherBefore = removalNamed(recordedBefore, other.getName());
        unreadable(other);

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(jar).doesNotExist();
        assertThat(other).exists();
        JsonObject recordedAfter = jsonOf(onlyRecord());
        assertThat(recordedAfter.keySet()).isEqualTo(recordedBefore.keySet());
        JsonArray removals = recordedAfter.getAsJsonArray("removals");
        assertThat(removals).hasSize(1);
        JsonObject otherAfter = removals.get(0).getAsJsonObject();
        assertThat(otherAfter.keySet()).isEqualTo(otherBefore.keySet());
        assertThat(otherAfter).isEqualTo(otherBefore);
        assertThat(start.pendingReports()).extracting(ModuleFileTransactions.Report::getKey)
                .containsExactlyInAnyOrder(ModuleFileTransactions.Keys.REMOVAL_UNREADABLE,
                        ModuleFileTransactions.Keys.REMOVED);
    }
}
