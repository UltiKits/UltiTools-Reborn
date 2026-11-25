package com.ultikits.ultitools.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.ModuleContentKind;

import io.papermc.paper.registry.RegistryKey;

/** This start's sealed declarations, in module JAR order. */
final class ContributionRegistry {
    private final ModuleJarAdmission admission;
    private final Logger logger;
    private final List<ModuleContributions> modules = new ArrayList<>();
    private final Set<ContentEntry> reserved = new HashSet<>();
    private final Set<Path> failures = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean composed = new AtomicBoolean();
    private List<EmbeddedDatapacks.PreparedPack> packs = List.of();
    private volatile boolean enabling;

    ContributionRegistry(ModuleJarAdmission admission, Logger logger) {
        this.admission = admission;
        this.logger = logger;
    }

    void accept(ModuleContributions module) {
        if (!module.isSealed()) {
            throw new IllegalStateException("Cannot accept declarations before the module bootstrap has returned");
        }
        var exclusive = module.exclusiveEntries();
        for (var entry : exclusive) {
            // Different module names can normalize to the same namespace. Never let a second
            // module replace an already-declared key or write over the same generated pack ZIP.
            if (reserved.contains(entry)) {
                throw new IllegalArgumentException("Custom content '" + entry.key() + "' (" + entry.kind()
                        + ") is already declared by another module");
            }
        }
        reserved.addAll(exclusive);
        modules.add(module);
    }

    List<ModuleContributions> modules() { return List.copyOf(modules); }

    boolean failed(Path jar) { return failures.contains(jar); }

    void beginEnable() { enabling = true; }

    void preparedPacks(List<EmbeddedDatapacks.PreparedPack> prepared) { packs = List.copyOf(prepared); }

    boolean hasDatapacks() { return !packs.isEmpty(); }

    boolean hasEnchantments() {
        return modules.stream().anyMatch(module -> !failed(module.jar()) && !module.enchantmentsByKey().isEmpty());
    }

    List<RegistryKey<?>> tagRegistries() {
        Set<RegistryKey<?>> keys = new LinkedHashSet<>();
        for (var module : modules) {
            if (!failed(module.jar())) {
                module.tagChanges().forEach(change -> keys.add(change.registry()));
            }
        }
        return List.copyOf(keys);
    }

    void composeEnchantments(WritableRegistryView writer, ComposeTags tags) {
        if (composed.compareAndSet(false, true)) {
            for (var module : modules) {
                composeModule(module, writer, tags);
            }
        }
    }

    private void composeModule(ModuleContributions module, WritableRegistryView writer, ComposeTags tags) {
        if (failed(module.jar())) {
            return;
        }
        for (var entry : module.enchantmentsByKey().entrySet()) {
            try {
                writer.register(entry.getKey(), builder -> entry.getValue().define(builder, tags));
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) { // NOPMD - a bad definition must not abort the registry or unrelated modules
                callbackFailure(module, ModuleContentKind.ENCHANTMENT, entry.getKey().asString(), failure);
                return;
            }
        }
    }

    void applyTags(RegistryKey<?> registry, TagWriter writer) {
        for (var module : modules) {
            if (!failed(module.jar())) {
                applyModuleTags(module, registry, writer);
            }
        }
    }

    private void applyModuleTags(ModuleContributions module, RegistryKey<?> registry, TagWriter writer) {
        for (var change : module.tagChanges()) {
            if (!registry.equals(change.registry())) {
                continue;
            }
            try {
                writer.apply(change);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) { // NOPMD - a bad tag must not abort Paper's tag event
                callbackFailure(module, ModuleContentKind.TAG, change.tag().asString(), failure);
                return;
            }
        }
    }

    void discoverDatapacks(PackSink sink) {
        for (var pack : packs) {
            if (failed(pack.module().jar())) {
                continue;
            }
            try {
                if (!sink.discover(pack.zip(), pack.id())) {
                    throw new IOException("Paper did not discover the data pack");
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) { // NOPMD - a bad pack must not abort Paper's discovery event
                callbackFailure(pack.module(), "data pack", pack.id(), failure);
            }
        }
    }

    void failPreparation(ModuleContributions module, String kind, String key, Throwable failure) {
        if (markFailed(module.jar(), failure)) {
            ModuleBootstraps.logFailure(logger, "[UltiTools] Module '" + module.jar().getFileName()
                    + "' could not prepare its " + kind + " '" + key + "': " + ModuleBootstraps.describe(failure)
                    + " -- the module is not loaded at this start.", failure);
        }
    }

    private void callbackFailure(ModuleContributions module, String kind, String key, Throwable failure) {
        String reason = "[UltiTools] Module '" + module.jar().getFileName() + "' failed while Paper registered its "
                + kind + " '" + key + "': " + ModuleBootstraps.describe(failure);
        if (enabling) {
            ModuleBootstraps.log(logger, Level.WARNING, reason + " -- the module is already running; restart after correcting it.", failure);
        } else if (markFailed(module.jar(), failure)) {
            ModuleBootstraps.logFailure(logger, reason + " -- the module is not loaded at this start. "
                    + "Entries it registered before this failure stay until the next restart.", failure);
        }
    }

    private boolean markFailed(Path jar, Throwable failure) {
        if (!failures.add(jar)) {
            return false;
        }
        admission.markBootstrapFailed(jar, failure);
        return true;
    }
}
