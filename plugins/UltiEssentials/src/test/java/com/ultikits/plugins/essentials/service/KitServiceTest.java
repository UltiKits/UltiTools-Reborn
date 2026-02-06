package com.ultikits.plugins.essentials.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.KitClaimData;
import com.ultikits.plugins.essentials.entity.KitData;
import com.ultikits.plugins.essentials.service.KitService.KitResult;
import com.ultikits.plugins.essentials.service.KitService.ClaimResult;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import com.ultikits.plugins.essentials.utils.TestHelper;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
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
 * Unit tests for KitService.
 * <p>
 * 测试礼包服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("KitService Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class KitServiceTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;
    private KitService kitService;

    @Mock
    private EssentialsConfig config;

    @Mock
    private DataOperator<KitData> kitOperator;

    @Mock
    private DataOperator<KitClaimData> claimOperator;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        MockitoAnnotations.openMocks(this);

        world = server.addSimpleWorld("world");
        player = server.addPlayer("TestPlayer");

        when(config.isKitEnabled()).thenReturn(true);

        kitService = new KitService();
        try {
            java.lang.reflect.Field configField = KitService.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(kitService, config);

            java.lang.reflect.Field kitOpField = KitService.class.getDeclaredField("kitOperator");
            kitOpField.setAccessible(true);
            kitOpField.set(kitService, kitOperator);

            java.lang.reflect.Field claimOpField = KitService.class.getDeclaredField("claimOperator");
            claimOpField.setAccessible(true);
            claimOpField.set(kitService, claimOperator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Create Kit Tests")
    class CreateKitTests {

        @Test
        @DisplayName("Should create kit successfully")
        void shouldCreateKitSuccessfully() {
            ItemStack[] items = {new ItemStack(Material.DIAMOND_SWORD)};
            when(kitOperator.getAll(any())).thenReturn(new ArrayList<>());

            KitResult result = kitService.createKit("starter", items, 3600, null, player.getUniqueId());

            assertThat(result).isEqualTo(KitResult.SUCCESS);
            verify(kitOperator).insert(any(KitData.class));
        }

        @Test
        @DisplayName("Should reject duplicate kit name")
        void shouldRejectDuplicateKitName() {
            ItemStack[] items = {new ItemStack(Material.DIAMOND_SWORD)};
            KitData existing = KitData.builder()
                .uuid(UUID.randomUUID())
                .name("starter")
                .items("test")
                .cooldown(3600)
                .enabled(true)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(kitOperator.getAll(any())).thenReturn(List.of(existing));

            KitResult result = kitService.createKit("starter", items, 3600, null, player.getUniqueId());

            assertThat(result).isEqualTo(KitResult.ALREADY_EXISTS);
        }

        @Test
        @DisplayName("Should reject empty kit")
        void shouldRejectEmptyKit() {
            ItemStack[] items = {};
            when(kitOperator.getAll(any())).thenReturn(new ArrayList<>());

            KitResult result = kitService.createKit("empty", items, 3600, null, player.getUniqueId());

            assertThat(result).isEqualTo(KitResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("Should reject invalid name")
        void shouldRejectInvalidName() {
            ItemStack[] items = {new ItemStack(Material.DIAMOND_SWORD)};

            KitResult result = kitService.createKit("", items, 3600, null, player.getUniqueId());

            assertThat(result).isEqualTo(KitResult.INVALID_NAME);
        }
    }

    @Nested
    @DisplayName("Get Kit Tests")
    class GetKitTests {

        @Test
        @DisplayName("Should get all enabled kits")
        void shouldGetAllEnabledKits() {
            List<KitData> kits = List.of(
                KitData.builder().uuid(UUID.randomUUID()).name("starter").enabled(true)
                    .items("test").cooldown(3600).createdBy(UUID.randomUUID().toString())
                    .createdAt(System.currentTimeMillis()).build(),
                KitData.builder().uuid(UUID.randomUUID()).name("disabled").enabled(false)
                    .items("test").cooldown(3600).createdBy(UUID.randomUUID().toString())
                    .createdAt(System.currentTimeMillis()).build()
            );

            when(kitOperator.getAll()).thenReturn(kits);

            List<KitData> result = kitService.getAllKits();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("starter");
        }

        @Test
        @DisplayName("Should get kit by name")
        void shouldGetKitByName() {
            KitData kit = KitData.builder()
                .uuid(UUID.randomUUID())
                .name("starter")
                .enabled(true)
                .items("test")
                .cooldown(3600)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(kitOperator.getAll(any())).thenReturn(List.of(kit));

            KitData result = kitService.getKit("starter");

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("starter");
        }
    }

    @Nested
    @DisplayName("Permission Tests")
    class PermissionTests {

        @Test
        @DisplayName("Should allow access without permission requirement")
        void shouldAllowAccessWithoutPermissionRequirement() {
            KitData kit = KitData.builder()
                .permission(null)
                .build();

            boolean canUse = kitService.canUseKit(player, kit);

            assertThat(canUse).isTrue();
        }

        @Test
        @DisplayName("Should allow access with permission")
        void shouldAllowAccessWithPermission() {
            KitData kit = KitData.builder()
                .permission("ultiessentials.kit.vip")
                .build();

            player.addAttachment(server.getPluginManager().getPlugin("MockPlugin"),
                "ultiessentials.kit.vip", true);

            boolean canUse = kitService.canUseKit(player, kit);

            assertThat(canUse).isTrue();
        }

        @Test
        @DisplayName("Should deny access without permission")
        void shouldDenyAccessWithoutPermission() {
            KitData kit = KitData.builder()
                .permission("ultiessentials.kit.vip")
                .build();

            boolean canUse = kitService.canUseKit(player, kit);

            assertThat(canUse).isFalse();
        }
    }

    @Nested
    @DisplayName("Cooldown Tests")
    class CooldownTests {

        @Test
        @DisplayName("Should calculate remaining cooldown")
        void shouldCalculateRemainingCooldown() {
            KitData kit = KitData.builder()
                .name("starter")
                .cooldown(3600)
                .build();

            KitClaimData claim = KitClaimData.builder()
                .playerUuid(player.getUniqueId().toString())
                .kitName("starter")
                .lastClaim(System.currentTimeMillis() - 1000)
                .claimCount(1)
                .build();

            when(claimOperator.getAll(any())).thenReturn(List.of(claim));

            long remaining = kitService.getRemainingCooldown(player.getUniqueId(), kit);

            assertThat(remaining).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should return 0 for no claim")
        void shouldReturnZeroForNoClaim() {
            KitData kit = KitData.builder()
                .name("starter")
                .cooldown(3600)
                .build();

            when(claimOperator.getAll(any())).thenReturn(new ArrayList<>());

            long remaining = kitService.getRemainingCooldown(player.getUniqueId(), kit);

            assertThat(remaining).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Delete Kit Tests")
    class DeleteKitTests {

        @Test
        @DisplayName("Should delete existing kit")
        void shouldDeleteExistingKit() {
            KitData kit = KitData.builder()
                .uuid(UUID.randomUUID())
                .name("starter")
                .enabled(true)
                .items("test")
                .cooldown(3600)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            when(kitOperator.getAll(any())).thenReturn(List.of(kit));

            boolean result = kitService.deleteKit("starter");

            assertThat(result).isTrue();
            verify(kitOperator).delById(kit.getId());
        }

        @Test
        @DisplayName("Should return false when kit not found")
        void shouldReturnFalseWhenKitNotFound() {
            when(kitOperator.getAll(any())).thenReturn(new ArrayList<>());

            boolean result = kitService.deleteKit("nonexistent");

            assertThat(result).isFalse();
        }
    }
}
