package com.ultikits.plugins.essentials.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.plugins.essentials.utils.TestHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TeleportService.
 * <p>
 * 传送服务单元测试。
 */
@DisplayName("TeleportService 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class TeleportServiceTest {

    private PlayerMock player;
    private TeleportService teleportService;
    private World world;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();

        player = server.addPlayer("testplayer");
        world = server.addSimpleWorld("world");
        player.setLocation(new Location(world, 0, 64, 0));

        teleportService = new TeleportService();
    }
    
    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }
    
    @Nested
    @DisplayName("即时传送测试")
    class InstantTeleportTests {
        
        @Test
        @DisplayName("应该成功即时传送玩家")
        void shouldTeleportPlayerInstantly() {
            Location target = new Location(world, 100, 64, 100);
            
            TeleportResult result = teleportService.teleport(player, target, 0, false);
            
            assertThat(result).isEqualTo(TeleportResult.SUCCESS);
            assertThat(player.getLocation().getX()).isEqualTo(100);
            assertThat(player.getLocation().getZ()).isEqualTo(100);
        }
        
        @Test
        @DisplayName("负数预热时间应该即时传送")
        void shouldTeleportInstantlyWithNegativeWarmup() {
            Location target = new Location(world, 50, 64, 50);
            
            TeleportResult result = teleportService.teleport(player, target, -1, false);
            
            assertThat(result).isEqualTo(TeleportResult.SUCCESS);
        }
    }
    
    @Nested
    @DisplayName("预热传送测试")
    class WarmupTeleportTests {
        
        @Test
        @DisplayName("应该开始预热传送")
        void shouldStartWarmupTeleport() {
            Location target = new Location(world, 100, 64, 100);
            
            TeleportResult result = teleportService.teleport(player, target, 3, false);
            
            assertThat(result).isEqualTo(TeleportResult.WARMUP_STARTED);
            assertThat(teleportService.isTeleporting(player.getUniqueId())).isTrue();
        }
        
        @Test
        @DisplayName("不应该允许同时进行多次传送")
        void shouldNotAllowMultipleTeleports() {
            Location target = new Location(world, 100, 64, 100);
            
            teleportService.teleport(player, target, 5, false);
            TeleportResult secondResult = teleportService.teleport(player, target, 5, false);
            
            assertThat(secondResult).isEqualTo(TeleportResult.ALREADY_TELEPORTING);
        }
    }
    
    @Nested
    @DisplayName("取消传送测试")
    class CancelTeleportTests {
        
        @Test
        @DisplayName("应该能取消进行中的传送")
        void shouldCancelPendingTeleport() {
            Location target = new Location(world, 100, 64, 100);
            teleportService.teleport(player, target, 5, false);
            
            assertThat(teleportService.isTeleporting(player.getUniqueId())).isTrue();
            
            teleportService.cancelTeleport(player.getUniqueId());
            
            assertThat(teleportService.isTeleporting(player.getUniqueId())).isFalse();
        }
        
        @Test
        @DisplayName("取消不存在的传送不应该报错")
        void shouldNotErrorWhenCancellingNonExistentTeleport() {
            Assertions.assertDoesNotThrow(() -> {
                teleportService.cancelTeleport(player.getUniqueId());
            });
        }
    }
    
    @Nested
    @DisplayName("传送状态检查测试")
    class TeleportStatusTests {
        
        @Test
        @DisplayName("应该正确检测玩家是否在传送中")
        void shouldDetectTeleportingStatus() {
            assertThat(teleportService.isTeleporting(player.getUniqueId())).isFalse();
            
            Location target = new Location(world, 100, 64, 100);
            teleportService.teleport(player, target, 5, false);
            
            assertThat(teleportService.isTeleporting(player.getUniqueId())).isTrue();
        }
    }
}
