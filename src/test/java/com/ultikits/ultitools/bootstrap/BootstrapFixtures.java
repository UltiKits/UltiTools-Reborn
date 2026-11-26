package com.ultikits.ultitools.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.ultikits.ultitools.text.MessageKey;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.kyori.adventure.key.Key;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;

/** Real archive/class fixtures, with no server or parent-classpath fixture classes. */
public final class BootstrapFixtures {
    private BootstrapFixtures() {
        // Test fixture factory only.
    }

    public static Path jar(Path path, String descriptor, Map<String, byte[]> classes) throws IOException {
        return jar(path, descriptor, classes, Map.of());
    }

    static Path jar(Path path, String descriptor, Map<String, byte[]> classes, Map<String, String> resources)
            throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            write(out, "plugin.yml", descriptor.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                write(out, entry.getKey().replace('.', '/') + ".class", entry.getValue());
            }
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                write(out, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return path.toAbsolutePath().normalize();
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    public static String descriptor(String name, String hook) {
        return "name: " + name + "\nmain: fixture." + name + "Main\napi-version: 700\nversion: '1.0.0'\n"
                + (hook == null ? "" : "bootstrapper: " + hook + "\n");
    }

    public static byte[] mainClass(String name) {
        return type(name, "com/ultikits/ultitools/abstracts/UltiToolsPlugin").toByteArray();
    }

    public static byte[] plainClass(String name) {
        return type(name, "java/lang/Object").toByteArray();
    }

    private static ClassWriter type(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, parent, null);
        writer.visitEnd();
        return writer;
    }

    public static byte[] dependencyProbe(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
                "com/ultikits/ultitools/abstracts/UltiToolsPlugin", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "dependency", "()Ljava/lang/Class;", null, new String[] {"java/lang/ClassNotFoundException"});
        method.visitCode();
        method.visitLdcInsn("fixture.dep.Api");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] hook(String name, String failureType) {
        return hook(name, failureType, false, null);
    }

    static byte[] contentHook(String name, String failureType) {
        return hook(name, failureType, false, "declareContent");
    }

    static byte[] caughtWorldgenHook(String name) {
        return hook(name, null, false, "declareCaughtWorldgen");
    }

    public static void declareCaughtWorldgen(ModuleBootstrapContext context) {
        declareContent(context);
        try {
            context.datapacks().embedded("land");
        } catch (IllegalArgumentException ignored) {
            // The module deliberately tries to proceed after a refused declaration.
        }
    }

    static byte[] allChannelsHook(String name) {
        return hook(name, null, false, "declareAllChannels");
    }

    public static void declareAllChannels(ModuleBootstrapContext context) {
        declareContent(context);
        context.tags().setTag(RegistryKey.ITEM, Key.key("boot2content:probe_tools"), List.of(Key.key("minecraft:stick")));
        context.tags().addToTag(RegistryKey.ENCHANTMENT, Key.key("minecraft:in_enchanting_table"),
                List.of(Key.key("boot2content:edge")));
        context.datapacks().embedded("probe");
    }

    public static byte[] initializerFailureHook(String name) {
        return hook(name, null, true, null);
    }

    public static void declareContent(ModuleBootstrapContext context) {
        String namespace = MessageKey.namespaceFor(context.moduleName());
        context.enchantments().register(Key.key(namespace, "edge"), (builder, tags) -> {});
    }

    private static byte[] hook(String name, String failureType, boolean initializerFailure, String declarationMethod) {
        String owner = name.replace('.', '/');
        String context = "Lcom/ultikits/ultitools/bootstrap/ModuleBootstrapContext;";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object",
                new String[] {"com/ultikits/ultitools/bootstrap/ModuleBootstrap"});
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "context", context, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "threadLoader", "Ljava/lang/ClassLoader;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor hook = writer.visitMethod(Opcodes.ACC_PUBLIC, "bootstrap", "(" + context + ")V", null, null);
        hook.visitCode();
        hook.visitVarInsn(Opcodes.ALOAD, 1);
        hook.visitFieldInsn(Opcodes.PUTSTATIC, owner, "context", context);
        hook.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        hook.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
        hook.visitFieldInsn(Opcodes.PUTSTATIC, owner, "threadLoader", "Ljava/lang/ClassLoader;");
        if (declarationMethod != null) {
            hook.visitVarInsn(Opcodes.ALOAD, 1);
            hook.visitMethodInsn(Opcodes.INVOKESTATIC, BootstrapFixtures.class.getName().replace('.', '/'),
                    declarationMethod, "(" + context + ")V", false);
        }
        if (failureType == null) {
            hook.visitInsn(Opcodes.RETURN);
        } else {
            hook.visitTypeInsn(Opcodes.NEW, failureType);
            hook.visitInsn(Opcodes.DUP);
            hook.visitLdcInsn("fixture failure");
            hook.visitMethodInsn(Opcodes.INVOKESPECIAL, failureType, "<init>", "(Ljava/lang/String;)V", false);
            hook.visitInsn(Opcodes.ATHROW);
        }
        hook.visitMaxs(3, 2);
        hook.visitEnd();
        if (initializerFailure) {
            MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            initializer.visitCode();
            initializer.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            initializer.visitInsn(Opcodes.DUP);
            initializer.visitLdcInsn("initialization cause");
            initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
            initializer.visitInsn(Opcodes.ATHROW);
            initializer.visitMaxs(3, 0);
            initializer.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
