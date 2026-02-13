package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.ChatConfig;
import com.ultikits.plugins.chat.config.ChannelConfig;
import com.ultikits.plugins.chat.service.AntiSpamService;
import com.ultikits.plugins.chat.service.ChannelService;
import com.ultikits.plugins.chat.service.EmojiService;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ChatListener — anti-spam, emoji, channel filtering, chat format, and mentions.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChatListener Tests")
class ChatListenerTest {

    private ChatListener listener;
    private ChatConfig chatConfig;
    private ChannelConfig channelConfig;
    private AntiSpamService antiSpamService;
    private ChannelService channelService;
    private EmojiService emojiService;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();

        chatConfig = new ChatConfig();
        channelConfig = new ChannelConfig();
        antiSpamService = mock(AntiSpamService.class);
        channelService = mock(ChannelService.class);
        emojiService = mock(EmojiService.class);

        listener = new ChatListener(
                chatConfig, channelConfig,
                antiSpamService, channelService, emojiService
        );

        playerUuid = UUID.randomUUID();
        player = ChatTestHelper.createMockPlayer("TestPlayer", playerUuid);

        // Default: emojiService returns message unchanged
        when(emojiService.replaceEmojis(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    private AsyncPlayerChatEvent createChatEvent(String message) {
        Set<Player> recipients = new HashSet<>();
        recipients.add(player);
        return new AsyncPlayerChatEvent(false, player, message, recipients);
    }

    private AsyncPlayerChatEvent createChatEventWithRecipients(String message, Set<Player> recipients) {
        return new AsyncPlayerChatEvent(false, player, message, recipients);
    }

    // ==================== Anti-Spam Tests ====================

    @Nested
    @DisplayName("Anti-Spam")
    class AntiSpamTests {

        @Test
        @DisplayName("Should cancel event when spam detected")
        void shouldCancelWhenSpamDetected() {
            chatConfig.setAntiSpamEnabled(true);
            when(antiSpamService.checkSpam(player, "spam message")).thenReturn("spam reason");

            AsyncPlayerChatEvent event = createChatEvent("spam message");
            listener.onChat(event);

            assertThat(event.isCancelled()).isTrue();
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("spam reason");
        }

        @Test
        @DisplayName("Should send reason to player when spam detected")
        void shouldSendReasonWhenSpam() {
            chatConfig.setAntiSpamEnabled(true);
            when(antiSpamService.checkSpam(player, "fast")).thenReturn("Too fast!");

            AsyncPlayerChatEvent event = createChatEvent("fast");
            listener.onChat(event);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("Too fast!");
        }

        @Test
        @DisplayName("Should record message when not spam")
        void shouldRecordMessageWhenNotSpam() {
            chatConfig.setAntiSpamEnabled(true);
            when(antiSpamService.checkSpam(player, "hello")).thenReturn(null);

            AsyncPlayerChatEvent event = createChatEvent("hello");
            listener.onChat(event);

            verify(antiSpamService).recordMessage(playerUuid, "hello");
            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should bypass anti-spam with permission")
        void shouldBypassWithPermission() {
            chatConfig.setAntiSpamEnabled(true);
            when(player.hasPermission("ultichat.spam.bypass")).thenReturn(true);

            AsyncPlayerChatEvent event = createChatEvent("message");
            listener.onChat(event);

            verify(antiSpamService, never()).checkSpam(any(), anyString());
            assertThat(event.isCancelled()).isFalse();
        }

        @Test
        @DisplayName("Should skip anti-spam when disabled")
        void shouldSkipWhenDisabled() {
            chatConfig.setAntiSpamEnabled(false);

            AsyncPlayerChatEvent event = createChatEvent("message");
            listener.onChat(event);

            verify(antiSpamService, never()).checkSpam(any(), anyString());
            verify(antiSpamService, never()).recordMessage(any(), anyString());
        }

        @Test
        @DisplayName("Should not record message when spam cancelled")
        void shouldNotRecordWhenCancelled() {
            chatConfig.setAntiSpamEnabled(true);
            when(antiSpamService.checkSpam(player, "spam")).thenReturn("blocked");

            AsyncPlayerChatEvent event = createChatEvent("spam");
            listener.onChat(event);

            verify(antiSpamService, never()).recordMessage(any(), anyString());
        }
    }

    // ==================== Emoji Tests ====================

    @Nested
    @DisplayName("Emoji Replacement")
    class EmojiTests {

        @Test
        @DisplayName("Should replace emojis when player has permission")
        void shouldReplaceEmojisWithPermission() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);
            when(player.hasPermission("ultichat.emoji")).thenReturn(true);
            when(emojiService.replaceEmojis(":heart:")).thenReturn("\u2764");

            AsyncPlayerChatEvent event = createChatEvent(":heart:");
            listener.onChat(event);

            assertThat(event.getMessage()).isEqualTo("\u2764");
        }

        @Test
        @DisplayName("Should not replace emojis without permission")
        void shouldNotReplaceWithoutPermission() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);
            when(player.hasPermission("ultichat.emoji")).thenReturn(false);

            AsyncPlayerChatEvent event = createChatEvent(":heart:");
            listener.onChat(event);

            verify(emojiService, never()).replaceEmojis(anyString());
            assertThat(event.getMessage()).isEqualTo(":heart:");
        }
    }

    // ==================== Channel Tests ====================

    @Nested
    @DisplayName("Channel Filtering")
    class ChannelTests {

        @Test
        @DisplayName("Should filter recipients by channel")
        void shouldFilterRecipientsByChannel() {
            channelConfig.setEnabled(true);
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);

            Player other = ChatTestHelper.createMockPlayer("Other", UUID.randomUUID());
            Set<Player> original = new HashSet<>(Arrays.asList(player, other));

            Set<Player> filtered = new HashSet<>();
            filtered.add(player);
            when(channelService.filterRecipients(eq(player), any())).thenReturn(filtered);

            AsyncPlayerChatEvent event = createChatEventWithRecipients("hello", original);
            listener.onChat(event);

            assertThat(event.getRecipients()).containsExactly(player);
        }

        @Test
        @DisplayName("Should not filter when channels disabled")
        void shouldNotFilterWhenDisabled() {
            channelConfig.setEnabled(false);
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);

            Player other = ChatTestHelper.createMockPlayer("Other", UUID.randomUUID());
            Set<Player> original = new HashSet<>(Arrays.asList(player, other));

            AsyncPlayerChatEvent event = createChatEventWithRecipients("hello", original);
            listener.onChat(event);

            verify(channelService, never()).filterRecipients(any(), any());
            assertThat(event.getRecipients()).hasSize(2);
        }
    }

    // ==================== Chat Format Tests ====================

    @Nested
    @DisplayName("Chat Format")
    class ChatFormatTests {

        @Test
        @DisplayName("Should apply chat format with player name placeholder")
        void shouldApplyFormatWithPlayerName() {
            chatConfig.setChatFormatEnabled(true);
            chatConfig.setChatFormat("{player}: {message}");
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);

            AsyncPlayerChatEvent event = createChatEvent("hello");
            listener.onChat(event);

            assertThat(event.getFormat()).contains("%1$s");
            assertThat(event.getFormat()).contains("%2$s");
        }

        @Test
        @DisplayName("Should prepend channel display name when channels enabled")
        void shouldPrependChannelDisplayName() {
            chatConfig.setChatFormatEnabled(true);
            chatConfig.setChatFormat("{player}: {message}");
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(true);

            when(channelService.getPlayerChannel(playerUuid)).thenReturn("global");
            when(channelService.getChannelDisplayName("global")).thenReturn("[Global]");

            AsyncPlayerChatEvent event = createChatEvent("hello");
            listener.onChat(event);

            assertThat(event.getFormat()).startsWith("[Global] ");
        }

        @Test
        @DisplayName("Should translate color codes in format")
        void shouldTranslateColorCodesInFormat() {
            chatConfig.setChatFormatEnabled(true);
            chatConfig.setChatFormat("&a{player}&7: {message}");
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);

            AsyncPlayerChatEvent event = createChatEvent("hello");
            listener.onChat(event);

            assertThat(event.getFormat()).contains("\u00a7a");
            assertThat(event.getFormat()).contains("\u00a77");
        }

        @Test
        @DisplayName("Should replace displayname placeholder")
        void shouldReplaceDisplayName() {
            chatConfig.setChatFormatEnabled(true);
            chatConfig.setChatFormat("{displayname} says: {message}");
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);

            when(player.getDisplayName()).thenReturn("FancyPlayer");

            AsyncPlayerChatEvent event = createChatEvent("hello");
            listener.onChat(event);

            assertThat(event.getFormat()).contains("FancyPlayer");
        }

        @Test
        @DisplayName("Should not apply format when disabled")
        void shouldNotApplyFormatWhenDisabled() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);

            AsyncPlayerChatEvent event = createChatEvent("hello");
            String originalFormat = event.getFormat();
            listener.onChat(event);

            assertThat(event.getFormat()).isEqualTo(originalFormat);
        }

        @Test
        @DisplayName("Should translate color codes in message with permission")
        void shouldTranslateColorInMessage() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);
            when(player.hasPermission("ultichat.color")).thenReturn(true);

            AsyncPlayerChatEvent event = createChatEvent("&ahello");
            listener.onChat(event);

            assertThat(event.getMessage()).contains("\u00a7a");
        }

        @Test
        @DisplayName("Should not translate color codes without permission")
        void shouldNotTranslateColorWithoutPermission() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);
            when(player.hasPermission("ultichat.color")).thenReturn(false);

            AsyncPlayerChatEvent event = createChatEvent("&ahello");
            listener.onChat(event);

            assertThat(event.getMessage()).isEqualTo("&ahello");
        }
    }

    // ==================== Mention Tests ====================

    @Nested
    @DisplayName("Mentions")
    class MentionTests {

        @Test
        @DisplayName("Should highlight mentioned player name")
        void shouldHighlightMention() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(true);
            chatConfig.setMentionFormat("&e@{player}&r");
            chatConfig.setMentionSound("ENTITY_EXPERIENCE_ORB_PICKUP");
            chatConfig.setSelfMention(false);
            channelConfig.setEnabled(false);

            Player mentioned = ChatTestHelper.createMockPlayer("Alice", UUID.randomUUID());

            // Mock Bukkit.getOnlinePlayers()
            List<Player> onlinePlayers = Arrays.asList(player, mentioned);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            AsyncPlayerChatEvent event = createChatEvent("Hello @Alice!");
            listener.onChat(event);

            // The message should contain the formatted mention
            assertThat(event.getMessage()).contains("@Alice");
            assertThat(event.getMessage()).contains("\u00a7e");
        }

        @Test
        @DisplayName("Should play sound to mentioned player")
        void shouldPlaySoundToMentioned() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(true);
            chatConfig.setMentionFormat("&e@{player}&r");
            chatConfig.setMentionSound("ENTITY_EXPERIENCE_ORB_PICKUP");
            chatConfig.setSelfMention(false);
            channelConfig.setEnabled(false);

            Player mentioned = ChatTestHelper.createMockPlayer("Alice", UUID.randomUUID());

            List<Player> onlinePlayers = Arrays.asList(player, mentioned);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            AsyncPlayerChatEvent event = createChatEvent("Hey @Alice");
            listener.onChat(event);

            verify(mentioned).playSound(any(org.bukkit.Location.class), any(org.bukkit.Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("Should block self-mention when disabled")
        void shouldBlockSelfMention() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(true);
            chatConfig.setMentionFormat("&e@{player}&r");
            chatConfig.setSelfMention(false);
            channelConfig.setEnabled(false);

            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            AsyncPlayerChatEvent event = createChatEvent("@TestPlayer hello");
            listener.onChat(event);

            // Self-mention should NOT be formatted
            assertThat(event.getMessage()).isEqualTo("@TestPlayer hello");
        }

        @Test
        @DisplayName("Should allow self-mention when enabled")
        void shouldAllowSelfMention() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(true);
            chatConfig.setMentionFormat("&e@{player}&r");
            chatConfig.setMentionSound("ENTITY_EXPERIENCE_ORB_PICKUP");
            chatConfig.setSelfMention(true);
            channelConfig.setEnabled(false);

            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            AsyncPlayerChatEvent event = createChatEvent("@TestPlayer hi");
            listener.onChat(event);

            assertThat(event.getMessage()).contains("\u00a7e");
        }

        @Test
        @DisplayName("Should not process mentions when disabled")
        void shouldNotProcessMentionsWhenDisabled() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(false);
            channelConfig.setEnabled(false);

            Player mentioned = ChatTestHelper.createMockPlayer("Alice", UUID.randomUUID());

            List<Player> onlinePlayers = Arrays.asList(player, mentioned);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            AsyncPlayerChatEvent event = createChatEvent("@Alice hi");
            listener.onChat(event);

            // Message should remain unchanged
            assertThat(event.getMessage()).isEqualTo("@Alice hi");
            verify(mentioned, never()).playSound(any(org.bukkit.Location.class), any(org.bukkit.Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("Should handle invalid sound name gracefully")
        void shouldHandleInvalidSound() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(true);
            chatConfig.setMentionFormat("&e@{player}&r");
            chatConfig.setMentionSound("INVALID_SOUND_NAME");
            chatConfig.setSelfMention(false);
            channelConfig.setEnabled(false);

            Player mentioned = ChatTestHelper.createMockPlayer("Alice", UUID.randomUUID());

            List<Player> onlinePlayers = Arrays.asList(player, mentioned);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            // Should not throw
            AsyncPlayerChatEvent event = createChatEvent("@Alice hi");
            listener.onChat(event);

            verify(mentioned, never()).playSound(any(org.bukkit.Location.class), any(org.bukkit.Sound.class), anyFloat(), anyFloat());
        }

        @Test
        @DisplayName("Should not highlight when no matching player online")
        void shouldNotHighlightNoMatch() {
            chatConfig.setChatFormatEnabled(false);
            chatConfig.setAntiSpamEnabled(false);
            chatConfig.setMentionsEnabled(true);
            chatConfig.setMentionFormat("&e@{player}&r");
            channelConfig.setEnabled(false);

            // Only current player online, no "Bob"
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            AsyncPlayerChatEvent event = createChatEvent("@Bob hi");
            listener.onChat(event);

            assertThat(event.getMessage()).isEqualTo("@Bob hi");
        }
    }

    // ==================== Format String Escaping ====================

    @Nested
    @DisplayName("Format String Escaping")
    class FormatEscapingTests {

        @Test
        @DisplayName("Should preserve %1$s and %2$s format specifiers")
        void shouldPreserveFormatSpecifiers() {
            String result = ChatListener.escapeFormatString("%1$s: %2$s");
            assertThat(result).isEqualTo("%1$s: %2$s");
        }

        @Test
        @DisplayName("Should escape stray percent characters")
        void shouldEscapeStrayPercent() {
            String result = ChatListener.escapeFormatString("100% done %1$s");
            assertThat(result).isEqualTo("100%% done %1$s");
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNull() {
            assertThat(ChatListener.escapeFormatString(null)).isNull();
        }

        @Test
        @DisplayName("Should handle string without percent")
        void shouldHandleNoPercent() {
            String result = ChatListener.escapeFormatString("hello world");
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        @DisplayName("Should handle percent at end of string")
        void shouldHandlePercentAtEnd() {
            String result = ChatListener.escapeFormatString("test%");
            assertThat(result).isEqualTo("test%%");
        }
    }
}
