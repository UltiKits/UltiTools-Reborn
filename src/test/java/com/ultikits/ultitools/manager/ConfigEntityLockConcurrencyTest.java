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
 * A panel write runs on the WebSocket thread, and {@code UltiTools#onDisable()} closes the
 * WebSocket without waiting for it before calling {@link ConfigManager#saveAll()}, so a panel write
 * already in flight can overlap the shutdown save (#510, gate-1 WR-02). The entity monitor held by
 * both paths makes each see the other's whole effect or none of it.
 * <p>
 * The overlap is forced with latches, not sleeps: the panel write is parked inside its validation
 * step, while its proposed value is applied in memory, by a constructor gate (validation constructs
 * a throwaway instance of the config class). The shutdown save is started only then, and the panel
 * write is released only once the shutdown thread is either blocked on the entity or finished.
 */
@DisplayName("ConfigManager.saveAll and a concurrent panel write never lose a change (#510)")
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
class ConfigEntityLockConcurrencyTest {

    /** One-shot gate: the next constructor call parks until released. */
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    static final AtomicReference<Gate> NEXT_CONSTRUCTION = new AtomicReference<>();

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

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin plugin;

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

    @Test
    @DisplayName("A refused panel write in flight during the shutdown save: the code change is saved, the refused value never reaches disk")
    void saveAll_duringInFlightPanelWrite_savesWholeCodeChange() throws Exception {
        File limitFile = new File(tempDir, "config/limit.yml");
        Files.createDirectories(limitFile.getParentFile().toPath());
        Files.write(limitFile.toPath(), "limit: 1\n".getBytes(StandardCharsets.UTF_8));
        LimitConfig config = new LimitConfig("config/limit.yml");
        configManager.register(plugin, config);

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

        Thread shutdown = new Thread(configManager::saveAll, "ultitools-reborn-510-shutdown");
        shutdown.start();
        Thread.State exitState = shutdown.getState();
        while (exitState != Thread.State.BLOCKED && exitState != Thread.State.TERMINATED) {
            Thread.yield();
            exitState = shutdown.getState();
        }
        // The overlap must be real: the panel write is still parked inside the monitor, and the
        // shutdown thread is blocked on it. A TERMINATED shutdown thread here would mean the two
        // never overlapped, which would make the assertions below prove nothing.
        assertThat(exitState).isEqualTo(Thread.State.BLOCKED);
        assertThat(panel.isAlive()).isTrue();

        gate.release.countDown();
        panel.join();
        shutdown.join();

        assertThat(panelOutcome.get()).isInstanceOf(ConfigurationException.class);
        assertThat(new String(Files.readAllBytes(limitFile.toPath()), StandardCharsets.UTF_8))
                .contains("limit: 5")
                .doesNotContain("99");
    }
}
