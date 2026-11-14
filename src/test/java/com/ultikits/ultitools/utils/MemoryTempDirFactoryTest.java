package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryTempDirFactoryTest {
    @TempDir
    Path disk;

    @Test
    void unavailableTmpfsFallsBackToAnOwnedTemporaryDirectory() throws Exception {
        Path created = MemoryTempDirFactory.create(disk.resolve("absent"));
        try {
            assertThat(created).isDirectory();
            assertThat(created.getParent()).isNotEqualTo(disk.resolve("absent"));
        } finally {
            Files.delete(created);
        }
    }

    @Test
    void availableDirectoryUsesTmpfsOnlyOtherwiseFallsBack() throws Exception {
        Path created = MemoryTempDirFactory.create(disk);
        try {
            assertThat(created).isDirectory();
            assertThat(created.getParent().equals(disk)).isEqualTo("tmpfs".equals(Files.getFileStore(disk).type()));
        } finally {
            Files.delete(created);
        }
    }
}
