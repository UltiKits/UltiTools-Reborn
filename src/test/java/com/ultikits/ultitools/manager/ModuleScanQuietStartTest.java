package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Module-scan regression guards retained from the former mixed audit/scan fixture (#557).
 * Each module goes through main-class loading and the real entity-scan loop. A missing
 * superclass supplies observable FINE and SEVERE records without the removed audit.
 */
@DisplayName("Module scans are quiet on a clean start and never print internal codes (#557)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ModuleScanQuietStartTest {

    private static final Pattern INTERNAL_CODE = Pattern.compile("[A-Z]{2,}-[0-9]+");

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private final List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
    private Handler captureHandler;
    private Logger diagnosticsLogger;
    private Level originalDiagnosticsLevel;
    private Logger bukkitLogger;
    private Level originalBukkitLevel;

    static class CleanPlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    public static class MissingParent {
    }

    public static class BrokenScannedClass extends MissingParent {
    }

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();

        captured.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
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
        captureHandler.setLevel(Level.ALL);
        // Initialize before attaching: production may install its bridge in the static initializer.
        Class.forName(ModuleScanDiagnostics.class.getName());
        diagnosticsLogger = Logger.getLogger(ModuleScanDiagnostics.class.getName());
        originalDiagnosticsLevel = diagnosticsLogger.getLevel();
        diagnosticsLogger.setLevel(Level.ALL);
        diagnosticsLogger.addHandler(captureHandler);
        bukkitLogger = Bukkit.getLogger();
        originalBukkitLevel = bukkitLogger.getLevel();
        bukkitLogger.setLevel(Level.ALL);
        bukkitLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        diagnosticsLogger.removeHandler(captureHandler);
        diagnosticsLogger.setLevel(originalDiagnosticsLevel);
        bukkitLogger.removeHandler(captureHandler);
        bukkitLogger.setLevel(originalBukkitLevel);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("two clean module jars registered at start-up produce no record at INFO or above")
    void twoCleanModulesAreQuiet() throws Exception {
        startUp(createModuleJar("clean-one.jar", CleanPlugin.class));
        startUp(createModuleJar("clean-two.jar", CleanPlugin.class));

        assertThat(atInfoOrAbove()).as("records a clean start printed").isEmpty();
    }

    @Test
    @DisplayName("no record text carries an internal requirement code")
    void noRecordCarriesAnInternalCode() throws Exception {
        startUp(createModuleJar("clean-code.jar", CleanPlugin.class));
        File brokenJar = createModuleJar("broken-code.jar", CleanPlugin.class, BrokenScannedClass.class);
        Field field = UltiTools.class.getDeclaredField("ultiToolsClassLoader");
        field.setAccessible(true);
        Object originalLoader = field.get(UltiTools.getInstance());
        // Define the scanned class from this JAR, but make its superclass genuinely unavailable.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{brokenJar.toURI().toURL()},
                getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(MissingParent.class.getName())) {
                        throw new ClassNotFoundException(name);
                    }
                    if (!name.equals(BrokenScannedClass.class.getName())) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        }) {
            field.set(UltiTools.getInstance(), loader);
            startUp(brokenJar);
        } finally {
            field.set(UltiTools.getInstance(), originalLoader);
        }

        List<LogRecord> records = snapshot();
        assertThat(records).as("control: the capture saw real scan diagnostics")
                .anyMatch(r -> r.getLevel().equals(Level.FINE) && r.getThrown() instanceof NoClassDefFoundError)
                .anyMatch(r -> r.getLevel().equals(Level.SEVERE) && r.getMessage().contains("broken-code.jar"));
        List<String> offending = records.stream()
                .map(LogRecord::getMessage)
                .filter(m -> m != null && INTERNAL_CODE.matcher(m).find())
                .collect(Collectors.toList());
        assertThat(offending).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"module-info.class", "com/example/package-info.class"})
    void metadataEntriesCurrentlyProduceOneWarning(String entry) throws Exception {
        File jar = new File(tempDir, "metadata.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("main: " + CleanPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            // Bytes are irrelevant: name validation rejects the entry before calling the loader.
            output.putNextEntry(new JarEntry(entry));
            output.closeEntry();
        }
        startUp(jar);

        assertThat(atInfoOrAbove()).hasSize(1);
        assertThat(atInfoOrAbove().get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(atInfoOrAbove().get(0).getMessage()).contains("Security violation during entity scan")
                .contains(entry.replace('/', '.').replace(".class", ""))
                .contains("Invalid class name format");
    }

    private List<LogRecord> snapshot() {
        synchronized (captured) {
            return new ArrayList<>(captured);
        }
    }

    private List<LogRecord> atInfoOrAbove() {
        return snapshot().stream()
                .filter(r -> r.getLevel().intValue() >= Level.INFO.intValue())
                .collect(Collectors.toList());
    }

    /** The two steps the framework runs for every module jar: read the main class, then scan for entities. */
    private void startUp(File moduleJar) throws Exception {
        Method load = PluginManager.class.getDeclaredMethod("loadPluginMainClass", ClassLoader.class, File.class);
        load.setAccessible(true);
        Object mainClass = load.invoke(pluginManager, Thread.currentThread().getContextClassLoader(), moduleJar);
        assertThat(mainClass).as("control: %s's main class was found", moduleJar.getName()).isEqualTo(CleanPlugin.class);

        Method scan = PluginManager.class.getDeclaredMethod("scanEntitiesInJar", File.class);
        scan.setAccessible(true);
        scan.invoke(pluginManager, moduleJar);
    }

    private File createModuleJar(String name, Class<?>... classes) throws IOException {
        File jar = new File(tempDir, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("main: " + CleanPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (Class<?> type : classes) {
                String resource = type.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resource));
                try (InputStream input = type.getResourceAsStream("/" + resource)) {
                    assertThat(input).as("compiled class resource for %s", type.getName()).isNotNull();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                output.closeEntry();
            }
        }
        return jar;
    }
}
