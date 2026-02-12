package com.ultikits.plugins.menu.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.ultikits.plugins.menu.config.MenuConfig;
import com.ultikits.plugins.menu.model.ButtonDefinition;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.EconomyUtils;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

@DisplayName("CustomMenuGui Tests")
class CustomMenuGuiTest {

    private Player mockPlayer;

    @BeforeEach
    void setUp() {
        mockPlayer = mock(Player.class);
        when(mockPlayer.getName()).thenReturn("TestPlayer");
    }

    /**
     * Access the private static parsePlaceholders method via reflection.
     */
    private String callParsePlaceholders(Player player, String text) throws Exception {
        Method method = CustomMenuGui.class.getDeclaredMethod("parsePlaceholders", Player.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, player, text);
    }

    /**
     * Create a minimal MenuDefinition suitable for constructing a CustomMenuGui.
     */
    private MenuDefinition createMinimalMenu() {
        MenuDefinition menu = new MenuDefinition();
        menu.setFileName("test");
        menu.setTitle("Test Menu");
        menu.setSize(27);
        return menu;
    }

    /**
     * Create a CustomMenuGui instance for testing.
     * The Gui superclass constructor just stores fields (no Bukkit calls).
     */
    private CustomMenuGui createGui(MenuDefinition menu, UltiToolsPlugin plugin, MenuService menuService) {
        return new CustomMenuGui(mockPlayer, plugin, menu, menuService);
    }

    /**
     * Create a mock UltiToolsPlugin with i18n passthrough and optional MenuConfig.
     */
    private UltiToolsPlugin createMockPlugin(MenuConfig config) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(plugin.getConfig(MenuConfig.class)).thenReturn(config);
        return plugin;
    }

    /**
     * Invoke the private handleButtonClick method via reflection.
     */
    private void callHandleButtonClick(CustomMenuGui gui, ButtonDefinition button) throws Exception {
        Method method = CustomMenuGui.class.getDeclaredMethod("handleButtonClick", ButtonDefinition.class);
        method.setAccessible(true);
        method.invoke(gui, button);
    }

    /**
     * Set the private lastClickTime field to simulate debounce state.
     */
    private void setLastClickTime(CustomMenuGui gui, long time) throws Exception {
        Field field = CustomMenuGui.class.getDeclaredField("lastClickTime");
        field.setAccessible(true);
        field.set(gui, time);
    }

    // ==================== Placeholder Parsing Tests ====================

    @Nested
    @DisplayName("Placeholder Parsing Tests")
    class PlaceholderParsingTests {

        @Test
        @DisplayName("Should replace {player} placeholder with player name")
        void shouldReplacePlayerPlaceholder() throws Exception {
            String result = callParsePlaceholders(mockPlayer, "Hello {player}!");
            assertThat(result).isEqualTo("Hello TestPlayer!");
        }

        @Test
        @DisplayName("Should handle multiple {player} occurrences")
        void shouldHandleMultiplePlayerPlaceholders() throws Exception {
            String result = callParsePlaceholders(mockPlayer, "{player}'s menu for {player}");
            assertThat(result).isEqualTo("TestPlayer's menu for TestPlayer");
        }

        @Test
        @DisplayName("Should return empty string for null text")
        void shouldReturnEmptyForNull() throws Exception {
            String result = callParsePlaceholders(mockPlayer, null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return original text when no placeholders present")
        void shouldReturnOriginalWithoutPlaceholders() throws Exception {
            String result = callParsePlaceholders(mockPlayer, "No placeholders here");
            assertThat(result).isEqualTo("No placeholders here");
        }

        @Test
        @DisplayName("Should handle empty text")
        void shouldHandleEmptyText() throws Exception {
            String result = callParsePlaceholders(mockPlayer, "");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle text with only {player}")
        void shouldHandleOnlyPlayer() throws Exception {
            String result = callParsePlaceholders(mockPlayer, "{player}");
            assertThat(result).isEqualTo("TestPlayer");
        }

        @Test
        @DisplayName("Should handle PlaceholderAPI placeholders gracefully")
        void shouldHandlePlaceholderAPIPlaceholders() throws Exception {
            // PlaceholderAPI is on the classpath but not properly initialized in tests,
            // so parsePlaceholders should either process them or leave them unchanged
            String result = callParsePlaceholders(mockPlayer, "Online: %server_online%");
            // Should at minimum not throw and contain some content
            assertThat(result).isNotNull();
            assertThat(result).contains("Online:");
        }
    }

    // ==================== Constructor Tests ====================

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should use default cooldown when config is null")
        void shouldUseDefaultCooldownWhenConfigNull() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();

            CustomMenuGui gui = createGui(menu, plugin, menuService);

            Field cooldownField = CustomMenuGui.class.getDeclaredField("clickCooldownMs");
            cooldownField.setAccessible(true);
            assertThat(cooldownField.getLong(gui)).isEqualTo(200);
        }

        @Test
        @DisplayName("Should use config cooldown when config is provided")
        void shouldUseConfigCooldown() throws Exception {
            MenuConfig config = new MenuConfig("config/config.yml");
            config.setClickCooldownMs(500);
            UltiToolsPlugin plugin = createMockPlugin(config);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();

            CustomMenuGui gui = createGui(menu, plugin, menuService);

            Field cooldownField = CustomMenuGui.class.getDeclaredField("clickCooldownMs");
            cooldownField.setAccessible(true);
            assertThat(cooldownField.getLong(gui)).isEqualTo(500);
        }
    }

    // ==================== onOpen Tests ====================

    @Nested
    @DisplayName("onOpen Tests")
    class OnOpenTests {

        @Test
        @DisplayName("Should return early when buttons map is null")
        void shouldReturnEarlyWhenButtonsNull() {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            // MenuDefinition default buttons is an empty HashMap, not null
            // So set it to a new map and clear it
            menu.getButtons().clear();

            CustomMenuGui gui = createGui(menu, plugin, menuService);
            InventoryOpenEvent event = mock(InventoryOpenEvent.class);

            // Should not throw
            gui.onOpen(event);
            // No icons should be placed (nothing to verify except no exception)
            assertThat(gui.getItems()).isEmpty();
        }

        @Test
        @DisplayName("Should return early when buttons map is empty")
        void shouldReturnEarlyWhenButtonsEmpty() {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();

            CustomMenuGui gui = createGui(menu, plugin, menuService);
            InventoryOpenEvent event = mock(InventoryOpenEvent.class);

            gui.onOpen(event);
            assertThat(gui.getItems()).isEmpty();
        }

        @Test
        @DisplayName("Should skip null button entries")
        void shouldSkipNullButtonEntries() {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            menu.getButtons().put("nullBtn", null);

            CustomMenuGui gui = createGui(menu, plugin, menuService);
            InventoryOpenEvent event = mock(InventoryOpenEvent.class);

            gui.onOpen(event);
            // The null button was skipped, no icons placed
            assertThat(gui.getItems()).isEmpty();
        }
    }

    // ==================== handleButtonClick Debounce Tests ====================

    @Nested
    @DisplayName("Click Debounce Tests")
    class ClickDebounceTests {

        @Test
        @DisplayName("Should ignore click within cooldown period")
        void shouldIgnoreClickWithinCooldown() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            // Set last click to now
            setLastClickTime(gui, System.currentTimeMillis());

            ButtonDefinition button = new ButtonDefinition();
            button.getPlayerCommands().add("test");

            callHandleButtonClick(gui, button);

            // Nothing should happen - no commands executed
            verify(mockPlayer, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("Should allow click after cooldown expires")
        void shouldAllowClickAfterCooldown() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            // Set last click to long ago
            setLastClickTime(gui, 0);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(false);
            button.getPlayerCommands().add("spawn");

            callHandleButtonClick(gui, button);

            verify(mockPlayer).performCommand("spawn");
        }
    }

    // ==================== handleButtonClick Permission Tests ====================

    @Nested
    @DisplayName("Button Permission Tests")
    class ButtonPermissionTests {

        @Test
        @DisplayName("Should deny click when player lacks button permission")
        void shouldDenyWithoutPermission() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPermission("vip.button");
            button.setCloseOnClick(false);
            when(mockPlayer.hasPermission("vip.button")).thenReturn(false);

            callHandleButtonClick(gui, button);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockPlayer, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("没有权限使用此按钮"));
        }

        @Test
        @DisplayName("Should allow click when player has button permission")
        void shouldAllowWithPermission() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPermission("vip.button");
            button.setCloseOnClick(false);
            when(mockPlayer.hasPermission("vip.button")).thenReturn(true);

            callHandleButtonClick(gui, button);

            // No permission error message
            verify(mockPlayer, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should skip permission check when permission is null")
        void shouldSkipWhenPermissionNull() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPermission(null);
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            verify(mockPlayer, never()).hasPermission(anyString());
        }

        @Test
        @DisplayName("Should skip permission check when permission is empty")
        void shouldSkipWhenPermissionEmpty() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPermission("");
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            verify(mockPlayer, never()).hasPermission(anyString());
        }
    }

    // ==================== handleButtonClick Economy Tests ====================

    @Nested
    @DisplayName("Button Economy Tests")
    class ButtonEconomyTests {

        /**
         * Set the private static 'economy' field on EconomyUtils via reflection.
         */
        private void setEconomy(Economy economy) throws Exception {
            Field economyField = EconomyUtils.class.getDeclaredField("economy");
            economyField.setAccessible(true);
            economyField.set(null, economy);
        }

        /**
         * Set the private static 'setupAttempted' field on EconomyUtils via reflection.
         */
        private void setSetupAttempted(boolean value) throws Exception {
            Field field = EconomyUtils.class.getDeclaredField("setupAttempted");
            field.setAccessible(true);
            field.set(null, value);
        }

        @AfterEach
        void resetEconomy() {
            EconomyUtils.reset();
        }

        @Test
        @DisplayName("Should skip economy when price is zero")
        void shouldSkipEconomyWhenFree() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(0);
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            // No economy message sent
            verify(mockPlayer, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should deny when economy is unavailable")
        void shouldDenyWhenEconomyUnavailable() throws Exception {
            // Make isAvailable() return false by setting setupAttempted=true, economy=null
            setSetupAttempted(true);
            setEconomy(null);

            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(100.0);
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockPlayer, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("经济系统不可用"));
        }

        @Test
        @DisplayName("Should deny when player has insufficient balance")
        void shouldDenyInsufficientBalance() throws Exception {
            Economy mockEconomy = mock(Economy.class);
            setEconomy(mockEconomy);
            when(mockEconomy.has(mockPlayer, 100.0)).thenReturn(false);
            when(mockEconomy.format(100.0)).thenReturn("$100.00");

            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(100.0);
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockPlayer, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("余额不足"));
        }

        @Test
        @DisplayName("Should deny when withdrawal fails")
        void shouldDenyWhenWithdrawFails() throws Exception {
            Economy mockEconomy = mock(Economy.class);
            setEconomy(mockEconomy);
            when(mockEconomy.has(mockPlayer, 50.0)).thenReturn(true);
            EconomyResponse failResponse = new EconomyResponse(0, 0,
                EconomyResponse.ResponseType.FAILURE, "error");
            when(mockEconomy.withdrawPlayer(mockPlayer, 50.0)).thenReturn(failResponse);

            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(50.0);
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockPlayer, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("扣款失败"));
        }

        @Test
        @DisplayName("Should charge and confirm when withdrawal succeeds")
        void shouldChargeSuccessfully() throws Exception {
            Economy mockEconomy = mock(Economy.class);
            setEconomy(mockEconomy);
            when(mockEconomy.has(mockPlayer, 50.0)).thenReturn(true);
            EconomyResponse successResponse = new EconomyResponse(50.0, 950.0,
                EconomyResponse.ResponseType.SUCCESS, "");
            when(mockEconomy.withdrawPlayer(mockPlayer, 50.0)).thenReturn(successResponse);
            when(mockEconomy.format(50.0)).thenReturn("$50.00");

            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(50.0);
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockPlayer, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("已扣除"));
        }
    }

    // ==================== handleButtonClick Command Tests ====================

    @Nested
    @DisplayName("Button Command Tests")
    class ButtonCommandTests {

        @Test
        @DisplayName("Should execute player commands with {player} replaced")
        void shouldExecutePlayerCommands() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(false);
            button.getPlayerCommands().add("give {player} diamond 1");
            button.getPlayerCommands().add("spawn");

            callHandleButtonClick(gui, button);

            verify(mockPlayer).performCommand("give TestPlayer diamond 1");
            verify(mockPlayer).performCommand("spawn");
        }

        @Test
        @DisplayName("Should skip player commands when list is empty")
        void shouldSkipEmptyPlayerCommands() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(false);
            // playerCommands is empty by default

            callHandleButtonClick(gui, button);

            verify(mockPlayer, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("Should execute console commands when UltiTools plugin is found")
        void shouldExecuteConsoleCommands() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(false);
            button.getConsoleCommands().add("broadcast {player} joined");

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager mockPM = mock(PluginManager.class);
                Plugin mockUltiTools = mock(Plugin.class);
                BukkitScheduler mockScheduler = mock(BukkitScheduler.class);

                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(mockPM);
                when(mockPM.getPlugin("UltiTools")).thenReturn(mockUltiTools);
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                when(mockScheduler.runTask(any(Plugin.class), any(Runnable.class))).thenReturn(mock(BukkitTask.class));

                callHandleButtonClick(gui, button);

                // Verify scheduler.runTask was called for the console command
                verify(mockScheduler).runTask(eq(mockUltiTools), any(Runnable.class));
            }
        }

        @Test
        @DisplayName("Should skip console commands when UltiTools plugin is not found")
        void shouldSkipConsoleCommandsWhenPluginNotFound() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(false);
            button.getConsoleCommands().add("broadcast hello");

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager mockPM = mock(PluginManager.class);
                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(mockPM);
                when(mockPM.getPlugin("UltiTools")).thenReturn(null);

                callHandleButtonClick(gui, button);

                // No scheduler interaction since plugin wasn't found
                mockedBukkit.verify(() -> Bukkit.getScheduler(), never());
            }
        }
    }

    // ==================== handleButtonClick Sub-Menu Tests ====================

    @Nested
    @DisplayName("Button Sub-Menu Tests")
    class ButtonSubMenuTests {

        @Test
        @DisplayName("Should show error when sub-menu does not exist")
        void shouldShowErrorForMissingSubMenu() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setOpenMenu("nonexistent");
            when(menuService.getMenu("nonexistent")).thenReturn(null);

            try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
                callHandleButtonClick(gui, button);
            }

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockPlayer, atLeastOnce()).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("不存在"));
        }

        @Test
        @DisplayName("Should close inventory and schedule sub-menu when it exists")
        void shouldOpenSubMenu() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            MenuDefinition subMenu = new MenuDefinition();
            subMenu.setFileName("sub");
            subMenu.setTitle("Sub Menu");
            subMenu.setSize(9);

            ButtonDefinition button = new ButtonDefinition();
            button.setOpenMenu("sub");
            when(menuService.getMenu("sub")).thenReturn(subMenu);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager mockPM = mock(PluginManager.class);
                Plugin mockUltiTools = mock(Plugin.class);
                BukkitScheduler mockScheduler = mock(BukkitScheduler.class);

                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(mockPM);
                when(mockPM.getPlugin("UltiTools")).thenReturn(mockUltiTools);
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                when(mockScheduler.runTask(any(Plugin.class), any(Runnable.class))).thenReturn(mock(BukkitTask.class));

                callHandleButtonClick(gui, button);

                verify(mockPlayer).closeInventory();
                verify(mockScheduler).runTask(eq(mockUltiTools), any(Runnable.class));
            }
        }

        @Test
        @DisplayName("Should skip sub-menu scheduling when UltiTools plugin not found")
        void shouldSkipSubMenuWhenPluginNotFound() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            MenuDefinition subMenu = new MenuDefinition();
            subMenu.setFileName("sub");
            subMenu.setTitle("Sub");
            subMenu.setSize(9);

            ButtonDefinition button = new ButtonDefinition();
            button.setOpenMenu("sub");
            when(menuService.getMenu("sub")).thenReturn(subMenu);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager mockPM = mock(PluginManager.class);
                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(mockPM);
                when(mockPM.getPlugin("UltiTools")).thenReturn(null);

                callHandleButtonClick(gui, button);

                verify(mockPlayer).closeInventory();
            }
        }
    }

    // ==================== handleButtonClick Close-on-Click Tests ====================

    @Nested
    @DisplayName("Close On Click Tests")
    class CloseOnClickTests {

        @Test
        @DisplayName("Should close inventory when closeOnClick is true")
        void shouldCloseWhenEnabled() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(true);

            callHandleButtonClick(gui, button);

            verify(mockPlayer).closeInventory();
        }

        @Test
        @DisplayName("Should not close inventory when closeOnClick is false")
        void shouldNotCloseWhenDisabled() throws Exception {
            UltiToolsPlugin plugin = createMockPlugin(null);
            MenuService menuService = mock(MenuService.class);
            MenuDefinition menu = createMinimalMenu();
            CustomMenuGui gui = createGui(menu, plugin, menuService);

            ButtonDefinition button = new ButtonDefinition();
            button.setCloseOnClick(false);

            callHandleButtonClick(gui, button);

            verify(mockPlayer, never()).closeInventory();
        }
    }

    // ==================== Button Click Data Tests ====================

    @Nested
    @DisplayName("Button Click Logic Data Tests")
    class ButtonClickDataTests {

        @Test
        @DisplayName("Should recognize button with price as paid button")
        void shouldRecognizePaidButton() {
            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(100.0);

            assertThat(button.getPrice()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should recognize button with zero price as free")
        void shouldRecognizeFreeButton() {
            ButtonDefinition button = new ButtonDefinition();
            button.setPrice(0);

            assertThat(button.getPrice()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should recognize button with open-menu as navigation button")
        void shouldRecognizeNavigationButton() {
            ButtonDefinition button = new ButtonDefinition();
            button.setOpenMenu("submenu");

            assertThat(button.getOpenMenu()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("Should recognize button with commands")
        void shouldRecognizeCommandButton() {
            ButtonDefinition button = new ButtonDefinition();
            button.getPlayerCommands().add("spawn");

            assertThat(button.getPlayerCommands()).isNotEmpty();
            assertThat(button.getConsoleCommands()).isEmpty();
        }

        @Test
        @DisplayName("Should recognize button with permission requirement")
        void shouldRecognizePermittedButton() {
            ButtonDefinition button = new ButtonDefinition();
            button.setPermission("vip.use");

            assertThat(button.getPermission()).isNotNull().isNotEmpty();
        }
    }

    // ==================== Menu Definition Completeness Tests ====================

    @Nested
    @DisplayName("Menu Definition Completeness Tests")
    class MenuDefinitionCompletenessTests {

        @Test
        @DisplayName("Should build complete menu definition for GUI")
        void shouldBuildCompleteMenu() {
            MenuDefinition menu = new MenuDefinition();
            menu.setFileName("test");
            menu.setSize(27);
            menu.setTitle("&6Test Menu");

            ButtonDefinition btn1 = new ButtonDefinition();
            btn1.setId("btn1");
            btn1.setItem(Material.DIAMOND);
            btn1.setPosition(13);
            btn1.setName("&bDiamond");

            menu.getButtons().put("btn1", btn1);

            assertThat(menu.getFileName()).isEqualTo("test");
            assertThat(menu.getSize()).isEqualTo(27);
            assertThat(menu.getSize() / 9).isEqualTo(3); // Row count for GUI
            assertThat(menu.getButtons()).hasSize(1);
            assertThat(menu.getButtons().get("btn1").getItem()).isEqualTo(Material.DIAMOND);
        }
    }
}
