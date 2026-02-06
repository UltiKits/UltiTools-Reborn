package com.ultikits.plugins.essentials.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.WarpData;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.plugins.essentials.service.WarpService.WarpResult;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.plugins.essentials.utils.TestHelper;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.Location;
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
 * Unit tests for WarpService.
 * <p>
 * 测试地标点服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("WarpService Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class WarpServiceTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private WarpService warpService;

    @Mock
    private EssentialsConfig config;

    @Mock
    private TeleportService teleportService;

    @Mock
    private DataOperator<WarpData> warpOperator;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        MockitoAnnotations.openMocks(this);

        world = server.addSimpleWorld("world");
        player = server.addPlayer("TestPlayer");
        player.setLocation(new Location(world, 0, 64, 0));

        // Setup mocks
        when(config.isWarpEnabled()).thenReturn(true);
        when(config.getWarpTeleportWarmup()).thenReturn(3);
        when(config.isHomeCancelOnMove()).thenReturn(true);

        // Create service with mocked dependencies
        warpService = new WarpService();
        try {
            java.lang.reflect.Field configField = WarpService.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(warpService, config);

            java.lang.reflect.Field teleportField = WarpService.class.getDeclaredField("teleportService");
            teleportField.setAccessible(true);
            teleportField.set(warpService, teleportService);

            java.lang.reflect.Field operatorField = WarpService.class.getDeclaredField("warpOperator");
            operatorField.setAccessible(true);
            operatorField.set(warpService, warpOperator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Create Warp Tests")
    class CreateWarpTests {

        @Test
        @DisplayName("Should create warp successfully")
        void shouldCreateWarpSuccessfully() {
            Location location = new Location(world, 0, 64, 0);
            when(warpOperator.getAll(any())).thenReturn(new ArrayList<>());

            WarpResult result = warpService.createWarp("spawn", location, player.getUniqueId(), null);

            assertThat(result).isEqualTo(WarpResult.CREATED);
            verify(warpOperator).insert(any(WarpData.class));
        }

        @Test
        @DisplayName("Should reject duplicate warp name")
        void shouldRejectDuplicateWarpName() {
            Location location = new Location(world, 0, 64, 0);
            WarpData existing = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(warpOperator.getAll(any())).thenReturn(List.of(existing));

            WarpResult result = warpService.createWarp("spawn", location, player.getUniqueId(), null);

            assertThat(result).isEqualTo(WarpResult.ALREADY_EXISTS);
            verify(warpOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should reject invalid warp name")
        void shouldRejectInvalidWarpName() {
            Location location = new Location(world, 0, 64, 0);

            WarpResult result = warpService.createWarp("", location, player.getUniqueId(), null);

            assertThat(result).isEqualTo(WarpResult.INVALID_NAME);
        }

        @Test
        @DisplayName("Should create warp with permission")
        void shouldCreateWarpWithPermission() {
            Location location = new Location(world, 0, 64, 0);
            when(warpOperator.getAll(any())).thenReturn(new ArrayList<>());

            WarpResult result = warpService.createWarp("vip", location, player.getUniqueId(), "ultiessentials.warp.vip");

            assertThat(result).isEqualTo(WarpResult.CREATED);
        }

        @Test
        @DisplayName("Should return disabled when feature disabled")
        void shouldReturnDisabledWhenFeatureDisabled() {
            when(config.isWarpEnabled()).thenReturn(false);
            Location location = new Location(world, 0, 64, 0);

            WarpResult result = warpService.createWarp("spawn", location, player.getUniqueId(), null);

            assertThat(result).isEqualTo(WarpResult.DISABLED);
        }
    }

    @Nested
    @DisplayName("Delete Warp Tests")
    class DeleteWarpTests {

        @Test
        @DisplayName("Should delete existing warp")
        void shouldDeleteExistingWarp() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(warpOperator.getAll(any())).thenReturn(List.of(warp));

            boolean result = warpService.deleteWarp("spawn");

            assertThat(result).isTrue();
            verify(warpOperator).delById(warp.getId());
        }

        @Test
        @DisplayName("Should return false when warp not found")
        void shouldReturnFalseWhenWarpNotFound() {
            when(warpOperator.getAll(any())).thenReturn(new ArrayList<>());

            boolean result = warpService.deleteWarp("nonexistent");

            assertThat(result).isFalse();
            verify(warpOperator, never()).delById(any());
        }
    }

    @Nested
    @DisplayName("Get Warp Tests")
    class GetWarpTests {

        @Test
        @DisplayName("Should get all warps")
        void shouldGetAllWarps() {
            List<WarpData> warps = List.of(
                WarpData.builder().uuid(UUID.randomUUID()).name("spawn").world("world")
                    .x(0).y(64).z(0).createdBy(UUID.randomUUID().toString())
                    .createdAt(System.currentTimeMillis()).build(),
                WarpData.builder().uuid(UUID.randomUUID()).name("arena").world("world")
                    .x(100).y(64).z(100).createdBy(UUID.randomUUID().toString())
                    .createdAt(System.currentTimeMillis()).build()
            );

            when(warpOperator.getAll()).thenReturn(warps);

            List<WarpData> result = warpService.getAllWarps();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should get warp by name")
        void shouldGetWarpByName() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(warpOperator.getAll(any())).thenReturn(List.of(warp));

            WarpData result = warpService.getWarp("spawn");

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("spawn");
        }
    }

    @Nested
    @DisplayName("Permission Tests")
    class PermissionTests {

        @Test
        @DisplayName("Should allow access to public warp")
        void shouldAllowAccessToPublicWarp() {
            WarpData warp = WarpData.builder()
                .permission(null)
                .build();

            boolean canAccess = warpService.canAccess(player, warp);

            assertThat(canAccess).isTrue();
        }

        @Test
        @DisplayName("Should allow access with permission")
        void shouldAllowAccessWithPermission() {
            WarpData warp = WarpData.builder()
                .permission("ultiessentials.warp.vip")
                .build();

            player.addAttachment(server.getPluginManager().getPlugin("MockPlugin"),
                "ultiessentials.warp.vip", true);

            boolean canAccess = warpService.canAccess(player, warp);

            assertThat(canAccess).isTrue();
        }

        @Test
        @DisplayName("Should deny access without permission")
        void shouldDenyAccessWithoutPermission() {
            WarpData warp = WarpData.builder()
                .permission("ultiessentials.warp.vip")
                .build();

            boolean canAccess = warpService.canAccess(player, warp);

            assertThat(canAccess).isFalse();
        }
    }

    @Nested
    @DisplayName("Teleport Tests")
    class TeleportTests {

        @Test
        @DisplayName("Should teleport to warp")
        void shouldTeleportToWarp() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(warpOperator.getAll(any())).thenReturn(List.of(warp));
            when(teleportService.isTeleporting(any())).thenReturn(false);
            when(teleportService.teleport(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(TeleportResult.SUCCESS);

            TeleportResult result = warpService.teleportToWarp(player, "spawn");

            assertThat(result).isEqualTo(TeleportResult.SUCCESS);
        }

        @Test
        @DisplayName("Should return no permission when player lacks permission")
        void shouldReturnNoPermissionWhenPlayerLacksPermission() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("vip")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .permission("ultiessentials.warp.vip")
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(warpOperator.getAll(any())).thenReturn(List.of(warp));
            when(teleportService.isTeleporting(any())).thenReturn(false);

            TeleportResult result = warpService.teleportToWarp(player, "vip");

            assertThat(result).isEqualTo(TeleportResult.NO_PERMISSION);
        }
    }
}
