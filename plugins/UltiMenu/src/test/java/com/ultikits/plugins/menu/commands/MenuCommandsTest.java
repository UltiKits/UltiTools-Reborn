package com.ultikits.plugins.menu.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.*;

import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("MenuCommands Tests")
class MenuCommandsTest {

    private UltiToolsPlugin mockPlugin;
    private MenuService mockMenuService;
    private MenuCommands commands;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(UltiToolsPlugin.class);
        mockMenuService = mock(MenuService.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        commands = new MenuCommands(mockPlugin, mockMenuService);
    }

    /**
     * Captures all sendMessage(String) calls and asserts at least one contains the substring.
     */
    private void assertSentMessageContaining(CommandSender sender, String substring) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues())
            .anyMatch(msg -> msg.contains(substring));
    }

    /**
     * Asserts that no sendMessage call contains the substring.
     */
    private void assertNoMessageContaining(CommandSender sender, String substring) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        // Use atLeast(0) to not fail if there were no calls
        verify(sender, atLeast(0)).sendMessage(captor.capture());
        assertThat(captor.getAllValues())
            .noneMatch(msg -> msg.contains(substring));
    }

    // ==================== Quick Open Tests ====================

    @Nested
    @DisplayName("Quick Open Tests (/menu <name>)")
    class QuickOpenTests {

        @Test
        @DisplayName("Should skip 'list' as quick-open name")
        void shouldSkipListName() {
            Player player = mock(Player.class);

            commands.onQuickOpen(player, "list");

            verify(mockMenuService, never()).getMenu(anyString());
        }

        @Test
        @DisplayName("Should skip 'reload' as quick-open name")
        void shouldSkipReloadName() {
            Player player = mock(Player.class);

            commands.onQuickOpen(player, "reload");

            verify(mockMenuService, never()).getMenu(anyString());
        }

        @Test
        @DisplayName("Should skip 'LIST' case-insensitively")
        void shouldSkipListCaseInsensitive() {
            Player player = mock(Player.class);

            commands.onQuickOpen(player, "LIST");

            verify(mockMenuService, never()).getMenu(anyString());
        }

        @Test
        @DisplayName("Should send error for nonexistent menu")
        void shouldSendErrorForMissing() {
            Player player = mock(Player.class);
            when(mockMenuService.getMenu("nonexistent")).thenReturn(null);

            commands.onQuickOpen(player, "nonexistent");

            assertSentMessageContaining(player, "不存在");
        }
    }

    // ==================== Open Tests ====================

    @Nested
    @DisplayName("Open Tests (/menu open <name>)")
    class OpenTests {

        @Test
        @DisplayName("Should send error for nonexistent menu")
        void shouldSendErrorForMissing() {
            Player player = mock(Player.class);
            when(mockMenuService.getMenu("missing")).thenReturn(null);

            commands.onOpen(player, "missing");

            assertSentMessageContaining(player, "不存在");
        }

        @Test
        @DisplayName("Should check menu permission before opening")
        void shouldCheckPermission() {
            Player player = mock(Player.class);
            MenuDefinition menu = new MenuDefinition();
            menu.setPermission("vip.menu");
            when(mockMenuService.getMenu("vip")).thenReturn(menu);
            when(player.hasPermission("vip.menu")).thenReturn(false);

            commands.onOpen(player, "vip");

            assertSentMessageContaining(player, "没有权限");
        }
    }

    // ==================== List Tests ====================

    @Nested
    @DisplayName("List Tests (/menu list)")
    class ListTests {

        @Test
        @DisplayName("Should show empty message when no menus")
        void shouldShowEmptyMessage() {
            CommandSender sender = mock(CommandSender.class);
            when(mockMenuService.getAllMenus()).thenReturn(Collections.emptyList());

            commands.onList(sender);

            assertSentMessageContaining(sender, "没有可用的菜单");
        }

        @Test
        @DisplayName("Should list all available menus")
        void shouldListAllMenus() {
            CommandSender sender = mock(CommandSender.class);
            MenuDefinition menu1 = new MenuDefinition();
            menu1.setFileName("spawn");
            menu1.setTitle("&6Spawn Menu");
            MenuDefinition menu2 = new MenuDefinition();
            menu2.setFileName("shop");
            menu2.setTitle("&aShop");

            when(mockMenuService.getAllMenus()).thenReturn(Arrays.asList(menu1, menu2));

            commands.onList(sender);

            // Header + 2 menu lines = 3 messages
            verify(sender, times(3)).sendMessage(anyString());
        }
    }

    // ==================== Reload Tests ====================

    @Nested
    @DisplayName("Reload Tests (/menu reload)")
    class ReloadTests {

        @Test
        @DisplayName("Should require admin permission for reload")
        void shouldRequireAdminPermission() {
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission("ultikits.menu.admin")).thenReturn(false);

            commands.onReload(sender);

            verify(mockMenuService, never()).reload();
            assertSentMessageContaining(sender, "没有权限");
        }

        @Test
        @DisplayName("Should reload and report count when has permission")
        void shouldReloadWhenPermitted() {
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission("ultikits.menu.admin")).thenReturn(true);

            MenuDefinition m1 = new MenuDefinition();
            MenuDefinition m2 = new MenuDefinition();
            when(mockMenuService.getAllMenus()).thenReturn(Arrays.asList(m1, m2));

            commands.onReload(sender);

            verify(mockMenuService).reload();
            assertSentMessageContaining(sender, "2");
        }
    }

    // ==================== Help Tests ====================

    @Nested
    @DisplayName("Help Tests")
    class HelpTests {

        @Test
        @DisplayName("Should display help message")
        void shouldDisplayHelp() {
            CommandSender sender = mock(CommandSender.class);

            commands.handleHelp(sender);

            // At least 4 help lines (header + 4 commands)
            verify(sender, atLeast(4)).sendMessage(anyString());
        }
    }

    // ==================== Tab Completion Tests ====================

    @Nested
    @DisplayName("Tab Completion Tests")
    class TabCompletionTests {

        @Test
        @DisplayName("Should return menu names for tab completion")
        void shouldReturnMenuNames() throws Exception {
            Player player = mock(Player.class);
            when(mockMenuService.getMenuNames()).thenReturn(Arrays.asList("spawn", "shop", "rules"));

            // Access private method via reflection
            Method method = MenuCommands.class.getDeclaredMethod("suggestMenuNames", Player.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(commands, player);

            assertThat(result).containsExactly("spawn", "shop", "rules");
        }
    }
}
