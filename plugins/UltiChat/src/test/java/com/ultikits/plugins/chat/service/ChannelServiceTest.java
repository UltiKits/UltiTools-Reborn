package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.ChannelConfig;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelService Tests")
class ChannelServiceTest {

    private ChannelConfig config;
    private ChannelService service;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();

        config = mock(ChannelConfig.class);
        lenient().when(config.getDefaultChannel()).thenReturn("global");
        lenient().when(config.getChannels()).thenReturn(createDefaultChannels());

        service = new ChannelService();
        ChatTestHelper.setField(service, "config", config);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    private Map<String, Map<String, Object>> createDefaultChannels() {
        Map<String, Map<String, Object>> channels = new HashMap<>();

        Map<String, Object> global = new HashMap<>();
        global.put("display-name", "&f[Global]");
        global.put("format", "{display}&f: {message}");
        global.put("permission", "");
        global.put("range", -1);
        global.put("cross-world", true);
        channels.put("global", global);

        Map<String, Object> local = new HashMap<>();
        local.put("display-name", "&a[Local]");
        local.put("format", "{display}&7: {message}");
        local.put("permission", "");
        local.put("range", 100);
        local.put("cross-world", false);
        channels.put("local", local);

        Map<String, Object> staff = new HashMap<>();
        staff.put("display-name", "&c[Staff]");
        staff.put("format", "&c[Staff] &f{player}&7: {message}");
        staff.put("permission", "ultichat.channel.staff");
        staff.put("range", -1);
        staff.put("cross-world", true);
        channels.put("staff", staff);

        return channels;
    }

    // ==================== getPlayerChannel Tests ====================

    @Nested
    @DisplayName("getPlayerChannel Tests")
    class GetPlayerChannelTests {

        @Test
        @DisplayName("Should return default channel for unassigned player")
        void shouldReturnDefaultForUnassigned() {
            UUID playerId = UUID.randomUUID();
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("global");
        }

        @Test
        @DisplayName("Should return assigned channel for player")
        void shouldReturnAssignedChannel() {
            UUID playerId = UUID.randomUUID();
            service.setPlayerChannel(playerId, "staff");
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("staff");
        }

        @Test
        @DisplayName("Should return default after player removed")
        void shouldReturnDefaultAfterRemoval() {
            UUID playerId = UUID.randomUUID();
            service.setPlayerChannel(playerId, "staff");
            service.removePlayer(playerId);
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("global");
        }
    }

    // ==================== setPlayerChannel Tests ====================

    @Nested
    @DisplayName("setPlayerChannel Tests")
    class SetPlayerChannelTests {

        @Test
        @DisplayName("Should update channel assignment")
        void shouldUpdateChannel() {
            UUID playerId = UUID.randomUUID();
            service.setPlayerChannel(playerId, "local");
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("local");

            service.setPlayerChannel(playerId, "staff");
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("staff");
        }

        @Test
        @DisplayName("Should handle multiple players independently")
        void shouldHandleMultiplePlayers() {
            UUID player1 = UUID.randomUUID();
            UUID player2 = UUID.randomUUID();

            service.setPlayerChannel(player1, "local");
            service.setPlayerChannel(player2, "staff");

            assertThat(service.getPlayerChannel(player1)).isEqualTo("local");
            assertThat(service.getPlayerChannel(player2)).isEqualTo("staff");
        }
    }

    // ==================== getChannelDef Tests ====================

    @Nested
    @DisplayName("getChannelDef Tests")
    class GetChannelDefTests {

        @Test
        @DisplayName("Should return channel definition for existing channel")
        void shouldReturnDefForExisting() {
            Map<String, Object> def = service.getChannelDef("global");
            assertThat(def).isNotNull();
            assertThat(def.get("display-name")).isEqualTo("&f[Global]");
        }

        @Test
        @DisplayName("Should return null for non-existent channel")
        void shouldReturnNullForNonExistent() {
            assertThat(service.getChannelDef("nonexistent")).isNull();
        }

        @Test
        @DisplayName("Should return null for null channel name")
        void shouldReturnNullForNullName() {
            assertThat(service.getChannelDef(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when channels map is null")
        void shouldReturnNullWhenChannelsNull() {
            when(config.getChannels()).thenReturn(null);
            assertThat(service.getChannelDef("global")).isNull();
        }
    }

    // ==================== getChannelDisplayName Tests ====================

    @Nested
    @DisplayName("getChannelDisplayName Tests")
    class GetChannelDisplayNameTests {

        @Test
        @DisplayName("Should return colorized display name")
        void shouldReturnColorizedName() {
            String name = service.getChannelDisplayName("global");
            assertThat(name).contains("[Global]");
        }

        @Test
        @DisplayName("Should return channel name for unknown channel")
        void shouldReturnChannelNameForUnknown() {
            assertThat(service.getChannelDisplayName("unknown")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("Should return channel name when display-name is null")
        void shouldReturnChannelNameWhenDisplayNull() {
            Map<String, Map<String, Object>> channels = createDefaultChannels();
            channels.get("global").remove("display-name");
            when(config.getChannels()).thenReturn(channels);

            assertThat(service.getChannelDisplayName("global")).isEqualTo("global");
        }
    }

    // ==================== getChannelFormat Tests ====================

    @Nested
    @DisplayName("getChannelFormat Tests")
    class GetChannelFormatTests {

        @Test
        @DisplayName("Should return configured format")
        void shouldReturnConfiguredFormat() {
            assertThat(service.getChannelFormat("global")).isEqualTo("{display}&f: {message}");
        }

        @Test
        @DisplayName("Should return default format for unknown channel")
        void shouldReturnDefaultForUnknown() {
            assertThat(service.getChannelFormat("unknown")).isEqualTo("{player}: {message}");
        }

        @Test
        @DisplayName("Should return default format when format is null")
        void shouldReturnDefaultWhenFormatNull() {
            Map<String, Map<String, Object>> channels = createDefaultChannels();
            channels.get("global").remove("format");
            when(config.getChannels()).thenReturn(channels);

            assertThat(service.getChannelFormat("global")).isEqualTo("{player}: {message}");
        }
    }

    // ==================== hasChannelPermission Tests ====================

    @Nested
    @DisplayName("hasChannelPermission Tests")
    class HasChannelPermissionTests {

        @Test
        @DisplayName("Should return true when permission is empty")
        void shouldReturnTrueForEmptyPermission() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            assertThat(service.hasChannelPermission(player, "global")).isTrue();
        }

        @Test
        @DisplayName("Should return true when player has permission")
        void shouldReturnTrueWhenHasPermission() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            when(player.hasPermission("ultichat.channel.staff")).thenReturn(true);
            assertThat(service.hasChannelPermission(player, "staff")).isTrue();
        }

        @Test
        @DisplayName("Should return false when player lacks permission")
        void shouldReturnFalseWhenLacksPermission() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            when(player.hasPermission("ultichat.channel.staff")).thenReturn(false);
            assertThat(service.hasChannelPermission(player, "staff")).isFalse();
        }

        @Test
        @DisplayName("Should return false for unknown channel")
        void shouldReturnFalseForUnknown() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            assertThat(service.hasChannelPermission(player, "nonexistent")).isFalse();
        }

        @Test
        @DisplayName("Should return true when permission field is null")
        void shouldReturnTrueWhenPermissionNull() {
            Map<String, Map<String, Object>> channels = createDefaultChannels();
            channels.get("global").remove("permission");
            when(config.getChannels()).thenReturn(channels);

            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            assertThat(service.hasChannelPermission(player, "global")).isTrue();
        }
    }

    // ==================== getAvailableChannels Tests ====================

    @Nested
    @DisplayName("getAvailableChannels Tests")
    class GetAvailableChannelsTests {

        @Test
        @DisplayName("Should return channels with empty permission for any player")
        void shouldReturnChannelsWithEmptyPermission() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            List<String> available = service.getAvailableChannels(player);
            assertThat(available).contains("global", "local");
        }

        @Test
        @DisplayName("Should include permission-gated channel when player has perm")
        void shouldIncludeWhenHasPermission() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            when(player.hasPermission("ultichat.channel.staff")).thenReturn(true);
            List<String> available = service.getAvailableChannels(player);
            assertThat(available).contains("global", "local", "staff");
        }

        @Test
        @DisplayName("Should exclude permission-gated channel when player lacks perm")
        void shouldExcludeWhenLacksPermission() {
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            List<String> available = service.getAvailableChannels(player);
            assertThat(available).doesNotContain("staff");
        }

        @Test
        @DisplayName("Should return empty list when channels map is null")
        void shouldReturnEmptyWhenChannelsNull() {
            when(config.getChannels()).thenReturn(null);
            Player player = ChatTestHelper.createMockPlayer("Test", UUID.randomUUID());
            assertThat(service.getAvailableChannels(player)).isEmpty();
        }
    }

    // ==================== removePlayer Tests ====================

    @Nested
    @DisplayName("removePlayer Tests")
    class RemovePlayerTests {

        @Test
        @DisplayName("Should remove player channel assignment")
        void shouldRemoveAssignment() {
            UUID playerId = UUID.randomUUID();
            service.setPlayerChannel(playerId, "staff");
            service.removePlayer(playerId);
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("global");
        }

        @Test
        @DisplayName("Should handle removing non-existent player gracefully")
        void shouldHandleRemovingNonExistent() {
            UUID playerId = UUID.randomUUID();
            service.removePlayer(playerId);
            assertThat(service.getPlayerChannel(playerId)).isEqualTo("global");
        }
    }

    // ==================== filterRecipients Tests ====================

    @Nested
    @DisplayName("filterRecipients Tests")
    class FilterRecipientsTests {

        private World world;
        private World otherWorld;

        @BeforeEach
        void setUpWorlds() {
            world = ChatTestHelper.createMockWorld("world");
            otherWorld = ChatTestHelper.createMockWorld("nether");
        }

        @Test
        @DisplayName("Should include recipients in same channel")
        void shouldIncludeSameChannel() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), world, 10, 64, 10);

            service.setPlayerChannel(sender.getUniqueId(), "global");
            service.setPlayerChannel(recipient.getUniqueId(), "global");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).contains(recipient);
        }

        @Test
        @DisplayName("Should exclude recipients in different channel")
        void shouldExcludeDifferentChannel() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), world, 10, 64, 10);

            service.setPlayerChannel(sender.getUniqueId(), "global");
            service.setPlayerChannel(recipient.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).doesNotContain(recipient);
        }

        @Test
        @DisplayName("Should include cross-world recipients when cross-world is true")
        void shouldIncludeCrossWorldWhenEnabled() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), otherWorld, 10, 64, 10);

            service.setPlayerChannel(sender.getUniqueId(), "global");
            service.setPlayerChannel(recipient.getUniqueId(), "global");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).contains(recipient);
        }

        @Test
        @DisplayName("Should exclude cross-world recipients when cross-world is false")
        void shouldExcludeCrossWorldWhenDisabled() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), otherWorld, 10, 64, 10);

            service.setPlayerChannel(sender.getUniqueId(), "local");
            service.setPlayerChannel(recipient.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).doesNotContain(recipient);
        }

        @Test
        @DisplayName("Should include recipients within range")
        void shouldIncludeWithinRange() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), world, 50, 64, 50);

            service.setPlayerChannel(sender.getUniqueId(), "local");
            service.setPlayerChannel(recipient.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).contains(recipient);
        }

        @Test
        @DisplayName("Should exclude recipients outside range")
        void shouldExcludeOutsideRange() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), world, 200, 64, 200);

            service.setPlayerChannel(sender.getUniqueId(), "local");
            service.setPlayerChannel(recipient.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).doesNotContain(recipient);
        }

        @Test
        @DisplayName("Should include all same-channel recipients when range is unlimited (-1)")
        void shouldIncludeAllWhenRangeUnlimited() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), world, 10000, 64, 10000);

            service.setPlayerChannel(sender.getUniqueId(), "global");
            service.setPlayerChannel(recipient.getUniqueId(), "global");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).contains(recipient);
        }

        @Test
        @DisplayName("Should exclude different-world recipients even with range when cross-world is false")
        void shouldExcludeDifferentWorldWithRange() {
            // local channel: range=100, cross-world=false
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), otherWorld, 10, 64, 10);

            service.setPlayerChannel(sender.getUniqueId(), "local");
            service.setPlayerChannel(recipient.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).doesNotContain(recipient);
        }

        @Test
        @DisplayName("Should handle null channel def gracefully (unknown channel)")
        void shouldHandleNullChannelDef() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), world, 10, 64, 10);

            service.setPlayerChannel(sender.getUniqueId(), "unknown");
            service.setPlayerChannel(recipient.getUniqueId(), "unknown");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            // With null def, defaults to crossWorld=true, range=-1 — should include
            assertThat(filtered).contains(recipient);
        }

        @Test
        @DisplayName("Should filter multiple recipients correctly")
        void shouldFilterMultipleRecipients() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player inChannel = ChatTestHelper.createMockPlayerAt("InChannel", UUID.randomUUID(), world, 10, 64, 10);
            Player outChannel = ChatTestHelper.createMockPlayerAt("OutChannel", UUID.randomUUID(), world, 20, 64, 20);

            service.setPlayerChannel(sender.getUniqueId(), "global");
            service.setPlayerChannel(inChannel.getUniqueId(), "global");
            service.setPlayerChannel(outChannel.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Arrays.asList(inChannel, outChannel));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).contains(inChannel);
            assertThat(filtered).doesNotContain(outChannel);
        }

        @Test
        @DisplayName("Should handle empty recipients set")
        void shouldHandleEmptyRecipients() {
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            service.setPlayerChannel(sender.getUniqueId(), "global");

            Set<Player> filtered = service.filterRecipients(sender, new HashSet<Player>());
            assertThat(filtered).isEmpty();
        }

        @Test
        @DisplayName("Should exclude different-world recipients from ranged channel even if 'close' by coords")
        void shouldExcludeDifferentWorldFromRangedChannel() {
            // range > 0 requires same world regardless of cross-world setting
            Player sender = ChatTestHelper.createMockPlayerAt("Sender", UUID.randomUUID(), world, 0, 64, 0);
            Player recipient = ChatTestHelper.createMockPlayerAt("Recipient", UUID.randomUUID(), otherWorld, 0, 64, 0);

            service.setPlayerChannel(sender.getUniqueId(), "local");
            service.setPlayerChannel(recipient.getUniqueId(), "local");

            Set<Player> recipients = new HashSet<>(Collections.singletonList(recipient));
            Set<Player> filtered = service.filterRecipients(sender, recipients);

            assertThat(filtered).doesNotContain(recipient);
        }
    }
}
