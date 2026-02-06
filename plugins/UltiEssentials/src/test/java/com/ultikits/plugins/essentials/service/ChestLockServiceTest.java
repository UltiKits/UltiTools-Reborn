package com.ultikits.plugins.essentials.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.block.BlockMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.ChestLockData;
import com.ultikits.plugins.essentials.service.ChestLockService.LockResult;
import com.ultikits.plugins.essentials.service.ChestLockService.UnlockResult;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.plugins.essentials.utils.TestHelper;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChestLockService.
 * <p>
 * 测试箱子锁服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChestLockService Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class ChestLockServiceTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private ChestLockService lockService;

    @Mock
    private EssentialsConfig config;

    @Mock
    private DataOperator<ChestLockData> lockOperator;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        MockitoAnnotations.openMocks(this);

        world = server.addSimpleWorld("world");
        player = server.addPlayer("TestPlayer");

        when(config.isChestLockEnabled()).thenReturn(true);
        when(config.isChestLockAdminBypass()).thenReturn(true);

        lockService = new ChestLockService();
        try {
            java.lang.reflect.Field configField = ChestLockService.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(lockService, config);

            java.lang.reflect.Field operatorField = ChestLockService.class.getDeclaredField("lockOperator");
            operatorField.setAccessible(true);
            operatorField.set(lockService, lockOperator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(lockOperator.getAll()).thenReturn(new ArrayList<>());
        lockService.init();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Lockable Block Tests")
    class LockableBlockTests {

        @Test
        @DisplayName("Should identify lockable blocks")
        void shouldIdentifyLockableBlocks() {
            assertThat(lockService.isLockable(Material.CHEST)).isTrue();
            assertThat(lockService.isLockable(Material.TRAPPED_CHEST)).isTrue();
            assertThat(lockService.isLockable(Material.BARREL)).isTrue();
            assertThat(lockService.isLockable(Material.FURNACE)).isTrue();
        }

        @Test
        @DisplayName("Should identify non-lockable blocks")
        void shouldIdentifyNonLockableBlocks() {
            assertThat(lockService.isLockable(Material.STONE)).isFalse();
            assertThat(lockService.isLockable(Material.DIRT)).isFalse();
            assertThat(lockService.isLockable(Material.AIR)).isFalse();
        }
    }

    @Nested
    @DisplayName("Lock Block Tests")
    class LockBlockTests {

        @Test
        @DisplayName("Should lock chest successfully")
        void shouldLockChestSuccessfully() {
            BlockMock block = new BlockMock(Material.CHEST, new Location(world, 100, 64, 100));

            LockResult result = lockService.lockBlock(block, player);

            assertThat(result).isEqualTo(LockResult.SUCCESS);
            verify(lockOperator).insert(any(ChestLockData.class));
        }

        @Test
        @DisplayName("Should reject non-lockable block")
        void shouldRejectNonLockableBlock() {
            BlockMock block = new BlockMock(Material.STONE, new Location(world, 100, 64, 100));

            LockResult result = lockService.lockBlock(block, player);

            assertThat(result).isEqualTo(LockResult.NOT_LOCKABLE);
            verify(lockOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should return disabled when feature disabled")
        void shouldReturnDisabledWhenFeatureDisabled() {
            when(config.isChestLockEnabled()).thenReturn(false);
            BlockMock block = new BlockMock(Material.CHEST, new Location(world, 100, 64, 100));

            LockResult result = lockService.lockBlock(block, player);

            assertThat(result).isEqualTo(LockResult.DISABLED);
        }
    }

    @Nested
    @DisplayName("Unlock Block Tests")
    class UnlockBlockTests {

        @Test
        @DisplayName("Should unlock own chest successfully")
        void shouldUnlockOwnChestSuccessfully() {
            Location location = new Location(world, 100, 64, 100);
            BlockMock block = new BlockMock(Material.CHEST, location);

            ChestLockData lock = ChestLockData.builder()
                .uuid(UUID.randomUUID())
                .world("world")
                .x(100)
                .y(64)
                .z(100)
                .ownerUuid(player.getUniqueId().toString())
                .ownerName(player.getName())
                .createdAt(System.currentTimeMillis())
                .build();

            // Inject lock into cache
            try {
                java.lang.reflect.Field cacheField = ChestLockService.class.getDeclaredField("lockCache");
                cacheField.setAccessible(true);
                java.util.Map<String, ChestLockData> cache = (java.util.Map<String, ChestLockData>) cacheField.get(lockService);
                cache.put(lock.getLocationKey(), lock);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            UnlockResult result = lockService.unlockBlock(block, player);

            assertThat(result).isEqualTo(UnlockResult.SUCCESS);
            verify(lockOperator).delById(lock.getId());
        }

        @Test
        @DisplayName("Should return not locked for unlocked chest")
        void shouldReturnNotLockedForUnlockedChest() {
            BlockMock block = new BlockMock(Material.CHEST, new Location(world, 100, 64, 100));

            UnlockResult result = lockService.unlockBlock(block, player);

            assertThat(result).isEqualTo(UnlockResult.NOT_LOCKED);
        }
    }

    @Nested
    @DisplayName("Access Control Tests")
    class AccessControlTests {

        @Test
        @DisplayName("Should allow access to unlocked chest")
        void shouldAllowAccessToUnlockedChest() {
            Location location = new Location(world, 100, 64, 100);

            boolean canAccess = lockService.canAccess(location, player);

            assertThat(canAccess).isTrue();
        }

        @Test
        @DisplayName("Should allow owner access")
        void shouldAllowOwnerAccess() {
            Location location = new Location(world, 100, 64, 100);
            ChestLockData lock = ChestLockData.builder()
                .uuid(UUID.randomUUID())
                .world("world")
                .x(100)
                .y(64)
                .z(100)
                .ownerUuid(player.getUniqueId().toString())
                .ownerName(player.getName())
                .createdAt(System.currentTimeMillis())
                .build();

            // Inject lock into cache
            try {
                java.lang.reflect.Field cacheField = ChestLockService.class.getDeclaredField("lockCache");
                cacheField.setAccessible(true);
                java.util.Map<String, ChestLockData> cache = (java.util.Map<String, ChestLockData>) cacheField.get(lockService);
                cache.put(lock.getLocationKey(), lock);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            boolean canAccess = lockService.canAccess(location, player);

            assertThat(canAccess).isTrue();
        }
    }
}
