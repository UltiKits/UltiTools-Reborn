package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * An uninstall cancels the module's staged update and its running update download by the identity
 * it resolved -- the unloaded instances' identify-strings and runtime names, and their JARs -- on
 * every outcome, not by the argument the operator typed (Codex round 10 on PR #561).
 *
 * <p>The module here is loaded as {@code Demo} (identify-string {@code demo}) from a JAR whose
 * {@code plugin.yml} declares {@code UltiTools-Demo}, and is uninstalled by that declared name. Its
 * update was staged under the runtime name, so matching the typed argument finds nothing; when the
 * modules folder also cannot be listed, no removed JAR name comes back either, and before this fix
 * the next start applied the update and brought back the module the operator removed.
 */
@DisplayName("An uninstall cancels the module's update by the identity it resolved, on every outcome (round 10)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class UninstallCancelsByIdentityTest {

    @TempDir
    File serverRoot;

    private File dataFolder;
    private File modules;
    private File transactions;
    private UltiToolsPlugin demo;
    private ModuleUpdateFixtures.CodeSources codeSources;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        transactions = ModuleFileTransactions.transactionsFolder(dataFolder);
        File oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "UltiTools-Demo", "1.0", "demo");
        AtomicReference<PluginManager> ref = new AtomicReference<>();
        CommandManager commandManager = mock(CommandManager.class);
        ListenerManager listenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(dataFolder);
            when(ultiTools.getPluginManager()).thenAnswer(invocation -> ref.get());
            when(ultiTools.getCommandManager()).thenReturn(commandManager);
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
        });
        pluginManager = new PluginManager();
        ref.set(pluginManager);
        demo = loaded("Demo", "demo");
        codeSources = new ModuleUpdateFixtures.CodeSources().with(demo, oldJar);
    }

    @AfterEach
    void tearDown() throws IOException {
        permissions(modules, "rwx------");
        MockBukkitHelper.safeUnmock();
    }

    private UltiToolsPlugin loaded(String runtimeName, String identifyString) {
        UltiToolsPlugin plugin = loadedModule(runtimeName, "1.0", identifyString);
        doCallRealMethod().when(plugin).unregisterSelf();
        pluginManager.getPluginList().add(plugin);
        return plugin;
    }

    private void stage() {
        ModuleFileTransactions.StageResult result = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                Collections.singletonList(demo), codeSources, catalogue("demo", "1.1"),
                downloading("UltiTools-Demo", "1.1", "demo"));
        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
    }

    private static void permissions(File folder, String permissions) throws IOException {
        Path path = folder.toPath();
        if (Files.isDirectory(path)) {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString(permissions);
            Files.setPosixFilePermissions(path, perms);
        }
    }

    /** Makes the modules folder unlistable but leaves its files reachable by path; skips where that cannot be done. */
    private void makeModulesFolderUnlistable() throws IOException {
        Assumptions.assumeTrue(modules.toPath().getFileSystem().supportedFileAttributeViews().contains("posix"),
                "needs POSIX permissions");
        permissions(modules, "-wx------");
        Assumptions.assumeTrue(modules.list() == null, "permissions are not enforced for this user");
    }

    /** What a start does next: applies what is recorded, before any module loads. */
    private List<String> nextStart() throws IOException {
        permissions(modules, "rwx------");
        new ModuleFileTransactions(dataFolder).applyBeforeLoad();
        return namesIn(modules);
    }

    @Test
    @DisplayName("round 10: by the declared name with an unlistable modules folder, the staged update is cancelled and the next start does not revive the module")
    void unlistableFolderByDeclaredName_cancelsTheStagedUpdate() throws IOException {
        stage();
        makeModulesFolderUnlistable();
        List<String> cancelled = new ArrayList<>();

        Throwable thrown = catchThrowable(() ->
                PluginInstallUtils.uninstallPluginReporting("UltiTools-Demo", codeSources, cancelled));

        assertThat(thrown).isInstanceOf(AccessDeniedException.class);
        assertThat(pluginManager.getPluginList()).doesNotContain(demo);
        assertThat(cancelled).containsExactly("1.1");
        assertThat(treeOf(transactions)).isEmpty();
        assertThat(nextStart()).as("the update is not applied: the module stays at the JAR the operator will remove")
                .containsExactly("demo-1.0.jar");
    }

    @Test
    @DisplayName("round 10: by the declared name with the JAR deleted, the staged update is cancelled")
    void deletedByDeclaredName_cancelsTheStagedUpdate() throws IOException {
        stage();
        List<String> cancelled = new ArrayList<>();

        PluginInstallUtils.UninstallReport report =
                PluginInstallUtils.uninstallPluginReporting("UltiTools-Demo", codeSources, cancelled);

        assertThat(report.jarsDeleted()).isTrue();
        assertThat(cancelled).containsExactly("1.1");
        assertThat(treeOf(transactions)).isEmpty();
        assertThat(nextStart()).isEmpty();
    }

    @Test
    @DisplayName("round 10: by the declared name with the deletion deferred, the staged update is cancelled and only the removal remains")
    void deferredByDeclaredName_cancelsTheStagedUpdate() throws IOException {
        stage();
        Assumptions.assumeTrue(modules.toPath().getFileSystem().supportedFileAttributeViews().contains("posix"),
                "needs POSIX permissions");
        permissions(modules, "r-x------");
        Assumptions.assumeFalse(Files.isWritable(modules.toPath()), "permissions are not enforced for this user");
        List<String> cancelled = new ArrayList<>();

        Throwable thrown = catchThrowable(() ->
                PluginInstallUtils.uninstallPluginReporting("UltiTools-Demo", codeSources, cancelled));

        assertThat(thrown).isInstanceOf(PluginInstallUtils.RemovalDeferredException.class);
        assertThat(cancelled).containsExactly("1.1");
        assertThat(treeOf(transactions)).allMatch(p -> p.startsWith("remove-"));
        assertThat(nextStart()).as("the recorded removal runs; no update is applied").isEmpty();
    }

    @Test
    @DisplayName("round 10: a download running when the module is uninstalled by the declared name, folder unlistable, is cancelled")
    void runningDownloadByDeclaredNameUnlistableFolder_isCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ModuleFileTransactions.StageResult> staging = pool.submit(() ->
                    new ModuleFileTransactions(dataFolder).stageUpdate("demo", Collections.singletonList(demo),
                            codeSources, catalogue("demo", "1.1"), (link, name, folder) -> {
                                entered.countDown();
                                try {
                                    release.await(20, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(e);
                                }
                                moduleJar(new File(folder, name), "UltiTools-Demo", "1.1", "demo");
                            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            makeModulesFolderUnlistable();

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.uninstallPluginReporting("UltiTools-Demo",
                    codeSources, new ArrayList<>()));
            release.countDown();
            ModuleFileTransactions.StageResult result = staging.get(20, TimeUnit.SECONDS);

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_CANCELLED_WHILE_DOWNLOADING);
            assertThat(treeOf(transactions)).isEmpty();
            assertThat(nextStart()).containsExactly("demo-1.0.jar");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("round 10 control: uninstalling another module leaves this module's staged update alone")
    void uninstallingAnotherModule_leavesTheUpdate() throws IOException {
        File otherJar = moduleJar(new File(modules, "other-1.0.jar"), "Other", "1.0", "other");
        UltiToolsPlugin other = loaded("Other", "other");
        codeSources.with(other, otherJar);
        stage();
        List<String> cancelled = new ArrayList<>();

        PluginInstallUtils.uninstallPluginReporting("Other", codeSources, cancelled);

        assertThat(otherJar).doesNotExist();
        assertThat(cancelled).isEmpty();
        assertThat(treeOf(transactions)).isNotEmpty();
        assertThat(pluginManager.getPluginList()).containsExactly(demo);
    }
}
