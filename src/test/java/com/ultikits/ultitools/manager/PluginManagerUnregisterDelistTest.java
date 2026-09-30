package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #507: {@link PluginManager#unregister(UltiToolsPlugin)} must leave the manager saying what is
 * actually loaded.
 * <p>
 * Before the fix {@code unregister} released a module's registrations and closed its container
 * but left it in the plugin list, so every caller had to delist it by hand -- and code outside
 * the manager could only do that by mutating the live internal list {@code getPluginList()}
 * returned. The same method also left the module's configuration entities in the
 * {@link ConfigManager}, so the shutdown save could still write an unloaded module's files.
 */
@DisplayName("PluginManager.unregister delists the module and releases its configuration (#507)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerUnregisterDelistTest {

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        configManager = new ConfigManager();
        Logger frameworkLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(frameworkLogger);
            when(ultiTools.getConfigManager()).thenReturn(configManager);
        });
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String name) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn(name);
        return plugin;
    }

    private static void write(File target, String content) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(File target) throws IOException {
        return Files.readAllBytes(target.toPath());
    }

    @Test
    @DisplayName("after unregister(m) the plugin list no longer contains m, and other modules stay listed")
    void unregisterRemovesTheModuleFromThePluginList() {
        UltiToolsPlugin target = module("Target");
        UltiToolsPlugin bystander = module("Bystander");
        PluginListSeeding.add(pluginManager, target);
        PluginListSeeding.add(pluginManager, bystander);

        pluginManager.unregister(target);

        assertThat(pluginManager.getPluginList())
                .as("an unregistered module must not stay listed as loaded")
                .doesNotContain(target)
                .containsExactly(bystander);
    }

    @Test
    @DisplayName("a module whose unload hook throws is still delisted, and the failure still reaches the caller")
    void unregisterDelistsEvenWhenTheUnloadHookThrows() {
        UltiToolsPlugin target = module("Throwing");
        IllegalStateException hookFailure = new IllegalStateException("unload hook boom");
        doThrow(hookFailure).when(target).unregisterSelf();
        PluginListSeeding.add(pluginManager, target);

        assertThatThrownBy(() -> pluginManager.unregister(target)).isSameAs(hookFailure);

        assertThat(pluginManager.getPluginList())
                .as("the module's container is closed either way, so it must not stay listed")
                .doesNotContain(target);
    }

    @Test
    @DisplayName("getPluginList() cannot be used to mutate the loaded-module list")
    void getPluginListIsUnmodifiable() {
        UltiToolsPlugin loaded = module("Loaded");
        PluginListSeeding.add(pluginManager, loaded);
        List<UltiToolsPlugin> list = pluginManager.getPluginList();

        assertThatThrownBy(() -> list.add(module("Intruder"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.remove(loaded)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(list::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(pluginManager.getPluginList()).containsExactly(loaded);
    }

    @Test
    @DisplayName("getPluginList() is a snapshot: a copy taken before an unload keeps its contents")
    void getPluginListIsASnapshot() {
        UltiToolsPlugin target = module("Target");
        PluginListSeeding.add(pluginManager, target);
        List<UltiToolsPlugin> before = pluginManager.getPluginList();

        pluginManager.unregister(target);

        assertThat(before).as("a snapshot is not a live view").containsExactly(target);
        assertThat(pluginManager.getPluginList()).isEmpty();
    }

    @Test
    @DisplayName("iterating snapshots while another thread unregisters modules never throws")
    void iteratingWhileAnotherThreadUnregistersNeverThrows() throws Exception {
        List<UltiToolsPlugin> modules = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            UltiToolsPlugin plugin = module("Module" + i);
            modules.add(plugin);
            PluginListSeeding.add(pluginManager, plugin);
        }
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                while (!done.get()) {
                    for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
                        assertThat(plugin).isNotNull();
                    }
                }
            } catch (Throwable t) { // NOPMD - any failure on the reader thread is the finding
                readerFailure.set(t);
            }
        }, "plugin-list-reader");
        reader.start();
        started.await();

        for (UltiToolsPlugin plugin : modules) {
            pluginManager.unregister(plugin);
        }
        done.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(readerFailure.get()).as("a reader iterating getPluginList() must never fail").isNull();
        assertThat(pluginManager.getPluginList()).isEmpty();
    }

    @Test
    @DisplayName("close() unregisters every listed module even though unregister now delists during the loop")
    void closeUnregistersEveryModule() {
        UltiToolsPlugin first = module("First");
        UltiToolsPlugin second = module("Second");
        UltiToolsPlugin third = module("Third");
        PluginListSeeding.add(pluginManager, first);
        PluginListSeeding.add(pluginManager, second);
        PluginListSeeding.add(pluginManager, third);

        pluginManager.close();

        verify(first).unregisterSelf();
        verify(second).unregisterSelf();
        verify(third).unregisterSelf();
        assertThat(pluginManager.getPluginList()).isEmpty();
    }

    @Test
    @DisplayName("close() at server shutdown keeps every module's configuration, so the shutdown save still writes its changes")
    void closeKeepsConfigurationForTheShutdownSave() throws IOException {
        // Gate-1 review, reviewer A P1: UltiTools#onDisable() calls pluginManager.close() and only
        // then configManager.saveAll(). If close() released the configuration entities the way a
        // runtime unload does, the shutdown save would find nothing and every module's unsaved
        // change would be lost on every restart.
        File moduleDir = new File(tempDir, "module");
        UltiToolsPlugin module = module("Module");
        when(module.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(module.getResourceFolderPath()).thenReturn(moduleDir.getAbsolutePath());
        ConfigFileStubs.stubConfigFolder(module, moduleDir);
        File configFile = new File(moduleDir, "config/scalar.yml");
        write(configFile, "value: original\n");
        ConfigManagerShutdownSaveTest.ScalarConfig config =
                new ConfigManagerShutdownSaveTest.ScalarConfig("config/scalar.yml");
        configManager.register(module, config);
        config.setValue("changed-in-memory");
        PluginListSeeding.add(pluginManager, module);

        pluginManager.close();
        configManager.saveAll();

        assertThat(new String(bytes(configFile), StandardCharsets.UTF_8))
                .as("the shutdown save after close() writes the module's in-memory change")
                .contains("changed-in-memory");
        assertThat(pluginManager.getPluginList()).isEmpty();
    }

    @Test
    @DisplayName("after unregister(m), the shutdown save does not write m's changed configuration, and still writes a loaded module's")
    void unregisterReleasesTheModulesConfigurationEntities() throws IOException {
        File unloadedDir = new File(tempDir, "unloaded");
        File loadedDir = new File(tempDir, "loaded");
        UltiToolsPlugin unloaded = module("Unloaded");
        UltiToolsPlugin loaded = module("Loaded");
        for (UltiToolsPlugin plugin : new UltiToolsPlugin[] {unloaded, loaded}) {
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        }
        when(unloaded.getResourceFolderPath()).thenReturn(unloadedDir.getAbsolutePath());
        when(loaded.getResourceFolderPath()).thenReturn(loadedDir.getAbsolutePath());
        ConfigFileStubs.stubConfigFolder(unloaded, unloadedDir);
        ConfigFileStubs.stubConfigFolder(loaded, loadedDir);

        File unloadedFile = new File(unloadedDir, "config/scalar.yml");
        File loadedFile = new File(loadedDir, "config/scalar.yml");
        write(unloadedFile, "value: original\n");
        write(loadedFile, "value: original\n");
        ConfigManagerShutdownSaveTest.ScalarConfig unloadedConfig =
                new ConfigManagerShutdownSaveTest.ScalarConfig("config/scalar.yml");
        ConfigManagerShutdownSaveTest.ScalarConfig loadedConfig =
                new ConfigManagerShutdownSaveTest.ScalarConfig("config/scalar.yml");
        configManager.register(unloaded, unloadedConfig);
        configManager.register(loaded, loadedConfig);
        unloadedConfig.setValue("changed-in-memory");
        loadedConfig.setValue("changed-in-memory");
        byte[] unloadedBefore = bytes(unloadedFile);
        PluginListSeeding.add(pluginManager, unloaded);
        PluginListSeeding.add(pluginManager, loaded);

        pluginManager.unregister(unloaded);
        configManager.saveAll();

        assertThat(bytes(unloadedFile))
                .as("an unloaded module's configuration file must not be written by the shutdown save")
                .isEqualTo(unloadedBefore);
        assertThat(new String(bytes(loadedFile), StandardCharsets.UTF_8))
                .as("control: the same change on a still-loaded module is written, so the check above is not vacuous")
                .contains("changed-in-memory");
        assertThat(configManager.getAllConfigEntities(unloaded))
                .as("the configuration registry must not pin the unloaded module")
                .isNull();
    }
}
