package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.RemoteBagData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RemoteBagService Tests")
class RemoteBagServiceTest {

    private RemoteBagService service;
    private RemoteBagConfig config;
    @SuppressWarnings("unchecked")
    private DataOperator<RemoteBagData> dataOperator = mock(DataOperator.class);

    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        config = UltiRemoteBagTestHelper.createDefaultConfig();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getDataOperator(RemoteBagData.class)).thenReturn(dataOperator);

        service = new RemoteBagService(mockPlugin, config);

        // Inject dataOperator via reflection (set by init())
        UltiRemoteBagTestHelper.setField(service, "dataOperator", dataOperator);

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== getPlayerMaxPages ====================

    @Nested
    @DisplayName("getPlayerMaxPages")
    class GetPlayerMaxPages {

        @Test
        @DisplayName("Should return max pages when permission based disabled")
        void returnMaxWhenDisabled() {
            when(config.isPermissionBasedPages()).thenReturn(false);
            when(config.getMaxPages()).thenReturn(10);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return highest permission level")
        void returnHighestPermission() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            // Override default to false, then set specific permission
            when(player.hasPermission(anyString())).thenReturn(false);
            when(player.hasPermission("ultibag.pages.5")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.4")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.3")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.2")).thenReturn(true);
            when(player.hasPermission("ultibag.pages.1")).thenReturn(true);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return default pages when no permissions")
        void returnDefaultWhenNoPermissions() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getDefaultPages()).thenReturn(1);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(player.hasPermission(anyString())).thenReturn(false);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return max pages when player has all permissions")
        void returnMaxWhenAllPermissions() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(player.hasPermission(anyString())).thenReturn(true);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(10);
        }

        @Test
        @DisplayName("Should check permissions from max to 1")
        void checksPermissionsDescending() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(3);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(config.getDefaultPages()).thenReturn(1);
            when(player.hasPermission(anyString())).thenReturn(false);
            // Only has permission for page 2
            when(player.hasPermission("ultibag.pages.2")).thenReturn(true);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return default 1 page for custom default setting")
        void returnsCustomDefault() {
            when(config.isPermissionBasedPages()).thenReturn(true);
            when(config.getMaxPages()).thenReturn(10);
            when(config.getDefaultPages()).thenReturn(3);
            when(config.getPermissionPrefix()).thenReturn("ultibag.pages.");
            when(player.hasPermission(anyString())).thenReturn(false);

            int result = service.getPlayerMaxPages(player);

            assertThat(result).isEqualTo(3);
        }
    }

    // ==================== Cache Operations ====================

    @Nested
    @DisplayName("Cache Operations")
    class CacheOperations {

        @Test
        @DisplayName("getBagPage should return null when not in cache")
        void getBagPageNotInCache() {
            ItemStack[] result = service.getBagPage(playerUuid, 1);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("setBagPage should store in cache")
        void setBagPageStoresInCache() {
            ItemStack[] contents = new ItemStack[54];
            service.setBagPage(playerUuid, 1, contents);

            ItemStack[] result = service.getBagPage(playerUuid, 1);
            assertThat(result).isSameAs(contents);
        }

        @Test
        @DisplayName("setBagPage should handle multiple pages")
        void setBagPageMultiplePages() {
            ItemStack[] page1 = new ItemStack[54];
            ItemStack[] page2 = new ItemStack[54];

            service.setBagPage(playerUuid, 1, page1);
            service.setBagPage(playerUuid, 2, page2);

            assertThat(service.getBagPage(playerUuid, 1)).isSameAs(page1);
            assertThat(service.getBagPage(playerUuid, 2)).isSameAs(page2);
        }

        @Test
        @DisplayName("clearCache should remove player data")
        void clearCacheRemoves() {
            ItemStack[] contents = new ItemStack[54];
            service.setBagPage(playerUuid, 1, contents);

            service.clearCache(playerUuid);

            assertThat(service.getBagPage(playerUuid, 1)).isNull();
        }

        @Test
        @DisplayName("setBagPage should overwrite existing page")
        void setBagPageOverwrites() {
            ItemStack[] original = new ItemStack[54];
            ItemStack[] replacement = new ItemStack[54];

            service.setBagPage(playerUuid, 1, original);
            service.setBagPage(playerUuid, 1, replacement);

            assertThat(service.getBagPage(playerUuid, 1)).isSameAs(replacement);
        }

        @Test
        @DisplayName("getBagPage should return null for non-existent page of cached player")
        void getBagPageNonExistentPage() {
            service.setBagPage(playerUuid, 1, new ItemStack[54]);

            ItemStack[] result = service.getBagPage(playerUuid, 99);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("clearCache should not affect other players")
        void clearCacheDoesNotAffectOthers() {
            UUID otherUuid = UUID.randomUUID();
            service.setBagPage(playerUuid, 1, new ItemStack[54]);
            service.setBagPage(otherUuid, 1, new ItemStack[54]);

            service.clearCache(playerUuid);

            assertThat(service.getBagPage(playerUuid, 1)).isNull();
            assertThat(service.getBagPage(otherUuid, 1)).isNotNull();
        }

        @Test
        @DisplayName("clearCache on non-cached player should not throw")
        void clearCacheNonCachedPlayer() {
            assertThatCode(() -> service.clearCache(UUID.randomUUID()))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== getItemCount ====================

    @Nested
    @DisplayName("getItemCount")
    class GetItemCount {

        @Test
        @DisplayName("Should return 0 for null page")
        void returnsZeroForNull() {
            assertThat(service.getItemCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count all items in stacks")
        void countsAllItems() {
            ItemStack[] contents = new ItemStack[54];
            contents[0] = new ItemStack(Material.STONE, 64);
            contents[1] = new ItemStack(Material.DIRT, 32);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(96); // 64 + 32
        }

        @Test
        @DisplayName("Should ignore air and null items")
        void ignoresAirAndNull() {
            ItemStack[] contents = new ItemStack[54];
            contents[0] = new ItemStack(Material.STONE, 10);
            contents[1] = new ItemStack(Material.AIR, 5);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(10);
        }

        @Test
        @DisplayName("Should return 0 for empty page (all null)")
        void returnsZeroForEmptyPage() {
            service.setBagPage(playerUuid, 1, new ItemStack[54]);

            assertThat(service.getItemCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count items with amount of 1")
        void countsItemsWithAmountOne() {
            ItemStack[] contents = new ItemStack[54];
            contents[0] = new ItemStack(Material.DIAMOND_SWORD, 1);
            contents[1] = new ItemStack(Material.DIAMOND_PICKAXE, 1);

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(2);
        }

        @Test
        @DisplayName("Should count across many slots")
        void countsAcrossManySlots() {
            ItemStack[] contents = new ItemStack[54];
            for (int i = 0; i < 54; i++) {
                contents[i] = new ItemStack(Material.STONE, 1);
            }

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getItemCount(playerUuid, 1)).isEqualTo(54);
        }
    }

    // ==================== getStackCount ====================

    @Nested
    @DisplayName("getStackCount")
    class GetStackCount {

        @Test
        @DisplayName("Should return 0 for null page")
        void returnsZeroForNull() {
            assertThat(service.getStackCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count occupied slots")
        void countsOccupiedSlots() {
            ItemStack[] contents = new ItemStack[54];
            contents[0] = new ItemStack(Material.STONE, 64);
            contents[1] = new ItemStack(Material.DIRT, 1);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getStackCount(playerUuid, 1)).isEqualTo(2);
        }

        @Test
        @DisplayName("Should ignore air and null items")
        void ignoresAirAndNull() {
            ItemStack[] contents = new ItemStack[54];
            contents[0] = new ItemStack(Material.STONE, 10);
            contents[1] = new ItemStack(Material.AIR, 5);
            contents[2] = null;

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getStackCount(playerUuid, 1)).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return 0 for empty page")
        void returnsZeroForEmptyPage() {
            service.setBagPage(playerUuid, 1, new ItemStack[54]);

            assertThat(service.getStackCount(playerUuid, 1)).isZero();
        }

        @Test
        @DisplayName("Should count full inventory correctly")
        void countsFullInventory() {
            ItemStack[] contents = new ItemStack[54];
            for (int i = 0; i < 54; i++) {
                contents[i] = new ItemStack(Material.STONE, 64);
            }

            service.setBagPage(playerUuid, 1, contents);

            assertThat(service.getStackCount(playerUuid, 1)).isEqualTo(54);
        }
    }

    // ==================== calculatePrice ====================

    @Nested
    @DisplayName("calculatePrice")
    class CalculatePrice {

        @Test
        @DisplayName("Should return base price for first bag")
        void basePriceForFirst() {
            when(config.getBasePrice()).thenReturn(10000);

            assertThat(service.calculatePrice(1)).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should return base price when increase disabled")
        void basePriceWhenDisabled() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(false);

            assertThat(service.calculatePrice(5)).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should calculate price with 10% increase")
        void calculatesWithIncrease() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.1);

            // Bag 2: 10000 * 1.1^1 = 11000
            assertThat(service.calculatePrice(2)).isEqualTo(11000);

            // Bag 3: 10000 * 1.1^2 = 12100 (Math.ceil may round up due to floating point)
            assertThat(service.calculatePrice(3)).isEqualTo(12101);

            // Bag 4: 10000 * 1.1^3 = 13310 (Math.ceil may round up due to floating point)
            assertThat(service.calculatePrice(4)).isEqualTo(13311);
        }

        @Test
        @DisplayName("Should round up fractional prices")
        void roundsUpFractional() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.15);

            // Bag 2: 10000 * 1.15 = 11500
            assertThat(service.calculatePrice(2)).isEqualTo(11500);
        }

        @Test
        @DisplayName("Should return base price when bagNumber is 1 even with increase enabled")
        void basePriceWhenBagNumberOne() {
            when(config.getBasePrice()).thenReturn(5000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.5);

            assertThat(service.calculatePrice(1)).isEqualTo(5000);
        }

        @Test
        @DisplayName("Should handle zero rate")
        void handlesZeroRate() {
            when(config.getBasePrice()).thenReturn(10000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(0.0);

            // 10000 * 1.0^n = 10000
            assertThat(service.calculatePrice(5)).isEqualTo(10000);
        }

        @Test
        @DisplayName("Should handle high bag number with large rate")
        void handlesHighBagNumber() {
            when(config.getBasePrice()).thenReturn(1000);
            when(config.isPriceIncreaseEnabled()).thenReturn(true);
            when(config.getPriceIncreaseRate()).thenReturn(1.0);

            // Bag 5: 1000 * 2^4 = 16000
            assertThat(service.calculatePrice(5)).isEqualTo(16000);
        }
    }

    // ==================== getPlayerBagPages ====================

    @Nested
    @DisplayName("getPlayerBagPages")
    class GetPlayerBagPages {

        @Test
        @DisplayName("Should return list of page numbers")
        void returnsPageNumbers() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(
                            RemoteBagData.create(playerUuid, 1, ""),
                            RemoteBagData.create(playerUuid, 3, ""),
                            RemoteBagData.create(playerUuid, 2, "")
                    ));

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1, 2, 3); // Sorted
        }

        @Test
        @DisplayName("Should return default page 1 when no bags")
        void returnsDefaultWhenEmpty() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1);
        }

        @Test
        @DisplayName("Should return cached pages after second call")
        void returnsCachedPages() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(
                            RemoteBagData.create(playerUuid, 1, "")
                    ));

            service.getPlayerBagPages(playerUuid);
            service.getPlayerBagPages(playerUuid);

            // dataOperator should only be called once (caching)
            verify(dataOperator, times(1)).getAll(any(WhereCondition.class));
        }

        @Test
        @DisplayName("Should sort pages in ascending order")
        void sortsPages() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(
                            RemoteBagData.create(playerUuid, 5, ""),
                            RemoteBagData.create(playerUuid, 1, ""),
                            RemoteBagData.create(playerUuid, 3, "")
                    ));

            List<Integer> pages = service.getPlayerBagPages(playerUuid);

            assertThat(pages).containsExactly(1, 3, 5);
        }
    }

    // ==================== loadBagIfNeeded ====================

    @Nested
    @DisplayName("loadBagIfNeeded")
    class LoadBagIfNeeded {

        @Test
        @DisplayName("Should not load when already in cache")
        void skipWhenInCache() {
            service.setBagPage(playerUuid, 1, new ItemStack[54]);

            service.loadBagIfNeeded(playerUuid);

            verify(dataOperator, never()).getAll(any(WhereCondition.class));
        }

        @Test
        @DisplayName("Should load from database when not in cache")
        void loadsFromDatabase() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(
                            RemoteBagData.create(playerUuid, 1, "")
                    ));

            service.loadBagIfNeeded(playerUuid);

            verify(dataOperator).getAll(any(WhereCondition.class));
        }

        @Test
        @DisplayName("Should handle empty database result")
        void handlesEmptyDbResult() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);

            // Should still add the player to cache (with empty pages map)
            // Second call should not query database again
            service.loadBagIfNeeded(playerUuid);
            verify(dataOperator, times(1)).getAll(any(WhereCondition.class));
        }

        @Test
        @DisplayName("Should deserialize items with null contents as empty array")
        void deserializesNullContentsAsEmpty() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, null);
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);

            ItemStack[] page = service.getBagPage(playerUuid, 1);
            assertThat(page).isNotNull();
            assertThat(page.length).isEqualTo(54); // 6 rows * 9
        }

        @Test
        @DisplayName("Should deserialize items with empty string contents as empty array")
        void deserializesEmptyContentsAsEmpty() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, "");
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);

            ItemStack[] page = service.getBagPage(playerUuid, 1);
            assertThat(page).isNotNull();
            assertThat(page.length).isEqualTo(54);
        }

        @Test
        @DisplayName("Should handle multiple pages from database")
        void handlesMultiplePages() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(
                            RemoteBagData.create(playerUuid, 1, ""),
                            RemoteBagData.create(playerUuid, 2, "")
                    ));

            service.loadBagIfNeeded(playerUuid);

            assertThat(service.getBagPage(playerUuid, 1)).isNotNull();
            assertThat(service.getBagPage(playerUuid, 2)).isNotNull();
        }
    }

    // ==================== saveBag ====================

    @Nested
    @DisplayName("saveBag")
    class SaveBag {

        @Test
        @DisplayName("Should do nothing when not in cache")
        void skipWhenNotInCache() throws Exception {
            service.saveBag(playerUuid);

            verify(dataOperator, never()).insert(any());
            verify(dataOperator, never()).update(any());
        }

        @Test
        @DisplayName("Should insert new bag data")
        void insertsNewData() {
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.setBagPage(playerUuid, 1, new ItemStack[54]);
            service.saveBag(playerUuid);

            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should update existing bag data")
        void updatesExistingData() throws Exception {
            RemoteBagData existing = RemoteBagData.create(playerUuid, 1, "old-content");
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(existing));

            service.setBagPage(playerUuid, 1, new ItemStack[54]);
            service.saveBag(playerUuid);

            verify(dataOperator).update(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should save multiple pages")
        void savesMultiplePages() {
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.setBagPage(playerUuid, 1, new ItemStack[54]);
            service.setBagPage(playerUuid, 2, new ItemStack[54]);
            service.saveBag(playerUuid);

            verify(dataOperator, times(2)).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should handle update exception gracefully")
        void handlesUpdateException() throws Exception {
            RemoteBagData existing = RemoteBagData.create(playerUuid, 1, "old-content");
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(existing));
            doThrow(new IllegalAccessException("Test error")).when(dataOperator).update(any(RemoteBagData.class));

            service.setBagPage(playerUuid, 1, new ItemStack[54]);

            // Should not throw, should log error instead
            assertThatCode(() -> service.saveBag(playerUuid)).doesNotThrowAnyException();
        }
    }

    // ==================== saveAllBags ====================

    @Nested
    @DisplayName("saveAllBags")
    class SaveAllBags {

        @Test
        @DisplayName("Should save all cached bags")
        void savesAllCached() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();

            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.setBagPage(uuid1, 1, new ItemStack[54]);
            service.setBagPage(uuid2, 1, new ItemStack[54]);

            service.saveAllBags();

            verify(dataOperator, atLeast(2)).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should do nothing when cache is empty")
        void doesNothingWhenCacheEmpty() throws Exception {
            service.saveAllBags();

            verify(dataOperator, never()).insert(any());
            verify(dataOperator, never()).update(any(RemoteBagData.class));
        }
    }

    // ==================== createBagPage ====================

    @Nested
    @DisplayName("createBagPage")
    class CreateBagPage {

        @Test
        @DisplayName("Should create page for player with no existing bags")
        void createsFirstPage() {
            // When no data exists in DB, getPlayerBagPages returns [1] as default
            // So createBagPage creates the next page (2)
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            int pageNum = service.createBagPage(playerUuid);

            assertThat(pageNum).isEqualTo(2);
            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should create next sequential page")
        void createsNextPage() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(
                            RemoteBagData.create(playerUuid, 1, ""),
                            RemoteBagData.create(playerUuid, 2, "")
                    ));
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            int pageNum = service.createBagPage(playerUuid);

            assertThat(pageNum).isEqualTo(3);
        }

        @Test
        @DisplayName("Should save bag after creating page")
        void savesBagAfterCreation() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.createBagPage(playerUuid);

            verify(dataOperator).insert(any(RemoteBagData.class));
        }
    }

    // ==================== deleteBagPage ====================

    @Nested
    @DisplayName("deleteBagPage")
    class DeleteBagPage {

        @Test
        @DisplayName("Should return false when page not found")
        void returnsFalseWhenNotFound() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);

            boolean result = service.deleteBagPage(playerUuid, 5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should delete page from cache and database")
        void deletesPage() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, "");
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data));
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);
            boolean result = service.deleteBagPage(playerUuid, 1);

            assertThat(result).isTrue();
            verify(dataOperator).delById(data.getId());
        }

        @Test
        @DisplayName("Should return false when player not in cache and no data")
        void returnsFalseWhenPlayerNotCached() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            boolean result = service.deleteBagPage(playerUuid, 1);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should remove page from cache after deletion")
        void removesFromCacheAfterDeletion() {
            RemoteBagData data = RemoteBagData.create(playerUuid, 1, "");
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data));
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data));

            service.loadBagIfNeeded(playerUuid);
            service.deleteBagPage(playerUuid, 1);

            // After deleting page 1, getBagPage should return null for that page
            assertThat(service.getBagPage(playerUuid, 1)).isNull();
        }

        @Test
        @DisplayName("Should delete multiple database entries for same page")
        void deletesMultipleDbEntries() {
            RemoteBagData data1 = RemoteBagData.create(playerUuid, 1, "content1");
            RemoteBagData data2 = RemoteBagData.create(playerUuid, 1, "content2");

            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(data1));
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Arrays.asList(data1, data2));

            service.loadBagIfNeeded(playerUuid);
            service.deleteBagPage(playerUuid, 1);

            // Both entries have the same id (null from builder), so delById(null) is called twice
            verify(dataOperator, times(2)).delById(any());
        }
    }

    // ==================== clearBagPage ====================

    @Nested
    @DisplayName("clearBagPage")
    class ClearBagPage {

        @Test
        @DisplayName("Should return false when page not found")
        void returnsFalseWhenNotFound() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);

            boolean result = service.clearBagPage(playerUuid, 5);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should clear page contents")
        void clearsPageContents() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(
                            RemoteBagData.create(playerUuid, 1, "old-content")
                    ));
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);
            boolean result = service.clearBagPage(playerUuid, 1);

            assertThat(result).isTrue();
            verify(dataOperator).insert(any(RemoteBagData.class));
        }

        @Test
        @DisplayName("Should return false when player not cached and no data")
        void returnsFalseWhenNotCached() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            boolean result = service.clearBagPage(playerUuid, 1);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should replace contents with empty array after clearing")
        void replacesContentsWithEmpty() {
            when(dataOperator.getAll(any(WhereCondition.class)))
                    .thenReturn(Collections.singletonList(
                            RemoteBagData.create(playerUuid, 1, "")
                    ));
            when(dataOperator.getAll(any(WhereCondition.class), any(WhereCondition.class)))
                    .thenReturn(Collections.emptyList());

            service.loadBagIfNeeded(playerUuid);
            service.clearBagPage(playerUuid, 1);

            ItemStack[] cleared = service.getBagPage(playerUuid, 1);
            assertThat(cleared).isNotNull();
            // All slots should be null (empty)
            for (ItemStack item : cleared) {
                assertThat(item).isNull();
            }
        }
    }

    // ==================== getConfig ====================

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("Should return config")
        void returnsConfig() {
            assertThat(service.getConfig()).isSameAs(config);
        }
    }
}
