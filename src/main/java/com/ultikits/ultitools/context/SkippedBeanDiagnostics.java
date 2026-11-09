package com.ultikits.ultitools.context;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Diagnostics for an observed bean skip, without changing the container's loading decisions. */
final class SkippedBeanDiagnostics {
    private SkippedBeanDiagnostics() {
        // Utility class; diagnostics have no instance state.
    }

    static void warn(String module, Class<?> beanClass, Throwable failure, Logger logger) {
        if (module == null || module.trim().isEmpty()) {
            return;
        }
        String missing = missingServerClass(failure);
        if (missing == null) {
            return;
        }
        String handlers;
        // Handler-isolation advice only helps a bean that lost handlers, or one whose handlers are unknown.
        boolean lostHandlers;
        try {
            List<String> methods = new ArrayList<>();
            collect(beanClass.getClassLoader(), beanClass.getName().replace('.', '/'), false,
                    new HashSet<>(), new HashSet<>(), methods);
            handlers = methods.isEmpty() ? "none" : String.join(", ", methods);
            lostHandlers = !methods.isEmpty();
        } catch (IOException | RuntimeException unreadable) {
            // This diagnostic must not replace the original, already observed bean failure.
            handlers = "could not read handler class bytes (" + unreadable.getClass().getSimpleName() + ")";
            lostHandlers = true;
        }
        logger.warning("Module '" + module + "': bean class '" + beanClass.getName()
                + "' was skipped because server class '" + missing
                + "' is absent; unregistered @EventHandler methods: " + handlers + ".");
        if (!lostHandlers) {
            return;
        }
        logger.warning("Module '" + module + "': move optional handlers for events that may be absent into a separate class "
                + "outside every configured scanBasePackages string prefix, and register that class only after a "
                + "Class.forName(name, false, loader) probe succeeds.");
    }

    /** Read declarations rather than getDeclaredMethods: the latter is the failing operation. */
    private static void collect(ClassLoader loader, String owner, boolean inherited, Set<String> visited,
            Set<String> methodsSeen, List<String> handlers) throws IOException {
        if (owner == null || "java/lang/Object".equals(owner) || !visited.add(owner)) {
            return;
        }
        try (InputStream input = loader.getResourceAsStream(owner + ".class")) {
            if (input == null) {
                throw new IOException("No class bytes for " + owner);
            }
            ClassReader reader = new ClassReader(input);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if ((inherited && (access & Opcodes.ACC_PUBLIC) == 0) || !methodsSeen.add(name + descriptor)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if ("Lorg/bukkit/event/EventHandler;".equals(annotation)) {
                                handlers.add(owner.replace('/', '.') + "." + name + descriptor);
                            }
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            collect(loader, reader.getSuperName(), true, visited, methodsSeen, handlers);
            for (String parent : reader.getInterfaces()) {
                collect(loader, parent, true, visited, methodsSeen, handlers);
            }
        }
    }

    private static String missingServerClass(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 8; cursor = cursor.getCause(), depth++) {
            String missing = null;
            if (cursor instanceof TypeNotPresentException) {
                missing = ((TypeNotPresentException) cursor).typeName();
            } else if (cursor instanceof NoClassDefFoundError || cursor instanceof ClassNotFoundException) {
                missing = cursor.getMessage();
            }
            if (missing == null) {
                continue;
            }
            missing = missing.trim().replace('/', '.');
            if (missing.indexOf(' ') < 0 && (missing.startsWith("org.bukkit.") || missing.startsWith("io.papermc.")
                    || missing.startsWith("com.destroystokyo.") || missing.startsWith("org.spigotmc.")
                    || missing.startsWith("net.kyori.") || missing.startsWith("co.aikar."))) {
                return missing;
            }
        }
        return null;
    }
}
