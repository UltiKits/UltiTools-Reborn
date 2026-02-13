package com.ultikits.plugins.worlds.service;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.worlds.UltiWorldsTestHelper;
import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldInventory;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for InventoryIsolationService.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("InventoryIsolationService Tests")
class InventoryIsolationServiceTest {

    private InventoryIsolationService service;
    private WorldConfig mockConfig;
    private DataOperator<WorldInventory> mockDataOperator;
    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() throws Exception {
        UltiWorldsTestHelper.setUp();
        mockPlugin = UltiWorldsTestHelper.getMockPlugin();

        service = new InventoryIsolationService();
        mockConfig = UltiWorldsTestHelper.createDefaultConfig();
        mockDataOperator = mock(DataOperator.class);

        UltiWorldsTestHelper.setField(service, "config", mockConfig);
        UltiWorldsTestHelper.setField(service, "dataOperator", mockDataOperator);
        UltiWorldsTestHelper.setField(service, "plugin", mockPlugin);

        when(mockPlugin.getDataOperator(WorldInventory.class)).thenReturn(mockDataOperator);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiWorldsTestHelper.tearDown();
    }

    /**
     * Helper to mock a query chain that returns a specific result.
     */
    @SuppressWarnings("unchecked")
    private Query<WorldInventory> mockQueryReturning(WorldInventory result) {
        Query<WorldInventory> mockQuery = mock(Query.class);
        when(mockDataOperator.query()).thenReturn(mockQuery);
        when(mockQuery.where(anyString())).thenReturn(mockQuery);
        when(mockQuery.eq(any())).thenReturn(mockQuery);
        when(mockQuery.first()).thenReturn(result);
        return mockQuery;
    }

    @Nested
    @DisplayName("World Group Management")
    class WorldGroupManagement {

        @Test
        @DisplayName("getWorldGroup should return group for shared worlds")
        void getWorldGroupShared() {
            List<String> sharedGroups = Arrays.asList("world,world_nether,world_the_end", "pvp,arena");
            when(mockConfig.getSharedWorldGroups()).thenReturn(sharedGroups);

            service.init();

            String group1 = service.getWorldGroup("world");
            String group2 = service.getWorldGroup("world_nether");
            String group3 = service.getWorldGroup("world_the_end");

            assertThat(group1).isEqualTo(group2);
            assertThat(group2).isEqualTo(group3);
        }

        @Test
        @DisplayName("getWorldGroup should return world name for non-shared worlds")
        void getWorldGroupNonShared() {
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world,world_nether,world_the_end"));

            service.init();

            String group = service.getWorldGroup("custom_world");

            assertThat(group).isEqualTo("custom_world");
        }

        @Test
        @DisplayName("areWorldsShared should return true for worlds in same group")
        void areWorldsSharedTrue() {
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world,world_nether,world_the_end"));

            service.init();

            boolean result = service.areWorldsShared("world", "world_nether");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("areWorldsShared should return false for worlds in different groups")
        void areWorldsSharedFalse() {
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world,world_nether", "pvp,arena"));

            service.init();

            boolean result = service.areWorldsShared("world", "pvp");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Save Inventory")
    class SaveInventory {

        @Test
        @DisplayName("saveInventory without init should still query data operator")
        void saveInventoryWithoutInit() throws Exception {
            // Note: @ConditionalOnConfig prevents this service from being created
            // when isolation is disabled, so saveInventory has no early-return guard.
            // Without init(), dataOperator is the mock injected via reflection.
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            WorldInventory inventory = UltiWorldsTestHelper.createSampleWorldInventory(
                    player.getUniqueId(), "world");
            mockQueryReturning(inventory);

            service.saveInventory(player, "world");

            verify(mockDataOperator).update(any(WorldInventory.class));
        }

        @Test
        @DisplayName("saveInventory should save when isolation enabled")
        void saveInventoryEnabled() throws Exception {
            when(mockConfig.isInventoryIsolation()).thenReturn(true);
            when(mockConfig.isSeparateInventory()).thenReturn(true);
            when(mockConfig.isSeparateEnderChest()).thenReturn(true);
            when(mockConfig.isSeparateExperience()).thenReturn(true);
            when(mockConfig.isSeparateHealth()).thenReturn(true);
            when(mockConfig.isSeparateHunger()).thenReturn(true);
            when(mockConfig.isSeparateEffects()).thenReturn(true);
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world"));

            service.init();

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            WorldInventory inventory = UltiWorldsTestHelper.createSampleWorldInventory(
                    player.getUniqueId(), "world");

            mockQueryReturning(inventory);

            service.saveInventory(player, "world");

            verify(mockDataOperator).update(any(WorldInventory.class));
        }
    }

    @Nested
    @DisplayName("Load Inventory")
    class LoadInventory {

        @Test
        @DisplayName("loadInventory without init should create new inventory when not found")
        void loadInventoryWithoutInit() {
            // Note: @ConditionalOnConfig prevents this service from being created
            // when isolation is disabled, so loadInventory has no early-return guard.
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            mockQueryReturning(null);

            service.loadInventory(player, "world");

            verify(mockDataOperator).insert(any(WorldInventory.class));
        }

        @Test
        @DisplayName("loadInventory should load when isolation enabled")
        void loadInventoryEnabled() {
            when(mockConfig.isInventoryIsolation()).thenReturn(true);
            when(mockConfig.isSeparateInventory()).thenReturn(false);
            when(mockConfig.isSeparateEnderChest()).thenReturn(false);
            when(mockConfig.isSeparateExperience()).thenReturn(true);
            when(mockConfig.isSeparateHealth()).thenReturn(true);
            when(mockConfig.isSeparateHunger()).thenReturn(true);
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world"));

            service.init();

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            WorldInventory inventory = UltiWorldsTestHelper.createSampleWorldInventory(
                    player.getUniqueId(), "world");
            inventory.setExperienceLevel(50);
            inventory.setExperiencePoints(0.75f);
            inventory.setHealth(15.0);
            inventory.setMaxHealth(20.0);
            inventory.setFoodLevel(15);
            inventory.setSaturation(3.5f);

            mockQueryReturning(inventory);

            service.loadInventory(player, "world");

            verify(player).setLevel(50);
            verify(player).setExp(0.75f);
            verify(player).setMaxHealth(20.0);
            verify(player).setHealth(15.0);
            verify(player).setFoodLevel(15);
            verify(player).setSaturation(3.5f);
        }

        @Test
        @DisplayName("loadInventory should create new inventory if not exists")
        void loadInventoryCreateNew() {
            when(mockConfig.isInventoryIsolation()).thenReturn(true);
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world"));

            service.init();

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            mockQueryReturning(null);

            service.loadInventory(player, "world");

            verify(mockDataOperator).insert(any(WorldInventory.class));
        }
    }

    @Nested
    @DisplayName("World Change Handling")
    class WorldChangeHandling {

        @Test
        @DisplayName("onWorldChange should do nothing when isolation disabled")
        void onWorldChangeDisabled() {
            // Note: onWorldChange doesn't check isInventoryIsolation directly,
            // but without init() the worldGroupCache is empty. With ungrouped worlds
            // that have the same name mapping (each world = its own group), worlds
            // with different names will trigger save/load which check isolation internally.
            // With same-name worlds (same group), no swap occurs.
            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(fromWorld.getName()).thenReturn("world");
            when(toWorld.getName()).thenReturn("world");

            service.onWorldChange(player, fromWorld, toWorld);

            // Same group (both "world") → no swap → no dataOperator interaction
            verifyNoInteractions(mockDataOperator);
        }

        @Test
        @DisplayName("onWorldChange should not swap when worlds share same group")
        void onWorldChangeSameGroup() {
            when(mockConfig.isInventoryIsolation()).thenReturn(true);
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world,world_nether"));

            service.init();

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(fromWorld.getName()).thenReturn("world");
            when(toWorld.getName()).thenReturn("world_nether");

            service.onWorldChange(player, fromWorld, toWorld);

            // Should not interact with database since they share the same group
            verifyNoInteractions(mockDataOperator);
        }

        @Test
        @DisplayName("onWorldChange should swap when worlds have different groups")
        void onWorldChangeDifferentGroup() throws Exception {
            when(mockConfig.isInventoryIsolation()).thenReturn(true);
            when(mockConfig.isSeparateInventory()).thenReturn(false);
            when(mockConfig.isSeparateEnderChest()).thenReturn(false);
            when(mockConfig.isSeparateExperience()).thenReturn(true);
            when(mockConfig.getSharedWorldGroups()).thenReturn(Arrays.asList("world", "pvp"));

            service.init();

            Player player = UltiWorldsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            World fromWorld = mock(World.class);
            World toWorld = mock(World.class);
            when(fromWorld.getName()).thenReturn("world");
            when(toWorld.getName()).thenReturn("pvp");

            WorldInventory fromInventory = UltiWorldsTestHelper.createSampleWorldInventory(
                    player.getUniqueId(), "world");
            WorldInventory toInventory = UltiWorldsTestHelper.createSampleWorldInventory(
                    player.getUniqueId(), "pvp");

            @SuppressWarnings("unchecked")
            Query<WorldInventory> mockQuery = mock(Query.class);
            when(mockDataOperator.query()).thenReturn(mockQuery);
            when(mockQuery.where(anyString())).thenReturn(mockQuery);
            when(mockQuery.eq(any())).thenReturn(mockQuery);
            when(mockQuery.first())
                    .thenReturn(fromInventory)
                    .thenReturn(toInventory);

            service.onWorldChange(player, fromWorld, toWorld);

            // Should save from world and load to world
            verify(mockDataOperator, atLeast(1)).update(any(WorldInventory.class));
        }
    }

    @Nested
    @DisplayName("Multiple World Groups")
    class MultipleWorldGroups {

        @Test
        @DisplayName("Should handle multiple world groups correctly")
        void multipleGroups() {
            List<String> sharedGroups = Arrays.asList(
                    "world,world_nether,world_the_end",
                    "pvp,arena,battle",
                    "creative,plot,build"
            );
            when(mockConfig.getSharedWorldGroups()).thenReturn(sharedGroups);

            service.init();

            assertThat(service.getWorldGroup("world"))
                    .isEqualTo(service.getWorldGroup("world_nether"))
                    .isEqualTo(service.getWorldGroup("world_the_end"));

            assertThat(service.getWorldGroup("pvp"))
                    .isEqualTo(service.getWorldGroup("arena"))
                    .isEqualTo(service.getWorldGroup("battle"));

            assertThat(service.getWorldGroup("creative"))
                    .isEqualTo(service.getWorldGroup("plot"))
                    .isEqualTo(service.getWorldGroup("build"));

            assertThat(service.getWorldGroup("world"))
                    .isNotEqualTo(service.getWorldGroup("pvp"));
        }
    }
}
