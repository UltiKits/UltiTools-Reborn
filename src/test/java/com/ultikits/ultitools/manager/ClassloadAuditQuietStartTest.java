package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;

/**
 * #557: a clean start prints no class-load audit line, a module the removed class-name filters would
 * have refused something from is reported exactly once, and no record carries an internal requirement
 * code.
 * <p>
 * Each module jar goes through the same two steps the framework runs at start-up: {@code init()} reads
 * its main class ({@code loadPluginMainClass}), then registration scans it for entities
 * ({@code scanEntitiesInJar}). Before the fix both steps emitted a summary for the same jar, so every
 * module was reported twice, at INFO through a private handler that printed WARN on a real server.
 */
@DisplayName("A clean start is quiet; a non-clean audit is reported once (#557)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ClassloadAuditQuietStartTest {

    private static final String AUDIT_LOGGER = "com.ultikits.ultitools.utils.ClassloadFilterAudit";
    private static final Pattern INTERNAL_CODE = Pattern.compile("[A-Z]{2,}-[0-9]+");

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private final List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
    private Handler captureHandler;
    private Logger auditLogger;
    private Logger diagnosticsLogger;

    /** A module main class that no removed filter layer would have refused. */
    static class CleanPlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
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
        auditLogger = Logger.getLogger(AUDIT_LOGGER);
        auditLogger.setLevel(Level.ALL);
        auditLogger.addHandler(captureHandler);
        diagnosticsLogger = Logger.getLogger(ModuleScanDiagnostics.class.getName());
        diagnosticsLogger.setLevel(Level.ALL);
        diagnosticsLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        auditLogger.removeHandler(captureHandler);
        diagnosticsLogger.removeHandler(captureHandler);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("two clean module jars registered at start-up produce no record at INFO or above")
    void twoCleanModulesAreQuiet() throws Exception {
        startUp(createModuleJar("clean-one.jar", CleanPlugin.class));
        startUp(createModuleJar("clean-two.jar", CleanPlugin.class));

        assertThat(atInfoOrAbove()).as("records a clean start printed").isEmpty();
    }

    @Test
    @DisplayName("a module holding classes the removed filters would have refused gets exactly one INFO record, naming it and the count")
    void aNonCleanModuleIsReportedOnceAtInfo() throws Exception {
        startUp(createModuleJar("flagged-one.jar", CleanPlugin.class, Gson.class, JsonObject.class));

        List<LogRecord> info = atInfoOrAbove();
        assertThat(info).hasSize(1);
        assertThat(info.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(info.get(0).getMessage()).contains("flagged-one.jar").contains("2 class");
    }

    @Test
    @DisplayName("each module jar is audited at most once per start, at any level")
    void eachModuleJarIsAuditedAtMostOnce() throws Exception {
        startUp(createModuleJar("clean-once.jar", CleanPlugin.class));
        startUp(createModuleJar("flagged-once.jar", CleanPlugin.class, Gson.class));

        for (String jar : new String[] {"clean-once.jar", "flagged-once.jar"}) {
            long summaries = snapshot().stream()
                    .filter(r -> r.getMessage() != null && r.getMessage().contains(jar)
                            && r.getMessage().toLowerCase(java.util.Locale.ROOT).contains("audit"))
                    .count();
            assertThat(summaries).as("audit summaries for %s", jar).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("no record text carries an internal requirement code")
    void noRecordCarriesAnInternalCode() throws Exception {
        startUp(createModuleJar("clean-code.jar", CleanPlugin.class));
        startUp(createModuleJar("flagged-code.jar", CleanPlugin.class, Gson.class));

        List<String> offending = snapshot().stream()
                .map(LogRecord::getMessage)
                .filter(m -> m != null && INTERNAL_CODE.matcher(m).find())
                .collect(Collectors.toList());
        assertThat(snapshot()).as("control: the capture saw records").isNotEmpty();
        assertThat(offending).isEmpty();
    }

    @Test
    @DisplayName("neither diagnostic class builds its own console handler or writes to System.err")
    void noPrivateConsoleOutput() throws IOException {
        Path utils = Paths.get("src/main/java/com/ultikits/ultitools/utils");
        for (String file : new String[] {"ClassloadFilterAudit.java", "ModuleScanDiagnostics.java"}) {
            String source = new String(Files.readAllBytes(utils.resolve(file)), StandardCharsets.UTF_8);
            assertThat(source.length()).as("control: %s was read", file).isGreaterThan(1000);
            assertThat(source).as("%s must not attach its own console handler", file).doesNotContain("ConsoleHandler");
            assertThat(source).as("%s must not write to System.err", file).doesNotContain("System.err");
        }
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
