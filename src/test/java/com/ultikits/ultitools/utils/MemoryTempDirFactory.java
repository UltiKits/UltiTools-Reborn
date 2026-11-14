package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Keeps fsync-heavy fixtures off shared disks when tmpfs is available; JUnit owns cleanup. */
public final class MemoryTempDirFactory implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
        return create(Paths.get("/dev/shm"));
    }

    static Path create(Path candidate) throws IOException {
        try {
            if (Files.isDirectory(candidate) && Files.isWritable(candidate)
                    && "tmpfs".equals(Files.getFileStore(candidate).type())) {
                return Files.createTempDirectory(candidate, "ultitools-test-");
            }
        } catch (IOException | SecurityException unavailable) {
            // Platforms without usable tmpfs retain JUnit's ordinary disk-backed semantics.
        }
        return Files.createTempDirectory("ultitools-test-");
    }
}
