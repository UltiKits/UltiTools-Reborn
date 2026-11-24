package com.ultikits.ultitools.manager;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Records a failed early hook before discovery can load the module main class.
     *
     * @param jar the module's own archive
     * @param failure the original early-hook failure
     */
    public void markBootstrapFailed(Path jar, Throwable failure) {
        inspect(jar.toFile()).bootstrapFailure = failure;
    }

    /** @return a filename-ordered snapshot of metadata already inspected at this start */
    public Map<Path, Entry> entries() {
        List<Path> paths = new ArrayList<>(entries.keySet());
        paths.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).thenComparing(Path::toString));
        Map<Path, Entry> ordered = new LinkedHashMap<>();
        for (Path path : paths) {
            ordered.put(path, entries.get(path));
        }
        return Collections.unmodifiableMap(ordered);
    }

    /** Minimum decision and the early-hook outcome; archive identity survives either refusal. */
    public static final class Entry {
        private final PluginYmlReader.PluginYmlInfo metadata;
        private final Integer apiLevel;
        private final boolean apiAllowed;
        private @Nullable Throwable bootstrapFailure;

        private Entry(PluginYmlReader.PluginYmlInfo metadata) {
            this.metadata = metadata;
            this.apiLevel = ModuleApiLevels.parse(metadata.getApiVersion());
            this.apiAllowed = ModuleApiLevels.isLoadable(apiLevel);
        }

        /** @return the metadata used to make this decision */
        public PluginYmlReader.PluginYmlInfo getMetadata() { return metadata; }

        /** @return the integer API declaration, or {@code null} when missing or invalid */
        public Integer getApiLevel() { return apiLevel; }

        /** @return whether the declaration meets the shared minimum; the existing upper gate is unchanged */
        public boolean isApiAllowed() { return apiAllowed; }

        /** @return the original early-hook failure, or {@code null} when no hook failed */
        public @Nullable Throwable getBootstrapFailure() { return bootstrapFailure; }
    }
}
