package com.ultikits.ultitools;

import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.descriptor;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.hook;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.jar;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.mainClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.bootstrap.BootstrapResult;
import com.ultikits.ultitools.bootstrap.UltiToolsBootstrap;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome;
import com.ultikits.ultitools.utils.ModuleUpdateFixtures;
import com.ultikits.ultitools.utils.PendingChanges;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

class BootstrapInstallRollbackTest {
    @TempDir File serverRoot;

    @Test
    void bootstrapFailureExcludesTheModuleFromTheLoadedList() throws Exception {
        File data = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        Path candidate = failingJar(ModuleFileTransactions.modulesFolder(data).toPath().resolve("boom-probe.jar"));
        BootstrapResult start = bootstrap(data);
        try (ModuleClassLoader loader = start.classLoader()) {
            assertThat(loader.getURLs()).contains(candidate.toUri().toURL());
            assertThat(start.bootstrapFailures()).containsOnlyKeys(candidate);
            assertThat(discover(start, data).getPluginList()).isEmpty();
        }
    }

    @Test
    void newInstallThatFailsAtBootstrapIsRemovedAgain() throws Exception {
        File data = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        Path transactions = ModuleFileTransactions.transactionsFolder(data).toPath();
        String id = "install-" + digest("INSTALL:boomprobe".getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        Path staged = failingJar(transactions.resolve(id).resolve("staged/boom-probe.jar"));
        Files.writeString(transactions.resolve(id + ".json"), """
                {"type":"INSTALL","key":"boomprobe","state":"PENDING","moduleName":"Boom",
                 "targetName":"boom-probe.jar","newVersion":"1.0.0","stagedName":"boom-probe.jar",
                 "stagedSha256":"%s"}
                """.formatted(digest(Files.readAllBytes(staged))));
        Path candidate = ModuleFileTransactions.modulesFolder(data).toPath().resolve("boom-probe.jar").toAbsolutePath();

        BootstrapResult start = bootstrap(data);
        try (ModuleClassLoader loader = start.classLoader()) {
            assertThat(loader.getURLs()).contains(candidate.toUri().toURL());
            assertThat(start.bootstrapFailures()).containsOnlyKeys(candidate);
            PluginManager manager = discover(start, data);
            assertThat(manager.getPluginList()).isEmpty();
            UltiTools.loadModulesThenObserve(start.transactions(), () -> { /* Discovery has already run. */ }, manager::getPluginList);
            assertThat(candidate).doesNotExist();
            assertThat(ModuleUpdateFixtures.treeOf(transactions.toFile())).isEmpty();
            assertThat(start.transactions().startOutcomes()).containsExactly(new StartOutcome(
                    PendingChanges.Kind.INSTALL, "Boom", null, "1.0.0", StartOutcome.Result.ROLLED_BACK));
        }
    }

    // Run the real manager initialization, with Bukkit wiring and scheduling isolated.
    private static PluginManager discover(BootstrapResult start, File data) throws Exception {
        var manager = new PluginManager(start.admission());
        UltiTools framework = mock(UltiTools.class);
        when(framework.getDataFolder()).thenReturn(data);
        var bukkitManager = mock(org.bukkit.plugin.PluginManager.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
             var tasks = mockConstruction(TaskManager.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
            bukkit.when(Bukkit::getPluginManager).thenReturn(bukkitManager);
            tools.when(UltiTools::getInstance).thenReturn(framework);
            tools.when(UltiTools::getJavaPluginClassLoader).thenReturn(start.classLoader());
            manager.init(start.classLoader());
            assertThat(tasks.constructed()).hasSize(1);
        }
        return manager;
    }

    private static Path failingJar(Path path) throws Exception {
        return jar(path, descriptor("Boom", "fixture.BoomHook") + "identify-string: boomprobe\n",
                Map.of("fixture.BoomMain", mainClass("fixture.BoomMain"),
                        "fixture.BoomHook", hook("fixture.BoomHook", "java/lang/IllegalStateException")));
    }

    private static String digest(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static BootstrapResult bootstrap(File data) {
        BootstrapContext context = mock(BootstrapContext.class);
        when(context.getDataDirectory()).thenReturn(data.toPath());
        when(context.getLogger()).thenReturn(mock(ComponentLogger.class));
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context);
        List<BootstrapResult> handed = new ArrayList<>();
        try (MockedConstruction<UltiTools> plugins = mockConstruction(UltiTools.class,
                (plugin, construction) -> handed.add((BootstrapResult) construction.arguments().get(0)))) {
            bootstrap.createPlugin(mock(PluginProviderContext.class));
            assertThat(plugins.constructed()).hasSize(1);
            return handed.getFirst();
        }
    }
}
