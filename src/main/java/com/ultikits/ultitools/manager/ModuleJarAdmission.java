package com.ultikits.ultitools.manager;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.utils.PluginYmlReader;

/**
 * This start's module metadata and minimum-API decisions, shared by class-path collection and
 * main-class discovery. Read after updates are applied and discarded with the framework;
 * a refused module never supplies classes merely because its JAR sorts before an accepted copy.
 * Diagnostics remain in discovery, after the language has been initialized.
 */
@ApiStatus.Internal
public final class ModuleJarAdmission {
    private final Map<Path, Entry> entries = new HashMap<>();

    /**
     * Reads each module's declaration and decides minimum admission once for this start.
     *
     * @param jar the module JAR, already checked by the archive guard
     * @return the cached metadata and decision
     */
    public Entry inspect(File jar) {
        Path path = jar.toPath().toAbsolutePath().normalize();
        return entries.computeIfAbsent(path, ignored -> new Entry(PluginYmlReader.readFromJarFile(jar)));
    }

    /** Immutable result; file-management identity is retained even when API admission is refused. */
    public static final class Entry {
        private final PluginYmlReader.PluginYmlInfo metadata;
        private final Integer apiLevel;
        private final boolean apiAllowed;

        private Entry(PluginYmlReader.PluginYmlInfo metadata) {
            this.metadata = metadata;
            this.apiLevel = ModuleApiLevels.parse(metadata.getApiVersion());
            this.apiAllowed = ModuleApiLevels.isLoadable(apiLevel);
        }

        /** @return the metadata used to make this decision */
        public PluginYmlReader.PluginYmlInfo getMetadata() {
            return metadata;
        }

        /** @return the integer API declaration, or {@code null} when missing or invalid */
        public Integer getApiLevel() {
            return apiLevel;
        }

        /** @return whether the declaration meets the shared minimum; the existing upper gate is unchanged */
        public boolean isApiAllowed() {
            return apiAllowed;
        }
    }
}
