package com.ultikits.ultitools.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.CodeSourceLocations;
import com.ultikits.ultitools.utils.PluginYmlReader;

/** The boundary of one module's early hook, with no Bukkit server or module construction. */
final class ModuleBootstraps {
    private ModuleBootstraps() {
        // Internal runner; there is no global bootstrap state.
    }

    static Run runAll(ModuleJarAdmission admission, ClassLoader loader, Logger logger) {
        Map<Path, Throwable> failures = new LinkedHashMap<>();
        int attempted = 0;
        // Filename order makes diagnostics repeatable; it is not an ordering contract for modules.
        for (Map.Entry<Path, ModuleJarAdmission.Entry> module : admission.entries().entrySet()) {
            PluginYmlReader.PluginYmlInfo metadata = module.getValue().getMetadata();
            String bootstrapper = metadata.getBootstrapper();
            if (!module.getValue().isApiAllowed() || bootstrapper == null || bootstrapper.trim().isEmpty()) {
                continue;
            }
            attempted++;
            try {
                runOne(module.getKey(), metadata, bootstrapper, loader);
            } catch (Throwable failure) { // NOPMD - a module failure must never abort the framework bootstrap
                Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                        ? failure.getCause() : failure;
                admission.markBootstrapFailed(module.getKey(), cause);
                failures.put(module.getKey(), cause);
                // Early output stays English; translated discovery diagnostics follow during enable.
                logger.log(Level.SEVERE, String.format("[UltiTools] Module '%s' failed in its bootstrap phase (%s): "
                        + "%s: %s -- the module is not loaded at this start. Fix or remove the module and restart the server.",
                        module.getKey().getFileName(), bootstrapper, cause.getClass().getName(), cause.getMessage()), cause);
            }
        }
        return new Run(failures, attempted);
    }

    private static void runOne(Path jar, PluginYmlReader.PluginYmlInfo metadata, String className,
                               ClassLoader loader) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection -- name comes from the module's own plugin.yml.
            Class<?> type = Class.forName(className, true, loader);
            requireOwnSource(type, jar);
            if (!ModuleBootstrap.class.isAssignableFrom(type)) {
                throw new IllegalStateException("bootstrapper does not implement ModuleBootstrap");
            }
            ModuleBootstrap hook = type.asSubclass(ModuleBootstrap.class).getConstructor().newInstance();
            String declaredName = metadata.getName();
            String name = declaredName == null ? jar.getFileName().toString() : declaredName;
            hook.bootstrap(new Context(name, jar));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void requireOwnSource(Class<?> type, Path jar) throws Exception {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null
                || !CodeSourceLocations.toFile(source.getLocation()).getCanonicalFile()
                        .equals(jar.toFile().getCanonicalFile())) {
            throw new IllegalStateException("bootstrapper class is not in this module's JAR");
        }
    }

    static final class Run {
        final Map<Path, Throwable> failures;
        final int attempted;

        Run(Map<Path, Throwable> failures, int attempted) {
            this.failures = failures;
            this.attempted = attempted;
        }
    }

    private static final class Context implements ModuleBootstrapContext {
        private final String name;
        private final Path jar;
        private final Logger logger;

        Context(String name, Path jar) {
            this.name = name;
            this.jar = jar;
            this.logger = Logger.getLogger("UltiTools/" + name);
        }

        @Override public String moduleName() { return name; }
        @Override public Path moduleJar() { return jar; }
        @Override public Logger logger() { return logger; }
    }
}
