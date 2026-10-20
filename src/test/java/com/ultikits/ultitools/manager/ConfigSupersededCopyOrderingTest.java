package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Own-JAR metadata identifies the old copy before incoming constructor reads its files. */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ConfigSupersededCopyOrderingTest {
    @TempDir Path directory;
    private ConfigManager configs;
    private PluginManager plugins;
    private UltiToolsPlugin old;
    private Values entity;
    private final List<String> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
        @Override public void flush() { /* No buffer. */ }
        @Override public void close() { /* No resource. */ }
    };
    public static final class Probe {
        public static Path file;
        public static int constructions;
        public static String observed;
        public static boolean fail;
        public static UltiToolsPlugin constructedOwner;
    }
    public static class Incoming extends UltiToolsPlugin {
    @SuppressWarnings("PMD.AssignmentToNonFinalStatic") // Reset test probes observe construction count, owner and file contents before replacement.
        public Incoming() {
            Probe.constructions++; Probe.constructedOwner = this;
            if (Probe.fail) { throw new IllegalStateException("injected subclass constructor failure"); }
            try { Probe.observed = new String(Files.readAllBytes(Probe.file), StandardCharsets.UTF_8); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public boolean registerSelf() { return false; }
        @Override public List<AbstractConfigEntity> getAllConfigs() {
            return java.util.Collections.singletonList(new Values("incoming.yml"));
        }
    }
    @BeforeEach void setup() throws Exception {
        MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); MockBukkit.createMockPlugin();
        configs = new ConfigManager(); plugins = new PluginManager();
        TestHelper.mockUltiToolsInstance(core -> {
            lenient().when(core.getConfigManager()).thenReturn(configs);
            lenient().when(core.getDataFolder()).thenReturn(directory.toFile());
            lenient().when(core.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(core.getLogger()).thenReturn(Logger.getLogger("SupersedeFixture"));
            // Framework version is controlled at the static compatibility boundary below.
        });
        old = mock(UltiToolsPlugin.class);
        lenient().when(old.getPluginName()).thenReturn("SupersededModule");
        lenient().when(old.getMainClass()).thenReturn("example.Main");
        lenient().when(old.getVersion()).thenReturn("1.0.0");
        lenient().when(old.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(old, directory.toFile());
        Probe.file = directory.resolve("copy.yml"); Probe.constructions = 0; Probe.observed = null;
        Probe.fail = false; Probe.constructedOwner = null;
        Files.write(Probe.file, "value: disk\n".getBytes(StandardCharsets.UTF_8));
        entity = new Values("copy.yml"); configs.register(old, entity); entity.value = "pending";
        PluginListSeeding.add(plugins, old); Bukkit.getLogger().addHandler(capture);
    }
    @AfterEach void cleanup() { Bukkit.getLogger().removeHandler(capture); MockBukkitHelper.safeUnmock(); }
    // 17-65 (maintainer decision 2026-10-04, superseding Follow-up 23's save-before-construct): no save first.
    @Test void incomingConstructorReadsTheFilesUnsavedAndOldStaysActiveOnRefusal() throws Exception {
        try (URLClassLoader loader = incomingJar(true)) {
            Class<?> type = loader.loadClass(Incoming.class.getName());
            initialize(type);
            assertThat(Probe.constructions).isEqualTo(1);
            assertThat(Probe.observed).contains("value: disk");
            assertThat(entity.isModifiedSinceSnapshot()).isTrue();
            verify(old, never()).unregisterSelf();
            assertThat(plugins.getPluginList()).contains(old);
        }
    }
    @Test void protectedOldFileNoLongerRefusesConstruction() throws Exception {
        String broken = "value: [broken\n";
        Files.write(Probe.file, broken.getBytes(StandardCharsets.UTF_8));
        // #589 (PR #591): a reload of an unparseable file throws; the file stays protected.
        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class);
        assertThat(entity.isLastLoadUnparseable()).isTrue();
        try (URLClassLoader loader = incomingJar(true)) {
            initialize(loader.loadClass(Incoming.class.getName()));
            assertThat(Probe.constructions).isEqualTo(1);
            assertThat(Probe.observed).isEqualTo(broken);
            verify(old, never()).unregisterSelf();
            assertThat(plugins.getPluginList()).contains(old);
        }
    }
    @Test void unavailableMetadataDoesNotSaveAndWarnsKeysAtSuccessfulSupersede() throws Exception {
        try (URLClassLoader loader = incomingJar(false)) {
            initialize(loader.loadClass(Incoming.class.getName()));
            assertThat(Probe.observed).contains("value: disk");
        }
        activateConstructedCopy(true);
        assertThat(warnings).anySatisfy(text -> assertThat(text).contains("SupersededModule", "copy.yml", "value", "dropped"));
        assertThat(configs.getAllConfigEntities(old)).isNull();
    }
    @Test void alreadyConstructedCopyNeverSavesOldAndRefusedIncomingReleasesOnlyItself() throws Exception {
        UltiToolsPlugin incoming = activateConstructedCopy(false);
        assertThat(configs.getAllConfigEntities(incoming)).isNull();
        assertThat(configs.getAllConfigEntities(old)).containsValue(entity);
        assertThat(entity.isModifiedSinceSnapshot()).isTrue();
        assertThat(new String(Files.readAllBytes(Probe.file), StandardCharsets.UTF_8)).contains("value: disk");
        verify(old, never()).unregisterSelf();
    }
    @Test void subclassConstructorFailureReleasesNewOwnerButRetainsSameClassExistingOwner() throws Exception {
        try (URLClassLoader loader = incomingJar(false)) {
            Class<?> type = loader.loadClass(Incoming.class.getName());
            UltiToolsPlugin prior = (UltiToolsPlugin) type.getDeclaredConstructor().newInstance();
            assertThat(configs.getAllConfigEntities(prior)).isNotEmpty();
            Probe.fail = true;
            assertThatThrownBy(() -> initialize(type)).hasRootCauseMessage("injected subclass constructor failure");
            assertThat(configs.getAllConfigEntities(Probe.constructedOwner)).isNull();
            assertThat(configs.getAllConfigEntities(prior)).isNotEmpty();
            assertThat(configs.getAllConfigEntities(old)).containsValue(entity);
        }
    }
    @Test void compatibilityRefusalReleasesConstructedIncomingOnly() throws Exception {
        try (URLClassLoader loader = incomingJar(false)) {
            initialize(loader.loadClass(Incoming.class.getName()));
            assertThat(configs.getAllConfigEntities(Probe.constructedOwner)).isNull();
            assertThat(configs.getAllConfigEntities(old)).containsValue(entity);
        }
    }
    @Test void throwingActivationReleasesOnlyIncomingRegistry() throws Exception {
        UltiToolsPlugin incoming = mock(UltiToolsPlugin.class);
        lenient().when(incoming.getPluginName()).thenReturn("RefusedActivation");
        lenient().when(incoming.getContext()).thenReturn(new SimpleContainer());
        lenient().when(incoming.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(incoming, directory.toFile());
        configs.register(incoming, new Values("copy.yml"));
        when(incoming.registerSelf()).thenThrow(new IllegalStateException("activation refusal"));
        Method method = PluginManager.class.getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
        method.setAccessible(true); assertThat(method.invoke(plugins, incoming)).isEqualTo(false);
        assertThat(configs.getAllConfigEntities(incoming)).isNull();
        assertThat(configs.getAllConfigEntities(old)).containsValue(entity);
    }
    private UltiToolsPlugin activateConstructedCopy(boolean accepted) throws Exception {
        UltiToolsPlugin incoming = mock(UltiToolsPlugin.class);
        lenient().when(incoming.getPluginName()).thenReturn("SupersededModule");
        lenient().when(incoming.getMainClass()).thenReturn("example.Main");
        lenient().when(incoming.getVersion()).thenReturn("2.0.0");
        when(incoming.isNewerVersionThan(old)).thenReturn(true);
        when(incoming.getContext()).thenReturn(new SimpleContainer());
        when(incoming.registerSelf()).thenReturn(accepted);
        lenient().when(incoming.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(incoming, directory.toFile());
        configs.register(incoming, new Values("copy.yml"));
        Method method = PluginManager.class.getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
        method.setAccessible(true); assertThat(method.invoke(plugins, incoming)).isEqualTo(accepted);
        return incoming;
    }
    private void initialize(Class<?> type) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("initializePlugin", ClassLoader.class, Class.class);
        method.setAccessible(true);
        try (org.mockito.MockedStatic<com.ultikits.ultitools.UltiTools> core =
                mockStatic(com.ultikits.ultitools.UltiTools.class, CALLS_REAL_METHODS)) {
            core.when(com.ultikits.ultitools.UltiTools::getPluginVersion).thenReturn(630);
            method.invoke(plugins, type.getClassLoader(), type);
        }
    }
    private URLClassLoader incomingJar(boolean identity) throws Exception {
        Path jar = directory.resolve(identity ? "identified.jar" : "unidentified.jar");
        String resource = Incoming.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(resource));
            try (InputStream input = Incoming.class.getResourceAsStream("/" + resource)) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = input.read(buffer)) != -1) { output.write(buffer, 0, read); }
            }
            output.closeEntry(); output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: SupersededModule\nversion: 2.0.0\napi-version: 999999\n"
                    + (identity ? "main: example.Main\n" : "")).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(Incoming.class.getName())) {
                    Class<?> found = findLoadedClass(name); if (found == null) { found = findClass(name); }
                    if (resolve) { resolveClass(found); } return found;
                }
                return super.loadClass(name, resolve);
            }
        };
    }
    @ConfigEntity("copy.yml")
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry String value = "default";
        public Values(String path) { super(path); }
    }

    /**
     * 17-65 (#599; maintainer decision 2026-10-04: no replacement save of whole entities): before an identified newer copy
     * is constructed nothing is saved - the newer copy reads the files as they are - and the old copy's unsaved change no
     * longer refuses the replacement.
     */
    @Test void identifiedReplacementNeverSavesTheOldCopyAndIsNotRefusedByItsUnsavedChange() throws Exception {
        Values refused = spy(entity);
        doThrow(new java.io.IOException("a save must not be attempted")).when(refused).save();
        configs.register(old, refused); refused.value = "pending";
        try (URLClassLoader loader = incomingJar(true)) {
            initialize(loader.loadClass(Incoming.class.getName()));
            assertThat(Probe.constructions).isEqualTo(1);
            assertThat(Probe.observed).contains("value: disk");
            verify(refused, never()).save();
            assertThat(refused.isModifiedSinceSnapshot()).isTrue();
        }
        activateConstructedCopy(true);
        assertThat(warnings).filteredOn(text -> text.contains("dropped")).hasSize(1)
                .allSatisfy(text -> assertThat(text).contains("SupersededModule", "copy.yml", "value").doesNotContain("pending"));
        assertThat(new String(Files.readAllBytes(Probe.file), StandardCharsets.UTF_8)).isEqualTo("value: disk\n");
    }
}
