package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
import com.ultikits.ultitools.manager.PluginListSeeding;
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
        PluginListSeeding.add(pluginManager, plugin);
        return plugin;
    }

    private PendingChanges pending() { return new PendingChanges(new ModuleFileTransactions(dataFolder)); }

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
    void unlistableFolderByDeclaredName_keepsTheStagedUpdate() throws IOException {
        stage();
        makeModulesFolderUnlistable();
        var request = PluginInstallUtils.requestUninstall("UltiTools-Demo", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_FOLDER_UNREADABLE);
        assertThat(pluginManager.getPluginList()).contains(demo);
        assertThat(pending().list()).singleElement().satisfies(item -> assertThat(item.kind()).isEqualTo(PendingChanges.Kind.UPDATE));
    }

    @Test
    @DisplayName("round 10: by the declared name with the JAR deleted, the staged update is cancelled")
    void deletedByDeclaredName_cancelsTheStagedUpdate() throws IOException {
        stage();
        var request = PluginInstallUtils.requestUninstall("UltiTools-Demo", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_QUEUED, UpmKeys.UNINSTALL_ALSO_CANCELLED_UPDATE);
        assertThat(pluginManager.getPluginList()).contains(demo);
        assertThat(treeOf(transactions)).singleElement().asString().startsWith("remove-");
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
        var request = PluginInstallUtils.requestUninstall("UltiTools-Demo", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_QUEUED, UpmKeys.UNINSTALL_ALSO_CANCELLED_UPDATE);
        assertThat(pluginManager.getPluginList()).contains(demo);
        assertThat(treeOf(transactions)).singleElement().asString().startsWith("remove-");
        assertThat(nextStart()).isEmpty();
    }

    @Test
    @DisplayName("round 10: a download running when the module is uninstalled by the declared name, folder unlistable, is cancelled")
    void runningDownloadByDeclaredNameUnlistableFolder_isNotCancelled() throws Exception {
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

            var request = PluginInstallUtils.requestUninstall("UltiTools-Demo", null, codeSources);
            assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_FOLDER_UNREADABLE);
            permissions(modules, "rwx------");
            release.countDown();
            assertThat(staging.get(20, TimeUnit.SECONDS).getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
            assertThat(pending().list()).singleElement().satisfies(item -> assertThat(item.kind()).isEqualTo(PendingChanges.Kind.UPDATE));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("self-review: an identify-string another loaded module also reports never cancels that module's update")
    void sharedIdentifyString_doesNotCancelTheOtherModulesUpdate() throws IOException {
        // A misconfiguration, but a real one: two modules whose plugin.yml files carry the same
        // identify-string. Demo's update is staged; uninstalling Twin must not cancel it -- the
        // identify-string is not Twin's alone, exactly as a name another loaded module owns is not.
        File twinJar = moduleJar(new File(modules, "twin-1.0.jar"), "Twin", "1.0", "demo");
        UltiToolsPlugin twin = loaded("Twin", "demo");
        codeSources.with(twin, twinJar);
        stage();
        var request = PluginInstallUtils.requestUninstall("Twin", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_QUEUED);
        assertThat(twinJar).exists();
        assertThat(pending().list()).extracting(PendingChanges.PendingChange::kind)
                .containsExactlyInAnyOrder(PendingChanges.Kind.UPDATE, PendingChanges.Kind.UNINSTALL);
    }

    @Test
    @DisplayName("gate 1 A-P2: a runtime name another loaded module also answers to never cancels that module's update")
    void sharedRuntimeName_doesNotCancelTheOtherModulesUpdate() throws IOException {
        // Two loaded modules both built with the runtime name "Shared" (it is a constructor argument,
        // not metadata). Target's JAR declares "TargetDeclared", so an uninstall by that name selects
        // Target alone; Other's staged update must survive it, exactly as Other's names are not keys.
        File targetJar = moduleJar(new File(modules, "target-1.0.jar"), "TargetDeclared", "1.0", "target");
        UltiToolsPlugin target = loaded("Shared", "target");
        codeSources.with(target, targetJar);
        File otherJar = moduleJar(new File(modules, "other-1.0.jar"), "OtherDeclared", "1.0", "other");
        UltiToolsPlugin other = loaded("Shared", "other");
        codeSources.with(other, otherJar);
        ModuleFileTransactions.StageResult staged = new ModuleFileTransactions(dataFolder).stageUpdate("other",
                Collections.singletonList(other), codeSources, catalogue("other", "1.1"),
                downloading("OtherDeclared", "1.1", "other"));
        assertThat(staged.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        var request = PluginInstallUtils.requestUninstall("TargetDeclared", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_QUEUED);
        assertThat(targetJar).exists();
        assertThat(pending().list()).extracting(PendingChanges.PendingChange::kind)
                .containsExactlyInAnyOrder(PendingChanges.Kind.UPDATE, PendingChanges.Kind.UNINSTALL);
    }

    @Test
    @DisplayName("Codex round 16: a module loaded from a JAR outside the modules folder never cancels the update of a module JAR sharing its file name")
    void externalJarSharingAFileName_doesNotCancelTheOtherModulesUpdate() throws IOException {
        // The external module's JAR has the same file name as Demo's JAR in the modules folder. An
        // update's old JAR is always directly in the modules folder, so the external one's name must
        // never be matched against it.
        File external = moduleJar(new File(serverRoot, "elsewhere/demo-1.0.jar"), "External", "1.0", "external");
        UltiToolsPlugin outside = loaded("External", "external");
        codeSources.with(outside, external);
        stage();
        var request = PluginInstallUtils.requestUninstall("External", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_NO_JAR);
        assertThat(pending().list()).singleElement().satisfies(item -> assertThat(item.kind()).isEqualTo(PendingChanges.Kind.UPDATE));
        assertThat(pluginManager.getPluginList()).contains(demo, outside);
    }

    @Test
    @DisplayName("round 19: an update held for the operator refuses /upm uninstall of the module before anything is unloaded or deleted")
    void updateHeldForTheOperator_refusesTheUninstall() throws IOException {
        stage();
        File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
        assertThat(records).hasSize(1);
        String text = new String(Files.readAllBytes(records[0].toPath()), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"state\": \"PENDING\"", "\"state\": \"NEEDS_OPERATOR\"");
        Files.write(records[0].toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> tree = treeOf(transactions);
        var request = PluginInstallUtils.requestUninstall("Demo", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_REFUSED_HELD_FOR_OPERATOR);
        assertThat(PendingTestValues.values(request.replies().getFirst().args())).contains(records[0].getAbsolutePath());
        assertThat(pluginManager.getPluginList()).contains(demo);
        assertThat(new File(modules, "demo-1.0.jar")).exists();
        assertThat(treeOf(transactions)).isEqualTo(tree);
    }

    @Test
    @DisplayName("round 10 control: uninstalling another module leaves this module's staged update alone")
    void uninstallingAnotherModule_leavesTheUpdate() throws IOException {
        File otherJar = moduleJar(new File(modules, "other-1.0.jar"), "Other", "1.0", "other");
        UltiToolsPlugin other = loaded("Other", "other");
        codeSources.with(other, otherJar);
        stage();
        var request = PluginInstallUtils.requestUninstall("Other", null, codeSources);
        assertThat(PendingTestValues.keys(request)).containsExactly(UpmKeys.UNINSTALL_QUEUED);
        assertThat(otherJar).exists();
        assertThat(pending().list()).extracting(PendingChanges.PendingChange::kind)
                .containsExactlyInAnyOrder(PendingChanges.Kind.UPDATE, PendingChanges.Kind.UNINSTALL);
    }
}
