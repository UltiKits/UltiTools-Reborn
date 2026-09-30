package com.ultikits.ultitools;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.ModuleUpdateFixtures;

/**
 * The start-up ordering the update transaction depends on (#505): the staged files are swapped
 * before the module class path is computed, so the class loader is built over the new JAR and never
 * sees the old one. Exercised through the same method {@code UltiTools#onEnable} builds its class
 * loader from.
 */
@DisplayName("Module update: apply runs before the module class path is computed (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateStartupOrderTest {

    @TempDir
    File dataFolder;

    @Test
    @DisplayName("the class path built at start-up holds the new JAR and not the old one")
    void classPathIsComputedAfterTheApply() throws IOException {
        File modules = ModuleFileTransactions.modulesFolder(dataFolder);
        File oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        UltiToolsPlugin loadedOld = loadedModule("Demo", "1.0", "demo");
        new ModuleFileTransactions(dataFolder).stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                downloading("Demo", "1.1", "demo"));

        URL[] classPath = UltiTools.moduleClassPath(new ModuleFileTransactions(dataFolder), modules, null);

        assertThat(Arrays.asList(classPath)).containsExactly(new File(modules, "demo-1.1.jar").toURI().toURL());
    }

    @Test
    @DisplayName("a module load that throws counts as \"not loaded\": the applied update is rolled back")
    void loadThatThrows_countsAsNotLoaded() throws IOException {
        File modules = ModuleFileTransactions.modulesFolder(dataFolder);
        File oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        UltiToolsPlugin loadedOld = loadedModule("Demo", "1.0", "demo");
        new ModuleFileTransactions(dataFolder).stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                downloading("Demo", "1.1", "demo"));
        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        UltiTools.moduleClassPath(start, modules, null);
        UltiToolsPlugin loadedNew = loadedModule("Demo", "1.1", "demo");

        Throwable thrown = catchThrowable(() -> UltiTools.loadModulesThenObserve(start, () -> {
            throw new IOException("module scan failed");
        }, () -> Collections.singletonList(loadedNew)));

        assertThat(thrown).isInstanceOf(IOException.class);
        assertThat(ModuleUpdateFixtures.namesIn(modules)).containsExactly("demo-1.0.jar");
        assertThat(ModuleUpdateFixtures.treeOf(ModuleFileTransactions.transactionsFolder(dataFolder))).isEmpty();
    }
}
