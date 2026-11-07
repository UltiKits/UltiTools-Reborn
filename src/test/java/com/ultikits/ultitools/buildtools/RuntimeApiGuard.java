package com.ultikits.ultitools.buildtools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Test-only direct binary-reference checks. Never loads or executes server or framework classes. */
final class RuntimeApiGuard {
    private static final List<String> API_PREFIXES = Arrays.asList(
            "com/google/gson/", "org/bukkit/", "io/papermc/paper/", "com/destroystokyo/paper/");
    private final Map<String, ApiClass> floor = new HashMap<>();
    private final Map<String, ApiClass> external = new HashMap<>();

    RuntimeApiGuard(Path... fixtures) throws IOException {
        for (Path fixture : fixtures) {
            for (byte[] bytes : jarClasses(fixture).values()) {
                ApiClass api = describe(bytes);
                floor.put(api.name, api);
            }
        }
    }

    Set<String> missingReferences(Map<String, byte[]> classes) throws IOException {
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            new ClassReader(entry.getValue()).accept(new ReferenceVisitor(entry.getKey(), missing),
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return missing;
    }

    private final class ReferenceVisitor extends ClassVisitor {
        private final String source;
        private final Set<String> missing;

        ReferenceVisitor(String source, Set<String> missing) {
            super(Opcodes.ASM9);
            this.source = source;
            this.missing = missing;
        }

        private void reference(String owner, String member, boolean method) {
            if (owner == null || !guarded(owner)) {
                return;
            }
            boolean exists = member == null ? floor.containsKey(owner)
                    : hasMember(owner, member, method, new HashSet<String>());
            if (!exists) {
                missing.add(source + " -> " + owner + (member == null ? "" : "." + member));
            }
        }

        private void descriptor(String descriptor) {
            type(Type.getType(descriptor));
        }

        private void type(Type type) {
            if (type.getSort() == Type.OBJECT) {
                reference(type.getInternalName(), null, false);
            } else if (type.getSort() == Type.ARRAY) {
                type(type.getElementType());
            } else if (type.getSort() == Type.METHOD) {
                type(type.getReturnType());
                for (Type argument : type.getArgumentTypes()) {
                    type(argument);
                }
            }
        }

        private void constant(Object value) {
            if (value instanceof Type) {
                type((Type) value);
            } else if (value instanceof Handle) {
                Handle handle = (Handle) value;
                boolean method = handle.getTag() > Opcodes.H_PUTSTATIC;
                reference(handle.getOwner(), handle.getName() + handle.getDesc(), method);
                descriptor(handle.getDesc());
            }
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            reference(superName, null, false);
            for (String iface : interfaces) {
                reference(iface, null, false);
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            descriptor(desc);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            descriptor(desc);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    reference(exception, null, false);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    if (type.startsWith("[")) {
                        descriptor(type);
                    } else {
                        reference(type, null, false);
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                    reference(owner, name + desc, false);
                    descriptor(desc);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean iface) {
                    reference(owner, name + desc, true);
                    descriptor(desc);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(value);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... arguments) {
                    descriptor(desc);
                    constant(bootstrap);
                    for (Object argument : arguments) {
                        constant(argument);
                    }
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    descriptor(descriptor);
                }

                @Override
                public void visitTryCatchBlock(net.bytebuddy.jar.asm.Label start,
                        net.bytebuddy.jar.asm.Label end, net.bytebuddy.jar.asm.Label handler, String type) {
                    reference(type, null, false);
                }
            };
        }
    }

    private boolean hasMember(String owner, String member, boolean method, Set<String> visited) {
        if (!visited.add(owner)) {
            return false;
        }
        ApiClass api = floor.get(owner);
        if (api == null && !guarded(owner)) {
            api = externalClass(owner);
        }
        if (api == null) {
            return false;
        }
        if ((method ? api.methods : api.fields).contains(member)) {
            return true;
        }
        // Constructors are never inherited.
        if (member.startsWith("<init>")) {
            return false;
        }
        for (String parent : api.parents) {
            if (hasMember(parent, member, method, visited)) {
                return true;
            }
        }
        return false;
    }

    private ApiClass externalClass(String name) {
        if (!external.containsKey(name)) {
            try (InputStream input = RuntimeApiGuard.class.getClassLoader().getResourceAsStream(name + ".class")) {
                external.put(name, input == null ? null : describe(readBytes(input)));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot inspect inherited external API " + name, error);
            }
        }
        return external.get(name);
    }

    private static boolean guarded(String name) {
        for (String prefix : API_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static ApiClass describe(byte[] bytes) {
        ApiClass description = new ApiClass();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                description.name = name;
                if (superName != null) {
                    description.parents.add(superName);
                }
                description.parents.addAll(Arrays.asList(interfaces));
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                description.fields.add(name + desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                description.methods.add(name + desc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return description;
    }

    static Map<String, byte[]> compiledClasses(Path root) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList())) {
                classes.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return classes;
    }

    static Map<String, byte[]> jarClasses(Path path) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        classes.put(entry.getName(), readBytes(input));
                    }
                }
            }
        }
        return classes;
    }

    static Set<String> platformReferences(Map<String, byte[]> classes) {
        Set<String> references = new TreeSet<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            // ISO-8859-1 maps bytes one-to-one; names in a class constant pool are ASCII.
            String bytes = new String(entry.getValue(), StandardCharsets.ISO_8859_1);
            if (bytes.contains("net/kyori/adventure/platform") || bytes.contains("net.kyori.adventure.platform")) {
                references.add(entry.getKey());
            }
        }
        return references;
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class ApiClass {
        private String name;
        private final Set<String> methods = new HashSet<>();
        private final Set<String> fields = new HashSet<>();
        private final List<String> parents = new ArrayList<>();
    }
}
