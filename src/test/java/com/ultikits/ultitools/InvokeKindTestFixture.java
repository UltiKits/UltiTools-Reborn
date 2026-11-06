package com.ultikits.ultitools;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.ConstantDynamic;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Real Java 8 classes isolated from the Paper types on the test class path. */
final class InvokeKindTestFixture {
    static final String DEFAULT_OWNER = "org/bukkit/compatfixture/Shape";
    static final String CALLER = "fixture/Caller";
    final String owner;
    final Path api;
    final Path module;
    final byte[] original;

    InvokeKindTestFixture(Path folder, String owner, boolean runtimeInterface, String form,
            boolean compileInterface, String member) throws IOException {
        Files.createDirectories(folder);
        this.owner = owner;
        Map<String, byte[]> types = new LinkedHashMap<>();
        types.put(owner, api(owner, runtimeInterface, false));
        types.put(owner + "Impl", implementation(owner, runtimeInterface));
        api = jar(folder.resolve("api.jar"), types, null);
        original = caller(owner, form, compileInterface, member);
        module = jar(folder.resolve("module.jar"), Collections.singletonMap(CALLER, original), "FixtureModule");
    }

    URLClassLoader parent() throws IOException {
        return new URLClassLoader(new URL[]{api.toUri().toURL()}, null);
    }

    Object invoke(ClassLoader loader) throws Exception {
        Class<?> caller = loader.loadClass(CALLER.replace('/', '.'));
        Object receiver = loader.loadClass((owner + "Impl").replace('/', '.')).getConstructor().newInstance();
        return caller.getMethod("run", Object.class).invoke(null, receiver);
    }

    static Throwable root(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    static ClassRewriteRule rule(ServerApiProbe probe) {
        return new InvokeKindAlignmentRule(probe);
    }

    static ServerApiProbe probe(ClassLoader parent) {
        return new RuntimeServerApiProbe(parent);
    }

    static Path jar(Path path, Map<String, byte[]> classes, String name) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
            if (name != null) {
                output.putNextEntry(new JarEntry("plugin.yml"));
                output.write(("name: " + name + "\nmain: fixture.Caller\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }

    static ClassWriter header(String name, boolean itf, String superclass, String... interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_SUPER),
                name, null, superclass, interfaces);
        return writer;
    }

    static void constructor(ClassWriter writer, String superclass) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    static void value(ClassWriter writer, int flags, String name, String descriptor, String result) {
        MethodVisitor method = writer.visitMethod(flags, name, descriptor, null, null);
        method.visitCode();
        method.visitLdcInsn(result);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, (flags & Opcodes.ACC_STATIC) == 0 ? 1 : 0);
        method.visitEnd();
    }

    static byte[] api(String owner, boolean itf, boolean explodingInitializer) {
        ClassWriter writer = header(owner, itf, "java/lang/Object");
        if (!itf) {
            constructor(writer, "java/lang/Object");
            value(writer, Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", "rewritten");
        } else {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()Ljava/lang/String;", null, null).visitEnd();
        }
        value(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "staticValue", "()Ljava/lang/String;", "rewritten");
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FIELD", "Ljava/lang/String;", null, "field").visitEnd();
        MethodVisitor bootstrap = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", null, null);
        bootstrap.visitCode();
        bootstrap.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
        bootstrap.visitInsn(Opcodes.DUP);
        bootstrap.visitLdcInsn(Type.getType(String.class));
        bootstrap.visitLdcInsn("rewritten");
        bootstrap.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "constant",
                "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false);
        bootstrap.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>",
                "(Ljava/lang/invoke/MethodHandle;)V", false);
        bootstrap.visitInsn(Opcodes.ARETURN);
        bootstrap.visitMaxs(4, 3);
        bootstrap.visitEnd();
        if (explodingInitializer) {
            MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            initializer.visitCode();
            initializer.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
            initializer.visitInsn(Opcodes.DUP);
            initializer.visitLdcInsn("initialized");
            initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
            initializer.visitInsn(Opcodes.ATHROW);
            initializer.visitMaxs(3, 0);
            initializer.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] implementation(String owner, boolean itf) {
        ClassWriter writer = header(owner + "Impl", false, itf ? "java/lang/Object" : owner,
                itf ? new String[]{owner} : new String[0]);
        constructor(writer, itf ? "java/lang/Object" : owner);
        if (itf) {
            value(writer, Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", "rewritten");
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] caller(String owner, String form, boolean itf, String member) {
        ClassWriter writer = header(CALLER, false, "java/lang/Object");
        if ("condy".equals(form)) {
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CALLER, null, "java/lang/Object", null);
        }
        writer.visitSource("KindCaller.java", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        method.visitCode();
        Label line = new Label();
        method.visitLabel(line);
        method.visitLineNumber(73, line);
        int virtualTag = itf ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
        String descriptor = "()Ljava/lang/String;";
        if ("bootstrap".equals(form)) {
            method.visitInvokeDynamicInsn("run", descriptor, new Handle(Opcodes.H_INVOKESTATIC, owner, "bootstrap",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", itf));
        } else if ("lambda".equals(form)) {
            receiver(method, owner);
            method.visitInvokeDynamicInsn("get", "(L" + owner + ";)Ljava/util/function/Supplier;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                    Type.getMethodType("()Ljava/lang/Object;"), new Handle(virtualTag, owner, member, descriptor, itf), Type.getMethodType(descriptor));
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
            method.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        } else if ("handle".equals(form) || "static-handle".equals(form) || "special-handle".equals(form) || "condy".equals(form)) {
            boolean isStatic = "static-handle".equals(form);
            int tag = isStatic ? Opcodes.H_INVOKESTATIC : "special-handle".equals(form) ? Opcodes.H_INVOKESPECIAL : virtualTag;
            Handle target = new Handle(tag, owner, member, descriptor, itf);
            if ("condy".equals(form)) {
                Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "explicitCast",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
                ConstantDynamic inner = new ConstantDynamic("inner", "Ljava/lang/invoke/MethodHandle;", bootstrap, target);
                method.visitLdcInsn(new ConstantDynamic("outer", "Ljava/lang/invoke/MethodHandle;", bootstrap, inner));
            } else {
                method.visitLdcInsn(target);
            }
            if (!isStatic) {
                receiver(method, owner);
            }
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                    isStatic ? descriptor : "(L" + owner + ";)Ljava/lang/String;", false);
        } else {
            boolean isStatic = "static".equals(form);
            if (!isStatic) {
                receiver(method, owner);
            }
            int opcode = isStatic ? Opcodes.INVOKESTATIC : "special".equals(form) ? Opcodes.INVOKESPECIAL
                    : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            method.visitMethodInsn(opcode, owner, member, descriptor, itf);
        }
        if ("twice".equals(form)) {
            method.visitInsn(Opcodes.POP);
            receiver(method, owner);
            method.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, owner, member, descriptor, itf);
        }
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(3, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void receiver(MethodVisitor method, String owner) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitTypeInsn(Opcodes.CHECKCAST, owner);
    }
}
