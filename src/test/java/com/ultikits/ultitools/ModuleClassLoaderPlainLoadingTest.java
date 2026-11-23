package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.context.BeanDefinition;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Real module JARs exercise ordinary JVM linking, without calling any audit entry point. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ModuleClassLoaderPlainLoadingTest {
    @TempDir
    Path temp;

    @Test
    void productionLoader_doesNotAdaptAnInterfaceCallToAClass() throws Exception {
        Path api = jar("api.jar", entries("org/bukkit/uat/Shape.class", bean("org/bukkit/uat/Shape", null, false)));
        ClassWriter caller = new ClassWriter(0);
        caller.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Caller", null, "java/lang/Object", null);
        MethodVisitor method = caller.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call",
                "(Lorg/bukkit/uat/Shape;)Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/bukkit/uat/Shape", "value", "()Ljava/lang/String;", true);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        caller.visitEnd();
        Path module = jar("caller.jar", entries("fixture/Caller.class", caller.toByteArray()));
        try (URLClassLoader parent = new URLClassLoader(new URL[]{api.toUri().toURL()}, getClass().getClassLoader());
                ModuleClassLoader loader = loader(new URL[]{module.toUri().toURL()}, parent)) {
            Class<?> shape = parent.loadClass("org.bukkit.uat.Shape");
            Object receiver = shape.getConstructor().newInstance();
            Class<?> type = loader.loadClass("fixture.Caller");
            assertThatThrownBy(() -> type.getMethod("call", shape).invoke(null, receiver))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseExactlyInstanceOf(IncompatibleClassChangeError.class);
        }
    }

    @Test
    void modulesShareClassesAndResourcesWithParentFirstOrdering() throws Exception {
        Path first = jar("first.jar", entries("fixture/First.class", bean("fixture/First", null, false),
                "shared.txt", "first".getBytes(StandardCharsets.UTF_8)));
        Path second = jar("second.jar", entries("fixture/Second.class", bean("fixture/Second", "fixture/First", false),
                "shared.txt", "second".getBytes(StandardCharsets.UTF_8)));
        Path parentJar = jar("parent.jar", entries("shared.txt", "parent".getBytes(StandardCharsets.UTF_8)));
        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, getClass().getClassLoader());
                ModuleClassLoader loader = loader(new URL[]{first.toUri().toURL(), second.toUri().toURL()}, parent)) {
            Class<?> secondClass = loader.loadClass("fixture.Second");
            assertThat(secondClass.getMethod("optional").getReturnType()).isSameAs(loader.loadClass("fixture.First"));
            assertThat(secondClass.getClassLoader()).isSameAs(loader);
            List<URL> resources = Collections.list(loader.getResources("shared.txt"));
            assertThat(resources).hasSize(3);
            assertThat(resources.get(0)).isEqualTo(parent.getResource("shared.txt"));
            assertThat(loader.getResource("shared.txt")).isEqualTo(resources.get(0));
            assertThat(resources.get(1).toString()).contains("first.jar");
            assertThat(resources.get(2).toString()).contains("second.jar");
        }
    }

    @Test
    void independentClassLoadingStillRunsInParallelWithoutRewriteHooks() throws Exception {
        Path module = jar("parallel.jar", entries("fixture/Left.class", bean("fixture/Left", null, false),
                "fixture/Right.class", bean("fixture/Right", null, false)));
        CyclicBarrier entered = new CyclicBarrier(2);
        List<Throwable> barrierFailures = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("fixture.Left") || name.equals("fixture.Right")) {
                    attempts.incrementAndGet();
                    try {
                        entered.await(10, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        barrierFailures.add(failure);
                        throw new ClassNotFoundException(name, failure);
                    }
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try (ModuleClassLoader loader = loader(new URL[]{module.toUri().toURL()}, parent)) {
            var left = pool.submit(() -> loader.loadClass("fixture.Left"));
            var right = pool.submit(() -> loader.loadClass("fixture.Right"));
            assertThat(left.get(15, TimeUnit.SECONDS).getPackage()).isSameAs(right.get(15, TimeUnit.SECONDS).getPackage());
            assertThat(barrierFailures).as("both classes must reach the parent barrier concurrently").isEmpty();
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(loader.isRegisteredAsParallelCapable()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void absentServerClass_isSkippedAndDiagnosedWithoutAnAudit() throws Exception {
        verifyContainerFailure(false, NoClassDefFoundError.class, "org.bukkit.uat.AbsentEvent");
    }

    @Test
    void absentServerMethod_isSkippedAndDiagnosedWithoutAnAudit() throws Exception {
        verifyContainerFailure(true, NoSuchMethodError.class, "org.bukkit.uat.Shape.removed");
    }

    private void verifyContainerFailure(boolean missingMethod, Class<? extends LinkageError> failureType,
            String missingSymbol) throws Exception {
        Path module = jar("broken.jar", entries("fixture/Broken.class", bean("fixture/Broken",
                missingMethod ? null : "org/bukkit/uat/AbsentEvent", missingMethod),
                "fixture/Survivor.class", bean("fixture/Survivor", null, false),
                "org/bukkit/uat/Shape.class", bean("org/bukkit/uat/Shape", null, false)));
        Logger diagnostics = Logger.getLogger(ModuleScanDiagnostics.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        diagnostics.addHandler(capture);
        SimpleContainer container = new SimpleContainer();
        try (ModuleClassLoader loader = loader(new URL[]{module.toUri().toURL()}, getClass().getClassLoader())) {
            container.setDisplayName("PlainModule");
            container.setAopProxyResolver(new AopProxyResolver());
            container.registerBeanDefinition("broken", new BeanDefinition(loader.loadClass("fixture.Broken")));
            container.registerBeanDefinition("survivor", new BeanDefinition(loader.loadClass("fixture.Survivor")));
            assertThatCode(container::refresh).doesNotThrowAnyException();
            assertThat(container.getBean("survivor")).isNotNull();
            assertThatThrownBy(() -> container.getBean("broken")).isExactlyInstanceOf(failureType);
            assertThat(container.getBeanNamesForType(Object.class)).contains("survivor").doesNotContain("broken");
            assertThat(records.stream().filter(r -> r.getLevel() == java.util.logging.Level.SEVERE))
                    .singleElement().satisfies(record -> assertThat(record.getMessage())
                            .contains("PlainModule", "fixture.Broken", missingSymbol));
        } finally {
            container.close();
            diagnostics.removeHandler(capture);
        }
    }

    private ModuleClassLoader loader(URL[] urls, ClassLoader parent) {
        return new ModuleClassLoader(urls, parent);
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path path = temp.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, byte[]> entries(Object... entries) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], (byte[]) entries[i + 1]);
        }
        return result;
    }

    private static byte[] bean(String name, String optionalType, boolean missingMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null);
        value.visitCode();
        value.visitLdcInsn("unchanged");
        value.visitInsn(Opcodes.ARETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();
        if (optionalType != null) {
            MethodVisitor optional = writer.visitMethod(Opcodes.ACC_PUBLIC, "optional", "()L" + optionalType + ";", null, null);
            optional.visitCode();
            optional.visitInsn(Opcodes.ACONST_NULL);
            optional.visitInsn(Opcodes.ARETURN);
            optional.visitMaxs(1, 1);
            optional.visitEnd();
        }
        if (missingMethod) {
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitMethodInsn(Opcodes.INVOKESTATIC, "org/bukkit/uat/Shape", "removed", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
