package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Releasing an unloaded module must not pin or write it; shutdown saves before that release. */
class ConfigRegistryReleaseTest {
    @TempDir Path directory;
    private ConfigManager configs;
    private PluginManager plugins;
    private UltiToolsPlugin owner;
    private Values entity;

    @BeforeEach void setup() throws Exception {
        MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); MockBukkit.createMockPlugin();
        configs = new ConfigManager(); plugins = new PluginManager();
        TestHelper.mockUltiToolsInstance(core -> {
            lenient().when(core.getConfigManager()).thenReturn(configs);
            lenient().when(core.getCommandManager()).thenReturn(mock(CommandManager.class));
            lenient().when(core.getListenerManager()).thenReturn(mock(ListenerManager.class));
            lenient().when(core.getPluginManager()).thenReturn(plugins);
            lenient().when(core.getDataFolder()).thenReturn(directory.toFile());
            lenient().when(core.getLogger()).thenReturn(java.util.logging.Logger.getLogger("ShutdownFixture"));
        });
        owner = mock(UltiToolsPlugin.class);
        lenient().when(owner.getPluginName()).thenReturn("ReleasedModule");
        lenient().when(owner.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(owner, directory.toFile());
        Files.write(directory.resolve("release.yml"), "value: disk\n".getBytes(StandardCharsets.UTF_8));
        entity = new Values("release.yml"); configs.register(owner, entity); entity.value = "pending";
        PluginListSeeding.add(plugins, owner);
    }
    @AfterEach void cleanup() { MockBukkitHelper.safeUnmock(); }
    private String disk() throws Exception {
        return new String(Files.readAllBytes(directory.resolve("release.yml")), StandardCharsets.UTF_8);
    }
    @Test void runtimeUnregisterReleasesEntitiesAndLaterShutdownCannotWriteThem() throws Exception {
        plugins.unregister(owner);
        assertThat(configs.getAllConfigEntities(owner)).isNull();
        configs.saveAll(); assertThat(disk()).contains("value: disk");
    }
    @Test void throwingUnloadStillReleasesConfigurationRegistry() throws Exception {
        doThrow(new IllegalStateException("unload refusal")).when(owner).unregisterSelf();
        assertThatThrownBy(() -> plugins.unregister(owner)).isInstanceOf(IllegalStateException.class);
        assertThat(configs.getAllConfigEntities(owner)).isNull();
        configs.saveAll(); assertThat(disk()).contains("value: disk");
    }
    // 17-65 (maintainer decision 2026-10-04, superseding Follow-up 26.2's final shutdown save): nothing below writes.
    @Test void closeWritesNothingBeforeOrAfterUnloadAndLaterReportCannotWriteEither() throws Exception {
        doAnswer(call -> {
            assertThat(disk()).contains("value: disk");
            assertThat(configs.getAllConfigEntities(owner)).containsValue(entity);
            return null;
        }).when(owner).unregisterSelf();
        plugins.close();
        verify(owner).unregisterSelf();
        assertThat(configs.getAllConfigEntities(owner)).isNull();
        entity.value = "after-release"; configs.saveAll(); assertThat(disk()).isEqualTo("value: disk\n");
    }
    @Test void shutdownWritesNoUnloadHookMutation() throws Exception {
        HookOwner hook = callbackOwner(false, false); hook.checkInitialSave = false;
        plugins.close();
        assertThat(hook.ran).isTrue();
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test void shutdownWritesNoPreDestroyMutationAfterTheUnloadHook() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        plugins.close();
        assertThat(hook.ran).isTrue();
        assertThat(hook.bean.ran).isTrue();
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
        entity.value = "released"; configs.saveAll();
        assertThat(disk()).isEqualTo("value: disk\n");
    }

    @Test void throwingActualHookStillRunsPreDestroyAndReleasesWithoutWriting() throws Exception {
        HookOwner hook = callbackOwner(true, true); hook.checkInitialSave = false;
        plugins.close();
        assertThat(hook.ran).isTrue(); assertThat(hook.bean.ran).isTrue();
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test void runtimeUnloadActualCallbacksDoNotAddSave() throws Exception {
        HookOwner hook = callbackOwner(false, true);
        hook.checkInitialSave = false;
        plugins.unregister(hook);
        assertThat(hook.ran).isTrue(); assertThat(hook.bean.ran).isTrue();
        assertThat(disk()).contains("value: disk");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test void runtimeUninstallActualCallbacksDoNotAddSave() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        Path moduleDirectory = directory.resolve("plugins"); Files.createDirectories(moduleDirectory);
        try (java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(
                Files.newOutputStream(moduleDirectory.resolve("fixture.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("plugin.yml"));
            jar.write("name: ReleasedModule\n".getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
        }
        com.ultikits.ultitools.utils.PluginInstallUtils.uninstallPluginReporting("ReleasedModule");
        assertThat(hook.ran).isTrue(); assertThat(hook.bean.ran).isTrue();
        assertThat(disk()).contains("value: disk");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Exercise the existing private activation boundary without unrelated bootstrap.
    void failedActivationPreDestroyDoesNotAddSave() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        PluginListSeeding.clear(plugins); when(hook.registerSelf()).thenReturn(false);
        java.lang.reflect.Method activation = PluginManager.class.getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
        activation.setAccessible(true);
        assertThat(activation.invoke(plugins, hook)).isEqualTo(false);
        assertThat(hook.bean.ran).isTrue(); assertThat(hook.ran).isFalse();
        assertThat(disk()).contains("value: disk");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Exercise the existing private supersede boundary without constructing a second module.
    void supersededActualOwnerMutationDoesNotAddSave() throws Exception {
        HookOwner hook = callbackOwner(false, false); hook.checkInitialSave = false;
        when(hook.getMainClass()).thenReturn("example.Module");
        UltiToolsPlugin incoming = mock(UltiToolsPlugin.class);
        when(incoming.getMainClass()).thenReturn("example.Module"); when(incoming.isNewerVersionThan(hook)).thenReturn(true);
        java.lang.reflect.Method supersede = PluginManager.class.getDeclaredMethod("unregisterSupersededVersions", UltiToolsPlugin.class);
        supersede.setAccessible(true); supersede.invoke(plugins, incoming);
        assertThat(hook.ran).isTrue(); assertThat(disk()).contains("value: disk");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test void protectedShutdownFileSurvivesBothCallbackSaves() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        String broken = "value: [broken\n";
        Files.write(directory.resolve("release.yml"), broken.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(entity::reload).isInstanceOf(com.ultikits.ultitools.exceptions.ConfigurationException.class); // #589
        plugins.close();
        assertThat(hook.bean.ran).isTrue(); assertThat(disk()).isEqualTo(broken);
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @Test void shutdownNeverSavesAnyEntityAndReleaseStillRuns() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        Values failing = spy(entity); configs.register(hook, failing); failing.value = "pending";
        hook.value = failing; hook.bean.value = failing;
        doThrow(new java.io.IOException("a save must not be attempted")).when(failing).save();
        Values healthy = new Values("healthy.yml"); configs.register(hook, healthy);
        String healthyAtInit = new String(Files.readAllBytes(directory.resolve("healthy.yml")), StandardCharsets.UTF_8);
        hook.bean.healthy = healthy;
        plugins.close();
        verify(failing, never()).save();
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(new String(Files.readAllBytes(directory.resolve("healthy.yml")), StandardCharsets.UTF_8))
                .isEqualTo(healthyAtInit);
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }

    /** The "never saved" report lines logged while {@code action} runs. */
    private java.util.List<String> reportsDuring(Action action) throws Exception {
        java.util.List<String> reports = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null && record.getMessage().contains("never saved")) { reports.add(record.getMessage()); }
            }
            @Override public void flush() { /* No buffer. */ }
            @Override public void close() { /* No resource. */ }
        };
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("ShutdownFixture");
        logger.addHandler(capture);
        try { action.run(); } finally { logger.removeHandler(capture); }
        return reports;
    }

    /**
     * 17-65 review round 1 R65-I5: a normal unload drops never-saved changes like the stop does - nothing is written -
     * and names them once, the same way (file and keys, never values).
     */
    @Test void runtimeUnloadNamesNeverSavedKeysOnceAndWritesNothing() throws Exception {
        java.util.List<String> reports = reportsDuring(() -> plugins.unregister(owner));
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("release.yml", "'value'").doesNotContain("pending");
        assertThat(reportsDuring(() -> configs.saveAll())).as("released entities are not named again").isEmpty();
    }

    @Test void runtimeUninstallNamesNeverSavedKeysOnceAndWritesNothing() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        Path moduleDirectory = directory.resolve("plugins"); Files.createDirectories(moduleDirectory);
        try (java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(
                Files.newOutputStream(moduleDirectory.resolve("fixture.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("plugin.yml"));
            jar.write("name: ReleasedModule\n".getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
        }
        java.util.List<String> reports = reportsDuring(
                () -> com.ultikits.ultitools.utils.PluginInstallUtils.uninstallPluginReporting("ReleasedModule"));
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(reports).hasSize(1).allSatisfy(report -> assertThat(report).contains("release.yml", "'value'")
                .doesNotContain("destroyed"));
    }

    /** Pin: a superseded owner is named only by the replacement's own drop warning, not a second time on its release. */
    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Exercise the existing private supersede boundary without constructing a second module.
    void supersededOwnerIsNotNamedTwiceOnItsRelease() throws Exception {
        HookOwner hook = callbackOwner(false, false); hook.checkInitialSave = false;
        when(hook.getMainClass()).thenReturn("example.Module");
        UltiToolsPlugin incoming = mock(UltiToolsPlugin.class);
        when(incoming.getMainClass()).thenReturn("example.Module"); when(incoming.isNewerVersionThan(hook)).thenReturn(true);
        java.lang.reflect.Method supersede = PluginManager.class.getDeclaredMethod("unregisterSupersededVersions", UltiToolsPlugin.class);
        supersede.setAccessible(true);
        assertThat(reportsDuring(() -> supersede.invoke(plugins, incoming))).isEmpty();
        assertThat(disk()).isEqualTo("value: disk\n");
    }

    private HookOwner callbackOwner(boolean throwing, boolean destroy) throws Exception {
        configs.unregisterAll(owner); PluginListSeeding.clear(plugins);
        HookOwner hook = mock(HookOwner.class);
        lenient().when(hook.getPluginName()).thenReturn("ReleasedModule");
        lenient().when(hook.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(hook, directory.toFile());
        hook.value = entity; hook.registry = configs; hook.path = directory.resolve("release.yml");
        hook.throwing = throwing; hook.checkInitialSave = true;
        doCallRealMethod().when(hook).onUnregister(); doCallRealMethod().when(hook).unregisterSelf();
        com.ultikits.ultitools.context.SimpleContainer context = new com.ultikits.ultitools.context.SimpleContainer();
        when(hook.getContext()).thenReturn(context);
        if (destroy) {
            hook.bean = new DestroyBean(hook, entity, configs); context.registerSingleton("destroyer", hook.bean);
        }
        configs.register(hook, entity); entity.value = "pending";
        PluginListSeeding.add(plugins, hook); return hook;
    }

    abstract static class HookOwner extends UltiToolsPlugin {
        Values value;
        ConfigManager registry; Path path; boolean throwing; boolean ran; boolean checkInitialSave; DestroyBean bean;
        @Override protected void onUnregister() {
            ran = true;
            assertThat(registry.getAllConfigEntities(this)).containsValue(value);
            if (checkInitialSave) {
                try { assertThat(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).contains("value: pending"); }
                catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
            value.value = "hook";
            if (throwing) { throw new IllegalStateException("actual hook failure"); }
        }
    }
    static class DestroyBean {
        final HookOwner owner; Values value; final ConfigManager registry; Values healthy; boolean ran;
        DestroyBean(HookOwner owner, Values value, ConfigManager registry) {
            this.owner = owner; this.value = value; this.registry = registry;
        }
        @com.ultikits.ultitools.annotations.PreDestroy
        void destroy() {
            ran = true; assertThat(registry.getAllConfigEntities(owner)).containsValue(value);
            value.value = "destroyed";
            if (healthy != null) { healthy.value = "healthy-destroyed"; }
        }
    }

    @ConfigEntity("release.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry String value = "default";
        public Values(String path) { super(path); }
    }

    /**
     * 17-65 (#599; maintainer decision 2026-10-04: no shutdown save of whole entities): a full stop - the module's
     * unload hook and its {@code @PreDestroy} changing the configuration, then the framework's final step - writes
     * nothing, and one line names the file and the never-saved key, never its value.
     */
    @Test void fullStopWritesNothingAndNamesTheUnsavedKeyOnce() throws Exception {
        HookOwner hook = callbackOwner(false, true); hook.checkInitialSave = false;
        java.util.List<String> reports = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null && record.getMessage().contains("never saved")) { reports.add(record.getMessage()); }
            }
            @Override public void flush() { /* No buffer. */ }
            @Override public void close() { /* No resource. */ }
        };
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("ShutdownFixture");
        logger.addHandler(capture);
        try {
            plugins.close();
            configs.saveAll();
        } finally { logger.removeHandler(capture); }
        assertThat(hook.ran).isTrue(); assertThat(hook.bean.ran).isTrue();
        assertThat(disk()).isEqualTo("value: disk\n");
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("release.yml", "'value'").doesNotContain("destroyed", "pending");
        assertThat(configs.getAllConfigEntities(hook)).isNull();
    }
}
