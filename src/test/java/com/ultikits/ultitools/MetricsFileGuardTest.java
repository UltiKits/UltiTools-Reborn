package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.utils.Metrics;

/**
 * UltiTools starts bStats only when {@code plugins/bStats/config.yml} is absent or already holds {@code serverUuid}
 * (inventory B3, UltiKits/UltiTools-Reborn#606; maintainer foundational rule of 2026-10-04: a file an operator may
 * edit is never overwritten automatically). The vendored {@code Metrics} saves defaults over the shared file when it
 * cannot read {@code serverUuid} - on a server an unparseable file loads as empty - so the guard decides before
 * {@code Metrics} is constructed, reads without writing, and on a refusal leaves the file byte-identical, logs one line
 * and keeps UltiTools' metrics off for that run.
 */
class MetricsFileGuardTest {

    @TempDir
    Path directory;

    private File bStatsFile;
    private JavaPlugin plugin;
    private Logger logger;
    private final List<LogRecord> records = new ArrayList<>();

    @BeforeEach
    void setUp() {
        File dataFolder = directory.resolve("UltiTools").toFile();
        bStatsFile = directory.resolve("bStats").resolve("config.yml").toFile();
        plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        when(plugin.getLogger()).thenReturn(logger);
    }

    @Test
    void anAbsentSharedFileStartsMetricsAndBStatsCreatesIt() throws Exception {
        Metrics metrics = UltiTools.startMetrics(plugin, 8652, logger);
        try {
            assertThat(metrics).isNotNull();
            assertThat(bStatsFile).exists();
            assertThat(YamlConfiguration.loadConfiguration(bStatsFile).isSet("serverUuid")).isTrue();
            assertThat(records).isEmpty();
        } finally {
            shutdown(metrics);
        }
    }

    @Test
    void aFileHoldingServerUuidStartsMetricsAndStaysByteIdentical() throws Exception {
        byte[] original = ("# operator note\nenabled: true\nserverUuid: 3f1c3b7e-0000-4000-8000-000000000001\n"
                + "logFailedRequests:    false   # aligned\n").getBytes(StandardCharsets.UTF_8);
        long modified = write(original);

        Metrics metrics = UltiTools.startMetrics(plugin, 8652, logger);
        try {
            assertThat(metrics).isNotNull();
            assertUntouched(original, modified);
            assertThat(records).isEmpty();
        } finally {
            shutdown(metrics);
        }
    }

    @Test
    void anUnparseableFileIsLeftAloneAndMetricsStayOff() throws Exception {
        byte[] original = "enabled: [true\nserverUuid: {broken\n".getBytes(StandardCharsets.UTF_8);
        long modified = write(original);

        Metrics metrics = UltiTools.startMetrics(plugin, 8652, logger);

        assertThat(metrics).isNull();
        assertUntouched(original, modified);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMessage()).contains("bStats").contains("config.yml");
    }

    @Test
    void aParseableFileWithoutServerUuidIsLeftAloneAndMetricsStayOff() throws Exception {
        byte[] original = "# my bStats settings\nenabled: false\n".getBytes(StandardCharsets.UTF_8);
        long modified = write(original);

        Metrics metrics = UltiTools.startMetrics(plugin, 8652, logger);

        assertThat(metrics).isNull();
        assertUntouched(original, modified);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMessage()).contains("serverUuid").doesNotContain("false");
    }

    @Test
    void anUnreadableFileIsLeftAloneAndMetricsStayOff() throws Exception {
        assertThat(bStatsFile.mkdirs()).isTrue(); // a directory where the file should be cannot be read as one

        Metrics metrics = UltiTools.startMetrics(plugin, 8652, logger);

        assertThat(metrics).isNull();
        assertThat(bStatsFile).isDirectory();
        assertThat(records).hasSize(1);
    }

    private long write(byte[] bytes) throws Exception {
        Files.createDirectories(bStatsFile.toPath().getParent());
        Files.write(bStatsFile.toPath(), bytes);
        long modified = bStatsFile.lastModified() - 10_000L;
        assertThat(bStatsFile.setLastModified(modified)).isTrue();
        return bStatsFile.lastModified();
    }

    private void assertUntouched(byte[] original, long modified) throws Exception {
        assertThat(Files.readAllBytes(bStatsFile.toPath())).isEqualTo(original);
        assertThat(bStatsFile.lastModified()).isEqualTo(modified);
    }

    private static void shutdown(Metrics metrics) {
        if (metrics != null) {
            metrics.shutdown();
        }
    }
}
