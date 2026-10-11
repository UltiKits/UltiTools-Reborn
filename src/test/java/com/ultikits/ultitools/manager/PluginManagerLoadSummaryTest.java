package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.mockito.ArgumentMatchers;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * The start-up summary {@code [UltiTools-API] Succeeded loaded <s>, Failed <f>.} counts as failed
 * every module JAR of that start that did not end up loaded (plan 18-41). Observed defect:
 * {@code Succeeded loaded 2, Failed 0} in the same boot that refused UltiRecipe, because a JAR
 * refused while its main class was read never reached the count.
 *
 * <p>The modules folder is a folder of real JARs whose main classes are generated here and loaded
 * through one class loader over those JARs in file-name order, as the server's shared module class
 * loader does; so the duplicate-copy and linkage paths run as they do on a server. Registration is
 * the one part stubbed: {@code register(Class)} answers as a module's registration would, so the
 * test counts what the summary counts without building module containers.
 */
@DisplayName("The boot load summary counts every module JAR that did not load (18-41)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerLoadSummaryTest {

    private static final String PACKAGE = "com.ultikits.testfixtures.loadsummary.";
    private static final String SUMMARY_PREFIX = "[UltiTools-API] Succeeded loaded ";
    private static final String NONE_FOUND = "[UltiTools-API] No UltiTools plugin found.";

    @TempDir
    File modules;

    private final List<LogRecord> logs = new ArrayList<>();
    private Handler capture;
    private URLClassLoader loader;
    /** Main classes whose registration fails (registerSelf returned false, or it threw). */
    private final Set<String> failingRegistration = new HashSet<>();

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing held
            }
        };
        Bukkit.getLogger().addHandler(capture);
    }

    @AfterEach
    void tearDown() throws Exception {
        Bukkit.getLogger().removeHandler(capture);
        injectModuleClassLoader(null);
        if (loader != null) {
            loader.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // ---------------------------------------------------------------- the start, minus containers

    /**
     * Runs the part of {@code init} after its listeners: discovery over {@link #modules}, then
     * dependency ordering, registration and the summary.
     */
    private void start() throws Exception {
        File[] jars = com.ultikits.ultitools.utils.ModuleFileTransactions.moduleJars(modules);
        List<URL> urls = new ArrayList<>();
        if (jars != null) {
            for (File jar : jars) {
                urls.add(jar.toURI().toURL());
            }
        }
        loader = new URLClassLoader(urls.toArray(new URL[0]), PluginManagerLoadSummaryTest.class.getClassLoader());
        injectModuleClassLoader(loader);
        PluginManager manager = spy(new PluginManager());
        doAnswer(invocation -> !failingRegistration.contains(((Class<?>) invocation.getArgument(0)).getName()))
                .when(manager).register(ArgumentMatchers.<Class<? extends UltiToolsPlugin>>any());
        logs.clear();
        assertThat(manager.discoverModuleClasses(modules)).isTrue();
        manager.loadDiscoveredModules();
    }

    private List<String> lines(String startingWith) {
        List<String> found = new ArrayList<>();
        for (LogRecord record : logs) {
            String message = String.valueOf(record.getMessage());
            if (message.startsWith(startingWith)) {
                found.add(message);
            }
        }
        return found;
    }

    private boolean severeNaming(String jarName) {
        return logs.stream().anyMatch(record -> Level.SEVERE.equals(record.getLevel())
                && String.valueOf(record.getMessage()).contains("Module '" + jarName + "'"));
    }

    // ---------------------------------------------------------------- tracer cases

    @Test
    @DisplayName("two loadable modules and one JAR whose main class overrides the now-final unregisterSelf(): the refusal line, then 'Succeeded loaded 2, Failed 1.'")
    void refusedJarBesideLoadableModules_isCountedAsFailed() throws Exception {
        moduleJar("alpha-1.0.jar", "AlphaModule", module("AlphaModule"), "630");
        moduleJar("bravo-1.0.jar", "BravoModule", module("BravoModule"), "630");
        moduleJar("ultirecipe-1.0.0.jar", "LegacyModule", legacyModule("LegacyModule"), "620");

        start();

        assertThat(severeNaming("ultirecipe-1.0.0.jar")).as("the existing refusal line names the JAR").isTrue();
        assertThat(lines(SUMMARY_PREFIX)).containsExactly("[UltiTools-API] Succeeded loaded 2, Failed 1.");
    }

    @Test
    @DisplayName("a modules folder holding only that refused JAR: 'Succeeded loaded 0, Failed 1.' and no 'No UltiTools plugin found.'")
    void onlyARefusedJar_printsTheSummary() throws Exception {
        moduleJar("ultirecipe-1.0.0.jar", "LegacyModule", legacyModule("LegacyModule"), "620");

        start();

        assertThat(lines(SUMMARY_PREFIX)).containsExactly("[UltiTools-API] Succeeded loaded 0, Failed 1.");
        assertThat(lines(NONE_FOUND)).isEmpty();
    }

    @Test
    @DisplayName("an empty modules folder: 'No UltiTools plugin found.' and no summary, as before")
    void emptyFolder_keepsTheNoneFoundLine() throws Exception {
        start();

        assertThat(lines(NONE_FOUND)).containsExactly(NONE_FOUND);
        assertThat(lines(SUMMARY_PREFIX)).isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private File moduleJar(String fileName, String simpleName, byte[] classBytes, String apiVersion)
            throws IOException {
        String main = PACKAGE + simpleName;
        String yml = "name: " + simpleName + "\nversion: '1.0'\nmain: " + main + "\napi-version: " + apiVersion + "\n";
        File jar = new File(modules, fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(yml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (classBytes != null) {
                out.putNextEntry(new JarEntry(main.replace('.', '/') + ".class"));
                out.write(classBytes);
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * A module main class built for this framework: extends {@code UltiToolsPlugin}, public no-arg
     * constructor, optionally {@code @PluginDependency(depends = ...)}.
     */
    static byte[] module(String simpleName, String... depends) {
        String superName = "com/ultikits/ultitools/abstracts/UltiToolsPlugin";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, (PACKAGE + simpleName).replace('.', '/'),
                null, superName, null);
        if (depends.length > 0) {
            AnnotationVisitor dependency = cw.visitAnnotation("Lcom/ultikits/ultitools/annotations/PluginDependency;", true);
            AnnotationVisitor names = dependency.visitArray("depends");
            for (String name : depends) {
                names.visit(null, name);
            }
            names.visitEnd();
            dependency.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The shape of a module built before 6.3.0 (UltiRecipe 1.0.0): it overrides
     * {@code unregisterSelf()}, which 6.3.0 made final, so defining it throws the real
     * {@code IncompatibleClassChangeError}.
     */
    static byte[] legacyModule(String simpleName) {
        String superName = "com/ultikits/ultitools/abstracts/UltiToolsPlugin";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, (PACKAGE + simpleName).replace('.', '/'),
                null, superName, null);
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

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the module class loader is a private field of UltiTools
    private static void injectModuleClassLoader(URLClassLoader moduleLoader) throws Exception {
        if (UltiTools.getInstance() == null) {
            return;
        }
        Field field = UltiTools.class.getDeclaredField("ultiToolsClassLoader");
        field.setAccessible(true);
        field.set(UltiTools.getInstance(), moduleLoader);
    }
}
