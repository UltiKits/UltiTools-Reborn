package com.ultikits.ultitools.bootstrap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.ultikits.ultitools.manager.ModuleJarAdmission;

/** This start's sealed declarations, in module JAR order. */
final class ContributionRegistry {
    private final ModuleJarAdmission admission;
    private final Logger logger;
    private final List<ModuleContributions> modules = new ArrayList<>();
    private final Set<ContentEntry> reserved = new HashSet<>();

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
}
