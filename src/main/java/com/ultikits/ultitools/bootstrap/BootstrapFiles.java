package com.ultikits.ultitools.bootstrap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates framework-owned derived files without following links within the owned subtree. */
final class BootstrapFiles {
    private BootstrapFiles() {
        // Static path checks only.
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
}
