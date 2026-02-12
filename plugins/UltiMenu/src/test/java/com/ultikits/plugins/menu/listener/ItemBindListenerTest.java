package com.ultikits.plugins.menu.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;

import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("ItemBindListener Tests")
class ItemBindListenerTest {

    private UltiToolsPlugin mockPlugin;
    private MenuService mockMenuService;
    private ItemBindListener listener;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(UltiToolsPlugin.class);
        mockMenuService = mock(MenuService.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        listener = new ItemBindListener(mockPlugin, mockMenuService);
    }

    private PlayerInteractEvent createEvent(Action action, Player player) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(action);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private Player createPlayerWithMainHandItem(Material material, String displayName, java.util.List<String> lore) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);

        when(player.getName()).thenReturn("TestPlayer");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(item);
        when(inventory.getItemInOffHand()).thenReturn(new ItemStack(Material.AIR));
        when(item.getType()).thenReturn(material);
        when(item.getItemMeta()).thenReturn(meta);

        if (displayName != null) {
            when(meta.hasDisplayName()).thenReturn(true);
            when(meta.getDisplayName()).thenReturn(displayName);
        } else {
            when(meta.hasDisplayName()).thenReturn(false);
        }

        if (lore != null) {
            when(meta.hasLore()).thenReturn(true);
            when(meta.getLore()).thenReturn(lore);
        } else {
            when(meta.hasLore()).thenReturn(false);
        }

        return player;
    }

    private MenuDefinition createBoundMenu(Material bindItem, String bindName, String bindLore) {
        MenuDefinition menu = new MenuDefinition();
        menu.setFileName("testmenu");
        menu.setBindItem(bindItem);
        menu.setBindName(bindName);
        menu.setBindLore(bindLore);
        return menu;
    }

    /**
     * Invoke listener and catch expected NullPointerException from
     * obliviate-invs InventoryAPI not being initialized in test environment.
     * We verify event.setCancelled(true) was called BEFORE the exception.
     */
    private void invokeAndCatchGuiError(PlayerInteractEvent event) {
        try {
            listener.onPlayerInteract(event);
        } catch (NullPointerException e) {
            // Expected: obliviate-invs InventoryAPI not initialized in tests
            // The event was already matched and cancelled before GUI open fails
        }
    }

    // ==================== Action Filtering Tests ====================

    @Nested
    @DisplayName("Action Filtering Tests")
    class ActionFilteringTests {

        @Test
        @DisplayName("Should ignore left-click air events")
        void shouldIgnoreLeftClickAir() {
            Player player = mock(Player.class);
            PlayerInteractEvent event = createEvent(Action.LEFT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should ignore left-click block events")
        void shouldIgnoreLeftClickBlock() {
            Player player = mock(Player.class);
            PlayerInteractEvent event = createEvent(Action.LEFT_CLICK_BLOCK, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should ignore physical events")
        void shouldIgnorePhysical() {
            Player player = mock(Player.class);
            PlayerInteractEvent event = createEvent(Action.PHYSICAL, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should process right-click air events and cancel on match")
        void shouldProcessRightClickAir() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should process right-click block events and cancel on match")
        void shouldProcessRightClickBlock() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_BLOCK, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }
    }

    // ==================== Material Matching Tests ====================

    @Nested
    @DisplayName("Material Matching Tests")
    class MaterialMatchingTests {

        @Test
        @DisplayName("Should match when material equals bind-item")
        void shouldMatchMaterial() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should not match when material differs from bind-item")
        void shouldNotMatchDifferentMaterial() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.DIAMOND, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should skip menu with no bind-item")
        void shouldSkipNoBind() {
            MenuDefinition menu = new MenuDefinition();
            menu.setFileName("nobind");
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }
    }

    // ==================== Display Name Matching Tests ====================

    @Nested
    @DisplayName("Display Name Matching Tests")
    class DisplayNameMatchingTests {

        @Test
        @DisplayName("Should match display name after color normalization")
        void shouldMatchWithColorCodes() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, "&6My Compass", null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            String coloredName = ChatColor.translateAlternateColorCodes('&', "&6My Compass");
            Player player = createPlayerWithMainHandItem(Material.COMPASS, coloredName, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should not match when display name differs")
        void shouldNotMatchDifferentName() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, "&6My Compass", null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, "Different Name", null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should not match when item has no display name but menu requires one")
        void shouldNotMatchMissingName() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, "&6My Compass", null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }
    }

    // ==================== Lore Matching Tests ====================

    @Nested
    @DisplayName("Lore Matching Tests")
    class LoreMatchingTests {

        @Test
        @DisplayName("Should match when lore contains expected text")
        void shouldMatchLore() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, "Right click");
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null,
                Arrays.asList("Some text", "Right click to open"));
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should not match when lore does not contain expected text")
        void shouldNotMatchDifferentLore() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, "Right click");
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null,
                Arrays.asList("Some other text", "Nothing matches"));
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("Should not match when item has no lore but menu requires it")
        void shouldNotMatchMissingLore() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, "Right click");
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event, never()).setCancelled(true);
        }
    }

    // ==================== Permission Tests ====================

    @Nested
    @DisplayName("Permission Tests")
    class PermissionTests {

        @Test
        @DisplayName("Should deny access when player lacks menu permission")
        void shouldDenyWithoutPermission() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            menu.setPermission("vip.menu");
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            when(player.hasPermission("vip.menu")).thenReturn(false);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            listener.onPlayerInteract(event);

            verify(event).setCancelled(true);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("没有权限"));
        }

        @Test
        @DisplayName("Should allow access when player has menu permission")
        void shouldAllowWithPermission() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            menu.setPermission("vip.menu");
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            when(player.hasPermission("vip.menu")).thenReturn(true);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should allow access when menu has no permission requirement")
        void shouldAllowWithNoPermission() {
            MenuDefinition menu = createBoundMenu(Material.COMPASS, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = createPlayerWithMainHandItem(Material.COMPASS, null, null);
            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
            verify(player, never()).sendMessage(anyString());
        }
    }

    // ==================== Off-hand Priority Tests ====================

    @Nested
    @DisplayName("Hand Priority Tests")
    class HandPriorityTests {

        @Test
        @DisplayName("Should check main hand before off hand")
        void shouldCheckMainHandFirst() {
            MenuDefinition compassMenu = createBoundMenu(Material.COMPASS, null, null);
            MenuDefinition clockMenu = createBoundMenu(Material.CLOCK, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Arrays.asList(compassMenu, clockMenu));

            Player player = mock(Player.class);
            when(player.getName()).thenReturn("TestPlayer");
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);

            ItemStack mainItem = mock(ItemStack.class);
            when(mainItem.getType()).thenReturn(Material.COMPASS);
            when(mainItem.getItemMeta()).thenReturn(null);
            when(inventory.getItemInMainHand()).thenReturn(mainItem);

            ItemStack offItem = mock(ItemStack.class);
            when(offItem.getType()).thenReturn(Material.CLOCK);
            when(offItem.getItemMeta()).thenReturn(null);
            when(inventory.getItemInOffHand()).thenReturn(offItem);

            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("Should fall through to off-hand when main hand does not match")
        void shouldFallThroughToOffHand() {
            MenuDefinition menu = createBoundMenu(Material.CLOCK, null, null);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.singletonList(menu));

            Player player = mock(Player.class);
            when(player.getName()).thenReturn("TestPlayer");
            PlayerInventory inventory = mock(PlayerInventory.class);
            when(player.getInventory()).thenReturn(inventory);

            ItemStack mainItem = mock(ItemStack.class);
            when(mainItem.getType()).thenReturn(Material.DIAMOND);
            when(mainItem.getItemMeta()).thenReturn(null);
            when(inventory.getItemInMainHand()).thenReturn(mainItem);

            ItemStack offItem = mock(ItemStack.class);
            when(offItem.getType()).thenReturn(Material.CLOCK);
            when(offItem.getItemMeta()).thenReturn(null);
            when(inventory.getItemInOffHand()).thenReturn(offItem);

            PlayerInteractEvent event = createEvent(Action.RIGHT_CLICK_AIR, player);

            invokeAndCatchGuiError(event);

            verify(event).setCancelled(true);
        }
    }

    // ==================== Normalize Text Tests ====================

    @Nested
    @DisplayName("Text Normalization Tests")
    class NormalizeTextTests {

        @Test
        @DisplayName("Should normalize null text to empty string")
        void shouldNormalizeNullToEmpty() throws Exception {
            java.lang.reflect.Method method = ItemBindListener.class.getDeclaredMethod("normalizeText", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(listener, (Object) null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should strip color codes during normalization")
        void shouldStripColorCodes() throws Exception {
            java.lang.reflect.Method method = ItemBindListener.class.getDeclaredMethod("normalizeText", String.class);
            method.setAccessible(true);

            String colored = ChatColor.translateAlternateColorCodes('&', "&6&lMy Item");
            String result = (String) method.invoke(listener, colored);
            assertThat(result).isEqualTo("My Item");
        }

        @Test
        @DisplayName("Should handle plain text without color codes")
        void shouldHandlePlainText() throws Exception {
            java.lang.reflect.Method method = ItemBindListener.class.getDeclaredMethod("normalizeText", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(listener, "Plain text");
            assertThat(result).isEqualTo("Plain text");
        }
    }
}
