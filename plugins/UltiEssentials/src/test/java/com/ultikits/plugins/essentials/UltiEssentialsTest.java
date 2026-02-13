package com.ultikits.plugins.essentials;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.plugins.essentials.utils.TestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for UltiEssentials main plugin class.
 * <p>
 * 测试 UltiEssentials 主插件类的生命周期。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("UltiEssentials Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class UltiEssentialsTest {

    private ServerMock server;
    private UltiEssentials plugin;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Plugin Lifecycle Tests")
    class PluginLifecycleTests {

        @Test
        @DisplayName("Should initialize plugin successfully")
        void shouldInitializePlugin() {
            plugin = new UltiEssentials();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should unregister plugin successfully")
        void shouldUnregisterPlugin() {
            plugin = new UltiEssentials();
            plugin.registerSelf();

            Assertions.assertDoesNotThrow(() -> plugin.unregisterSelf());
        }

        @Test
        @DisplayName("Should reload plugin successfully")
        void shouldReloadPlugin() {
            plugin = new UltiEssentials();
            plugin.registerSelf();

            Assertions.assertDoesNotThrow(() -> plugin.reloadSelf());
        }
    }
}
