package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * A module JAR built against an older UltiTools-API is refused with one plain SEVERE line that says
 * so, names the module's declared version and api-version, and tells the operator what to do --
 * instead of a raw linkage message plus a full stack trace. Every other declared-main-class load
 * failure keeps its original generic SEVERE line, stack trace included.
 */
@DisplayName("PluginManager: modules built against an older UltiTools-API are refused with an upgrade hint")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerLegacyModuleLoadTest {

    private static final String LEGACY_MAIN = "com.ultikits.testfixtures.legacymodule.LegacyModule";
    private static final String OLDER_API = "built against an older UltiTools-API version";
    private static final String GENERIC = "but it could not be loaded";

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();

        bukkitLogs.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bukkitLogs.add(record);
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
        previousLevel = Bukkit.getLogger().getLevel();
        // FINE must reach the capture handler, or "the stack trace moved to FINE" is untestable.
        Bukkit.getLogger().setLevel(Level.ALL);
        Bukkit.getLogger().addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() throws Exception {
        Bukkit.getLogger().removeHandler(captureHandler);
        Bukkit.getLogger().setLevel(previousLevel);
        injectUltiToolsClassLoader(null);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("a real module class overriding the now-final unregisterSelf() gets the older-API line, not a stack trace")
    void finalOverride_isReportedAsBuiltAgainstOlderApi() throws Exception {
        File jar = moduleJar("UltiLegacy-1.2.0.jar", LEGACY_MAIN, "1.2.0", "620");
        Map<String, Object> defs = new HashMap<>();
        defs.put(LEGACY_MAIN, legacyModuleOverridingFinalUnregisterSelf());

        assertThat(loadWith(jar, defs)).isNull();

        LogRecord line = onlySevereNaming(jar.getName());
        assertThat(line.getMessage())
                .contains(OLDER_API)
                .contains("version 1.2.0")
                .contains("api-version 620")
                .contains("COMPATIBILITY.md")
                .contains("refusing to load")
                .contains("overrides final method")
                .contains("unregisterSelf");
        assertThat(line.getThrown()).as("no stack trace at SEVERE").isNull();
        assertThat(bukkitLogs).as("the stack trace is still available, at FINE")
                .anyMatch(r -> Level.FINE.equals(r.getLevel())
                        && r.getThrown() instanceof IncompatibleClassChangeError);
    }

    @Test
    @DisplayName("a NoSuchMethodError naming a framework member gets the older-API line")
    void noSuchFrameworkMethod_isReportedAsBuiltAgainstOlderApi() throws Exception {
        File jar = moduleJar("UltiOld.jar", LEGACY_MAIN, "2.0.1", "620");
        Map<String, Object> defs = new HashMap<>();
        defs.put(LEGACY_MAIN, new NoSuchMethodError(
                "'void com.ultikits.ultitools.abstracts.UltiToolsPlugin.removedHook()'"));

        assertThat(loadWith(jar, defs)).isNull();

        LogRecord line = onlySevereNaming(jar.getName());
        assertThat(line.getMessage()).contains(OLDER_API).contains("version 2.0.1").contains("api-version 620")
                .contains("removedHook");
        assertThat(line.getThrown()).isNull();
    }

    @Test
    @DisplayName("a missing framework class gets the older-API line; version and api-version are omitted when not declared")
    void missingFrameworkClass_isReportedAsBuiltAgainstOlderApi() throws Exception {
        File jar = moduleJar("UltiNoMeta.jar", LEGACY_MAIN, null, null);
        Map<String, Object> defs = new HashMap<>();
        defs.put(LEGACY_MAIN, new NoClassDefFoundError("com/ultikits/ultitools/abstracts/AbstractCommandExecutor"));

        assertThat(loadWith(jar, defs)).isNull();

        LogRecord line = onlySevereNaming(jar.getName());
        assertThat(line.getMessage()).contains(OLDER_API).doesNotContain("version null").doesNotContain("()");
        assertThat(line.getThrown()).isNull();
    }

    @Test
    @DisplayName("a missing third-party class keeps the original generic line, stack trace included")
    void missingThirdPartyClass_keepsTheGenericLine() throws Exception {
        File jar = moduleJar("UltiPapi.jar", LEGACY_MAIN, "1.0.0", "620");
        Map<String, Object> defs = new HashMap<>();
        defs.put(LEGACY_MAIN, new NoClassDefFoundError("me/clip/placeholderapi/expansion/PlaceholderExpansion"));

        assertThat(loadWith(jar, defs)).isNull();

        LogRecord line = onlySevereNaming(jar.getName());
        assertThat(line.getMessage()).contains(GENERIC).doesNotContain(OLDER_API);
        assertThat(line.getThrown()).isInstanceOf(NoClassDefFoundError.class);
    }

    @Test
    @DisplayName("a NoSuchMethodError naming a non-framework member keeps the original generic line")
    void noSuchThirdPartyMethod_keepsTheGenericLine() throws Exception {
        File jar = moduleJar("UltiBukkit.jar", LEGACY_MAIN, "1.0.0", "620");
        Map<String, Object> defs = new HashMap<>();
        defs.put(LEGACY_MAIN, new NoSuchMethodError("'void org.bukkit.entity.Player.removedMethod()'"));

        assertThat(loadWith(jar, defs)).isNull();

        LogRecord line = onlySevereNaming(jar.getName());
        assertThat(line.getMessage()).contains(GENERIC).doesNotContain(OLDER_API);
        assertThat(line.getThrown()).isInstanceOf(NoSuchMethodError.class);
    }

    @Test
    @DisplayName("a refused older-API module does not stop the next module in the folder from loading")
    void refusedLegacyModule_doesNotBlockOtherModules() throws Exception {
        File modules = new File(tempDir, "modules");
        assertThat(modules.mkdirs()).isTrue();
        File legacy = moduleJarIn(modules, "A-legacy.jar", LEGACY_MAIN, "1.2.0", "620");
        moduleJarIn(modules, "B-current.jar", CurrentPlugin.class.getName(), "1.0.0", "630");
        Map<String, Object> defs = new HashMap<>();
        defs.put(LEGACY_MAIN, legacyModuleOverridingFinalUnregisterSelf());
        try (URLClassLoader loader = new FixtureLoader(defs)) {
            injectUltiToolsClassLoader(loader);
            assertThat(pluginManager.discoverModuleClasses(modules)).isTrue();
        }

        assertThat(onlySevereNaming(legacy.getName()).getMessage()).contains(OLDER_API);
        assertThat(pluginClassList()).containsExactly(CurrentPlugin.class);
    }

    @Test
    @DisplayName("classification: which linkage failures mean 'built against an older UltiTools-API'")
    void classification() throws Exception {
        String fw = "com.ultikits.ultitools.abstracts.UltiToolsPlugin";
        assertThat(classify(new IncompatibleClassChangeError(
                "class a.B overrides final method " + fw + ".unregisterSelf()V"))).isTrue();
        assertThat(classify(new AbstractMethodError("Receiver class a.B does not define or inherit an "
                + "implementation of the resolved method 'abstract void x()' of abstract class " + fw + "."))).isTrue();
        assertThat(classify(new NoSuchFieldError("'int " + fw + ".removed'"))).isTrue();
        assertThat(classify(new NoClassDefFoundError("com/ultikits/ultitools/Gone"))).isTrue();
        ClassNotFoundException wrapped = new ClassNotFoundException("Failed to load class: a.B",
                new ClassNotFoundException("com.ultikits.ultitools.Gone"));
        assertThat(classify(wrapped)).isTrue();

        // Not about the framework, or not a "compiled against a different version" error at all.
        assertThat(classify(new NoClassDefFoundError("org/example/Gone"))).isFalse();
        assertThat(classify(new ClassNotFoundException("Failed to load class: a.B",
                new ClassNotFoundException("a.B")))).isFalse();
        assertThat(classify(new NoClassDefFoundError("Could not initialize class " + fw))).isFalse();
        assertThat(classify(new IncompatibleClassChangeError((String) null))).isFalse();
        assertThat(classify(new VerifyError("Bad type on operand stack in " + fw))).isFalse();
        assertThat(classify(new UnsupportedClassVersionError(fw + " has been compiled by a more recent "
                + "version of the Java Runtime"))).isFalse();
        assertThat(classify(new ClassFormatError("Truncated class file"))).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private boolean classify(Throwable failure) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("isBuiltAgainstOlderFrameworkApi", Throwable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, failure);
    }

    private LogRecord onlySevereNaming(String jarName) {
        List<LogRecord> matching = new ArrayList<>();
        for (LogRecord record : bukkitLogs) {
            if (Level.SEVERE.equals(record.getLevel()) && record.getMessage() != null
                    && record.getMessage().contains(jarName)) {
                matching.add(record);
            }
        }
        assertThat(matching).as("exactly one SEVERE line names %s", jarName).hasSize(1);
        return matching.get(0);
    }

    private Class<? extends UltiToolsPlugin> loadWith(File jar, Map<String, Object> defs) throws Exception {
        try (URLClassLoader loader = new FixtureLoader(defs)) {
            injectUltiToolsClassLoader(loader);
            Method method = PluginManager.class.getDeclaredMethod("loadPluginMainClass", ClassLoader.class, File.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Class<? extends UltiToolsPlugin> result =
                    (Class<? extends UltiToolsPlugin>) method.invoke(pluginManager, loader, jar);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Class<?>> pluginClassList() throws Exception {
        Field field = PluginManager.class.getDeclaredField("pluginClassList");
        field.setAccessible(true);
        return (List<Class<?>>) field.get(pluginManager);
    }

    private void injectUltiToolsClassLoader(URLClassLoader loader) throws Exception {
        if (UltiTools.getInstance() == null) {
            return;
        }
        Field field = UltiTools.class.getDeclaredField("ultiToolsClassLoader");
        field.setAccessible(true);
        field.set(UltiTools.getInstance(), loader);
    }

    private File moduleJar(String name, String main, String version, String apiVersion) throws IOException {
        return moduleJarIn(tempDir, name, main, version, apiVersion);
    }

    private static File moduleJarIn(File folder, String name, String main, String version, String apiVersion)
            throws IOException {
        StringBuilder yml = new StringBuilder("name: Fixture\nmain: ").append(main).append('\n');
        if (version != null) {
            yml.append("version: ").append(version).append('\n');
        }
        if (apiVersion != null) {
            yml.append("api-version: ").append(apiVersion).append('\n');
        }
        File jar = new File(folder, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    /**
     * The bytes a 6.2.5-era module main class compiles to: it extends {@code UltiToolsPlugin} and
     * overrides {@code unregisterSelf()}, which 6.3.0 made final. javac refuses to compile this
     * against the current framework, so it is assembled directly; defining it makes the JVM throw
     * the real {@code IncompatibleClassChangeError} an operator sees.
     */
    private static byte[] legacyModuleOverridingFinalUnregisterSelf() {
        String superName = "com/ultikits/ultitools/abstracts/UltiToolsPlugin";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                LEGACY_MAIN.replace('.', '/'), null, superName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor override = cw.visitMethod(Opcodes.ACC_PUBLIC, "unregisterSelf", "()V", null, null);
        override.visitCode();
        override.visitInsn(Opcodes.RETURN);
        override.visitMaxs(0, 0);
        override.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Stands in for the shared module class loader: a name mapped to {@code byte[]} is defined from
     * those bytes, a name mapped to a {@code Throwable} throws it (the linkage failures no compiler
     * will produce on demand), and everything else is delegated to the test class path.
     */
    private static final class FixtureLoader extends URLClassLoader {
        private final Map<String, Object> defs;

        FixtureLoader(Map<String, Object> defs) {
            super(new URL[0], PluginManagerLegacyModuleLoadTest.class.getClassLoader());
            this.defs = defs;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Object def = defs.get(name);
            if (def instanceof Error) {
                throw (Error) def;
            }
            if (def instanceof byte[]) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        byte[] bytes = (byte[]) def;
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    static class CurrentPlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }
}
