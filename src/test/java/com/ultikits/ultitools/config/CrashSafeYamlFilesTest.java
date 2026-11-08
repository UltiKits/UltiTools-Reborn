package com.ultikits.ultitools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.ultikits.ultitools.config.document.ConfigDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Explicit operator edits own only their named keys; no automatic save or value repair is authorized. */
class CrashSafeYamlFilesTest {
    @TempDir
    Path directory;
    private Path file;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null,
                "This API supports the default local POSIX filesystem, not Windows");
        file = directory.resolve("kit.yml");
    }

    @Test
    void explicitEditPreservesEveryUnownedByteIncludingBomAndCrLf() throws Exception {
        String before = "﻿# handwritten\r\nunknown: NOT_A_MATERIAL\r\nitems: old\r\n";
        Files.write(file, before.getBytes(StandardCharsets.UTF_8));
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(Files.readAllBytes(file)).isEqualTo(before.replace("items: old", "items: new")
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void operatorSaveAfterSnapshotWins() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.write(file, "items: operator\n".getBytes(StandardCharsets.UTF_8));
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(text()).isEqualTo("items: operator\n");
    }

    @Test
    void missingSnapshotTargetIsNotCreated() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.delete(file);
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(file).doesNotExist();
    }

    @Test
    void emptyEditDoesNoIoEvenAfterParentIsRemoved() throws Exception {
        Path child = Files.createDirectory(directory.resolve("child"));
        file = child.resolve("kit.yml");
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.delete(file);
        Files.delete(child);
        assertThat(CrashSafeYamlFiles.write(snapshot, Collections.emptyMap()))
                .isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertThat(child).doesNotExist();
    }

    @Test
    void semanticNoOpDoesNotReplaceOrChmodReadOnlyFile() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: 1.50\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        Object key = Files.readAttributes(file, PosixFileAttributes.class).fileKey();
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", 1.5)))
                .isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertThat(text()).isEqualTo("items: 1.50\n");
        assertThat(Files.readAttributes(file, PosixFileAttributes.class).fileKey()).isEqualTo(key);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("r--------"));
    }

    @Test
    void writeValidatesAllArgumentsBeforeIo() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.delete(file);
        assertThatThrownBy(() -> CrashSafeYamlFiles.write(null, change("items", "new")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.write(snapshot, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.write(snapshot, Collections.singletonMap(null, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.write(snapshot, Collections.singletonMap(Collections.emptyList(), "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.write(snapshot, Collections.singletonMap(Arrays.asList("x", null), "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.write(snapshot, change("items", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    void createValidatesTheWholePlainMapBeforeIo() {
        File missingParent = directory.resolve("absent/kit.yml").toFile();
        Map<String, Object> badKey = new LinkedHashMap<>();
        badKey.put(null, "bad");
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("cycle", cycle);
        assertThatThrownBy(() -> CrashSafeYamlFiles.create(null, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.create(missingParent, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.create(missingParent, badKey))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.create(missingParent, cycle))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrashSafeYamlFiles.create(missingParent, Collections.singletonMap("x", new byte[1])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(missingParent.getParentFile()).doesNotExist();
    }

    @Test
    void createPublishesCompleteMapWithLiteralDottedKeysNullAndPrivateMode() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("o.O", Arrays.asList("one", "two"));
        values.put("nullable", null);
        assertThat(CrashSafeYamlFiles.create(file.toFile(), values)).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(ConfigDocument.parse(text()).toPlain()).isEqualTo(values);
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertNoStages();
    }

    @Test
    void emptyMapIsACompleteCreationNotAnUnchangedAbsentFile() throws Exception {
        assertThat(CrashSafeYamlFiles.create(file.toFile(), Collections.emptyMap()))
                .isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(file).exists();
        assertThat(ConfigDocument.parse(text()).toPlain()).isEmpty();
    }

    @Test
    void existingFileEvenWithIdenticalBytesIsACreationConflict() throws Exception {
        snapshot("items: old\n");
        assertThat(CrashSafeYamlFiles.create(file.toFile(), Collections.singletonMap("items", "old")))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(text()).isEqualTo("items: old\n");
        assertNoStages();
    }

    @Test
    void createDoesNotCreateItsParent() throws Exception {
        Path absent = directory.resolve("absent");
        assertThatThrownBy(() -> CrashSafeYamlFiles.create(absent.resolve("kit.yml").toFile(), Collections.emptyMap()))
                .isInstanceOf(java.io.IOException.class);
        assertThat(absent).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"items: [unterminated\n", "base: &b {x: 1}\ncopy: *b\nitems: old\n",
            "unknown:    aligned   # hand spacing\nitems: old\n"})
    void malformedAnchoredOrUnpreservableYamlIsNeverRepaired(String before) throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot(before);
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(text()).isEqualTo(before);
        assertNoStages();
    }

    @Test
    void existingModeOwnerAndGroupArePreserved() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        PosixFileAttributes before = Files.readAttributes(file, PosixFileAttributes.class);
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        PosixFileAttributes after = Files.readAttributes(file, PosixFileAttributes.class);
        assertThat(after.permissions()).isEqualTo(before.permissions());
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.group()).isEqualTo(before.group());
        assertNoStages();
    }

    @Test
    void readOnlyTargetIsRefusedEvenWhenDirectoryAllowsReplacement() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r-----"));
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(text()).isEqualTo("items: old\n");
        assertNoStages();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void finalSymlinkIsRefusedAndCreationConflictsWithoutFollowingIt(boolean dangling) throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Path referent = directory.resolve("referent.yml");
        Files.move(file, referent);
        Files.createSymbolicLink(file, referent.getFileName());
        if (dangling) {
            Files.delete(referent);
        }
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(CrashSafeYamlFiles.create(file.toFile(), Collections.emptyMap()))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(Files.isSymbolicLink(file)).isTrue();
        if (!dangling) {
            assertThat(new String(Files.readAllBytes(referent), StandardCharsets.UTF_8)).isEqualTo("items: old\n");
        }
    }

    @Test
    void directoryIsNotWrittenOrReplaced() throws Exception {
        OperatorFiles.Snapshot snapshot = snapshot("items: old\n");
        Files.delete(file);
        Files.createDirectory(file);
        assertThat(CrashSafeYamlFiles.write(snapshot, change("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertThat(CrashSafeYamlFiles.create(file.toFile(), Collections.emptyMap()))
                .isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void parentSymlinkDotDotResolutionIsNotLexicallyNormalized() throws Exception {
        Path real = Files.createDirectories(directory.resolve("real/nested"));
        Path alias = directory.resolve("alias");
        Files.createSymbolicLink(alias, real);
        Path throughLink = alias.resolve("../created.yml");
        assertThat(CrashSafeYamlFiles.create(throughLink.toFile(), Collections.singletonMap("items", "new")))
                .isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(real.getParent().resolve("created.yml")).exists();
        assertThat(directory.resolve("created.yml")).doesNotExist();
        assertThat(Files.isSymbolicLink(alias)).isTrue();
    }

    private OperatorFiles.Snapshot snapshot(String before) throws Exception {
        Files.write(file, before.getBytes(StandardCharsets.UTF_8));
        return OperatorFiles.read(file.toFile());
    }

    private String text() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void assertNoStages() throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            assertThat(entries.filter(path -> path.getFileName().toString().contains(".ultitools-stage-")).count()).isZero();
        }
    }

    private static Map<List<String>, Object> change(String key, Object value) {
        return Collections.singletonMap(Collections.singletonList(key), value);
    }
}
