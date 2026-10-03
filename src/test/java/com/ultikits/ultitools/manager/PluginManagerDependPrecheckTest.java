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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import com.ultikits.testfixtures.dependmissing.AbsentEconomyApi;
import com.ultikits.testfixtures.dependprecheck.PrecheckModule;
import com.ultikits.testfixtures.dependprecheck.PrecheckProbe;
import com.ultikits.testfixtures.dependprecheck.PrecheckProviderService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #554, real-server acceptance: a module whose {@code plugin.yml} lists a plugin under {@code
 * depend:} that is not installed is refused before anything of it runs -- not constructed, no
 * resource extracted, no class scanned -- with the one line naming the missing plugin and nothing
 * else. On a server without Vault, UltiEconomy used to be constructed and scanned first, and every
 * scan of its Vault-typed component logged a class-not-found trace plus a summary blaming an older
 * UltiTools-API, before the refusal line.
 */
@DisplayName("#554: a module whose required plugin is not installed is refused before construction")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerDependPrecheckTest {

    private static final String REQUIRED_PLUGIN = "FakeVault";
    private static final String MODULE_NAME = "PrecheckDependent";
    private static final String FIXTURE_PACKAGE = PrecheckModule.class.getPackage().getName() + ".";

    @TempDir
    File tempDir;

    private File dataFolder;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private Handler capture;
    private URLClassLoader moduleLoader;

    /**
     * Defines the fixture module's classes from its jar and refuses the absent required plugin's
     * type; the probe and everything else come from the test's own class loader.
     */
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
                if (found == null && name.startsWith(FIXTURE_PACKAGE)
                        && !PrecheckProbe.class.getName().equals(name)) {
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
        dataFolder = new File(tempDir, "UltiTools");
        TestHelper.mockUltiToolsInstance(mock -> {
            Mockito.lenient().when(mock.getDataFolder()).thenReturn(dataFolder);
            Mockito.lenient().when(mock.getConfig()).thenReturn(new YamlConfiguration());
            Mockito.lenient().when(mock.getConfigManager()).thenReturn(Mockito.mock(ConfigManager.class));
        });
        PrecheckProbe.INITIALIZED.set(0);
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
        // The root logger also receives the component scanner's own logger, which does not log
        // through Bukkit's; a record both see is counted once (distinctRecords).
        Logger.getLogger("").addHandler(capture);
        Bukkit.getLogger().addHandler(capture);
    }

    @AfterEach
    void tearDown() throws IOException {
        Logger.getLogger("").removeHandler(capture);
        Bukkit.getLogger().removeHandler(capture);
        if (moduleLoader != null) {
            moduleLoader.close();
        }
        MockBukkitHelper.safeUnmock();
    }

    private static void copyClass(JarOutputStream out, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        out.putNextEntry(new JarEntry(entry));
        try (InputStream classBytes = type.getClassLoader().getResourceAsStream(entry)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = classBytes.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends UltiToolsPlugin> moduleClassRequiringAbsentPlugin() throws Exception {
        File jar = new File(tempDir, "precheck-dependent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: " + MODULE_NAME + "\nversion: 1.0.0\nmain: " + PrecheckModule.class.getName()
                    + "\ndepend: [" + REQUIRED_PLUGIN + "]\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("config/precheck.yml"));
            out.write("enabled: true\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            copyClass(out, PrecheckModule.class);
            copyClass(out, PrecheckProviderService.class);
        }
        moduleLoader = new ModuleJarLoader(jar.toURI().toURL(), getClass().getClassLoader());
        // The class name is a compile-time constant, never attacker-controllable.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        return (Class<? extends UltiToolsPlugin>) Class.forName(PrecheckModule.class.getName(), false,
                moduleLoader);
    }

    /** Every captured record, each once even if two capturing loggers saw it. */
    private List<LogRecord> distinctRecords() {
        Set<LogRecord> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<LogRecord> result = new ArrayList<>();
        synchronized (records) {
            for (LogRecord logRecord : records) {
                if (seen.add(logRecord)) {
                    result.add(logRecord);
                }
            }
        }
        return result;
    }

    private List<LogRecord> warningsAndAbove() {
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord logRecord : distinctRecords()) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                result.add(logRecord);
            }
        }
        return result;
    }

    private void assertOnlyTheRefusalLine() {
        for (LogRecord logRecord : distinctRecords()) {
            String message = String.valueOf(logRecord.getMessage());
            assertThat(logRecord.getThrown()).as("trace on: %s", message).isNull();
            assertThat(message).doesNotContain(PrecheckProviderService.class.getSimpleName())
                    .doesNotContain("older UltiTools-API")
                    .doesNotContainIgnoringCase("skipped")
                    .doesNotContain("Cannot initialize plugin");
        }
        List<LogRecord> warnings = warningsAndAbove();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains(MODULE_NAME).contains(REQUIRED_PLUGIN);
    }

    @Test
    @DisplayName("register(Class): one line, no construction, no extraction, no scan, not loaded")
    void classPathRefusesBeforeConstruction() throws Exception {
        Class<? extends UltiToolsPlugin> moduleClass = moduleClassRequiringAbsentPlugin();
        PluginManager pluginManager = new PluginManager();

        boolean registered = pluginManager.register(moduleClass);

        assertThat(registered).isFalse();
        assertThat(PrecheckProbe.INITIALIZED.get()).as("module class initialized or constructed").isZero();
        assertThat(new File(dataFolder, "pluginConfig" + File.separator + MODULE_NAME))
                .as("resource extraction folder").doesNotExist();
        assertThat(pluginManager.getPluginList()).isEmpty();
        assertOnlyTheRefusalLine();
    }

    @Test
    @DisplayName("register(UltiToolsPlugin): one line, no container assembled or scanned, not loaded")
    void instancePathRefusesBeforeAssembly() throws Exception {
        Class<? extends UltiToolsPlugin> moduleClass = moduleClassRequiringAbsentPlugin();
        // The caller constructs the instance on this path; only what follows is the manager's.
        UltiToolsPlugin instance = moduleClass.getDeclaredConstructor().newInstance();
        records.clear();
        PluginManager pluginManager = new PluginManager();

        boolean registered = pluginManager.register(instance);

        assertThat(registered).isFalse();
        assertThat(instance.getContext()).as("container assembled").isNull();
        assertThat(pluginManager.getPluginList()).isEmpty();
        assertOnlyTheRefusalLine();
    }

    @Test
    @DisplayName("required plugin installed and enabled: the module is constructed as before")
    void presentRequiredPluginIsNotRefusedByThePrecheck() throws Exception {
        MockBukkit.createMockPlugin(REQUIRED_PLUGIN);
        Class<? extends UltiToolsPlugin> moduleClass = moduleClassRequiringAbsentPlugin();

        new PluginManager().register(moduleClass);

        assertThat(PrecheckProbe.INITIALIZED.get()).isEqualTo(1);
        for (LogRecord logRecord : warningsAndAbove()) {
            assertThat(String.valueOf(logRecord.getMessage())).doesNotContain("requires " + REQUIRED_PLUGIN);
        }
    }
}
