package com.ultikits.ultitools;

import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.descriptor;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.hook;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.jar;
import static com.ultikits.ultitools.bootstrap.BootstrapFixtures.mainClass;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.bootstrap.BootstrapResult;
import com.ultikits.ultitools.bootstrap.UltiToolsBootstrap;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.ModuleUpdateFixtures;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

class BootstrapUpdateRollbackTest {
    @TempDir File serverRoot;

    @Test
    void failedUpdatedHookRestoresOnlyItsOldCopyAndDoesNotRetryOnTheNextStart() throws Exception {
        File data = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        File modules = ModuleFileTransactions.modulesFolder(data);
        File old = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        byte[] original = Files.readAllBytes(old.toPath());
        UltiToolsPlugin loadedOld = loadedModule("Demo", "1.0", "demo");
        Path unchanged = jar(new File(modules, "unchanged.jar").toPath(), descriptor("Unchanged", "fixture.Unchanged"),
                Collections.singletonMap("fixture.Unchanged", hook("fixture.Unchanged", "java/lang/NoClassDefFoundError")));
        byte[] unchangedBytes = Files.readAllBytes(unchanged);
        Map<String, byte[]> updatedClasses = new LinkedHashMap<>();
        updatedClasses.put("com.example.demo.DemoModule", mainClass("com.example.demo.DemoModule"));
        updatedClasses.put("fixture.NewHook", hook("fixture.NewHook", "java/lang/NoClassDefFoundError"));
        ModuleFileTransactions staging = new ModuleFileTransactions(data);
        ModuleFileTransactions.StageResult staged = staging.stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, old), catalogue("demo", "1.1"),
                (link, fileName, directory) -> jar(new File(directory, fileName).toPath(),
                        descriptor("Demo", "fixture.NewHook").replace("fixture.DemoMain", "com.example.demo.DemoModule")
                                .replace("'1.0.0'", "'1.1'") + "identify-string: demo\n",
                        updatedClasses)
                        .toFile());
        assertThat(staged.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);

        BootstrapResult first = bootstrap(data.toPath());
        try (ModuleClassLoader loader = first.classLoader()) {
            Path updated = new File(modules, "demo-1.1.jar").toPath().toAbsolutePath().normalize();
            assertThat(first.classPathFailure()).isNull();
            assertThat(first.bootstrapFailures()).containsOnlyKeys(updated, unchanged);
            assertThat(first.admission().inspect(updated.toFile()).getBootstrapFailure()).isNotNull();
            assertThat(old).doesNotExist();
            assertThat(loader.getURLs()).contains(updated.toUri().toURL());
            assertThat(ModuleUpdateFixtures.treeOf(ModuleFileTransactions.transactionsFolder(data)))
                    .anyMatch(name -> name.endsWith("backup/demo-1.0.jar"));
            // Discovery's refusal is covered by PluginManagerBootstrapFailureTest; observe its empty load result.
            UltiTools.loadModulesThenObserve(first.transactions(), () -> { /* Both hooks failed. */ }, Collections::emptyList);
            assertThat(old).exists();
            assertThat(updated).doesNotExist();
            assertThat(Files.readAllBytes(old.toPath())).isEqualTo(original);
            assertThat(Files.readAllBytes(unchanged)).isEqualTo(unchangedBytes);
            assertThat(ModuleUpdateFixtures.treeOf(ModuleFileTransactions.transactionsFolder(data))).isEmpty();
        }

        BootstrapResult second = bootstrap(data.toPath());
        try (ModuleClassLoader loader = second.classLoader()) {
            assertThat(second.bootstrapFailures()).containsOnlyKeys(unchanged);
            assertThat(second.bootstrapsRun()).isEqualTo(1);
            assertThat(second.admission().inspect(old).getMetadata().getVersion()).isEqualTo("1.0");
            assertThat(loader.getURLs()).contains(old.toURI().toURL());
            UltiTools.loadModulesThenObserve(second.transactions(), () -> { /* No pending updates remain. */ }, Collections::emptyList);
            assertThat(Files.readAllBytes(old.toPath())).isEqualTo(original);
            assertThat(Files.readAllBytes(unchanged)).isEqualTo(unchangedBytes);
            assertThat(ModuleUpdateFixtures.treeOf(ModuleFileTransactions.transactionsFolder(data))).isEmpty();
        }
    }

    private static BootstrapResult bootstrap(Path data) {
        BootstrapContext context = mock(BootstrapContext.class);
        when(context.getDataDirectory()).thenReturn(data);
        when(context.getLogger()).thenReturn(mock(ComponentLogger.class));
        UltiToolsBootstrap bootstrap = new UltiToolsBootstrap();
        bootstrap.bootstrap(context);
        List<BootstrapResult> handed = new ArrayList<>();
        try (MockedConstruction<UltiTools> plugins = mockConstruction(UltiTools.class,
                (plugin, construction) -> handed.add((BootstrapResult) construction.arguments().get(0)))) {
            bootstrap.createPlugin(mock(PluginProviderContext.class));
            assertThat(plugins.constructed()).hasSize(1);
            return handed.get(0);
        }
    }
}
