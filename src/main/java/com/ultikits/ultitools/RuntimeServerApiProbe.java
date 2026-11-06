package com.ultikits.ultitools;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.bytebuddy.jar.asm.Type;

/** Facts from this start's real parent loader, without initializing server API classes. */
final class RuntimeServerApiProbe implements ServerApiProbe {
    private final ClassLoader parent;
    private final ConcurrentMap<String, Optional<Class<?>>> types = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> methods = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> fields = new ConcurrentHashMap<>();

    RuntimeServerApiProbe(ClassLoader parent) {
        this.parent = parent;
    }

    @Override
    public ServerApiTypeKind typeKind(String owner) {
        Optional<Class<?>> type = resolve(owner);
        return !type.isPresent() ? ServerApiTypeKind.ABSENT
                : type.get().isInterface() ? ServerApiTypeKind.INTERFACE : ServerApiTypeKind.CLASS;
    }

    @Override
    public boolean hasMethod(String owner, String name, String descriptor) {
        return methods.computeIfAbsent(owner + "." + name + descriptor,
                ignored -> resolve(owner).map(type -> findMethod(type, name, descriptor)).orElse(false));
    }

    @Override
    public boolean hasField(String owner, String name, String descriptor) {
        return fields.computeIfAbsent(owner + "." + name + ":" + descriptor,
                ignored -> resolve(owner).map(type -> findField(type, name, descriptor, new HashSet<Class<?>>())).orElse(false));
    }

    private Optional<Class<?>> resolve(String owner) {
        return types.computeIfAbsent(owner, name -> {
            try {
                // Names come from the module's own bytecode references; initialize=false runs no static initializer.
                // Classes are only inspected for kind/members, never instantiated or invoked.
                // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
                return Optional.of(Class.forName(name.replace('/', '.'), false, parent));
            } catch (ClassNotFoundException absent) {
                return Optional.empty();
            }
            // A missing dependency or broken API is a linkage failure, not an absent owner.
        });
    }

    private static boolean findMethod(Class<?> type, String name, String descriptor) {
        if ("<init>".equals(name)) {
            return declaresConstructor(type, descriptor);
        }
        if ("<clinit>".equals(name)) {
            return false;
        }
        boolean declared = type.isInterface() ? findInterfaceOwnerMethod(type, name, descriptor)
                : findClassMethod(type, name, descriptor);
        return declared || findInheritedInterfaceMethod(type, name, descriptor);
    }

    private static boolean declaresConstructor(Class<?> type, String descriptor) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Type.getConstructorDescriptor(constructor).equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findInterfaceOwnerMethod(Class<?> type, String name, String descriptor) {
        if (declaresMethod(type, name, descriptor, false)) {
            return true;
        }
        // JVMS interface method resolution also admits public instance methods of Object.
        for (Method method : Object.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                    && matches(method, name, descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findClassMethod(Class<?> type, String name, String descriptor) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (declaresMethod(current, name, descriptor, false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findInheritedInterfaceMethod(Class<?> type, String name, String descriptor) {
        Set<Class<?>> visited = new HashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> superinterface : current.getInterfaces()) {
                if (findInterfaceMethod(superinterface, name, descriptor, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean findInterfaceMethod(Class<?> type, String name, String descriptor, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return false;
        }
        if (declaresMethod(type, name, descriptor, true)) {
            return true;
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (findInterfaceMethod(parent, name, descriptor, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresMethod(Class<?> type, String name, String descriptor, boolean inheritedInterface) {
        for (Method method : type.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if ((!inheritedInterface || (!Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)))
                    && matches(method, name, descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Method method, String name, String descriptor) {
        return method.getName().equals(name) && Type.getMethodDescriptor(method).equals(descriptor);
    }

    private static boolean findField(Class<?> type, String name, String descriptor, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return false;
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name) && Type.getDescriptor(field.getType()).equals(descriptor)) {
                return true;
            }
        }
        for (Class<?> superinterface : type.getInterfaces()) {
            if (findField(superinterface, name, descriptor, visited)) {
                return true;
            }
        }
        return findField(type.getSuperclass(), name, descriptor, visited);
    }
}
