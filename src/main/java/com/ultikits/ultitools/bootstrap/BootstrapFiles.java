package com.ultikits.ultitools.bootstrap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Locates framework-owned derived files without following links within the owned subtree. */
final class BootstrapFiles {
    static final String LEDGER_TEMP_PREFIX = ".contributions-";
    static final String PACK_TEMP_PREFIX = ".pack-";
    static final String TEMP_SUFFIX = ".tmp";

    private BootstrapFiles() {
        // Framework-owned paths only.
    }

    static Path ensureDirectory(Path path) throws IOException {
        if (Files.notExists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path);
        }
        requireDirectory(path);
        return path;
    }

    static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw new IOException("Not a framework-owned directory: " + path);
        }
    }

    static Path root(Path dataFolder) {
        return dataFolder.resolve("bootstrap");
    }

    static Path datapacks(Path bootstrapRoot) {
        return bootstrapRoot.resolve("datapacks");
    }

    /** Runs before any new writes; only regular files with the writers' reserved names are stale. */
    static void cleanStaleTemporaries(Path dataFolder, Logger logger) {
        Path root = root(dataFolder);
        if (!Files.isDirectory(dataFolder, NOFOLLOW_LINKS) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            return;
        }
        deleteTemporaries(root, LEDGER_TEMP_PREFIX, logger);
        Path packs = datapacks(root);
        if (Files.isDirectory(packs, NOFOLLOW_LINKS)) {
            deleteTemporaries(packs, PACK_TEMP_PREFIX, logger);
        }
    }

    private static void deleteTemporaries(Path directory, String prefix, Logger logger) {
        try (var files = Files.newDirectoryStream(directory, prefix + '*' + TEMP_SUFFIX)) {
            for (Path file : files) {
                deleteTemporaryFile(file, logger);
            }
        } catch (IOException | RuntimeException failure) {
            warnCleanupFailed(logger, directory, failure);
        }
    }

    private static void deleteTemporaryFile(Path file, Logger logger) {
        try {
            if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException failure) {
            warnCleanupFailed(logger, file, failure);
        }
    }

    private static void warnCleanupFailed(Logger logger, Path path, Exception failure) {
        ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Could not clean up stale bootstrap temporary files ("
                + path + "): " + ModuleBootstraps.describe(failure), failure);
    }
}
