package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.config.OperatorFiles;
import com.ultikits.ultitools.manager.ConfigManager;

/**
 * UltiKits/UltiTools-Reborn#622 and #582 comment 4 item 2: when the backed in-place fallback of a write fails after the
 * target was opened, the framework puts the file back from its framework backup before it reports the failure, so the
 * file holds what it held before the write and agrees with a caller that rolls back on the documented
 * {@code IOException}. If putting it back fails too, one SEVERE names the file and the backup (never a value), the backup
 * is kept, and the entity treats the file as changed since it was read, so no later save or operator write writes over
 * it until a reload reads it again.
 * <p>
 * Why it cannot overwrite operator content: the restore writes back exactly the bytes the target held immediately
 * before this write opened it, which are the bytes the framework backup holds.
 */
@DisplayName("A failed in-place fallback puts the file back from its backup (#622, #582)")
class InPlaceFallbackRestoreTest {

    private static final String ORIGINAL_VALUE = "original-motd-4b1e";
    private static final String NEW_VALUE = "new-motd-9d2a";
    private static final String TEXT = "# Gate settings\nmotd: " + ORIGINAL_VALUE + "\nlimit: 5\n";

    @TempDir
    Path tempDir;
    private UltiToolsPlugin plugin;
    private Faults faults;
    private MockedStatic<AtomicConfigWriter> writer;
    private final List<LogRecord> logged = Collections.synchronizedList(new ArrayList<LogRecord>());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                logged.add(record);
            }
        }

        @Override
        public void flush() {
            // In-memory capture.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    @ConfigEntity("gate.yml")
    public static class Gate extends AbstractConfigEntity {
        @ConfigEntry(path = "motd") String motd = "default-motd";
        @ConfigEntry(path = "limit") int limit = 5;

        public Gate(String path) {
            super(path);
        }
    }

    /**
     * Refuses the atomic move onto a configuration file (so the backed in-place fallback runs) and fails chosen steps of
     * the in-place writes to the target, counted by target open.
     */
    static final class Faults implements AtomicConfigWriter.FileOperations {
        private int targetOpens;
        private FileChannel target;
        int failForceAtOpen;
        int failOpenAt;
        int failWriteAtOpen;

        @Override
        public void move(Path source, Path destination, CopyOption... options) throws IOException {
            if (!destination.getFileName().toString().contains(".ultitools-backup-")) {
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
            }
            Files.move(source, destination, options);
        }

        @Override
        public FileChannel openTarget(Path file) throws IOException {
            targetOpens++;
            if (targetOpens == failOpenAt) {
                throw new IOException("injected open failure");
            }
            target = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        }

        @Override
        public void write(FileChannel channel, java.nio.ByteBuffer data) throws IOException {
            if (channel == target && targetOpens == failWriteAtOpen) {
                java.nio.ByteBuffer half = data.duplicate();
                half.limit(half.position() + Math.max(1, half.remaining() / 2));
                channel.write(half);
                data.position(half.position());
                throw new IOException("injected write failure after part of the bytes");
            }
            channel.write(data);
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            if (channel == target && targetOpens == failForceAtOpen) {
                throw new IOException("injected force failure");
            }
            channel.force(true);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("RestoreModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        ConfigFileStubs.stubConfigFolder(plugin, tempDir.toFile());
        Logger.getLogger("com.ultikits").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.close();
        }
        Logger.getLogger("com.ultikits").removeHandler(capture);
    }

    /** Routes every atomic write of the framework through {@link Faults}. */
    private void injectFaults() {
        faults = new Faults();
        writer = Mockito.mockStatic(AtomicConfigWriter.class, Mockito.CALLS_REAL_METHODS);
        writer.when(() -> AtomicConfigWriter.stage(any(Path.class), anyString()))
                .thenAnswer(call -> AtomicConfigWriter.stage(call.<Path>getArgument(0), call.<String>getArgument(1), faults));
        writer.when(() -> AtomicConfigWriter.write(any(Path.class), anyString())).thenAnswer(call -> {
            AtomicConfigWriter.write(call.<Path>getArgument(0), call.<String>getArgument(1), faults);
            return null;
        });
    }

    private void stopFaults() {
        writer.close();
        writer = null;
    }

    @Test
    @DisplayName("#622: a single operator write whose in-place force fails leaves the file as it was, and no backup")
    void singleWriteIsPutBack() throws Exception {
        Gate gate = loaded("gate.yml");
        injectFaults();
        faults.failForceAtOpen = 1;

        gate.motd = NEW_VALUE;
        Throwable thrown = catchThrowable(() -> gate.saveOperatorChange("motd"));
        // The module follows the documented contract: on the IOException it rolls its change back.
        gate.motd = ORIGINAL_VALUE;

        assertThat(thrown).isInstanceOf(IOException.class).isNotInstanceOf(ConfigWriteRefusedException.class);
        assertThat(read("gate.yml")).as("the file holds what it held before the write").isEqualTo(TEXT);
        assertThat(backups()).as("the backup is removed once the file is put back").isEmpty();
        assertThat(gate.isFileModifiedSinceSnapshot()).isFalse();
        assertThat(severe()).isEmpty();

        stopFaults();
        gate.motd = "later-value";
        gate.saveOperatorChange("motd");
        assertThat(read("gate.yml")).isEqualTo(TEXT.replace(ORIGINAL_VALUE, "later-value"));
    }

    @Test
    @DisplayName("#622: a torn in-place write (failing after half the bytes) is put back too")
    void tornWriteIsPutBack() throws Exception {
        Gate gate = loaded("gate.yml");
        injectFaults();
        faults.failWriteAtOpen = 1;

        gate.motd = NEW_VALUE;
        Throwable thrown = catchThrowable(() -> gate.saveOperatorChange("motd"));

        assertThat(thrown).isInstanceOf(IOException.class);
        assertThat(read("gate.yml")).isEqualTo(TEXT);
        assertThat(backups()).isEmpty();
    }

    @Test
    @DisplayName("#622: OperatorFiles.write under the same failure leaves the file as it was")
    void operatorFileWriteIsPutBack() throws Exception {
        Files.write(tempDir.resolve("kit.yml"), "# my kit\nitems: [a]\nnote: keep\n".getBytes(StandardCharsets.UTF_8));
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(tempDir.resolve("kit.yml").toFile());
        injectFaults();
        faults.failForceAtOpen = 1;

        Throwable thrown = catchThrowable(() -> OperatorFiles.write(snapshot,
                Collections.singletonMap(Collections.singletonList("items"), (Object) Collections.singletonList("b"))));

        assertThat(thrown).isInstanceOf(IOException.class);
        assertThat(read("kit.yml")).isEqualTo("# my kit\nitems: [a]\nnote: keep\n");
        assertThat(backups()).isEmpty();
    }

    @Test
    @DisplayName("#582 item 2: a panel batch whose second file fails in place puts every opened file back and fails")
    void panelBatchPutsEveryOpenedFileBack() throws Exception {
        write("a.yml", TEXT);
        write("b.yml", TEXT);
        ConfigManager manager = new ConfigManager();
        Gate first = new Gate("a.yml");
        Gate second = new Gate("b.yml");
        manager.register(plugin, first);
        manager.register(plugin, second);
        JsonObject edit = new JsonObject();
        edit.addProperty("motd", NEW_VALUE);
        JsonObject files = new JsonObject();
        files.add("a.yml", edit);
        files.add("b.yml", edit);
        JsonObject root = new JsonObject();
        root.add("RestoreModule", files);
        injectFaults();
        faults.failForceAtOpen = 2;

        Throwable thrown = catchThrowable(() -> manager.loadFromJson(root.toString()));

        assertThat(thrown).as("the batch reports failure").isInstanceOf(IOException.class);
        assertThat(read("a.yml")).isEqualTo(TEXT);
        assertThat(read("b.yml")).isEqualTo(TEXT);
        assertThat(first.motd).isEqualTo(ORIGINAL_VALUE);
        assertThat(second.motd).isEqualTo(ORIGINAL_VALUE);
        assertThat(backups()).as("the failed file's backup is removed once it is put back; the file the batch"
                + " replaced and then rolled back keeps its backup until the next load, as any in-place write does")
                .hasSize(1);
    }

    @Test
    @DisplayName("#622: when putting the file back fails too, one SEVERE names file and backup, and the file is protected")
    void failedRestoreIsReportedAndProtectsTheFile() throws Exception {
        Gate gate = loaded("gate.yml");
        injectFaults();
        faults.failForceAtOpen = 1;
        faults.failOpenAt = 2;

        gate.motd = NEW_VALUE;
        Throwable thrown = catchThrowable(() -> gate.saveOperatorChange("motd"));
        gate.motd = ORIGINAL_VALUE;
        stopFaults();

        assertThat(thrown).isInstanceOf(IOException.class);
        List<Path> backups = backups();
        assertThat(backups).as("the backup is kept for the operator").hasSize(1);
        assertThat(read(backups.get(0).getFileName().toString())).isEqualTo(TEXT);
        List<LogRecord> severe = severe();
        assertThat(severe).hasSize(1);
        assertThat(severe.get(0).getMessage()).contains(tempDir.resolve("gate.yml").toAbsolutePath().toString())
                .contains(backups.get(0).toString()).doesNotContain(ORIGINAL_VALUE).doesNotContain(NEW_VALUE);
        assertThat(messages()).noneMatch(message -> message.contains(NEW_VALUE) || message.contains(ORIGINAL_VALUE));
        assertThat(gate.isFileModifiedSinceSnapshot()).as("the entity treats the file as changed since read").isTrue();

        String onDisk = read("gate.yml");
        gate.limit = 9;
        Throwable later = catchThrowable(() -> gate.saveOperatorChange("limit"));
        assertThat(later).isInstanceOf(ConfigWriteRefusedException.class);
        assertThat(((ConfigWriteRefusedException) later).getReason()).contains("changed since it was read");
        gate.save();
        assertThat(read("gate.yml")).as("neither an operator write nor a save writes over it").isEqualTo(onDisk);

        gate.reload();
        assertThat(gate.isFileModifiedSinceSnapshot()).as("a reload reads the file again").isFalse();
        assertThat(backups).allMatch(Files::exists);
    }

    // -------------------------------------------------------------------------------------------------- helpers

    private Gate loaded(String name) throws IOException {
        write(name, TEXT);
        Gate gate = new Gate(name);
        gate.init(plugin);
        logged.clear();
        return gate;
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> paths = Files.list(tempDir)) {
            return paths.filter(path -> path.getFileName().toString().matches(".*\\.ultitools-backup-[0-9a-f]{16}"))
                    .collect(Collectors.toList());
        }
    }

    private List<LogRecord> severe() {
        synchronized (logged) {
            return logged.stream().filter(record -> record.getLevel() == Level.SEVERE).collect(Collectors.toList());
        }
    }

    private List<String> messages() {
        synchronized (logged) {
            return logged.stream().map(record -> String.valueOf(record.getMessage())).collect(Collectors.toList());
        }
    }

    private void write(String name, String text) throws IOException {
        Files.write(tempDir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(tempDir.resolve(name)), StandardCharsets.UTF_8);
    }
}
