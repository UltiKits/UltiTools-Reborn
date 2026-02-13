package com.ultikits.plugins.sidebar.commands;

import com.ultikits.plugins.sidebar.UltiSideBar;
import com.ultikits.plugins.sidebar.UltiSideBarTestHelper;
import com.ultikits.plugins.sidebar.service.SideBarService;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("SideBarCommand Tests")
class SideBarCommandTest {

    private SideBarCommand command;
    private SideBarService service;
    private Player player;

    @BeforeEach
    void setUp() throws Exception {
        UltiSideBarTestHelper.setUp();

        service = mock(SideBarService.class);
        command = new SideBarCommand();

        // Inject dependencies via reflection
        UltiSideBarTestHelper.setField(command, "plugin", UltiSideBarTestHelper.getMockPlugin());
        UltiSideBarTestHelper.setField(command, "sideBarService", service);

        player = UltiSideBarTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiSideBarTestHelper.tearDown();
    }

    // ==================== toggle ====================

    @Nested
    @DisplayName("toggle")
    class Toggle {

        @Test
        @DisplayName("Should send on message when toggled on")
        void toggleOn() {
            when(service.toggleSidebar(player)).thenReturn(true);

            command.toggle(player);

            verify(service).toggleSidebar(player);
            verify(player).sendMessage("sidebar_toggle_on");
        }

        @Test
        @DisplayName("Should send off message when toggled off")
        void toggleOff() {
            when(service.toggleSidebar(player)).thenReturn(false);

            command.toggle(player);

            verify(service).toggleSidebar(player);
            verify(player).sendMessage("sidebar_toggle_off");
        }
    }

    // ==================== on ====================

    @Nested
    @DisplayName("on")
    class On {

        @Test
        @DisplayName("Should enable sidebar and send message")
        void enablesSidebar() {
            command.on(player);

            verify(service).enableSidebar(player);
            verify(player).sendMessage("sidebar_toggle_on");
        }
    }

    // ==================== off ====================

    @Nested
    @DisplayName("off")
    class Off {

        @Test
        @DisplayName("Should disable sidebar and send message")
        void disablesSidebar() {
            command.off(player);

            verify(service).disableSidebar(player);
            verify(player).sendMessage("sidebar_toggle_off");
        }
    }

    // ==================== reload ====================

    @Nested
    @DisplayName("reload")
    class Reload {

        @Test
        @DisplayName("Should reload plugin and send message")
        void reloadsPlugin() {
            CommandSender sender = mock(CommandSender.class);
            UltiSideBar plugin = UltiSideBarTestHelper.getMockPlugin();

            command.reload(sender);

            verify(plugin).reloadSelf();
            verify(sender).sendMessage("sidebar_reloaded");
        }
    }

    // ==================== help ====================

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("Should send basic help messages")
        void sendsBasicHelp() {
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(false);

            command.help(sender);

            verify(sender).sendMessage("sidebar_help_title");
            verify(sender).sendMessage("sidebar_help_toggle");
            verify(sender).sendMessage("sidebar_help_on");
            verify(sender).sendMessage("sidebar_help_off");
            verify(sender, never()).sendMessage("sidebar_help_reload");
        }

        @Test
        @DisplayName("Should send admin help when has permission")
        void sendsAdminHelp() {
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(true);

            command.help(sender);

            verify(sender).sendMessage("sidebar_help_title");
            verify(sender).sendMessage("sidebar_help_toggle");
            verify(sender).sendMessage("sidebar_help_on");
            verify(sender).sendMessage("sidebar_help_off");
            verify(sender).sendMessage("sidebar_help_reload");
        }
    }

    // ==================== handleHelp ====================

    @Nested
    @DisplayName("handleHelp")
    class HandleHelp {

        @Test
        @DisplayName("Should delegate to help method")
        void delegatesToHelp() {
            SideBarCommand spyCommand = spy(command);
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(anyString())).thenReturn(false);

            spyCommand.handleHelp(sender);

            verify(spyCommand).help(sender);
        }
    }

    // ==================== suggest ====================

    @Nested
    @DisplayName("suggest")
    class Suggest {

        private Command mockCommand;

        @BeforeEach
        void setUpCommand() {
            mockCommand = mock(Command.class);
        }

        @Test
        @DisplayName("Should suggest all commands for empty input")
        void suggestsAll() {
            Player sender = mock(Player.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(false);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{""});

            assertThat(suggestions).containsExactlyInAnyOrder("toggle", "on", "off");
        }

        @Test
        @DisplayName("Should suggest reload for admin")
        void suggestsReloadForAdmin() {
            Player sender = mock(Player.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(true);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{""});

            assertThat(suggestions).containsExactlyInAnyOrder("toggle", "on", "off", "reload");
        }

        @Test
        @DisplayName("Should filter suggestions by input")
        void filtersbyInput() {
            Player sender = mock(Player.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(false);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{"to"});

            assertThat(suggestions).containsExactly("toggle");
        }

        @Test
        @DisplayName("Should handle case insensitive filtering")
        void caseInsensitiveFiltering() {
            Player sender = mock(Player.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(false);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{"TO"});

            assertThat(suggestions).containsExactly("toggle");
        }

        @Test
        @DisplayName("Should return empty for second argument")
        void emptyForSecondArg() {
            Player sender = mock(Player.class);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{"toggle", ""});

            assertThat(suggestions).isEmpty();
        }

        @Test
        @DisplayName("Should filter reload when no permission")
        void noReloadWithoutPermission() {
            Player sender = mock(Player.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(false);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{"re"});

            assertThat(suggestions).isEmpty();
        }

        @Test
        @DisplayName("Should suggest reload when has permission")
        void reloadWithPermission() {
            Player sender = mock(Player.class);
            when(sender.hasPermission("ultisidebar.admin")).thenReturn(true);

            List<String> suggestions = command.suggest(sender, mockCommand, new String[]{"re"});

            assertThat(suggestions).containsExactly("reload");
        }
    }
}
