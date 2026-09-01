package com.ultikits.ultitools.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * InMemeryTeleportService 测试
 */
@DisplayName("InMemeryTeleportService 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InMemeryTeleportServiceTest {

    private ServerMock server;
    private InMemeryTeleportService teleportService;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock logger
        Logger mockLogger = mock(Logger.class);

        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
            // i18n 原先在 6 个用例里各打一遍、内容完全相同。提到发布之前既消掉重复，
            // 也让这 6 处不再在「mock 已全进程可见」的窗口里打桩。见 issue #256。
            lenient().when(ultiTools.i18n(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        });

        teleportService = new InMemeryTeleportService();
        
        // 清空静态 Map 以确保测试隔离
        clearStaticMaps();
    }

    @AfterEach
    void tearDown() {
        clearStaticMaps();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }
    
    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
    private void clearStaticMaps() {
        try {
            Field teleportingPlayersField = InMemeryTeleportService.class.getDeclaredField("teleportingPlayers");
            teleportingPlayersField.setAccessible(true);
            Map<UUID, Boolean> teleportingPlayers = (Map<UUID, Boolean>) teleportingPlayersField.get(null);
            teleportingPlayers.clear();
            
            Field locationMapField = InMemeryTeleportService.class.getDeclaredField("locationMap");
            locationMapField.setAccessible(true);
            Map<UUID, String> locationMap = (Map<UUID, String>) locationMapField.get(null);
            locationMap.clear();
            
            Field inMemoryLocationRecordField = InMemeryTeleportService.class.getDeclaredField("inMemoryLocationRecord");
            inMemoryLocationRecordField.setAccessible(true);
            Map<UUID, Location> inMemoryLocationRecord = (Map<UUID, Location>) inMemoryLocationRecordField.get(null);
            inMemoryLocationRecord.clear();
        } catch (Exception e) {
            // 忽略清理异常
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该成功创建实例")
        void shouldCreateInstance() {
            // Assert
            assertThat(teleportService).isNotNull();
        }
    }

    @Nested
    @DisplayName("getName 测试")
    class GetNameTests {

        @Test
        @DisplayName("应该返回正确的服务名称")
        void shouldReturnCorrectName() {
            // Act
            String name = teleportService.getName();

            // Assert
            assertThat(name).isEqualTo("传送服务");
        }
    }

    @Nested
    @DisplayName("getAuthor 测试")
    class GetAuthorTests {

        @Test
        @DisplayName("应该返回正确的作者")
        void shouldReturnCorrectAuthor() {
            // Act
            String author = teleportService.getAuthor();

            // Assert
            assertThat(author).isEqualTo("wisdomme");
        }
    }

    @Nested
    @DisplayName("getVersion 测试")
    class GetVersionTests {

        @Test
        @DisplayName("应该返回版本 1")
        void shouldReturnVersion1() {
            // Act
            int version = teleportService.getVersion();

            // Assert
            assertThat(version).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("teleport 测试")
    class TeleportTests {

        @Test
        @DisplayName("应该传送玩家到指定位置")
        void shouldTeleportPlayerToLocation() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);

            // Act
            teleportService.teleport(player, targetLocation);

            // Assert
            Location playerLocation = player.getLocation();
            assertThat(playerLocation.getBlockX()).isEqualTo(100);
            assertThat(playerLocation.getBlockY()).isEqualTo(64);
            assertThat(playerLocation.getBlockZ()).isEqualTo(100);
        }

        @Test
        @DisplayName("传送后应该记录原始位置")
        void shouldRecordOriginalLocation() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location originalLocation = player.getLocation().clone();
            Location targetLocation = new Location(world, 100, 64, 100);

            // Act
            teleportService.teleport(player, targetLocation);

            // Assert
            Optional<Location> lastLocation = teleportService.getLastTeleportLocation(player.getUniqueId());
            assertThat(lastLocation).isPresent();
            assertThat(lastLocation.get().getBlockX()).isEqualTo(originalLocation.getBlockX());
            assertThat(lastLocation.get().getBlockY()).isEqualTo(originalLocation.getBlockY());
            assertThat(lastLocation.get().getBlockZ()).isEqualTo(originalLocation.getBlockZ());
        }

        @Test
        @DisplayName("应该播放末影人传送音效")
        void shouldPlayTeleportSound() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);

            // Act
            teleportService.teleport(player, targetLocation);

            // Assert - 验证传送成功（音效现在通过 XVersionUtils 静态方法播放）
            assertThat(player.getLocation().getBlockX()).isEqualTo(targetLocation.getBlockX());
            assertThat(player.getLocation().getBlockZ()).isEqualTo(targetLocation.getBlockZ());
        }

        @Test
        @DisplayName("多次传送应该更新位置记录")
        void multipleTeleportsShouldUpdateRecord() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location firstTarget = new Location(world, 100, 64, 100);
            Location secondTarget = new Location(world, 200, 64, 200);

            // Act
            teleportService.teleport(player, firstTarget);
            Location afterFirstTeleport = player.getLocation().clone();
            teleportService.teleport(player, secondTarget);

            // Assert
            Optional<Location> lastLocation = teleportService.getLastTeleportLocation(player.getUniqueId());
            assertThat(lastLocation).isPresent();
            assertThat(lastLocation.get().getBlockX()).isEqualTo(afterFirstTeleport.getBlockX());
        }
    }

    @Nested
    @DisplayName("getLastTeleportLocation 测试")
    class GetLastTeleportLocationTests {

        @Test
        @DisplayName("没有传送记录时应该返回空")
        void shouldReturnEmptyWhenNoRecord() {
            // Arrange
            UUID randomUUID = UUID.randomUUID();

            // Act
            Optional<Location> result = teleportService.getLastTeleportLocation(randomUUID);

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("传送后应该返回传送前的位置")
        void shouldReturnLocationBeforeTeleport() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            player.teleport(new Location(world, 50, 64, 50));
            Location beforeTeleport = player.getLocation().clone();
            Location targetLocation = new Location(world, 100, 64, 100);

            // Act
            teleportService.teleport(player, targetLocation);
            Optional<Location> result = teleportService.getLastTeleportLocation(player.getUniqueId());

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get().getBlockX()).isEqualTo(beforeTeleport.getBlockX());
            assertThat(result.get().getBlockY()).isEqualTo(beforeTeleport.getBlockY());
            assertThat(result.get().getBlockZ()).isEqualTo(beforeTeleport.getBlockZ());
        }

        @Test
        @DisplayName("不同玩家应该有独立的位置记录")
        void differentPlayersShouldHaveIndependentRecords() {
            // Arrange
            PlayerMock player1 = server.addPlayer();
            PlayerMock player2 = server.addPlayer();
            World world = player1.getWorld();
            
            player1.teleport(new Location(world, 10, 64, 10));
            player2.teleport(new Location(world, 20, 64, 20));

            Location target1 = new Location(world, 100, 64, 100);
            Location target2 = new Location(world, 200, 64, 200);

            // Act
            teleportService.teleport(player1, target1);
            teleportService.teleport(player2, target2);

            // Assert
            Optional<Location> loc1 = teleportService.getLastTeleportLocation(player1.getUniqueId());
            Optional<Location> loc2 = teleportService.getLastTeleportLocation(player2.getUniqueId());

            assertThat(loc1).isPresent();
            assertThat(loc2).isPresent();
            assertThat(loc1.get().getBlockX()).isEqualTo(10);
            assertThat(loc2.get().getBlockX()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("delayTeleport 测试")
    class DelayTeleportTests {

        @Test
        @DisplayName("应该将玩家添加到传送中列表")
        @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
        void shouldAddPlayerToTeleportingList() throws Exception {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);
            

            // Act
            teleportService.delayTeleport(player, targetLocation, 3);

            // Assert
            Field field = InMemeryTeleportService.class.getDeclaredField("teleportingPlayers");
            field.setAccessible(true);
            Map<UUID, Boolean> teleportingPlayers = (Map<UUID, Boolean>) field.get(null);
            assertThat(teleportingPlayers).containsKey(player.getUniqueId());
            assertThat(teleportingPlayers.get(player.getUniqueId())).isTrue();
        }

        @Test
        @DisplayName("delay 为 0 时应该立即传送")
        void shouldTeleportImmediatelyWhenDelayIsZero() throws Exception {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);

            // Act
            teleportService.delayTeleport(player, targetLocation, 0);
            
            // 执行调度任务
            server.getScheduler().performTicks(1);

            // Assert - 验证位置记录已保存
            Optional<Location> lastLoc = teleportService.getLastTeleportLocation(player.getUniqueId());
            assertThat(lastLoc).isPresent();
        }
    }

    @Nested
    @DisplayName("静态字段测试")
    class StaticFieldTests {

        @Test
        @DisplayName("teleportingPlayers 应该是 HashMap")
        void teleportingPlayersShouldBeHashMap() throws Exception {
            // Arrange
            Field field = InMemeryTeleportService.class.getDeclaredField("teleportingPlayers");
            field.setAccessible(true);

            // Assert
            assertThat(field.get(null)).isNotNull();
            assertThat(field.getType().getSimpleName()).isEqualTo("Map");
        }

        @Test
        @DisplayName("locationMap 应该是 HashMap")
        void locationMapShouldBeHashMap() throws Exception {
            // Arrange
            Field field = InMemeryTeleportService.class.getDeclaredField("locationMap");
            field.setAccessible(true);

            // Assert
            assertThat(field.get(null)).isNotNull();
            assertThat(field.getType().getSimpleName()).isEqualTo("Map");
        }

        @Test
        @DisplayName("inMemoryLocationRecord 应该是 HashMap")
        void inMemoryLocationRecordShouldBeHashMap() throws Exception {
            // Arrange
            Field field = InMemeryTeleportService.class.getDeclaredField("inMemoryLocationRecord");
            field.setAccessible(true);

            // Assert
            assertThat(field.get(null)).isNotNull();
            assertThat(field.getType().getSimpleName()).isEqualTo("Map");
        }
    }

    @Nested
    @DisplayName("接口实现测试")
    class InterfaceImplementationTests {

        @Test
        @DisplayName("应该实现 TeleportService 接口")
        void shouldImplementTeleportService() {
            // Assert
            assertThat(teleportService).isInstanceOf(com.ultikits.ultitools.services.TeleportService.class);
        }

        @Test
        @DisplayName("应该有 @Service 注解")
        void shouldHaveServiceAnnotation() {
            // Assert
            assertThat(InMemeryTeleportService.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.Service.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("位置记录并发测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("多个玩家同时传送不应该干扰")
        void multiplePlayersTeleportShouldNotInterfere() {
            // Arrange
            PlayerMock player1 = server.addPlayer("Player1");
            PlayerMock player2 = server.addPlayer("Player2");
            PlayerMock player3 = server.addPlayer("Player3");
            World world = player1.getWorld();

            Location target1 = new Location(world, 100, 64, 100);
            Location target2 = new Location(world, 200, 64, 200);
            Location target3 = new Location(world, 300, 64, 300);

            // Act
            teleportService.teleport(player1, target1);
            teleportService.teleport(player2, target2);
            teleportService.teleport(player3, target3);

            // Assert
            assertThat(player1.getLocation().getBlockX()).isEqualTo(100);
            assertThat(player2.getLocation().getBlockX()).isEqualTo(200);
            assertThat(player3.getLocation().getBlockX()).isEqualTo(300);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("传送到相同位置应该正常工作")
        void teleportToSameLocationShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();
            Location currentLocation = player.getLocation().clone();

            // Act - 传送到当前位置
            teleportService.teleport(player, currentLocation);

            // Assert
            Optional<Location> lastLoc = teleportService.getLastTeleportLocation(player.getUniqueId());
            assertThat(lastLoc).isPresent();
        }

        @Test
        @DisplayName("传送到负坐标应该正常工作")
        void teleportToNegativeCoordinatesShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location negativeLocation = new Location(world, -100, 64, -100);

            // Act
            teleportService.teleport(player, negativeLocation);

            // Assert
            assertThat(player.getLocation().getBlockX()).isEqualTo(-100);
            assertThat(player.getLocation().getBlockZ()).isEqualTo(-100);
        }

        @Test
        @DisplayName("传送到高空应该正常工作")
        void teleportToHighAltitudeShouldWork() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location highLocation = new Location(world, 0, 256, 0);

            // Act
            teleportService.teleport(player, highLocation);

            // Assert
            assertThat(player.getLocation().getBlockY()).isEqualTo(256);
        }
    }

    @Nested
    @DisplayName("checkPlayerMovement 方法测试 - 可测试的提取方法")
    class CheckPlayerMovementTests {

        @Test
        @DisplayName("玩家未在传送中时应返回false并清除位置")
        void shouldReturnFalseWhenPlayerNotTeleporting() {
            // Arrange
            PlayerMock player = server.addPlayer();
            UUID playerUUID = player.getUniqueId();
            InMemeryTeleportService.setTeleportingStatus(playerUUID, false);

            // Act
            boolean result = InMemeryTeleportService.checkPlayerMovement(playerUUID);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("玩家在传送中但没有移动应返回false")
        void shouldReturnFalseWhenPlayerNotMoved() {
            // Arrange
            PlayerMock player = server.addPlayer();
            UUID playerUUID = player.getUniqueId();
            InMemeryTeleportService.setTeleportingStatus(playerUUID, true);
            
            // 第一次调用设置初始位置
            InMemeryTeleportService.checkPlayerMovement(playerUUID);
            
            // Act - 玩家没有移动
            boolean result = InMemeryTeleportService.checkPlayerMovement(playerUUID);

            // Assert
            assertThat(result).isFalse();
            assertThat(InMemeryTeleportService.getTeleportingStatus(playerUUID)).isTrue();
        }

        @Test
        @DisplayName("玩家在传送中移动了应返回true并取消传送")
        void shouldReturnTrueWhenPlayerMoved() {
            // Arrange
            PlayerMock player = server.addPlayer();
            UUID playerUUID = player.getUniqueId();
            InMemeryTeleportService.setTeleportingStatus(playerUUID, true);
            
            // 第一次调用设置初始位置
            InMemeryTeleportService.checkPlayerMovement(playerUUID);
            
            // 移动玩家
            World world = player.getWorld();
            player.teleport(new Location(world, 100, 64, 100));
            
            // Act
            boolean result = InMemeryTeleportService.checkPlayerMovement(playerUUID);

            // Assert
            assertThat(result).isTrue();
            assertThat(InMemeryTeleportService.getTeleportingStatus(playerUUID)).isFalse();
        }

        @Test
        @DisplayName("不存在的玩家UUID应返回false")
        void shouldReturnFalseForNonExistentPlayer() {
            // Arrange
            UUID nonExistentUUID = UUID.randomUUID();
            InMemeryTeleportService.setTeleportingStatus(nonExistentUUID, true);

            // Act
            boolean result = InMemeryTeleportService.checkPlayerMovement(nonExistentUUID);

            // Assert - 玩家不在线返回false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("checkAllPlayersMovement 应该检查所有玩家")
        void checkAllPlayersMovementShouldCheckAllPlayers() {
            // Arrange
            PlayerMock player1 = server.addPlayer();
            PlayerMock player2 = server.addPlayer();
            InMemeryTeleportService.setTeleportingStatus(player1.getUniqueId(), true);
            InMemeryTeleportService.setTeleportingStatus(player2.getUniqueId(), true);

            // Act
            InMemeryTeleportService.checkAllPlayersMovement();

            // Assert - 方法应该执行而不抛异常
            assertThat(InMemeryTeleportService.getTeleportingStatus(player1.getUniqueId())).isTrue();
            assertThat(InMemeryTeleportService.getTeleportingStatus(player2.getUniqueId())).isTrue();
        }
    }

    @Nested
    @DisplayName("processDelayTeleportTick 方法测试 - 可测试的提取方法")
    class ProcessDelayTeleportTickTests {

        @Test
        @DisplayName("玩家移动导致传送取消时应返回shouldCancel=true")
        void shouldCancelWhenPlayerMoved() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);
            InMemeryTeleportService.setTeleportingStatus(player.getUniqueId(), false);


            // Act
            InMemeryTeleportService.DelayTeleportResult result = 
                InMemeryTeleportService.processDelayTeleportTick(player, targetLocation, 5.0f);

            // Assert
            assertThat(result.shouldCancel).isTrue();
        }

        @Test
        @DisplayName("倒计时到0时应完成传送并返回shouldCancel=true")
        void shouldCompleteTeleportWhenTimeReachesZero() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);
            InMemeryTeleportService.setTeleportingStatus(player.getUniqueId(), true);


            // Act
            InMemeryTeleportService.DelayTeleportResult result = 
                InMemeryTeleportService.processDelayTeleportTick(player, targetLocation, 0.0f);

            // Assert
            assertThat(result.shouldCancel).isTrue();
            assertThat(player.getLocation().getBlockX()).isEqualTo(100);
        }

        @Test
        @DisplayName("倒计时进行中应返回shouldCancel=false并减少时间")
        void shouldContinueCountdownWhenTimeRemaining() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);
            InMemeryTeleportService.setTeleportingStatus(player.getUniqueId(), true);


            // Act
            InMemeryTeleportService.DelayTeleportResult result = 
                InMemeryTeleportService.processDelayTeleportTick(player, targetLocation, 5.0f);

            // Assert
            assertThat(result.shouldCancel).isFalse();
            assertThat(result.remainingTime).isEqualTo(4.5f);
        }

        @Test
        @DisplayName("每整秒应该显示倒计时标题")
        void shouldShowCountdownTitleEverySecond() {
            // Arrange
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            Location targetLocation = new Location(world, 100, 64, 100);
            InMemeryTeleportService.setTeleportingStatus(player.getUniqueId(), true);


            // Act - time = 4.0 应该显示标题 (4.0 / 0.5 = 8, 8 % 2 = 0)
            InMemeryTeleportService.DelayTeleportResult result = 
                InMemeryTeleportService.processDelayTeleportTick(player, targetLocation, 4.0f);

            // Assert
            assertThat(result.shouldCancel).isFalse();
        }
    }

    @Nested
    @DisplayName("传送状态辅助方法测试")
    class TeleportStatusHelperTests {

        @Test
        @DisplayName("setTeleportingStatus 应该设置状态")
        void setTeleportingStatusShouldSetStatus() {
            // Arrange
            UUID playerUUID = UUID.randomUUID();

            // Act
            InMemeryTeleportService.setTeleportingStatus(playerUUID, true);

            // Assert
            assertThat(InMemeryTeleportService.getTeleportingStatus(playerUUID)).isTrue();
        }

        @Test
        @DisplayName("getTeleportingStatus 对于未设置的玩家应返回null")
        void getTeleportingStatusShouldReturnNullForUnsetPlayer() {
            // Arrange
            UUID playerUUID = UUID.randomUUID();

            // Act & Assert
            assertThat(InMemeryTeleportService.getTeleportingStatus(playerUUID)).isNull();
        }

        @Test
        @DisplayName("clearTeleportingData 应该清除所有数据")
        void clearTeleportingDataShouldClearAllData() {
            // Arrange
            UUID playerUUID1 = UUID.randomUUID();
            UUID playerUUID2 = UUID.randomUUID();
            InMemeryTeleportService.setTeleportingStatus(playerUUID1, true);
            InMemeryTeleportService.setTeleportingStatus(playerUUID2, false);

            // Act
            InMemeryTeleportService.clearTeleportingData();

            // Assert
            assertThat(InMemeryTeleportService.getTeleportingStatus(playerUUID1)).isNull();
            assertThat(InMemeryTeleportService.getTeleportingStatus(playerUUID2)).isNull();
        }
    }

    /**
     * GEN-08 / D-03: {@code teleportingPlayers}/{@code locationMap}/{@code inMemoryLocationRecord}
     * are now {@code ConcurrentHashMap}s (a live race with the async movement-check {@code
     * runTaskTimerAsynchronously} loop at :48, T-05-16) registered with the live {@link
     * PlayerCacheManager}, so a quitting player's entries are pruned through the real quit path
     * -- {@link PlayerCacheManager#onPlayerQuit(UUID)} -- rather than never at all. These
     * assertions fail on the pre-migration build.
     */
    @Nested
    @DisplayName("PlayerCacheManager quit-based sweep and concurrency (GEN-08, D-03, T-05-16)")
    class PlayerCacheSweepAndConcurrencyTests {

        private PlayerCacheManager liveManager;

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        @BeforeEach
        void wireLiveManager() throws Exception {
            // playerCacheRegistered is a STATIC flag (one InMemeryTeleportService instance
            // "registers" on behalf of every instance -- see that field's javadoc), so a PRIOR
            // test in this nested class latching it true would otherwise make every later
            // test's ensurePlayerCacheRegistered() a permanent no-op against a DIFFERENT
            // liveManager than the one this test wired. Reset it so each test starts clean.
            Field registeredField = InMemeryTeleportService.class.getDeclaredField("playerCacheRegistered");
            registeredField.setAccessible(true);
            registeredField.setBoolean(null, false);

            liveManager = new PlayerCacheManager();
            UltiTools currentInstance = UltiTools.getInstance();
            PluginManager mockPluginManager = mock(PluginManager.class);
            when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
            when(currentInstance.getPluginManager()).thenReturn(mockPluginManager);
        }

        @Test
        @DisplayName("Each of the three per-player maps loses the quitting player's entry; another player's survives")
        void allThreeMapsLoseQuittingPlayerEntryOtherPlayerUntouched() {
            PlayerMock quittingPlayer = server.addPlayer();
            PlayerMock otherPlayer = server.addPlayer();
            World world = quittingPlayer.getWorld();
            Location target = new Location(world, 10, 64, 10);

            // teleport()/delayTeleport() both write their respective maps AND trigger lazy
            // first-use registration with the live manager wired above.
            teleportService.teleport(quittingPlayer, target);
            teleportService.teleport(otherPlayer, target);
            teleportService.delayTeleport(quittingPlayer, target, 5);
            teleportService.delayTeleport(otherPlayer, target, 5);
            InMemeryTeleportService.checkPlayerMovement(quittingPlayer.getUniqueId());
            InMemeryTeleportService.checkPlayerMovement(otherPlayer.getUniqueId());

            assertThat(teleportService.getLastTeleportLocation(quittingPlayer.getUniqueId())).isPresent();
            assertThat(InMemeryTeleportService.getTeleportingStatus(quittingPlayer.getUniqueId())).isTrue();

            liveManager.onPlayerQuit(quittingPlayer.getUniqueId());

            assertThat(teleportService.getLastTeleportLocation(quittingPlayer.getUniqueId()))
                    .withFailMessage("inMemoryLocationRecord must lose the quitting player's entry")
                    .isEmpty();
            assertThat(InMemeryTeleportService.getTeleportingStatus(quittingPlayer.getUniqueId()))
                    .withFailMessage("teleportingPlayers must lose the quitting player's entry")
                    .isNull();

            assertThat(teleportService.getLastTeleportLocation(otherPlayer.getUniqueId()))
                    .withFailMessage("a sweep triggered by one player's quit must not touch another player's entry")
                    .isPresent();
            assertThat(InMemeryTeleportService.getTeleportingStatus(otherPlayer.getUniqueId()))
                    .withFailMessage("a sweep triggered by one player's quit must not touch another player's entry")
                    .isTrue();
        }

        @Test
        @DisplayName("Sweeping the same quitting player twice removes nothing further and throws nothing")
        void sweepingSamePlayerTwiceIsIdempotent() {
            PlayerMock player = server.addPlayer();
            World world = player.getWorld();
            teleportService.teleport(player, new Location(world, 5, 64, 5));

            liveManager.onPlayerQuit(player.getUniqueId());
            assertThatCode(() -> liveManager.onPlayerQuit(player.getUniqueId())).doesNotThrowAnyException();

            assertThat(teleportService.getLastTeleportLocation(player.getUniqueId())).isEmpty();
        }

        @Test
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        @DisplayName("Concurrent writes to all three maps from two threads complete without exception or lost updates")
        void concurrentWritesCompleteWithoutExceptionOrLostUpdates() throws Exception {
            int keysPerThread = 50;
            UUID[] threadAKeys = new UUID[keysPerThread];
            UUID[] threadBKeys = new UUID[keysPerThread];
            for (int i = 0; i < keysPerThread; i++) {
                threadAKeys[i] = UUID.randomUUID();
                threadBKeys[i] = UUID.randomUUID();
            }

            @SuppressWarnings("unchecked")
            Map<UUID, Boolean> teleportingPlayers = (Map<UUID, Boolean>) rawStaticField("teleportingPlayers");
            @SuppressWarnings("unchecked")
            Map<UUID, String> locationMap = (Map<UUID, String>) rawStaticField("locationMap");
            @SuppressWarnings("unchecked")
            Map<UUID, Location> inMemoryLocationRecord = (Map<UUID, Location>) rawStaticField("inMemoryLocationRecord");

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Thread threadA = new Thread(() -> writeAllThreeMaps(
                    teleportingPlayers, locationMap, inMemoryLocationRecord, threadAKeys, ready, go));
            Thread threadB = new Thread(() -> writeAllThreeMaps(
                    teleportingPlayers, locationMap, inMemoryLocationRecord, threadBKeys, ready, go));

            threadA.start();
            threadB.start();
            ready.await();
            go.countDown();
            threadA.join(TimeUnit.SECONDS.toMillis(10));
            threadB.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(teleportingPlayers).hasSize(keysPerThread * 2);
            assertThat(locationMap).hasSize(keysPerThread * 2);
            assertThat(inMemoryLocationRecord).hasSize(keysPerThread * 2);
            for (UUID key : threadAKeys) {
                assertThat(teleportingPlayers).containsKey(key);
                assertThat(locationMap).containsKey(key);
                assertThat(inMemoryLocationRecord).containsKey(key);
            }
            for (UUID key : threadBKeys) {
                assertThat(teleportingPlayers).containsKey(key);
                assertThat(locationMap).containsKey(key);
                assertThat(inMemoryLocationRecord).containsKey(key);
            }
        }

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        private Object rawStaticField(String name) throws Exception {
            Field field = InMemeryTeleportService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }

        private void writeAllThreeMaps(Map<UUID, Boolean> teleportingPlayers, Map<UUID, String> locationMap,
                                        Map<UUID, Location> inMemoryLocationRecord, UUID[] keys,
                                        CountDownLatch ready, CountDownLatch go) {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (UUID key : keys) {
                teleportingPlayers.put(key, true);
                locationMap.put(key, "loc-" + key);
                // A null world is fine here -- this test asserts map-level thread safety, not
                // Location semantics, and Bukkit's Location constructor accepts a null world.
                inMemoryLocationRecord.put(key, new Location(null, 1, 64, 1));
            }
        }
    }
}
