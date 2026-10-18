package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.config.OperatorFiles;

/**
 * A file that merely holds a retained YAML {@code !!binary} value must stay writable (issue #673, "Retained binary
 * values compare by content"). The document layer reads a binary as a {@code byte[]} and hands out defensive copies,
 * so the write gate's self-check compares two different arrays holding the same bytes; it must compare them by
 * content, or every gated write to such a file is refused. Every test leaves the binary line byte-identical.
 */
@DisplayName("A retained !!binary value does not block a write of another key")
class ConfigRetainedBinaryWriteTest {

    private static final String WRITER_LOGGER = "com.ultikits.ultitools.config.document.OperatorFileWriter";
    private static final String BLOB_LINE = "blob: !!binary YWI=\n";
    private static final String KIT = "# header\n" + BLOB_LINE + "motd: old\n";

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
        file = directory.resolve("kits").resolve("binary.yml");
        Logger.getLogger(WRITER_LOGGER).addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(WRITER_LOGGER).removeHandler(capture);
    }

    @Test
    @DisplayName("OperatorFiles.write of a neighbour key is written and the binary line stays byte-identical")
    void retainedBinaryDoesNotBlockANeighbourWrite() throws Exception {
        put(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "new"));

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(result).isEqualTo(OperatorFiles.WriteResult.WRITTEN);
        assertThat(text()).isEqualTo("# header\n" + BLOB_LINE + "motd: new\n");
    }

    @Test
    @DisplayName("writing the value a key already holds settles as unchanged and leaves the file untouched")
    void retainedBinaryUnchangedWriteSettlesUnchanged() throws Exception {
        long modified = put(KIT);
        OperatorFiles.Snapshot snapshot = OperatorFiles.read(file.toFile());

        OperatorFiles.WriteResult result = OperatorFiles.write(snapshot, values(Collections.singletonList("motd"), "old"));

        assertThat(warnings).as("refusal reasons").extracting(LogRecord::getMessage).isEmpty();
        assertThat(result).isEqualTo(OperatorFiles.WriteResult.UNCHANGED);
        assertThat(text()).isEqualTo(KIT);
        assertThat(file.toFile().lastModified()).isEqualTo(modified);
    }

    private static Map<List<String>, Object> values(List<String> path, Object value) {
        Map<List<String>, Object> values = new LinkedHashMap<>();
        values.put(path, value);
        return values;
    }

    private long put(String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        File target = file.toFile();
        assertThat(target.setLastModified(target.lastModified() - 10_000L)).isTrue();
        return target.lastModified();
    }

    private String text() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
