package com.ultikits.ultitools.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OperatorFiles} lets a module write named keys of an operator-editable YAML file it manages - a kit file - and
 * nothing else (maintainer decision of 2026-10-04, "what code may write, by file type": an operator-created file gets
 * only the edited part on an explicit edit; comments and other keys are kept). It writes only the named whole keys,
 * through the config write gate, only while the file still holds the bytes the module read; it never creates, deletes
 * or renames a file, and it accepts only plain data.
 */
class OperatorFilesTest {

    private static final String KIT = "# Starter kit, edited by hand\n"
            + "displayName: '&aStarter'\n"
            + "description:\n"
            + "- '&7first line'\n"
            + "# keep this icon even though it is not a material yet\n"
            + "icon: NOT_A_MATERIAL\n"
            + "price: 0.0\n"
            + "custom-note: keep me\n"
            + "items: old-serialized\n";

    @TempDir
    Path directory;

    private Path file;
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // Records are kept in memory; there is nothing to flush.
        }

        @Override
        public void close() {
            // No resource is held; there is nothing to close.
        }
    };

    @BeforeEach
    void setUp() {
        file = directory.resolve("kits").resolve("starter.yml");
        Logger.getLogger("com.ultikits.ultitools.config.document.OperatorFileWriter").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("com.ultikits.ultitools.config.document.OperatorFileWriter").removeHandler(capture);
    }

    @Test
    void readReturnsTheTextAndItsFingerprint() throws Exception {
        long modified = write(KIT);

        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        assertThat(snapshot.getText()).isEqualTo(KIT);
        assertThat(snapshot.getFingerprint()).isEqualTo(sha256(KIT.getBytes(StandardCharsets.UTF_8)));
        assertThat(snapshot.getFile().getAbsoluteFile()).isEqualTo(file.toFile().getAbsoluteFile());
        assertThat(file.toFile().lastModified()).as("read writes nothing").isEqualTo(modified);
    }

    @Test
    void writeChangesOnlyTheNamedKeyAndKeepsCommentsUnknownKeysAndAnInvalidIcon() throws Exception {
        write(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                "new-serialized"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo(KIT.replace("items: old-serialized\n", "items: new-serialized\n"));
        assertThat(warnings).isEmpty();
    }

    @Test
    void aFileEditedAfterReadIsNotWritten() throws Exception {
        write(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        String edited = KIT.replace("price: 0.0", "price: 25.0");
        write(edited);

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                "new-serialized"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(text()).isEqualTo(edited);
    }

    @Test
    void anAnchoredFileIsRefusedWithOneWarning() throws Exception {
        String anchored = "base: &b {amount: 1}\ncopy: *b\nitems: old-serialized\n";
        long modified = write(anchored);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                "new-serialized"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertUnchanged(anchored, modified);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("anchors").doesNotContain("new-serialized");
    }

    @Test
    void aHandAlignedFileIsRefusedWithOneWarning() throws Exception {
        String aligned = "icon:    CHEST   # aligned by hand\nitems: old-serialized\n";
        long modified = write(aligned);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                "new-serialized"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.REFUSED);
        assertUnchanged(aligned, modified);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("items").contains("line 1").doesNotContain("new-serialized");
    }

    @Test
    void valuesTheFileAlreadyHoldsWriteNothing() throws Exception {
        long modified = write(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                "old-serialized"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertUnchanged(KIT, modified);
    }

    @Test
    void aKeyContainingADotIsOneKey() throws Exception {
        write("o.O: x\nitems: a\n");
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("o.O"), "y"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo("o.O: y\nitems: a\n");
    }

    @Test
    void aValueThatIsNotPlainDataIsRefusedBeforeAnythingIsWritten() throws Exception {
        long modified = write(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        assertThatThrownBy(() -> OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                mock(ItemStack.class)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperatorFiles.write(snapshot, values(Arrays.asList("owner", "id"), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        Map<List<String>, Object> nested = new LinkedHashMap<>();
        nested.put(Collections.singletonList("items"), Collections.singletonList(UUID.randomUUID()));
        assertThatThrownBy(() -> OperatorFiles.write(snapshot, nested)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperatorFiles.write(snapshot, values(Collections.<String>emptyList(), "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertUnchanged(KIT, modified);
        assertThat(warnings).isEmpty();
    }

    @Test
    void itNeverCreatesAFile() throws Exception {
        assertThatThrownBy(() -> OperatorFiles.read(file.toFile())).isInstanceOf(IOException.class);
        assertThat(file).doesNotExist();

        write(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());
        Files.delete(file);

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("items"),
                "new-serialized"));

        assertThat(result).isEqualTo(OperatorFiles.WriteResult.FILE_CHANGED);
        assertThat(file).doesNotExist();
    }

    @Test
    void aFileThatIsNotUtf8CannotBeRead() throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{'a', ':', ' ', (byte) 0xE9, '\n'});

        assertThatThrownBy(() -> OperatorFiles.read(file.toFile())).isInstanceOf(IOException.class);
    }

    @Test
    void aSnapshotCanOnlyComeFromRead() {
        assertThat(Modifier.isFinal(OperatorFiles.class.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(OperatorFiles.Snapshot.class.getModifiers())).isTrue();
        assertThat(OperatorFiles.Snapshot.class.getConstructors()).isEmpty();
        assertThat(OperatorFiles.class.getConstructors()).isEmpty();
    }

    private static Map<List<String>, Object> values(List<String> path, Object value) {
        Map<List<String>, Object> values = new LinkedHashMap<>();
        values.put(path, value);
        return values;
    }

    private long write(String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        File target = file.toFile();
        assertThat(target.setLastModified(target.lastModified() - 10_000L)).isTrue();
        return target.lastModified();
    }

    private String text() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private void assertUnchanged(String expected, long modified) throws Exception {
        assertThat(text()).isEqualTo(expected);
        assertThat(file.toFile().lastModified()).isEqualTo(modified);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
