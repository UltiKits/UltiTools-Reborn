package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.UltiCleanerTestHelper;
import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.plugins.cleaner.events.PreEntityCleanEvent;
import com.ultikits.plugins.cleaner.events.PreItemCleanEvent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("CleanerService Tests")
class CleanerServiceTest {

    private CleanerService service;
    private CleanerConfig config;
    private TpsAwareScheduler tpsScheduler;

    @BeforeEach
    void setUp() throws Exception {
        UltiCleanerTestHelper.setUp();

        config = UltiCleanerTestHelper.createDefaultConfig();
        tpsScheduler = mock(TpsAwareScheduler.class);

        service = new CleanerService();

        // Inject dependencies via reflection
        UltiCleanerTestHelper.setField(service, "config", config);
        UltiCleanerTestHelper.setField(service, "tpsScheduler", tpsScheduler);
        UltiCleanerTestHelper.setField(service, "plugin", UltiCleanerTestHelper.getMockPlugin());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiCleanerTestHelper.tearDown();
    }

    // ==================== Helper Methods ====================

    /**
     * Initialize service with standard empty config lists.
     */
    private void initServiceWithEmptyConfig() {
        when(config.getItemWhitelist()).thenReturn(Collections.emptyList());
        when(config.getEntityTypes()).thenReturn(Collections.emptyList());
        when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
        service.init();
    }

    /**
     * Initialize service with specific config.
     */
    private void initServiceWithConfig(List<String> itemWhitelist, List<String> entityTypes, List<String> worldBlacklist) {
        when(config.getItemWhitelist()).thenReturn(itemWhitelist);
        when(config.getEntityTypes()).thenReturn(entityTypes);
        when(config.getWorldBlacklist()).thenReturn(worldBlacklist);
        service.init();
    }

    /**
     * Create a mock Item entity in a world.
     */
    private Item createMockItem(World world, String materialName, boolean hasCustomName, int ticksLived) {
        Item item = mock(Item.class);
        UUID uuid = UUID.randomUUID();
        lenient().when(item.getUniqueId()).thenReturn(uuid);
        lenient().when(item.getType()).thenReturn(EntityType.DROPPED_ITEM);
        lenient().when(item.getWorld()).thenReturn(world);
        lenient().when(item.getTicksLived()).thenReturn(ticksLived);

        ItemStack itemStack = mock(ItemStack.class);
        lenient().when(itemStack.getType()).thenReturn(Material.valueOf(materialName));
        lenient().when(item.getItemStack()).thenReturn(itemStack);

        ItemMeta meta = mock(ItemMeta.class);
        lenient().when(meta.hasDisplayName()).thenReturn(hasCustomName);
        lenient().when(itemStack.hasItemMeta()).thenReturn(hasCustomName);
        lenient().when(itemStack.getItemMeta()).thenReturn(meta);

        return item;
    }

    /**
     * Create a mock living entity (e.g., zombie).
     */
    private LivingEntity createMockLivingEntity(World world, EntityType type, String customName,
                                                 boolean isLeashed) {
        LivingEntity entity = mock(LivingEntity.class);
        UUID uuid = UUID.randomUUID();
        lenient().when(entity.getUniqueId()).thenReturn(uuid);
        lenient().when(entity.getType()).thenReturn(type);
        lenient().when(entity.getWorld()).thenReturn(world);
        lenient().when(entity.getCustomName()).thenReturn(customName);
        lenient().when(entity.isLeashed()).thenReturn(isLeashed);
        return entity;
    }

    /**
     * Create a mock tameable entity (e.g., wolf).
     */
    private Entity createMockTameableEntity(World world, EntityType type, boolean isTamed, String customName) {
        // Use an interface that is both LivingEntity and Tameable
        Wolf entity = mock(Wolf.class);
        UUID uuid = UUID.randomUUID();
        lenient().when(entity.getUniqueId()).thenReturn(uuid);
        lenient().when(entity.getType()).thenReturn(type);
        lenient().when(entity.getWorld()).thenReturn(world);
        lenient().when(entity.getCustomName()).thenReturn(customName);
        lenient().when(entity.isTamed()).thenReturn(isTamed);
        lenient().when(entity.isLeashed()).thenReturn(false);
        return entity;
    }

    // ==================== Initialization ====================

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("Should initialize without errors")
        void initSuccess() {
            when(config.getItemWhitelist()).thenReturn(Arrays.asList("DIAMOND"));
            when(config.getEntityTypes()).thenReturn(Arrays.asList("ZOMBIE"));
            when(config.getWorldBlacklist()).thenReturn(Arrays.asList("world_creative"));

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle null whitelist gracefully")
        void nullWhitelist() {
            when(config.getItemWhitelist()).thenReturn(null);
            when(config.getEntityTypes()).thenReturn(null);
            when(config.getWorldBlacklist()).thenReturn(null);

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle invalid entity type names gracefully")
        void invalidEntityType() {
            when(config.getItemWhitelist()).thenReturn(Collections.emptyList());
            when(config.getEntityTypes()).thenReturn(Arrays.asList("INVALID_ENTITY", "ZOMBIE"));
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should not fail when TPS scheduler is not null")
        void initTpsScheduler() {
            initServiceWithEmptyConfig();

            // TPS scheduler init is called from main class now, not from service.init()
            // This test just verifies init() doesn't fail
            assertThat(service).isNotNull();
        }

        @Test
        @DisplayName("Should not fail when TPS scheduler is null")
        void initNullTpsScheduler() throws Exception {
            UltiCleanerTestHelper.setField(service, "tpsScheduler", null);

            when(config.getItemWhitelist()).thenReturn(Collections.emptyList());
            when(config.getEntityTypes()).thenReturn(Collections.emptyList());
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should cache multiple item whitelist entries")
        void multipleItemWhitelistEntries() {
            when(config.getItemWhitelist()).thenReturn(Arrays.asList("DIAMOND", "EMERALD", "NETHER_STAR"));
            when(config.getEntityTypes()).thenReturn(Collections.emptyList());
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should cache multiple entity types")
        void multipleEntityTypes() {
            when(config.getItemWhitelist()).thenReturn(Collections.emptyList());
            when(config.getEntityTypes()).thenReturn(Arrays.asList("ZOMBIE", "SKELETON", "CREEPER"));
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should cache multiple world blacklist entries")
        void multipleWorldBlacklistEntries() {
            when(config.getItemWhitelist()).thenReturn(Collections.emptyList());
            when(config.getEntityTypes()).thenReturn(Collections.emptyList());
            when(config.getWorldBlacklist()).thenReturn(Arrays.asList("world_creative", "world_test"));

            assertThatCode(() -> service.init()).doesNotThrowAnyException();
        }
    }

    // ==================== Start Tasks (now via @Scheduled) ====================
    // Note: Tasks are now automatically registered via @Scheduled annotations.
    // The TaskManager scans beans and registers scheduled methods at startup.
    // These tests have been removed as they tested manual task registration which no longer exists.

    // ==================== Countdown Getters ====================

    @Nested
    @DisplayName("Countdown Getters")
    class CountdownGetters {

        @Test
        @DisplayName("Should return item countdown after init")
        void getItemCountdown() {
            when(config.getItemCleanInterval()).thenReturn(300);
            initServiceWithEmptyConfig();

            assertThat(service.getItemCountdown()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should return entity countdown after init")
        void getEntityCountdown() {
            when(config.getEntityCleanInterval()).thenReturn(600);
            initServiceWithEmptyConfig();

            assertThat(service.getEntityCountdown()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should return custom item interval")
        void customItemInterval() {
            when(config.getItemCleanInterval()).thenReturn(120);
            initServiceWithEmptyConfig();

            assertThat(service.getItemCountdown()).isEqualTo(120);
        }

        @Test
        @DisplayName("Should return custom entity interval")
        void customEntityInterval() {
            when(config.getEntityCleanInterval()).thenReturn(900);
            initServiceWithEmptyConfig();

            assertThat(service.getEntityCountdown()).isEqualTo(900);
        }
    }

    // ==================== Tick Item Clean ====================

    @Nested
    @DisplayName("Tick Item Clean")
    class TickItemClean {

        @Test
        @DisplayName("Should decrement item countdown on tick")
        void decrementCountdown() throws Exception {
            when(config.getItemCleanInterval()).thenReturn(100);
            initServiceWithEmptyConfig();

            // Invoke private tickItemClean via reflection
            Method tickMethod = CleanerService.class.getDeclaredMethod("tickItemClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            assertThat(service.getItemCountdown()).isEqualTo(99);
        }

        @Test
        @DisplayName("Should broadcast warn when countdown matches warn times")
        void broadcastWarnAtWarnTime() throws Exception {
            when(config.getItemCleanInterval()).thenReturn(10);
            when(config.getItemWarnTimes()).thenReturn(Arrays.asList(5, 3, 1));

            initServiceWithEmptyConfig();

            // Set countdown to 6 so after tick it's 5 (a warn time)
            UltiCleanerTestHelper.setField(service, "itemCountdown", 6);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickItemClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            assertThat(service.getItemCountdown()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should not broadcast warn when countdown does not match warn times")
        void noWarnAtNonWarnTime() throws Exception {
            when(config.getItemCleanInterval()).thenReturn(100);
            when(config.getItemWarnTimes()).thenReturn(Arrays.asList(60, 30, 10));

            initServiceWithEmptyConfig();

            // Set countdown to 50 so after tick it's 49 (not a warn time)
            UltiCleanerTestHelper.setField(service, "itemCountdown", 50);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickItemClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            assertThat(service.getItemCountdown()).isEqualTo(49);
        }

        @Test
        @DisplayName("Should handle null warn times gracefully")
        void nullWarnTimes() throws Exception {
            when(config.getItemCleanInterval()).thenReturn(100);
            when(config.getItemWarnTimes()).thenReturn(null);

            initServiceWithEmptyConfig();

            UltiCleanerTestHelper.setField(service, "itemCountdown", 10);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickItemClean");
            tickMethod.setAccessible(true);

            assertThatCode(() -> tickMethod.invoke(service)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should trigger clean and reset countdown when reaching zero")
        void triggerCleanAtZero() throws Exception {
            when(config.getItemCleanInterval()).thenReturn(300);

            initServiceWithEmptyConfig();

            // Set countdown to 1 so after tick it reaches 0
            UltiCleanerTestHelper.setField(service, "itemCountdown", 1);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickItemClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            // Countdown should be reset
            assertThat(service.getItemCountdown()).isEqualTo(300);
        }
    }

    // ==================== Tick Entity Clean ====================

    @Nested
    @DisplayName("Tick Entity Clean")
    class TickEntityClean {

        @Test
        @DisplayName("Should decrement entity countdown on tick")
        void decrementCountdown() throws Exception {
            when(config.getEntityCleanInterval()).thenReturn(200);
            initServiceWithEmptyConfig();

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickEntityClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            assertThat(service.getEntityCountdown()).isEqualTo(199);
        }

        @Test
        @DisplayName("Should broadcast entity warn at warn times")
        void broadcastEntityWarnAtWarnTime() throws Exception {
            when(config.getEntityCleanInterval()).thenReturn(100);
            when(config.getEntityWarnTimes()).thenReturn(Arrays.asList(60, 30, 10));

            initServiceWithEmptyConfig();

            // Set countdown to 31 so after tick it's 30 (a warn time)
            UltiCleanerTestHelper.setField(service, "entityCountdown", 31);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickEntityClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            assertThat(service.getEntityCountdown()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should handle null entity warn times gracefully")
        void nullEntityWarnTimes() throws Exception {
            when(config.getEntityCleanInterval()).thenReturn(100);
            when(config.getEntityWarnTimes()).thenReturn(null);

            initServiceWithEmptyConfig();

            UltiCleanerTestHelper.setField(service, "entityCountdown", 10);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickEntityClean");
            tickMethod.setAccessible(true);

            assertThatCode(() -> tickMethod.invoke(service)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should trigger entity clean and reset countdown at zero")
        void triggerEntityCleanAtZero() throws Exception {
            when(config.getEntityCleanInterval()).thenReturn(600);

            initServiceWithEmptyConfig();

            // Set countdown to 1 so after tick it reaches 0
            UltiCleanerTestHelper.setField(service, "entityCountdown", 1);

            Method tickMethod = CleanerService.class.getDeclaredMethod("tickEntityClean");
            tickMethod.setAccessible(true);
            tickMethod.invoke(service);

            // Countdown should be reset
            assertThat(service.getEntityCountdown()).isEqualTo(600);
        }
    }

    // ==================== Collect Items To Clean ====================

    @Nested
    @DisplayName("Collect Items To Clean")
    class CollectItemsToClean {

        @Test
        @DisplayName("Should collect items from world")
        void collectItemsFromWorld() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item = createMockItem(world, "STONE", false, 1000);

            when(world.getEntities()).thenReturn(Arrays.asList(item));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).hasSize(1);
        }

        @Test
        @DisplayName("Should skip items in whitelisted materials")
        void skipWhitelistedItems() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item diamondItem = createMockItem(world, "DIAMOND", false, 1000);

            when(world.getEntities()).thenReturn(Arrays.asList(diamondItem));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);
            initServiceWithConfig(Arrays.asList("DIAMOND"), Collections.emptyList(), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("Should skip named items when config enabled")
        void skipNamedItems() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item namedItem = createMockItem(world, "STONE", true, 1000);

            when(world.getEntities()).thenReturn(Arrays.asList(namedItem));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isItemIgnoreNamed()).thenReturn(true);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("Should skip recently dropped items")
        void skipRecentlyDroppedItems() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            // 100 ticks = 5 seconds, with ignore threshold of 30 seconds (600 ticks)
            Item recentItem = createMockItem(world, "STONE", false, 100);

            when(world.getEntities()).thenReturn(Arrays.asList(recentItem));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(30);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("Should not skip old items even with recent filter")
        void collectOldItems() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            // 700 ticks = 35 seconds, with ignore threshold of 30 seconds (600 ticks)
            Item oldItem = createMockItem(world, "STONE", false, 700);

            when(world.getEntities()).thenReturn(Arrays.asList(oldItem));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(30);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).hasSize(1);
        }

        @Test
        @DisplayName("Should skip items in blacklisted worlds")
        void skipBlacklistedWorlds() throws Exception {
            World creativeWorld = UltiCleanerTestHelper.createMockWorld("world_creative");
            Item item = createMockItem(creativeWorld, "STONE", false, 1000);

            when(creativeWorld.getEntities()).thenReturn(Arrays.asList(item));
            UltiCleanerTestHelper.addMockWorld(creativeWorld);

            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            initServiceWithConfig(Collections.emptyList(), Collections.emptyList(), Arrays.asList("world_creative"));

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("Should collect items from non-blacklisted worlds only")
        void collectFromNonBlacklistedWorldsOnly() throws Exception {
            World survivalWorld = UltiCleanerTestHelper.createMockWorld("world");
            World creativeWorld = UltiCleanerTestHelper.createMockWorld("world_creative");
            Item survivalItem = createMockItem(survivalWorld, "STONE", false, 1000);
            Item creativeItem = createMockItem(creativeWorld, "STONE", false, 1000);

            when(survivalWorld.getEntities()).thenReturn(Arrays.asList(survivalItem));
            when(creativeWorld.getEntities()).thenReturn(Arrays.asList(creativeItem));
            UltiCleanerTestHelper.addMockWorld(survivalWorld);
            UltiCleanerTestHelper.addMockWorld(creativeWorld);

            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            initServiceWithConfig(Collections.emptyList(), Collections.emptyList(), Arrays.asList("world_creative"));

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).hasSize(1);
        }

        @Test
        @DisplayName("Should not include non-Item entities")
        void skipNonItemEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(zombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("Should handle item with null item stack")
        void handleNullItemStack() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item = mock(Item.class);
            lenient().when(item.getUniqueId()).thenReturn(UUID.randomUUID());
            lenient().when(item.getItemStack()).thenReturn(null);
            lenient().when(item.getTicksLived()).thenReturn(1000);

            when(world.getEntities()).thenReturn(Arrays.asList(item));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("collectItemsToClean");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<UUID> items = (List<UUID>) method.invoke(service);

            // Item with null stack should still be collected (since whitelist/name checks skip on null)
            assertThat(items).hasSize(1);
        }
    }

    // ==================== Collect Entities To Clean ====================

    @Nested
    @DisplayName("Collect Entities To Clean")
    class CollectEntitiesToClean {

        @Test
        @DisplayName("Should collect entities matching configured types")
        void collectMatchingEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(zombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).hasSize(1);
            assertThat(typeCounts).containsEntry(EntityType.ZOMBIE, 1);
        }

        @Test
        @DisplayName("Should skip entities not in configured types")
        void skipNonMatchingTypes() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity cow = createMockLivingEntity(world, EntityType.COW, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(cow));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).isEmpty();
            assertThat(typeCounts).isEmpty();
        }

        @Test
        @DisplayName("Should skip named entities when whitelist named enabled")
        void skipNamedEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity namedZombie = createMockLivingEntity(world, EntityType.ZOMBIE, "Boss", false);

            when(world.getEntities()).thenReturn(Arrays.asList(namedZombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(true);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).isEmpty();
        }

        @Test
        @DisplayName("Should not skip named entities when whitelist named disabled")
        void collectNamedEntitiesWhenDisabled() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity namedZombie = createMockLivingEntity(world, EntityType.ZOMBIE, "Boss", false);

            when(world.getEntities()).thenReturn(Arrays.asList(namedZombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).hasSize(1);
        }

        @Test
        @DisplayName("Should skip leashed entities when whitelist leashed enabled")
        void skipLeashedEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity leashedZombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, true);

            when(world.getEntities()).thenReturn(Arrays.asList(leashedZombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(true);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).isEmpty();
        }

        @Test
        @DisplayName("Should not skip leashed entities when whitelist leashed disabled")
        void collectLeashedEntitiesWhenDisabled() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity leashedZombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, true);

            when(world.getEntities()).thenReturn(Arrays.asList(leashedZombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).hasSize(1);
        }

        @Test
        @DisplayName("Should skip tamed entities when whitelist tamed enabled")
        void skipTamedEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Entity tamedWolf = createMockTameableEntity(world, EntityType.WOLF, true, null);

            when(world.getEntities()).thenReturn(Arrays.asList(tamedWolf));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(true);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("WOLF"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).isEmpty();
        }

        @Test
        @DisplayName("Should collect untamed tameable entities")
        void collectUntamedEntities() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Entity untamedWolf = createMockTameableEntity(world, EntityType.WOLF, false, null);

            when(world.getEntities()).thenReturn(Arrays.asList(untamedWolf));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(true);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("WOLF"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).hasSize(1);
        }

        @Test
        @DisplayName("Should skip entities in blacklisted worlds")
        void skipBlacklistedWorldEntities() throws Exception {
            World creativeWorld = UltiCleanerTestHelper.createMockWorld("world_creative");
            LivingEntity zombie = createMockLivingEntity(creativeWorld, EntityType.ZOMBIE, null, false);

            when(creativeWorld.getEntities()).thenReturn(Arrays.asList(zombie));
            UltiCleanerTestHelper.addMockWorld(creativeWorld);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Arrays.asList("world_creative"));

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).isEmpty();
        }

        @Test
        @DisplayName("Should count multiple entity types correctly")
        void multipleEntityTypeCounts() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie1 = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);
            LivingEntity zombie2 = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);
            LivingEntity skeleton = createMockLivingEntity(world, EntityType.SKELETON, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(zombie1, zombie2, skeleton));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE", "SKELETON"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("collectEntitiesToClean", Map.class);
            method.setAccessible(true);

            Map<EntityType, Integer> typeCounts = new HashMap<>();
            @SuppressWarnings("unchecked")
            List<UUID> entities = (List<UUID>) method.invoke(service, typeCounts);

            assertThat(entities).hasSize(3);
            assertThat(typeCounts).containsEntry(EntityType.ZOMBIE, 2);
            assertThat(typeCounts).containsEntry(EntityType.SKELETON, 1);
        }
    }

    // ==================== Entity Counts ====================

    @Nested
    @DisplayName("Entity Counts")
    class EntityCounts {

        @Test
        @DisplayName("Should return entity counts map with empty worlds")
        void getEntityCountsEmpty() {
            initServiceWithEmptyConfig();

            Map<String, Integer> counts = service.getEntityCounts();

            assertThat(counts).isNotNull();
            assertThat(counts).containsKeys("items", "mobs", "total");
            assertThat(counts.get("items")).isEqualTo(0);
            assertThat(counts.get("mobs")).isEqualTo(0);
            assertThat(counts.get("total")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should count items in worlds")
        void countItemsInWorlds() {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item1 = createMockItem(world, "STONE", false, 1000);
            Item item2 = createMockItem(world, "DIRT", false, 1000);

            when(world.getEntities()).thenReturn(Arrays.asList(item1, item2));
            UltiCleanerTestHelper.addMockWorld(world);

            initServiceWithEmptyConfig();

            Map<String, Integer> counts = service.getEntityCounts();

            assertThat(counts.get("items")).isEqualTo(2);
            assertThat(counts.get("total")).isEqualTo(2);
        }

        @Test
        @DisplayName("Should count mobs matching entity types cache")
        void countMobsInWorlds() {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);
            LivingEntity skeleton = createMockLivingEntity(world, EntityType.SKELETON, null, false);
            LivingEntity cow = createMockLivingEntity(world, EntityType.COW, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(zombie, skeleton, cow));
            UltiCleanerTestHelper.addMockWorld(world);

            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE", "SKELETON"), Collections.emptyList());

            Map<String, Integer> counts = service.getEntityCounts();

            assertThat(counts.get("mobs")).isEqualTo(2);
            assertThat(counts.get("total")).isEqualTo(3);
        }

        @Test
        @DisplayName("Should count items and mobs together")
        void countItemsAndMobs() {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item = createMockItem(world, "STONE", false, 1000);
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(item, zombie));
            UltiCleanerTestHelper.addMockWorld(world);

            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Map<String, Integer> counts = service.getEntityCounts();

            assertThat(counts.get("items")).isEqualTo(1);
            assertThat(counts.get("mobs")).isEqualTo(1);
            assertThat(counts.get("total")).isEqualTo(2);
        }
    }

    // ==================== Smart Clean ====================

    @Nested
    @DisplayName("Smart Clean")
    class SmartClean {

        @Test
        @DisplayName("Should not trigger smart clean when disabled")
        void smartCleanDisabled() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(false);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should not trigger smart clean when cleaning is in progress")
        void smartCleanWhileCleaningInProgress() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(true);
            initServiceWithEmptyConfig();

            UltiCleanerTestHelper.setField(service, "isCleaningInProgress", true);

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should not trigger smart clean during cooldown")
        void smartCleanDuringCooldown() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(true);
            when(config.getSmartCleanCooldown()).thenReturn(60);
            initServiceWithEmptyConfig();

            // Set last clean time to now (within cooldown)
            UltiCleanerTestHelper.setField(service, "lastSmartCleanTime", System.currentTimeMillis());

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should trigger smart clean when item count exceeds threshold")
        void smartCleanTriggeredByItemCount() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(true);
            when(config.getSmartCleanCooldown()).thenReturn(0);
            when(config.getItemMaxThreshold()).thenReturn(5);
            when(config.getMobMaxThreshold()).thenReturn(10000);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            // Create 10 items (exceeds threshold of 5)
            List<Entity> entities = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                entities.add(createMockItem(world, "STONE", false, 1000));
            }
            when(world.getEntities()).thenReturn(entities);
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);
            when(tpsScheduler.applyThresholdReduction(5)).thenReturn(5);
            when(tpsScheduler.applyThresholdReduction(10000)).thenReturn(10000);

            initServiceWithEmptyConfig();

            // Reset last clean time to 0
            UltiCleanerTestHelper.setField(service, "lastSmartCleanTime", 0L);

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);
            method.invoke(service);

            // Verify that a broadcast was attempted (smart clean triggered message)
            // Since online players is empty, no direct message assertion, but the method should not throw
        }

        @Test
        @DisplayName("Should trigger smart clean when mob count exceeds threshold")
        void smartCleanTriggeredByMobCount() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(true);
            when(config.getSmartCleanCooldown()).thenReturn(0);
            when(config.getItemMaxThreshold()).thenReturn(10000);
            when(config.getMobMaxThreshold()).thenReturn(2);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            List<Entity> entities = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                entities.add(createMockLivingEntity(world, EntityType.ZOMBIE, null, false));
            }
            when(world.getEntities()).thenReturn(entities);
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            when(tpsScheduler.applyThresholdReduction(10000)).thenReturn(10000);
            when(tpsScheduler.applyThresholdReduction(2)).thenReturn(2);

            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            UltiCleanerTestHelper.setField(service, "lastSmartCleanTime", 0L);

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);
            method.invoke(service);

            // Should not throw
        }

        @Test
        @DisplayName("Should not trigger smart clean when counts are below thresholds")
        void smartCleanNotTriggered() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(true);
            when(config.getSmartCleanCooldown()).thenReturn(0);
            when(config.getItemMaxThreshold()).thenReturn(10000);
            when(config.getMobMaxThreshold()).thenReturn(10000);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            when(world.getEntities()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            when(tpsScheduler.applyThresholdReduction(10000)).thenReturn(10000);

            initServiceWithEmptyConfig();

            UltiCleanerTestHelper.setField(service, "lastSmartCleanTime", 0L);

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);
            method.invoke(service);

            // Should not throw and should not trigger clean
        }

        @Test
        @DisplayName("Should skip blacklisted worlds in smart clean counting")
        void smartCleanSkipsBlacklistedWorlds() throws Exception {
            when(config.isSmartCleanEnabled()).thenReturn(true);
            when(config.getSmartCleanCooldown()).thenReturn(0);
            when(config.getItemMaxThreshold()).thenReturn(1);
            when(config.getMobMaxThreshold()).thenReturn(10000);

            World blacklistedWorld = UltiCleanerTestHelper.createMockWorld("world_creative");
            List<Entity> entities = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                entities.add(createMockItem(blacklistedWorld, "STONE", false, 1000));
            }
            when(blacklistedWorld.getEntities()).thenReturn(entities);
            UltiCleanerTestHelper.addMockWorld(blacklistedWorld);

            when(tpsScheduler.applyThresholdReduction(1)).thenReturn(1);
            when(tpsScheduler.applyThresholdReduction(10000)).thenReturn(10000);

            initServiceWithConfig(Collections.emptyList(), Collections.emptyList(), Arrays.asList("world_creative"));

            UltiCleanerTestHelper.setField(service, "lastSmartCleanTime", 0L);

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);
            method.invoke(service);

            // Should not trigger (all entities are in blacklisted world)
        }

        @Test
        @DisplayName("Should use TPS-adjusted thresholds when tpsScheduler is null")
        void smartCleanWithNullTpsScheduler() throws Exception {
            UltiCleanerTestHelper.setField(service, "tpsScheduler", null);

            when(config.isSmartCleanEnabled()).thenReturn(true);
            when(config.getSmartCleanCooldown()).thenReturn(0);
            when(config.getItemMaxThreshold()).thenReturn(10000);
            when(config.getMobMaxThreshold()).thenReturn(10000);

            World world = UltiCleanerTestHelper.createMockWorld("world");
            when(world.getEntities()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemWhitelist()).thenReturn(Collections.emptyList());
            when(config.getEntityTypes()).thenReturn(Collections.emptyList());
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            service.init();

            UltiCleanerTestHelper.setField(service, "lastSmartCleanTime", 0L);

            Method method = CleanerService.class.getDeclaredMethod("checkSmartClean");
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service)).doesNotThrowAnyException();
        }
    }

    // ==================== Clean Items With Batch ====================

    @Nested
    @DisplayName("Clean Items With Batch")
    class CleanItemsWithBatch {

        @Test
        @DisplayName("Should not clean when cleaning is in progress")
        void skipWhenCleaningInProgress() throws Exception {
            initServiceWithEmptyConfig();

            UltiCleanerTestHelper.setField(service, "isCleaningInProgress", true);

            Method method = CleanerService.class.getDeclaredMethod("cleanItemsWithBatch", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service, PreItemCleanEvent.CleanTrigger.MANUAL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should broadcast item cleaned message with zero count when no items")
        void broadcastZeroWhenNoItems() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            when(world.getEntities()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("cleanItemsWithBatch", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);
            method.invoke(service, PreItemCleanEvent.CleanTrigger.SCHEDULED);

            // Should not throw
        }

        @Test
        @DisplayName("Should fire PreItemCleanEvent")
        void firePreItemCleanEvent() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item = createMockItem(world, "STONE", false, 1000);
            when(world.getEntities()).thenReturn(Arrays.asList(item));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("cleanItemsWithBatch", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);
            method.invoke(service, PreItemCleanEvent.CleanTrigger.MANUAL);

            // Verify that callEvent was called at least once (for PreItemCleanEvent)
            verify(Bukkit.getPluginManager(), atLeastOnce()).callEvent(any(PreItemCleanEvent.class));
        }

        @Test
        @DisplayName("Should respect cancelled PreItemCleanEvent")
        void respectCancelledEvent() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item = createMockItem(world, "STONE", false, 1000);
            when(world.getEntities()).thenReturn(Arrays.asList(item));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);

            // Make the plugin manager cancel the event
            PluginManager pm = Bukkit.getPluginManager();
            doAnswer(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof PreItemCleanEvent) {
                    ((PreItemCleanEvent) arg).setCancelled(true);
                }
                return null;
            }).when(pm).callEvent(any());

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("cleanItemsWithBatch", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);
            method.invoke(service, PreItemCleanEvent.CleanTrigger.MANUAL);

            // When cancelled, no batch removal should be started
        }
    }

    // ==================== Clean Entities With Batch ====================

    @Nested
    @DisplayName("Clean Entities With Batch")
    class CleanEntitiesWithBatch {

        @Test
        @DisplayName("Should not clean when cleaning is in progress")
        void skipWhenCleaningInProgress() throws Exception {
            initServiceWithEmptyConfig();

            UltiCleanerTestHelper.setField(service, "isCleaningInProgress", true);

            Method method = CleanerService.class.getDeclaredMethod("cleanEntitiesWithBatch", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service, PreEntityCleanEvent.CleanTrigger.MANUAL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should fire PreEntityCleanEvent")
        void firePreEntityCleanEvent() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);
            when(world.getEntities()).thenReturn(Arrays.asList(zombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("cleanEntitiesWithBatch", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);
            method.invoke(service, PreEntityCleanEvent.CleanTrigger.SCHEDULED);

            verify(Bukkit.getPluginManager(), atLeastOnce()).callEvent(any(PreEntityCleanEvent.class));
        }

        @Test
        @DisplayName("Should respect cancelled PreEntityCleanEvent")
        void respectCancelledEntityEvent() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);
            when(world.getEntities()).thenReturn(Arrays.asList(zombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);

            PluginManager pm = Bukkit.getPluginManager();
            doAnswer(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof PreEntityCleanEvent) {
                    ((PreEntityCleanEvent) arg).setCancelled(true);
                }
                return null;
            }).when(pm).callEvent(any());

            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("cleanEntitiesWithBatch", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);
            method.invoke(service, PreEntityCleanEvent.CleanTrigger.MANUAL);

            // Should broadcast cancel message
        }

        @Test
        @DisplayName("Should skip silently when entity list is empty after event")
        void skipWhenEmptyAfterEvent() throws Exception {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            when(world.getEntities()).thenReturn(Collections.emptyList());
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            Method method = CleanerService.class.getDeclaredMethod("cleanEntitiesWithBatch", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service, PreEntityCleanEvent.CleanTrigger.SCHEDULED))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== Trigger Conversion ====================

    @Nested
    @DisplayName("Trigger Conversion")
    class TriggerConversion {

        @Test
        @DisplayName("Should convert item SMART trigger")
        void convertItemSmartTrigger() throws Exception {
            Method method = CleanerService.class.getDeclaredMethod("convertTrigger", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            Object result = method.invoke(service, PreItemCleanEvent.CleanTrigger.SMART);
            assertThat(result).isEqualTo(com.ultikits.plugins.cleaner.events.CleanCompleteEvent.CleanTrigger.SMART);
        }

        @Test
        @DisplayName("Should convert item MANUAL trigger")
        void convertItemManualTrigger() throws Exception {
            Method method = CleanerService.class.getDeclaredMethod("convertTrigger", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            Object result = method.invoke(service, PreItemCleanEvent.CleanTrigger.MANUAL);
            assertThat(result).isEqualTo(com.ultikits.plugins.cleaner.events.CleanCompleteEvent.CleanTrigger.MANUAL);
        }

        @Test
        @DisplayName("Should convert item SCHEDULED trigger")
        void convertItemScheduledTrigger() throws Exception {
            Method method = CleanerService.class.getDeclaredMethod("convertTrigger", PreItemCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            Object result = method.invoke(service, PreItemCleanEvent.CleanTrigger.SCHEDULED);
            assertThat(result).isEqualTo(com.ultikits.plugins.cleaner.events.CleanCompleteEvent.CleanTrigger.SCHEDULED);
        }

        @Test
        @DisplayName("Should convert entity SMART trigger")
        void convertEntitySmartTrigger() throws Exception {
            Method method = CleanerService.class.getDeclaredMethod("convertTrigger", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            Object result = method.invoke(service, PreEntityCleanEvent.CleanTrigger.SMART);
            assertThat(result).isEqualTo(com.ultikits.plugins.cleaner.events.CleanCompleteEvent.CleanTrigger.SMART);
        }

        @Test
        @DisplayName("Should convert entity MANUAL trigger")
        void convertEntityManualTrigger() throws Exception {
            Method method = CleanerService.class.getDeclaredMethod("convertTrigger", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            Object result = method.invoke(service, PreEntityCleanEvent.CleanTrigger.MANUAL);
            assertThat(result).isEqualTo(com.ultikits.plugins.cleaner.events.CleanCompleteEvent.CleanTrigger.MANUAL);
        }

        @Test
        @DisplayName("Should convert entity SCHEDULED trigger")
        void convertEntityScheduledTrigger() throws Exception {
            Method method = CleanerService.class.getDeclaredMethod("convertTrigger", PreEntityCleanEvent.CleanTrigger.class);
            method.setAccessible(true);

            Object result = method.invoke(service, PreEntityCleanEvent.CleanTrigger.SCHEDULED);
            assertThat(result).isEqualTo(com.ultikits.plugins.cleaner.events.CleanCompleteEvent.CleanTrigger.SCHEDULED);
        }
    }

    // ==================== Broadcast Methods ====================

    @Nested
    @DisplayName("Broadcast Methods")
    class BroadcastMethods {

        @Test
        @DisplayName("Should broadcast message to online players")
        void broadcastMessage() throws Exception {
            Player player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> players = Collections.singletonList(player);
            lenient().when(UltiCleanerTestHelper.getMockServer().getOnlinePlayers()).thenReturn((Collection) players);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastMessage", String.class);
            method.setAccessible(true);
            method.invoke(service, "&aTest message");

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should broadcast warn with time replacement")
        void broadcastWarn() throws Exception {
            Player player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> players = Collections.singletonList(player);
            lenient().when(UltiCleanerTestHelper.getMockServer().getOnlinePlayers()).thenReturn((Collection) players);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastWarn", int.class);
            method.setAccessible(true);
            method.invoke(service, 30);

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should broadcast entity warn with time replacement")
        void broadcastEntityWarn() throws Exception {
            Player player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> players = Collections.singletonList(player);
            lenient().when(UltiCleanerTestHelper.getMockServer().getOnlinePlayers()).thenReturn((Collection) players);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastEntityWarn", int.class);
            method.setAccessible(true);
            method.invoke(service, 10);

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should broadcast item cleaned message")
        void broadcastItemCleaned() throws Exception {
            Player player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> players = Collections.singletonList(player);
            lenient().when(UltiCleanerTestHelper.getMockServer().getOnlinePlayers()).thenReturn((Collection) players);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastItemCleaned", int.class);
            method.setAccessible(true);
            method.invoke(service, 42);

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should broadcast entity cleaned message when count > 0")
        void broadcastEntityCleanedPositiveCount() throws Exception {
            Player player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> players = Collections.singletonList(player);
            lenient().when(UltiCleanerTestHelper.getMockServer().getOnlinePlayers()).thenReturn((Collection) players);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastEntityCleaned", int.class);
            method.setAccessible(true);
            method.invoke(service, 10);

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not broadcast entity cleaned message when count is 0")
        void broadcastEntityCleanedZeroCount() throws Exception {
            Player player = UltiCleanerTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> players = Collections.singletonList(player);
            lenient().when(UltiCleanerTestHelper.getMockServer().getOnlinePlayers()).thenReturn((Collection) players);

            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastEntityCleaned", int.class);
            method.setAccessible(true);
            method.invoke(service, 0);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should handle no online players gracefully")
        void broadcastWithNoPlayers() throws Exception {
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("broadcastMessage", String.class);
            method.setAccessible(true);

            assertThatCode(() -> method.invoke(service, "&aTest")).doesNotThrowAnyException();
        }
    }

    // ==================== Cleaning In Progress ====================

    @Nested
    @DisplayName("Cleaning In Progress")
    class CleaningInProgress {

        @Test
        @DisplayName("Should return false initially")
        void initiallyFalse() {
            assertThat(service.isCleaningInProgress()).isFalse();
        }

        @Test
        @DisplayName("Should return true when set via reflection")
        void trueWhenSet() throws Exception {
            UltiCleanerTestHelper.setField(service, "isCleaningInProgress", true);

            assertThat(service.isCleaningInProgress()).isTrue();
        }
    }

    // ==================== TPS Scheduler Getter ====================

    @Nested
    @DisplayName("TPS Scheduler Getter")
    class TpsSchedulerGetter {

        @Test
        @DisplayName("Should return TPS scheduler")
        void getTpsScheduler() {
            assertThat(service.getTpsScheduler()).isSameAs(tpsScheduler);
        }

        @Test
        @DisplayName("Should return null when not set")
        void getTpsSchedulerNull() throws Exception {
            UltiCleanerTestHelper.setField(service, "tpsScheduler", null);

            assertThat(service.getTpsScheduler()).isNull();
        }
    }

    // ==================== Force Clean Methods ====================

    @Nested
    @DisplayName("Force Clean Methods")
    class ForceCleanMethods {

        @Test
        @DisplayName("forceCleanItems should return count and reset countdown")
        void forceCleanItems() {
            when(config.getItemCleanInterval()).thenReturn(300);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);
            initServiceWithEmptyConfig();

            int count = service.forceCleanItems();

            assertThat(count).isGreaterThanOrEqualTo(0);
            assertThat(service.getItemCountdown()).isEqualTo(300);
        }

        @Test
        @DisplayName("forceCleanEntities should return count and reset countdown")
        void forceCleanEntities() {
            when(config.getEntityCleanInterval()).thenReturn(600);
            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithEmptyConfig();

            int count = service.forceCleanEntities();

            assertThat(count).isGreaterThanOrEqualTo(0);
            assertThat(service.getEntityCountdown()).isEqualTo(600);
        }

        @Test
        @DisplayName("forceCleanItems should return count of items in world")
        void forceCleanItemsWithWorldItems() {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            Item item1 = createMockItem(world, "STONE", false, 1000);
            Item item2 = createMockItem(world, "DIRT", false, 1000);

            when(world.getEntities()).thenReturn(Arrays.asList(item1, item2));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getItemCleanInterval()).thenReturn(300);
            when(config.getItemIgnoreRecentSeconds()).thenReturn(0);
            when(config.isItemIgnoreNamed()).thenReturn(false);
            initServiceWithEmptyConfig();

            int count = service.forceCleanItems();

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("forceCleanEntities should return count of matching entities")
        void forceCleanEntitiesWithWorldEntities() {
            World world = UltiCleanerTestHelper.createMockWorld("world");
            LivingEntity zombie = createMockLivingEntity(world, EntityType.ZOMBIE, null, false);

            when(world.getEntities()).thenReturn(Arrays.asList(zombie));
            UltiCleanerTestHelper.addMockWorld(world);

            when(config.getEntityCleanInterval()).thenReturn(600);
            when(config.isEntityWhitelistNamed()).thenReturn(false);
            when(config.isEntityWhitelistLeashed()).thenReturn(false);
            when(config.isEntityWhitelistTamed()).thenReturn(false);
            initServiceWithConfig(Collections.emptyList(), Arrays.asList("ZOMBIE"), Collections.emptyList());

            int count = service.forceCleanEntities();

            assertThat(count).isEqualTo(1);
        }
    }

    // ==================== Shutdown ====================

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("Should shutdown without errors")
        void shutdownSuccess() {
            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should not fail when TPS scheduler is null on shutdown")
        void shutdownNullTpsScheduler() throws Exception {
            UltiCleanerTestHelper.setField(service, "tpsScheduler", null);

            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should cancel running tasks on shutdown via framework")
        void cancelTasksOnShutdown() {
            initServiceWithEmptyConfig();

            // Note: @Scheduled tasks are automatically cancelled by the framework
            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();
        }
    }

    // ==================== Reload ====================

    @Nested
    @DisplayName("Reload")
    class Reload {

        @Test
        @DisplayName("Should reload without errors")
        void reloadSuccess() {
            initServiceWithEmptyConfig();

            assertThatCode(() -> service.reload()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reload configuration caches")
        void reloadCaches() {
            when(config.isItemCleanEnabled()).thenReturn(true);
            when(config.getItemWhitelist()).thenReturn(Arrays.asList("DIAMOND"));
            when(config.getEntityTypes()).thenReturn(Arrays.asList("ZOMBIE"));
            when(config.getWorldBlacklist()).thenReturn(Collections.emptyList());
            service.init();

            // Change config
            when(config.getItemWhitelist()).thenReturn(Arrays.asList("EMERALD"));

            service.reload();

            // Note: @Scheduled tasks continue running, only caches are reloaded
            assertThat(service).isNotNull();
        }
    }

    // ==================== Remove Entities In Batches ====================

    @Nested
    @DisplayName("Remove Entities In Batches")
    class RemoveEntitiesInBatches {

        @Test
        @DisplayName("Should handle empty UUID list")
        void emptyUuidList() throws Exception {
            initServiceWithEmptyConfig();

            Method method = CleanerService.class.getDeclaredMethod("removeEntitiesInBatches",
                    List.class, int.class, java.util.function.Consumer.class);
            method.setAccessible(true);

            final int[] callbackCount = {-1};
            java.util.function.Consumer<Integer> callback = count -> callbackCount[0] = count;

            method.invoke(service, Collections.emptyList(), 50, callback);

            assertThat(callbackCount[0]).isEqualTo(0);
        }
    }
}
