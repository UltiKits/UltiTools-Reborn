package com.ultikits.ultitools;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Audit-only resource index. Reads API bytecode without defining or initializing API classes. */
final class LinkageApiIndex {
    private static final ApiType ABSENT = new ApiType();
    private final ClassLoader loader;
    private final Map<String, ApiType> types = new HashMap<>();

    LinkageApiIndex(ClassLoader loader) {
        this.loader = loader;
    }

    ServerApiTypeKind typeKind(String owner) {
        ApiType type = read(owner);
        return type == ABSENT ? ServerApiTypeKind.ABSENT
                : type.isInterface ? ServerApiTypeKind.INTERFACE : ServerApiTypeKind.CLASS;
    }

    boolean hasMethod(String owner, String name, String descriptor) {
        return method(owner, name + descriptor, "<init>".equals(name), new HashSet<>());
    }

    private boolean method(String owner, String key, boolean constructor, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }
        ApiType type = read(owner);
        if (type == ABSENT) {
            return false;
        }
        if (type.methods.containsKey(key)) {
            return true;
        }
        if (constructor) {
            return false;
        }
        return inheritedMethod(type, key, seen);
    }

    private boolean inheritedMethod(ApiType type, String key, Set<String> seen) {
        // Interface resolution may use public instance members of Object, not its private methods.
        if (type.isInterface) {
            Integer access = read("java/lang/Object").methods.get(key);
            if (access != null && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC) {
                return true;
            }
        } else if (method(type.superName, key, false, seen)) {
            return true;
        }
        for (String parent : type.interfaces) {
            if (method(parent, key, false, seen)) {
                return true;
            }
        }
        return false;
    }

    boolean hasField(String owner, String name, String descriptor) {
        return field(owner, name + ":" + descriptor, new HashSet<>());
    }

    private boolean field(String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }
        ApiType type = read(owner);
        if (type == ABSENT) {
            return false;
        }
        if (type.fields.contains(key)) {
            return true;
        }
        for (String parent : type.interfaces) {
            if (field(parent, key, seen)) {
                return true;
            }
        }
        return field(type.superName, key, seen);
    }

    private ApiType read(String owner) {
        ApiType cached = types.get(owner);
        if (cached != null) {
            return cached;
        }
        try (InputStream input = loader.getResourceAsStream(owner + ".class")) {
            if (input == null) {
                types.put(owner, ABSENT);
                return ABSENT;
            }
            ClassReader reader = new ClassReader(input);
            ApiType type = new ApiType();
            type.isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
            type.superName = reader.getSuperName();
            type.interfaces = reader.getInterfaces();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    type.methods.put(name + descriptor, access);
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    type.fields.add(name + ":" + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            types.put(owner, type);
            return type;
        } catch (IOException failure) {
            // An unreadable API is not an absent API. Keep the cause instead of inventing findings.
            throw new UncheckedIOException("Could not inspect API class " + owner, failure);
        }
    }

    private static final class ApiType {
        private boolean isInterface;
        private String superName;
        private String[] interfaces = new String[0];
        private final Map<String, Integer> methods = new HashMap<>();
        private final Set<String> fields = new HashSet<>();
    }
}
