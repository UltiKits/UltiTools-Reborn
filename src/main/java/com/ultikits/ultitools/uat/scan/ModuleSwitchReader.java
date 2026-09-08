package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.MergedAnnotationResolver;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads a module's {@code cmdExecutor}/{@code eventListener}/{@code config} registration
 * switches from its entry class (Phase 10, D-10-04), surfaced at document level as
 * {@code registers_commands}/{@code registers_listeners}/{@code registers_config}, and
 * {@code @UltiToolsModule}'s {@code additionalEntities()} attribute — {@code @Table} classes the
 * module owns that live outside its own classesRoot (a shared library jar, a multi-module
 * build's common artifact).
 * <p>
 * A {@code false} switch is reported, never used to suppress the rows it governs — a suppressed
 * registration is a fact to verify, not a reason to hide the function it would have registered.
 * <p>
 * Reading a {@code Class<?>}-typed annotation attribute resolves that class (so the JVM can hand
 * back a real {@link Class} object) but does not initialize it — resolution and initialization
 * are distinct per the JVM specification, so this stays within D-10-01's no-initialization
 * contract without any extra classloading of its own.
 * <p>
 * {@link #findModuleEntryClass} identifies the entry class by {@code PluginManager
 * .loadPluginMainClass}'s own runtime predicate — a concrete, non-interface
 * {@link UltiToolsPlugin} subclass — not by carrying {@code @UltiToolsModule} (Codex review of
 * PR #427): a module using the supported direct {@code @EnableAutoRegister}/
 * {@code @ComponentScan} annotations instead of {@code @UltiToolsModule} is still a valid entry
 * point at runtime, and requiring {@code @UltiToolsModule} specifically made this reader return
 * {@code null} for it, silently treating the scan-package set as unrestricted and omitting the
 * registration switches entirely.
 * <p>
 * The three switches and {@code additionalEntities()} are resolved differently on that entry
 * class, matching two DIFFERENT real lookups: {@code PluginManager.registerBukkit}/
 * {@code UltiToolsPlugin.initConfig} both resolve {@code @EnableAutoRegister} via
 * {@code MergedAnnotationResolver.find} (so an unannotated subclass of an annotated abstract
 * base still inherits the switches, and a bare {@code @EnableAutoRegister} works the same as one
 * composed via {@code @UltiToolsModule}) — {@code registerBukkit} registers NEITHER commands nor
 * listeners at all when that resolution finds nothing, which this reader mirrors by defaulting
 * both (and {@code config}, matching {@code initConfig}'s own null-check) to {@code false} rather
 * than {@code EnableAutoRegister}'s own annotation-default {@code true} — but
 * {@code PluginManager.scanPluginEntities} reads {@code pluginClass.getAnnotation
 * (UltiToolsModule.class)} DIRECTLY, since only {@code @UltiToolsModule} (not bare
 * {@code @EnableAutoRegister}) declares {@code additionalEntities()} at all, and the annotation
 * is not {@code @Inherited} — so an unannotated subclass gets NONE of an abstract base's
 * {@code additionalEntities()}, and using merged resolution for it would falsely attribute them.
 *
 * @since 6.3.0
 */
public final class ModuleSwitchReader {

    /**
     * Finds the module's entry class among {@code classes} and reads its registration switches
     * (merged {@code @EnableAutoRegister} resolution) and {@code additionalEntities()} (direct
     * {@code @UltiToolsModule} lookup on the same concrete class only).
     *
     * @param classes the loaded (uninitialized) classes to scan
     * @return the module's switches, or {@code null} if no valid entry class is present
     */
    public Switches read(List<Class<?>> classes) {
        Class<?> entryClass = findModuleEntryClass(classes);
        if (entryClass == null) {
            return null;
        }
        // PluginManager.registerBukkit/UltiToolsPlugin.initConfig both return/branch away
        // early when this resolves to null -- neither commands, listeners, nor package-scanned
        // config are ever auto-registered for a class with no @EnableAutoRegister anywhere in
        // its hierarchy (whether declared bare or composed via @UltiToolsModule). Defaulting
        // the switches to true here (EnableAutoRegister's own annotation default) would
        // misreport a module the runtime never auto-registers anything for.
        EnableAutoRegister autoRegister = MergedAnnotationResolver.find(entryClass, EnableAutoRegister.class);
        boolean registersCommands = autoRegister != null && autoRegister.cmdExecutor();
        boolean registersListeners = autoRegister != null && autoRegister.eventListener();
        boolean registersConfig = autoRegister != null && autoRegister.config();

        UltiToolsModule directlyDeclared = entryClass.getAnnotation(UltiToolsModule.class);
        Class<?>[] additionalEntities = directlyDeclared != null
                ? directlyDeclared.additionalEntities()
                : new Class<?>[0];
        return new Switches(registersCommands, registersListeners, registersConfig,
                Arrays.asList(additionalEntities));
    }

    /**
     * Finds the module's entry class among {@code classes}, the same way
     * {@code PluginManager.loadPluginMainClass} selects a module's real entry point: a concrete
     * (non-{@code abstract}, non-interface) {@link UltiToolsPlugin} subclass. Deliberately does
     * NOT require {@code @UltiToolsModule} -- a module using the supported direct
     * {@code @EnableAutoRegister}/{@code @ComponentScan} annotations instead is still a valid
     * entry point at runtime (Codex review of PR #427).
     *
     * @param classes the loaded (uninitialized) classes to scan
     * @return the entry class, or {@code null} if none qualifies
     */
    public static Class<?> findModuleEntryClass(List<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            if (UltiToolsPlugin.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        }
        return null;
    }

    /** The three registration switches plus {@code additionalEntities()}. */
    public static final class Switches {
        private final boolean registersCommands;
        private final boolean registersListeners;
        private final boolean registersConfig;
        private final List<Class<?>> additionalEntities;

        Switches(boolean registersCommands, boolean registersListeners, boolean registersConfig,
                List<Class<?>> additionalEntities) {
            this.registersCommands = registersCommands;
            this.registersListeners = registersListeners;
            this.registersConfig = registersConfig;
            this.additionalEntities = additionalEntities == null
                    ? Collections.emptyList() : Collections.unmodifiableList(additionalEntities);
        }

        public boolean isRegistersCommands() {
            return registersCommands;
        }

        public boolean isRegistersListeners() {
            return registersListeners;
        }

        public boolean isRegistersConfig() {
            return registersConfig;
        }

        public List<Class<?>> getAdditionalEntities() {
            return additionalEntities;
        }
    }
}
