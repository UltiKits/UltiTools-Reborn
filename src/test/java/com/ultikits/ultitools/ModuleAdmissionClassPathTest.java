package com.ultikits.ultitools;

import com.ultikits.ultitools.bootstrap.ModuleClassPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.PluginYmlReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Admission must isolate bytes, not merely skip a module's registration. */
class ModuleAdmissionClassPathTest {
    private static final String MAIN = "fixture.admission.Main";
    @TempDir Path modules;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"630", "699", "''", "'26.3'", "1.21", "abc"})
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void realLoaderUsesCurrentBytesWhenAnEarlierCopyIsRefused(String declaration) throws Exception {
        File old = module("m-6.3.0.jar", declaration, "old", true);
        File current = module("m-7.0.0.jar", "700", "current", false);
        ModuleJarAdmission admission = new ModuleJarAdmission();
        try (MockedStatic<PluginYmlReader> metadata = mockStatic(PluginYmlReader.class, CALLS_REAL_METHODS)) {
            List<URL> urls = ModuleClassPath.collectModuleJarUrls(modules.toFile(), admission);
            List<LogRecord> logs = new ArrayList<>();
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { logs.add(record); }
                @Override public void flush() {
                    // Records are kept in memory; there is nothing to flush.
                }
                @Override public void close() {
                    // Records are kept in memory; there is nothing to close.
                }
            });
            UltiTools framework = mock(UltiTools.class);
            when(framework.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            when(framework.getDescription()).thenReturn(new PluginDescriptionFile("UltiTools", "7.0.0-SNAPSHOT", "unused.Main"));
            try (ModuleClassLoader loader = new ModuleClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
                    MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                    MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                tools.when(UltiTools::getInstance).thenReturn(framework);
                tools.when(UltiTools::getJavaPluginClassLoader).thenReturn(loader);
                bukkit.when(Bukkit::getLogger).thenReturn(logger);
                PluginManager manager = spy(new PluginManager(admission));
                doReturn(true).when(manager).register(org.mockito.ArgumentMatchers.<Class<? extends UltiToolsPlugin>>any());
                Method discover = PluginManager.class.getDeclaredMethod("discoverModuleClasses", File.class);
                discover.setAccessible(true);
                assertThat(discover.invoke(manager, modules.toFile())).isEqualTo(true);
                Field candidates = PluginManager.class.getDeclaredField("pluginClassList");
                candidates.setAccessible(true);
                assertThat((List<?>) candidates.get(manager)).hasSize(1);
                Class<?> loaded = (Class<?>) ((List<?>) candidates.get(manager)).get(0);
                assertThat(loaded.getProtectionDomain().getCodeSource().getLocation()).isEqualTo(current.toURI().toURL());
                assertThat(loaded.getMethod("origin").invoke(null)).isEqualTo("current");
                assertThat(loader.getURLs()).containsExactly(current.toURI().toURL());
                assertThatThrownBy(() -> loader.loadClass("fixture.admission.RefusedOnly"))
                        .isInstanceOf(ClassNotFoundException.class);
                Method finish = PluginManager.class.getDeclaredMethod("loadDiscoveredModules");
                finish.setAccessible(true);
                finish.invoke(manager);
                assertThat(logs.stream().filter(record -> record.getLevel() == Level.SEVERE)).singleElement()
                        .satisfies(record -> assertThat(record.getMessage()).contains(old.getName(), "api-version", "700")
                                .doesNotContain(current.getName()));
                assertThat(logs).anyMatch(record -> record.getMessage().equals("[UltiTools-API] Succeeded loaded 1, Failed 1."));
                assertThat(manager.getModuleJarIndex().jarsDeclaring(MAIN)).containsExactly(old, current);
                assertThat(manager.getModuleJarIndex().supplierOf(MAIN)).isEqualTo(current);
                assertThat(logs.stream().filter(record -> record.getLevel() == Level.WARNING)).singleElement()
                        .satisfies(record -> assertThat(record.getMessage()).contains("m-6.3.0.jar, m-7.0.0.jar", "m-7.0.0.jar"));
            }
            metadata.verify(() -> PluginYmlReader.readFromJarFile(old), times(1));
            metadata.verify(() -> PluginYmlReader.readFromJarFile(current), times(1));
        }
    }

    private File module(String name, String api, String origin, boolean oldOnly) throws Exception {
        Path file = modules.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            entry(out, "plugin.yml", ("name: Probe\nversion: '1.0'\nmain: " + MAIN + "\n" + (api == null ? "" : "api-version: " + api + "\n")).getBytes(StandardCharsets.UTF_8));
            entry(out, MAIN.replace('.', '/') + ".class", type(MAIN, origin));
            if (oldOnly) {
                entry(out, "fixture/admission/RefusedOnly.class", type("fixture.admission.RefusedOnly", "old"));
            }
        }
        return file.toFile();
    }

    private static void entry(JarOutputStream out, String path, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(path));
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] type(String name, String origin) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
                "com/ultikits/ultitools/abstracts/UltiToolsPlugin", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "origin", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(origin);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
