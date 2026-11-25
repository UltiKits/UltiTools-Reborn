package com.ultikits.ultitools.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    static Run runAll(ModuleJarAdmission admission, ClassLoader loader, Logger logger, ContributionRegistry contributions) {
        Map<Path, Throwable> failures = new LinkedHashMap<>();
        Set<String> mains = new HashSet<>();
        int attempted = 0;
        // Filename order makes diagnostics repeatable; it is not an ordering contract for modules.
        for (Map.Entry<Path, ModuleJarAdmission.Entry> module : admission.entries().entrySet()) {
            PluginYmlReader.PluginYmlInfo metadata = module.getValue().getMetadata();
            if (!module.getValue().isApiAllowed()) {
                continue;
            }
            String main = metadata.getMain();
            // The shared loader supplies the first copy, even if that copy has no early hook.
            // Discovery retains responsibility for the ordinary duplicate-copy warning.
            if (main != null && !main.isBlank() && !mains.add(main)) {
                continue;
            }
            String bootstrapper = metadata.getBootstrapper();
            if (bootstrapper == null || bootstrapper.isBlank()) {
                contributions.recordWithoutHook(moduleName(module.getKey(), metadata));
                continue;
            }
            attempted++;
            try {
                contributions.accept(runOne(module.getKey(), metadata, bootstrapper, loader, logger));
            } catch (VirtualMachineError failure) {
                throw failure;
            } catch (Throwable cause) { // NOPMD - a module failure must never abort the framework bootstrap
                admission.markBootstrapFailed(module.getKey(), cause);
                failures.put(module.getKey(), cause);
                // Early output stays English; translated discovery diagnostics follow during enable.
                logFailure(logger, String.format("[UltiTools] Module '%s' failed in its bootstrap phase (%s): "
                        + "%s -- the module is not loaded at this start. Update rollback is checked during enable; "
                        + "check the enable log before changing any module JARs.",
                        module.getKey().getFileName(), bootstrapper, describe(cause)), cause);
            }
        }
        return new Run(failures, attempted);
    }

    private static ModuleContributions runOne(Path jar, PluginYmlReader.PluginYmlInfo metadata, String className,
                               ClassLoader loader, Logger logger) throws Throwable {
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
            ModuleBootstrap hook = construct(type);
            ModuleContributions contributions = new ModuleContributions(moduleName(jar, metadata), jar);
            try {
                hook.bootstrap(new Context(contributions, logger));
                return contributions;
            } finally {
                contributions.seal();
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static String moduleName(Path jar, PluginYmlReader.PluginYmlInfo metadata) {
        String declared = metadata.getName();
        return declared == null ? jar.getFileName().toString() : declared;
    }

    private static ModuleBootstrap construct(Class<?> type) throws Throwable {
        try {
            return type.asSubclass(ModuleBootstrap.class).getConstructor().newInstance();
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }

    static String describe(Throwable failure) {
        String description = failure.getClass().getName() + ": " + safeMessage(failure);
        try {
            Throwable cause = failure.getCause();
            return cause == null ? description : description + "; caused by "
                    + cause.getClass().getName() + ": " + safeMessage(cause);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unavailable) { // NOPMD - a module's diagnostic method must not abort bootstrap
            return description + "; cause unavailable";
        }
    }

    private static String safeMessage(Throwable failure) {
        try {
            String message = failure.getMessage();
            return message == null ? "(no message)" : message;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unavailable) { // NOPMD - a module's diagnostic method must not abort bootstrap
            return "message unavailable";
        }
    }

    static void logFailure(Logger logger, String message, Throwable failure) {
        log(logger, Level.SEVERE, message, failure);
    }

    static void log(Logger logger, Level level, String message, Throwable failure) {
        try {
            logger.log(level, message, failure);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unavailable) { // NOPMD - a logging handler must not let a module abort bootstrap
            // The failure remains in admission/the handover for enable even if early logging fails.
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
        private final ModuleContributions contributions;
        private final Logger logger;

        Context(ModuleContributions contributions, Logger parent) {
            this.contributions = contributions;
            this.logger = BootstrapLogger.module(parent, contributions.name());
        }

        @Override public String moduleName() { return contributions.name(); }
        @Override public Path moduleJar() { return contributions.jar(); }
        @Override public Logger logger() { return logger; }
        @Override public EnchantmentChannel enchantments() { return contributions.enchantments(); }
        @Override public TagChannel tags() { return contributions.tags(); }
        @Override public DatapackChannel datapacks() { return contributions.datapacks(); }
    }
}
