package com.ultikits.plugins.essentials.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.HomeData;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.plugins.essentials.service.HomeService.SetHomeResult;
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
 * Unit tests for HomeService.
 * <p>
 * 测试家位置服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("HomeService Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class HomeServiceTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private HomeService homeService;

    @Mock
    private EssentialsConfig config;

    @Mock
    private TeleportService teleportService;

    @Mock
    private DataOperator<HomeData> homeOperator;

    @Mock
    private UltiEssentials plugin;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        MockitoAnnotations.openMocks(this);

        world = server.addSimpleWorld("world");
        player = server.addPlayer("TestPlayer");
        player.setLocation(new Location(world, 100, 64, 100));

        // Setup mocks
        when(config.isHomeEnabled()).thenReturn(true);
        when(config.getHomeDefaultMaxHomes()).thenReturn(3);
        when(config.getHomeTeleportWarmup()).thenReturn(3);
        when(config.isHomeCancelOnMove()).thenReturn(true);

        // Create service with mocked dependencies
        homeService = new HomeService();
        // Inject mocked dependencies via reflection or setters (simplified for test)
        try {
            java.lang.reflect.Field configField = HomeService.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(homeService, config);

            java.lang.reflect.Field teleportField = HomeService.class.getDeclaredField("teleportService");
            teleportField.setAccessible(true);
            teleportField.set(homeService, teleportService);

            java.lang.reflect.Field operatorField = HomeService.class.getDeclaredField("homeOperator");
            operatorField.setAccessible(true);
            operatorField.set(homeService, homeOperator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Set Home Tests")
    class SetHomeTests {

        @Test
        @DisplayName("Should create new home successfully")
        void shouldCreateNewHome() {
            when(homeOperator.getAll(any())).thenReturn(new ArrayList<>());

            SetHomeResult result = homeService.setHome(player, "home1");

            assertThat(result).isEqualTo(SetHomeResult.CREATED);
            verify(homeOperator).insert(any(HomeData.class));
        }

        @Test
        @DisplayName("Should update existing home")
        void shouldUpdateExistingHome() throws Exception {
            HomeData existingHome = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(player.getUniqueId().toString())
                .name("home1")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdAt(System.currentTimeMillis())
                .build();

            when(homeOperator.getAll(any())).thenReturn(List.of(existingHome));

            SetHomeResult result = homeService.setHome(player, "home1");

            assertThat(result).isEqualTo(SetHomeResult.UPDATED);
            verify(homeOperator).update(any(HomeData.class));
        }

        @Test
        @DisplayName("Should reject empty name")
        void shouldRejectEmptyName() {
            SetHomeResult result = homeService.setHome(player, "");

            assertThat(result).isEqualTo(SetHomeResult.INVALID_NAME);
            verify(homeOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should reject too long name")
        void shouldRejectTooLongName() {
            String longName = "a".repeat(33);

            SetHomeResult result = homeService.setHome(player, longName);

            assertThat(result).isEqualTo(SetHomeResult.INVALID_NAME);
        }

        @Test
        @DisplayName("Should enforce home limit")
        void shouldEnforceHomeLimit() {
            List<HomeData> existingHomes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                existingHomes.add(HomeData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .name("home" + i)
                    .world("world")
                    .x(0)
                    .y(64)
                    .z(0)
                    .createdAt(System.currentTimeMillis())
                    .build());
            }

            when(homeOperator.getAll(any())).thenReturn(existingHomes).thenReturn(new ArrayList<>());

            SetHomeResult result = homeService.setHome(player, "home4");

            assertThat(result).isEqualTo(SetHomeResult.LIMIT_REACHED);
            verify(homeOperator, never()).insert(any());
        }

        @Test
        @DisplayName("Should return disabled when feature disabled")
        void shouldReturnDisabledWhenFeatureDisabled() {
            when(config.isHomeEnabled()).thenReturn(false);

            SetHomeResult result = homeService.setHome(player, "home1");

            assertThat(result).isEqualTo(SetHomeResult.DISABLED);
        }
    }

    @Nested
    @DisplayName("Delete Home Tests")
    class DeleteHomeTests {

        @Test
        @DisplayName("Should delete existing home")
        void shouldDeleteExistingHome() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(player.getUniqueId().toString())
                .name("home1")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdAt(System.currentTimeMillis())
                .build();

            when(homeOperator.getAll(any())).thenReturn(List.of(home));

            boolean result = homeService.deleteHome(player.getUniqueId(), "home1");

            assertThat(result).isTrue();
            verify(homeOperator).delById(home.getId());
        }

        @Test
        @DisplayName("Should return false when home not found")
        void shouldReturnFalseWhenHomeNotFound() {
            when(homeOperator.getAll(any())).thenReturn(new ArrayList<>());

            boolean result = homeService.deleteHome(player.getUniqueId(), "nonexistent");

            assertThat(result).isFalse();
            verify(homeOperator, never()).delById(any());
        }
    }

    @Nested
    @DisplayName("Get Home Tests")
    class GetHomeTests {

        @Test
        @DisplayName("Should get all homes for player")
        void shouldGetAllHomesForPlayer() {
            List<HomeData> homes = List.of(
                HomeData.builder().uuid(UUID.randomUUID()).playerUuid(player.getUniqueId().toString())
                    .name("home1").world("world").x(0).y(64).z(0).createdAt(System.currentTimeMillis()).build(),
                HomeData.builder().uuid(UUID.randomUUID()).playerUuid(player.getUniqueId().toString())
                    .name("home2").world("world").x(100).y(64).z(100).createdAt(System.currentTimeMillis()).build()
            );

            when(homeOperator.getAll(any())).thenReturn(homes);

            List<HomeData> result = homeService.getHomes(player.getUniqueId());

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should get specific home by name")
        void shouldGetSpecificHomeByName() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(player.getUniqueId().toString())
                .name("home1")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .createdAt(System.currentTimeMillis())
                .build();

            when(homeOperator.getAll(any())).thenReturn(List.of(home));

            HomeData result = homeService.getHome(player.getUniqueId(), "home1");

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("home1");
        }

        @Test
        @DisplayName("Should return null when home not found")
        void shouldReturnNullWhenHomeNotFound() {
            when(homeOperator.getAll(any())).thenReturn(new ArrayList<>());

            HomeData result = homeService.getHome(player.getUniqueId(), "nonexistent");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Teleport Tests")
    class TeleportTests {

        @Test
        @DisplayName("Should teleport to home")
        void shouldTeleportToHome() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(player.getUniqueId().toString())
                .name("home1")
                .world("world")
                .x(100)
                .y(64)
                .z(100)
                .createdAt(System.currentTimeMillis())
                .build();

            when(homeOperator.getAll(any())).thenReturn(List.of(home));
            when(teleportService.isTeleporting(any())).thenReturn(false);
            when(teleportService.teleport(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(TeleportResult.SUCCESS);

            TeleportResult result = homeService.teleportToHome(player, "home1");

            assertThat(result).isEqualTo(TeleportResult.SUCCESS);
            verify(teleportService).teleport(eq(player), any(Location.class), eq(3), eq(true));
        }

        @Test
        @DisplayName("Should return not found when home not exists")
        void shouldReturnNotFoundWhenHomeNotExists() {
            when(homeOperator.getAll(any())).thenReturn(new ArrayList<>());

            TeleportResult result = homeService.teleportToHome(player, "nonexistent");

            assertThat(result).isEqualTo(TeleportResult.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return already teleporting when in progress")
        void shouldReturnAlreadyTeleportingWhenInProgress() {
            when(teleportService.isTeleporting(player.getUniqueId())).thenReturn(true);

            TeleportResult result = homeService.teleportToHome(player, "home1");

            assertThat(result).isEqualTo(TeleportResult.ALREADY_TELEPORTING);
        }
    }

    @Nested
    @DisplayName("Max Homes Tests")
    class MaxHomesTests {

        @Test
        @DisplayName("Should return default max homes")
        void shouldReturnDefaultMaxHomes() {
            int maxHomes = homeService.getMaxHomes(player);

            assertThat(maxHomes).isEqualTo(3);
        }

        @Test
        @DisplayName("Should return unlimited with permission")
        void shouldReturnUnlimitedWithPermission() {
            player.addAttachment(server.getPluginManager().getPlugin("MockPlugin"),
                "ultiessentials.home.unlimited", true);

            int maxHomes = homeService.getMaxHomes(player);

            assertThat(maxHomes).isEqualTo(Integer.MAX_VALUE);
        }
    }
}
