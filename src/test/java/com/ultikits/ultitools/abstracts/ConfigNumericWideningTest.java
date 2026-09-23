package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * #531 gate-1 CR-01: a boxed numeric {@code @ConfigEntry} field must load from real YAML.
 * <p>
 * SnakeYAML hands back an {@code Integer} for {@code 1800}. {@code Field.set} widens that into a
 * {@code long} or {@code double} primitive, but refuses it for a {@code Long}, {@code Double} or
 * {@code Float} field (measured: {@code IllegalArgumentException: Can not set java.lang.Long field
 * ... to java.lang.Integer}). So before this fix a {@code Long} field survived the first boot -- the
 * key was missing and the field default was written -- and threw on every later boot and on every
 * {@code reload()}. These tests go through the file, never through setters: write the default,
 * boot again from that file, edit the file, reload.
 * <p>
 * The fix is JLS widening only, the conversions the matching primitive field already accepts. A
 * narrowing value is still refused exactly as before. ({@code float}/{@code Float} fields are not
 * covered: YAML hands back a {@code Double} for a decimal, and {@code double} to {@code float} is
 * narrowing, so they cannot load a decimal before or after this change -- #534.)
 */
@DisplayName("AbstractConfigEntity numeric widening from YAML (#531 CR-01)")
class ConfigNumericWideningTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    private static final String PATH = "config/numbers.yml";

    @SuppressWarnings("unused") // read reflectively by the config binder and by the assertions below
    static class NumbersConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits.boxed-long")
        Long boxedLong = 1800L;

        @ConfigEntry(path = "limits.plain-long")
        long plainLong = 60L;

        @ConfigEntry(path = "limits.boxed-double")
        Double boxedDouble = 2.0D;

        @ConfigEntry(path = "limits.boxed-int")
        Integer boxedInt = 5;

        public NumbersConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class NarrowConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "small")
        Integer small = 1;

        public NarrowConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    /** Blocks inside validateFields() -- outside init()'s monitor -- when armed. */
    static class BlockingConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limits.first")
        Long first = 1L;

        @ConfigEntry(path = "limits.second")
        Long second = 2L;

        volatile java.util.concurrent.CountDownLatch entered;
        volatile java.util.concurrent.CountDownLatch release;

        public BlockingConfig(String configFilePath) {
            super(configFilePath);
        }

        @Override
        protected void validateFields() {
            java.util.concurrent.CountDownLatch armed = entered;
            if (armed != null) {
                armed.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.validateFields();
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("NumbersModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private void writeFile(String yaml) throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("first boot writes the defaults; a second boot from that file loads every boxed numeric field")
    void secondBootFromTheWrittenDefaultsLoads() throws IOException {
        new NumbersConfig(PATH).init(plugin);
        assertThat(file()).exists();

        NumbersConfig secondBoot = new NumbersConfig(PATH);
        assertThatCode(() -> secondBoot.init(plugin)).as("second boot from the written defaults").doesNotThrowAnyException();

        assertThat(secondBoot.boxedLong).isEqualTo(1800L);
        assertThat(secondBoot.plainLong).isEqualTo(60L);
        assertThat(secondBoot.boxedDouble).isEqualTo(2.0D);
        assertThat(secondBoot.boxedInt).isEqualTo(5);
    }

    @Test
    @DisplayName("an operator's whole numbers load into Long and Double fields")
    void wholeNumbersFromTheFileLoadIntoBoxedFields() throws IOException {
        writeFile("limits:\n  boxed-long: 30\n  plain-long: 45\n  boxed-double: 3\n  boxed-int: 6\n");

        NumbersConfig config = new NumbersConfig(PATH);
        assertThatCode(() -> config.init(plugin)).as("boot from operator-written whole numbers").doesNotThrowAnyException();

        assertThat(config.boxedLong).isEqualTo(30L);
        assertThat(config.plainLong).isEqualTo(45L);
        assertThat(config.boxedDouble).isEqualTo(3.0D);
        assertThat(config.boxedInt).isEqualTo(6);
    }

    @Test
    @DisplayName("reload() picks up an edited whole number in a Long field")
    void reloadPicksUpAnEditedLong() throws IOException {
        NumbersConfig config = new NumbersConfig(PATH);
        config.init(plugin);

        writeFile("limits:\n  boxed-long: 900\n  plain-long: 60\n  boxed-double: 2.0\n  boxed-int: 5\n");
        assertThatCode(config::reload).as("reload of an edited whole number").doesNotThrowAnyException();

        assertThat(config.boxedLong).isEqualTo(900L);
    }

    /**
     * Round 2 of gate-1 CR-01: the #510 snapshot re-reads the file into a probe instance through a
     * third read path. Unwidened, it threw for the {@code Long} field, the snapshot was dropped with a
     * "Cannot snapshot" WARNING, and {@code isModifiedSinceSnapshot()} stayed {@code true} -- so the
     * shutdown save would overwrite an operator's edit to the file.
     */
    @Test
    @DisplayName("the #510 snapshot holds for boxed Long and Double fields after first boot, second boot and reload")
    void snapshotHoldsForBoxedNumericFields() throws IOException {
        List<LogRecord> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
                // Records are appended straight to the in-memory list.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Logger logger = Logger.getLogger(AbstractConfigEntity.class.getName());
        logger.addHandler(capture);
        try {
            NumbersConfig firstBoot = new NumbersConfig(PATH);
            firstBoot.init(plugin);
            assertThat(firstBoot.isModifiedSinceSnapshot()).as("first boot").isFalse();

            NumbersConfig secondBoot = new NumbersConfig(PATH);
            assertThatCode(() -> secondBoot.init(plugin)).doesNotThrowAnyException();
            assertThat(secondBoot.isModifiedSinceSnapshot()).as("second boot").isFalse();

            writeFile("limits:\n  boxed-long: 900\n  plain-long: 60\n  boxed-double: 3\n  boxed-int: 5\n");
            assertThatCode(secondBoot::reload).doesNotThrowAnyException();
            assertThat(secondBoot.isModifiedSinceSnapshot()).as("after reload").isFalse();

            assertThat(warnings).as("no 'Cannot snapshot' or other WARNING from the config layer")
                    .extracting(LogRecord::getMessage).isEmpty();
        } finally {
            logger.removeHandler(capture);
        }
    }

    /**
     * Codex round 3 on #536: the "last init did not complete" marker the config binding reads is kept
     * on the entity (so nothing outside it can retain a discarded entity). It is set by an init whose
     * write-back fails and cleared by the next init that completes.
     */
    @Test
    @DisplayName("isLastInitIncomplete: set by an init whose write-back failed, cleared by the next complete init")
    void lastInitIncompleteTracksTheLastInit() throws IOException {
        NumbersConfig config = new NumbersConfig(PATH);
        config.init(plugin);
        assertThat(config.isLastInitIncomplete()).as("a completed first boot").isFalse();

        writeFile("limits:\n  boxed-long: 30\n");
        assertThat(file().toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(file()), "needs a non-root user so the write-back really fails");
            assertThatThrownBy(() -> config.init(plugin)).isInstanceOf(IOException.class);
            assertThat(config.isLastInitIncomplete()).as("after a failed write-back").isTrue();
        } finally {
            assertThat(file().toFile().setWritable(true)).isTrue();
        }

        config.init(plugin);
        assertThat(config.isLastInitIncomplete()).as("after the next complete init").isFalse();
    }

    /**
     * Codex round 12 on #536: two overlapping init() calls on one entity. The earlier one is still
     * in validateFields() -- outside init()'s monitor -- when a later one fails its write-back and
     * sets the marker. When the earlier one finishes it must not clear the later one's failure.
     */
    @Test
    @DisplayName("an earlier, overlapping init that completes does not clear a later init's failure marker")
    void anEarlierInitDoesNotClearALaterInitsFailure() throws Exception {
        BlockingConfig config = new BlockingConfig(PATH);
        config.init(plugin);
        config.entered = new java.util.concurrent.CountDownLatch(1);
        config.release = new java.util.concurrent.CountDownLatch(1);
        Thread earlier = new Thread(() -> {
            try {
                config.init(plugin);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, "fw531-earlier-init");
        earlier.start();
        assertThat(config.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("earlier init reached validation").isTrue();
        config.entered = null;

        writeFile("limits:\n  first: 30\n");
        assertThat(file().toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(file()), "needs a non-root user so the write-back really fails");
            assertThatThrownBy(() -> config.init(plugin)).isInstanceOf(IOException.class);
            assertThat(config.isLastInitIncomplete()).as("the later init failed").isTrue();
        } finally {
            config.release.countDown();
            earlier.join(10_000L);
            assertThat(file().toFile().setWritable(true)).isTrue();
        }

        assertThat(config.isLastInitIncomplete())
                .as("the earlier init finishing afterwards must not clear the later init's failure")
                .isTrue();
    }

    @Test
    @DisplayName("a value too large for an Integer field is still refused -- widening only, never narrowing")
    void narrowingIsStillRefused() throws IOException {
        writeFile("small: 3000000000\n");

        assertThatThrownBy(() -> new NarrowConfig(PATH).init(plugin))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
