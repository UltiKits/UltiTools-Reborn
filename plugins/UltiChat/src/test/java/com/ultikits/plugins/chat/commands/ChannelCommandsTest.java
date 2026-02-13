package com.ultikits.plugins.chat.commands;

import com.ultikits.plugins.chat.service.ChannelService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ChannelCommands — list and switch channel commands.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChannelCommands Tests")
class ChannelCommandsTest {

    private UltiToolsPlugin mockPlugin;
    private ChannelService mockChannelService;
    private ChannelCommands commands;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(UltiToolsPlugin.class);
        mockChannelService = mock(ChannelService.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(mockPlugin.i18n("channel_not_found")).thenReturn("Channel '{0}' not found.");
        when(mockPlugin.i18n("channel_switched")).thenReturn("Switched to {0}.");
        when(mockPlugin.i18n("channel_no_permission")).thenReturn("No permission for {0}.");
        when(mockPlugin.i18n("channel_current")).thenReturn("Current: {0}");
        when(mockPlugin.i18n("channel_list_entry")).thenReturn("{0} ({1})");

        commands = new ChannelCommands(mockPlugin, mockChannelService);
    }

    private void assertSentMessageContaining(CommandSender sender, String substring) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(msg -> msg.contains(substring));
    }

    // ==================== List Tests ====================

    @Nested
    @DisplayName("List Command")
    class ListTests {

        @Test
        @DisplayName("Should list available channels")
        void shouldListChannels() {
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);

            when(mockChannelService.getAvailableChannels(player)).thenReturn(Arrays.asList("global", "local"));
            when(mockChannelService.getChannelDisplayName("global")).thenReturn("[Global]");
            when(mockChannelService.getChannelDisplayName("local")).thenReturn("[Local]");
            when(mockChannelService.getPlayerChannel(uuid)).thenReturn("global");

            commands.onList(player);

            // header + 2 entries + current channel = 4 messages
            verify(player, times(4)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show header even when no channels available")
        void shouldShowHeaderWhenEmpty() {
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);
            when(mockChannelService.getAvailableChannels(player)).thenReturn(Collections.<String>emptyList());
            when(mockChannelService.getPlayerChannel(uuid)).thenReturn("global");
            when(mockChannelService.getChannelDisplayName("global")).thenReturn("[Global]");

            commands.onList(player);

            assertSentMessageContaining(player, "channel_list_header");
        }

        @Test
        @DisplayName("Should show current channel")
        void shouldShowCurrentChannel() {
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);
            when(mockChannelService.getAvailableChannels(player)).thenReturn(Arrays.asList("staff"));
            when(mockChannelService.getChannelDisplayName("staff")).thenReturn("[Staff]");
            when(mockChannelService.getPlayerChannel(uuid)).thenReturn("staff");

            commands.onList(player);

            assertSentMessageContaining(player, "Current:");
        }

        @Test
        @DisplayName("Should reject console sender")
        void shouldRejectConsole() {
            CommandSender console = mock(CommandSender.class);

            commands.onList(console);

            verify(mockChannelService, never()).getAvailableChannels(any());
        }
    }

    // ==================== Switch Tests ====================

    @Nested
    @DisplayName("Switch Command")
    class SwitchTests {

        @Test
        @DisplayName("Should switch to valid channel")
        void shouldSwitchToValidChannel() {
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);

            Map<String, Object> channelDef = new HashMap<>();
            channelDef.put("display-name", "&f[Global]");
            when(mockChannelService.getChannelDef("global")).thenReturn(channelDef);
            when(mockChannelService.hasChannelPermission(player, "global")).thenReturn(true);
            when(mockChannelService.getChannelDisplayName("global")).thenReturn("[Global]");

            commands.onSwitch(player, "global");

            verify(mockChannelService).setPlayerChannel(uuid, "global");
            assertSentMessageContaining(player, "Switched to");
        }

        @Test
        @DisplayName("Should send error for nonexistent channel")
        void shouldSendErrorForMissing() {
            Player player = mock(Player.class);

            when(mockChannelService.getChannelDef("nonexistent")).thenReturn(null);

            commands.onSwitch(player, "nonexistent");

            verify(mockChannelService, never()).setPlayerChannel(any(), anyString());
            assertSentMessageContaining(player, "nonexistent");
        }

        @Test
        @DisplayName("Should send error when no permission for channel")
        void shouldSendErrorWhenNoPermission() {
            Player player = mock(Player.class);

            Map<String, Object> channelDef = new HashMap<>();
            channelDef.put("permission", "ultichat.channel.staff");
            when(mockChannelService.getChannelDef("staff")).thenReturn(channelDef);
            when(mockChannelService.hasChannelPermission(player, "staff")).thenReturn(false);

            commands.onSwitch(player, "staff");

            verify(mockChannelService, never()).setPlayerChannel(any(), anyString());
            assertSentMessageContaining(player, "No permission");
        }

        @Test
        @DisplayName("Should skip 'list' as channel name")
        void shouldSkipListName() {
            Player player = mock(Player.class);

            commands.onSwitch(player, "list");

            verify(mockChannelService, never()).getChannelDef(anyString());
        }

        @Test
        @DisplayName("Should skip 'LIST' case-insensitively")
        void shouldSkipListCaseInsensitive() {
            Player player = mock(Player.class);

            commands.onSwitch(player, "LIST");

            verify(mockChannelService, never()).getChannelDef(anyString());
        }

        @Test
        @DisplayName("Should reject console sender")
        void shouldRejectConsole() {
            CommandSender console = mock(CommandSender.class);

            commands.onSwitch(console, "global");

            verify(mockChannelService, never()).setPlayerChannel(any(), anyString());
        }
    }

    // ==================== Help Tests ====================

    @Nested
    @DisplayName("Help Command")
    class HelpTests {

        @Test
        @DisplayName("Should display help message")
        void shouldDisplayHelp() {
            CommandSender sender = mock(CommandSender.class);

            commands.handleHelp(sender);

            verify(sender, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should mention list and switch in help")
        void shouldMentionCommands() {
            CommandSender sender = mock(CommandSender.class);

            commands.handleHelp(sender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(sender, atLeast(1)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("list"));
        }
    }

    // ==================== Tab Completion Tests ====================

    @Nested
    @DisplayName("Tab Completion")
    class TabCompletionTests {

        @Test
        @DisplayName("Should return available channel names")
        void shouldReturnChannelNames() throws Exception {
            Player player = mock(Player.class);
            when(mockChannelService.getAvailableChannels(player)).thenReturn(Arrays.asList("global", "local", "staff"));

            Method method = ChannelCommands.class.getDeclaredMethod("getName", Player.class);
            method.setAccessible(true); // NOPMD

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(commands, player);

            assertThat(result).containsExactly("global", "local", "staff");
        }

        @Test
        @DisplayName("Should return empty list when no channels available")
        void shouldReturnEmptyList() throws Exception {
            Player player = mock(Player.class);
            when(mockChannelService.getAvailableChannels(player)).thenReturn(Collections.<String>emptyList());

            Method method = ChannelCommands.class.getDeclaredMethod("getName", Player.class);
            method.setAccessible(true); // NOPMD

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(commands, player);

            assertThat(result).isEmpty();
        }
    }
}
