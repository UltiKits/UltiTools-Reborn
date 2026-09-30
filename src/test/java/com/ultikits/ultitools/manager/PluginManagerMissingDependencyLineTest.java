package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.testfixtures.dependmissing.AbsentEconomyApi;
import com.ultikits.testfixtures.dependmissing.EconomyDependentModule;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #554: a module that cannot load because a plugin in its {@code depend:} list is not installed or
 * not enabled gets one line naming the module and the missing plugin, without a raw class-not-found
 * trace. With every {@code depend:} plugin present, the existing message and trace are unchanged.
 * <p>
 * Drives the real {@code PluginManager#register(Class)} path with a module jar whose main class
 * cannot be resolved because a type from its required plugin is absent -- the shape UltiEconomy
 * has on a server without Vault (observed in Phase 17 wave-4 acceptance, session 4).
 */
@DisplayName("#554: a module whose required plugin is missing gets one line naming it")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerMissingDependencyLineTest {

    private static final String REQUIRED_PLUGIN = "FakeVault";
    private static final String MODULE_NAME = "EconomyDependent";

    @TempDir
    File tempDir;

    private final List<LogRecord> records = new ArrayList<>();
    private Handler capture;
    private URLClassLoader moduleLoader;

    /** Loads the fixture module class from its jar and refuses the absent required plugin's type. */
    private static final class ModuleJarLoader extends URLClassLoader {
        ModuleJarLoader(URL jar, ClassLoader parent) {
            super(new URL[]{jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (AbsentEconomyApi.class.getName().equals(name)) {
                throw new ClassNotFoundException(name);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && EconomyDependentModule.class.getName().equals(name)) {
                    found = findClass(name);
                }
                if (found == null) {
                    found = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        records.clear();
        capture = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Bukkit.getLogger().addHandler(capture);
    }

    @AfterEach
    void tearDown() throws IOException {
        Bukkit.getLogger().removeHandler(capture);
        if (moduleLoader != null) {
            moduleLoader.close();
        }
        MockBukkitHelper.safeUnmock();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends UltiToolsPlugin> moduleClassRequiring(String requiredPlugin) throws Exception {
        File jar = new File(tempDir, "economy-dependent.jar");
        String entry = EconomyDependentModule.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()));
             InputStream classBytes = EconomyDependentModule.class.getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: " + MODULE_NAME + "\nversion: 1.0.0\nmain: " + EconomyDependentModule.class.getName()
                    + "\ndepend: [" + requiredPlugin + "]\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(entry));
            byte[] buffer = new byte[4096];
            int read;
            while ((read = classBytes.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.closeEntry();
        }
        moduleLoader = new ModuleJarLoader(jar.toURI().toURL(), getClass().getClassLoader());
        // The class name is a compile-time constant, never attacker-controllable.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        return (Class<? extends UltiToolsPlugin>) Class.forName(EconomyDependentModule.class.getName(), false,
                moduleLoader);
    }

    private List<LogRecord> warningsAndAbove() {
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord logRecord : records) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                result.add(logRecord);
            }
        }
        return result;
    }

    @Test
    @DisplayName("required plugin not installed: exactly one line naming the module and the plugin, no trace")
    void missingRequiredPluginGetsOneLineWithoutATrace() throws Exception {
        Class<? extends UltiToolsPlugin> moduleClass = moduleClassRequiring(REQUIRED_PLUGIN);

        boolean registered = new PluginManager().register(moduleClass);

        assertThat(registered).isFalse();
        List<LogRecord> warnings = warningsAndAbove();
        assertThat(warnings).hasSize(1);
        LogRecord line = warnings.get(0);
        assertThat(line.getMessage()).contains(MODULE_NAME).contains(REQUIRED_PLUGIN);
        assertThat(line.getThrown()).isNull();
        assertThat(line.getMessage()).doesNotContain("Cannot initialize plugin");
    }

    @Test
    @DisplayName("required plugin installed but disabled: the same one line")
    void disabledRequiredPluginGetsTheSameLine() throws Exception {
        MockBukkit.getMock().getPluginManager().disablePlugin(MockBukkit.createMockPlugin(REQUIRED_PLUGIN));
        Class<? extends UltiToolsPlugin> moduleClass = moduleClassRequiring(REQUIRED_PLUGIN);

        new PluginManager().register(moduleClass);

        List<LogRecord> warnings = warningsAndAbove();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains(MODULE_NAME).contains(REQUIRED_PLUGIN);
        assertThat(warnings.get(0).getThrown()).isNull();
    }

    @Test
    @DisplayName("every required plugin present and enabled: the existing message and trace, unchanged")
    void presentRequiredPluginKeepsTheExistingMessageAndTrace() throws Exception {
        MockBukkit.createMockPlugin(REQUIRED_PLUGIN);
        Class<? extends UltiToolsPlugin> moduleClass = moduleClassRequiring(REQUIRED_PLUGIN);

        new PluginManager().register(moduleClass);

        List<LogRecord> warnings = warningsAndAbove();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage())
                .startsWith("[UltiTools-API] Cannot initialize plugin for " + EconomyDependentModule.class.getName());
        assertThat(warnings.get(0).getThrown()).isInstanceOf(NoClassDefFoundError.class);
    }
}
