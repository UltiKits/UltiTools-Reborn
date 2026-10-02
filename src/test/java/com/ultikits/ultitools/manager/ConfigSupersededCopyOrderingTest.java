package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.context.SimpleContainer;
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
    }
    public static class Incoming extends UltiToolsPlugin {
        public Incoming() {
            Probe.constructions++;
            try { Probe.observed = new String(Files.readAllBytes(Probe.file), StandardCharsets.UTF_8); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public boolean registerSelf() { return false; }
    }
    @BeforeEach void setup() throws Exception {
        MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); MockBukkit.createMockPlugin();
        configs = new ConfigManager(); plugins = new PluginManager();
        TestHelper.mockUltiToolsInstance(core -> {
            lenient().when(core.getConfigManager()).thenReturn(configs);
            lenient().when(core.getDataFolder()).thenReturn(directory.toFile());
            lenient().when(core.getConfig()).thenReturn(new YamlConfiguration());
        });
        old = mock(UltiToolsPlugin.class);
        lenient().when(old.getPluginName()).thenReturn("SupersededModule");
        lenient().when(old.getMainClass()).thenReturn("example.Main");
        lenient().when(old.getVersion()).thenReturn("1.0.0");
        lenient().when(old.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(old, directory.toFile());
        Probe.file = directory.resolve("copy.yml"); Probe.constructions = 0; Probe.observed = null;
        Files.write(Probe.file, "value: disk\n".getBytes(StandardCharsets.UTF_8));
        entity = new Values("copy.yml"); configs.register(old, entity); entity.value = "pending";
        plugins.getPluginList().add(old); Bukkit.getLogger().addHandler(capture);
    }
    @AfterEach void cleanup() { Bukkit.getLogger().removeHandler(capture); MockBukkitHelper.safeUnmock(); }
    @Test void ownMetadataSavePrecedesIncomingConstructorAndOldStaysActiveOnRefusal() throws Exception {
        try (URLClassLoader loader = incomingJar(true)) {
            Class<?> type = loader.loadClass(Incoming.class.getName());
            initialize(type);
            assertThat(Probe.constructions).isEqualTo(1);
            assertThat(Probe.observed).contains("value: pending");
            assertThat(entity.isModifiedSinceSnapshot()).isFalse();
            verify(old, never()).unregisterSelf();
            assertThat(plugins.getPluginList()).contains(old);
        }
    }
    @Test void failedOldSaveRefusesConstructionAndKeepsOldDirty() throws Exception {
        Values refused = spy(entity);
        doThrow(new java.io.IOException("injected old save refusal")).when(refused).save();
        // Replace the registered path with the fault-injecting entity, retaining the same live state.
        configs.register(old, refused); refused.value = "pending";
        try (URLClassLoader loader = incomingJar(true)) {
            assertThatThrownBy(() -> initialize(loader.loadClass(Incoming.class.getName())))
                    .hasRootCauseMessage("injected old save refusal");
            assertThat(Probe.constructions).isZero();
            assertThat(refused.isModifiedSinceSnapshot()).isTrue();
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
        method.setAccessible(true); method.invoke(plugins, type.getClassLoader(), type);
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
}
