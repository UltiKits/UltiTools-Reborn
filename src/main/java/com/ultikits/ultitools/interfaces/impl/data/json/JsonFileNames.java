package com.ultikits.ultitools.interfaces.impl.data.json;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The file an entity id names in a JSON table directory: {@code <table directory>/<id>.json}.
 * Every path the JSON backend builds from an id goes through {@link #entityFile(Path, String)}.
 *
 * @since 6.3.0
 */
final class JsonFileNames {

    /** The extension of every entity file. */
    static final String EXTENSION = ".json";

    /** The default resolution: the file system of the table directory decides. */
    private static final Resolver DEFAULT_RESOLVER = Path::resolve;

    /**
     * Test seam: how a file name is resolved against a table directory. The default is
     * {@link Path#resolve(String)}; a test installs one that rejects names the way another
     * operating system's file system would, and resets it afterwards.
     */
    private static volatile Resolver resolver = DEFAULT_RESOLVER;

    private JsonFileNames() {
    }

    /**
     * The entity file of {@code key} in {@code tableDirectory}.
     *
     * @param tableDirectory the table directory
     * @param key            the entity's key
     * @return {@code <tableDirectory>/<key>.json}
     * @throws InvalidPathException if the file system cannot name that file
     */
    static Path entityFile(Path tableDirectory, String key) {
        return resolver.resolve(tableDirectory, key + EXTENSION);
    }

    static void installResolver(Resolver replacement) {
        resolver = replacement == null ? DEFAULT_RESOLVER : replacement;
    }

    static void resetResolver() {
        resolver = DEFAULT_RESOLVER;
    }

    /** Resolves a file name against a directory; replaceable in tests. */
    interface Resolver {
        Path resolve(Path directory, String fileName);
    }
}
