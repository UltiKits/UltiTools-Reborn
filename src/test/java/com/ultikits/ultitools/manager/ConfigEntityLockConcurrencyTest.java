package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/**
 * An off-thread registry save refuses before an entity monitor already held by a direct edit.
 * Latches force the edit to remain inside validation until the refused save returns. After the
 * edit refuses, a controlled server-thread save persists the original code change, never the
 * refused candidate. Actual panel callbacks marshal their entire operation to the server thread.
 */
@DisplayName("ConfigManager.saveAll and a concurrent panel write never lose a change (#510)")
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
class ConfigEntityLockConcurrencyTest {

    static final AtomicReference<Gate> NEXT_CONSTRUCTION = new AtomicReference<>();

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin plugin;

    /** One-shot gate: the next constructor call parks until released. */
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    @ConfigEntity("config/limit.yml")
    public static class LimitConfig extends AbstractConfigEntity {
        @Range(min = 0, max = 10)
        @ConfigEntry(path = "limit", comment = "A bounded value")
        private int limit = 1;

        public LimitConfig(String configFilePath) {
            super(configFilePath);
            Gate gate = NEXT_CONSTRUCTION.getAndSet(null);
            if (gate != null) {
                gate.entered.countDown();
                try {
                    gate.release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void setLimit(int limit) {
            this.limit = limit;
        }
    }

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger frameworkLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                ultiTools -> when(ultiTools.getLogger()).thenReturn(frameworkLogger));
        plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn("LockTestModule");
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ConfigFileStubs.stubConfigFolder(plugin, tempDir);
        configManager = new ConfigManager();
        NEXT_CONSTRUCTION.set(null);
    }

    @AfterEach
    void tearDown() {
        NEXT_CONSTRUCTION.set(null);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private void registerOnControlledServerThread(LimitConfig config) throws IOException {
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(
                org.bukkit.Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            when(org.bukkit.Bukkit.isPrimaryThread()).thenReturn(true);
            configManager.register(plugin, config);
            assertThat(configManager.getConfigEntity(plugin, LimitConfig.class)).isSameAs(config);
        }
    }

    private void saveOnControlledServerThread() {
        // The separate-thread timeout fixture is not MockBukkit's server thread; control only that predicate.
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(
                org.bukkit.Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            when(org.bukkit.Bukkit.isPrimaryThread()).thenReturn(true);
            configManager.saveAll();
        }
    }

    @Test
    @DisplayName("Off-thread stop report refuses while an entity is held; a later server-thread stop writes nothing (17-65)")
    void saveAll_duringInFlightPanelWrite_savesWholeCodeChange() throws Exception {
        File limitFile = new File(tempDir, "config/limit.yml");
        Files.createDirectories(limitFile.getParentFile().toPath());
        Files.write(limitFile.toPath(), "limit: 1\n".getBytes(StandardCharsets.UTF_8));
        LimitConfig config = new LimitConfig("config/limit.yml");
        registerOnControlledServerThread(config);

        // Module code changes the value in memory without saving.
        config.setLimit(5);

        Gate gate = new Gate();
        NEXT_CONSTRUCTION.set(gate);
        AtomicReference<Throwable> panelOutcome = new AtomicReference<>();
        Thread panel = new Thread(() -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("limit", 99);
            try {
                config.updateProperties(payload);
            } catch (ConfigurationException | IOException e) {
                panelOutcome.set(e);
            }
        }, "ultitools-reborn-510-panel");
        panel.start();
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).as("panel write reached validation").isTrue();

        Logger refusedLogger = mock(Logger.class);
        Thread shutdown = new Thread(() -> {
            try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                when(org.bukkit.Bukkit.getServer()).thenReturn(mock(org.bukkit.Server.class));
                when(org.bukkit.Bukkit.isPrimaryThread()).thenReturn(false);
                when(org.bukkit.Bukkit.getLogger()).thenReturn(refusedLogger);
                configManager.saveAll();
            }
        }, "ultitools-reborn-510-shutdown");
        try {
            shutdown.start(); shutdown.join(2000);
            assertThat(shutdown.isAlive()).as("guard cannot wait on the held entity monitor").isFalse();
            assertThat(panel.isAlive()).isTrue();
            org.mockito.Mockito.verify(refusedLogger).log(org.mockito.ArgumentMatchers.eq(java.util.logging.Level.WARNING),
                    org.mockito.ArgumentMatchers.contains("ultitools-reborn-510-shutdown"));
            assertThat(new String(Files.readAllBytes(limitFile.toPath()), StandardCharsets.UTF_8)).contains("limit: 1");
        } finally {
            gate.release.countDown(); panel.join(2000); shutdown.join(2000);
        }
        assertThat(panelOutcome.get()).isInstanceOf(ConfigurationException.class);
        saveOnControlledServerThread();
        // 17-65 (maintainer decision 2026-10-04): nothing is written at server stop; the code change is only named.
        assertThat(new String(Files.readAllBytes(limitFile.toPath()), StandardCharsets.UTF_8))
                .contains("limit: 1")
                .doesNotContain("99");
    }
}
