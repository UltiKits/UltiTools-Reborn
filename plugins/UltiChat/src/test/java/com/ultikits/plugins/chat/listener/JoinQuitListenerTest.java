package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.ChatConfig;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JoinQuitListener Tests")
class JoinQuitListenerTest {

    private ChatConfig config;
    private JoinQuitListener listener;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();

        config = mock(ChatConfig.class);
        lenient().when(config.isJoinMessageEnabled()).thenReturn(true);
        lenient().when(config.getJoinMessageFormat()).thenReturn("&a[+] &e%player_name% &7joined");
        lenient().when(config.isQuitMessageEnabled()).thenReturn(true);
        lenient().when(config.getQuitMessageFormat()).thenReturn("&c[-] &e%player_name% &7left");
        lenient().when(config.isWelcomeEnabled()).thenReturn(false);
        lenient().when(config.isTitleEnabled()).thenReturn(false);
        lenient().when(config.getFirstJoinMessage()).thenReturn("");

        listener = new JoinQuitListener();
        ChatTestHelper.setField(listener, "config", config);

        playerUuid = UUID.randomUUID();
        player = ChatTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    private PlayerJoinEvent createJoinEvent(Player p) {
        return new PlayerJoinEvent(p, "default join");
    }

    private PlayerQuitEvent createQuitEvent(Player p) {
        return new PlayerQuitEvent(p, "default quit");
    }

    // ==================== Join Message Tests ====================

    @Nested
    @DisplayName("Join Message Tests")
    class JoinMessageTests {

        @Test
        @DisplayName("Should set custom join message with placeholder replacement")
        void shouldSetCustomJoinMessage() {
            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            String joinMsg = event.getJoinMessage();
            assertThat(joinMsg).isNotNull();
            assertThat(joinMsg).contains("TestPlayer");
            assertThat(joinMsg).contains("joined");
        }

        @Test
        @DisplayName("Should apply color codes to join message")
        void shouldApplyColorCodes() {
            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            String joinMsg = event.getJoinMessage();
            assertThat(joinMsg).isNotNull();
            // Color codes should be translated
            assertThat(joinMsg).doesNotContain("&a");
            assertThat(joinMsg).doesNotContain("&e");
        }

        @Test
        @DisplayName("Should not override join message when disabled")
        void shouldNotOverrideWhenDisabled() {
            when(config.isJoinMessageEnabled()).thenReturn(false);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            assertThat(event.getJoinMessage()).isEqualTo("default join");
        }
    }

    // ==================== Quit Message Tests ====================

    @Nested
    @DisplayName("Quit Message Tests")
    class QuitMessageTests {

        @Test
        @DisplayName("Should set custom quit message with placeholder replacement")
        void shouldSetCustomQuitMessage() {
            PlayerQuitEvent event = createQuitEvent(player);
            listener.onPlayerQuit(event);

            String quitMsg = event.getQuitMessage();
            assertThat(quitMsg).isNotNull();
            assertThat(quitMsg).contains("TestPlayer");
            assertThat(quitMsg).contains("left");
        }

        @Test
        @DisplayName("Should apply color codes to quit message")
        void shouldApplyColorCodes() {
            PlayerQuitEvent event = createQuitEvent(player);
            listener.onPlayerQuit(event);

            String quitMsg = event.getQuitMessage();
            assertThat(quitMsg).isNotNull();
            assertThat(quitMsg).doesNotContain("&c");
        }

        @Test
        @DisplayName("Should not override quit message when disabled")
        void shouldNotOverrideWhenDisabled() {
            when(config.isQuitMessageEnabled()).thenReturn(false);

            PlayerQuitEvent event = createQuitEvent(player);
            listener.onPlayerQuit(event);

            assertThat(event.getQuitMessage()).isEqualTo("default quit");
        }
    }

    // ==================== Welcome Message Tests ====================

    @Nested
    @DisplayName("Welcome Message Tests")
    class WelcomeMessageTests {

        @Test
        @DisplayName("Should send welcome lines to player")
        void shouldSendWelcomeLines() {
            when(config.isWelcomeEnabled()).thenReturn(true);
            when(config.getWelcomeLines()).thenReturn(Arrays.asList(
                    "&7========",
                    "&6Welcome, &e%player_name%&6!",
                    "&7========"
            ));

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, atLeast(3)).sendMessage(captor.capture());

            List<String> messages = captor.getAllValues();
            assertThat(messages).hasSize(3);
            assertThat(messages.get(1)).contains("TestPlayer");
        }

        @Test
        @DisplayName("Should not send welcome when disabled")
        void shouldNotSendWhenDisabled() {
            when(config.isWelcomeEnabled()).thenReturn(false);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should handle null welcome lines gracefully")
        void shouldHandleNullWelcomeLines() {
            when(config.isWelcomeEnabled()).thenReturn(true);
            when(config.getWelcomeLines()).thenReturn(null);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should replace {displayname} in welcome lines")
        void shouldReplaceDisplayName() {
            when(config.isWelcomeEnabled()).thenReturn(true);
            when(config.getWelcomeLines()).thenReturn(Collections.singletonList("Hello {displayname}!"));
            when(player.getDisplayName()).thenReturn("FancyName");

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("FancyName");
        }

        @Test
        @DisplayName("Should replace %online_players% and %max_players% in welcome lines")
        void shouldReplaceOnlineAndMaxPlayers() {
            when(config.isWelcomeEnabled()).thenReturn(true);
            when(config.getWelcomeLines()).thenReturn(
                    Collections.singletonList("Online: %online_players%/%max_players%")
            );

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("0");
            assertThat(captor.getValue()).contains("100");
        }
    }

    // ==================== Title Tests ====================

    @Nested
    @DisplayName("Title Tests")
    class TitleTests {

        @Test
        @DisplayName("Should send welcome title to player")
        void shouldSendWelcomeTitle() {
            when(config.isTitleEnabled()).thenReturn(true);
            when(config.getTitleMain()).thenReturn("&6Welcome Back");
            when(config.getTitleSub()).thenReturn("&7%player_name%");

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subtitleCaptor = ArgumentCaptor.forClass(String.class);
            verify(player).sendTitle(titleCaptor.capture(), subtitleCaptor.capture(),
                    eq(10), eq(70), eq(20));

            assertThat(titleCaptor.getValue()).contains("Welcome Back");
            assertThat(subtitleCaptor.getValue()).contains("TestPlayer");
        }

        @Test
        @DisplayName("Should not send title when disabled")
        void shouldNotSendTitleWhenDisabled() {
            when(config.isTitleEnabled()).thenReturn(false);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should handle null title/subtitle gracefully")
        void shouldHandleNullTitle() {
            when(config.isTitleEnabled()).thenReturn(true);
            when(config.getTitleMain()).thenReturn(null);
            when(config.getTitleSub()).thenReturn(null);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(player).sendTitle(eq(""), eq(""), eq(10), eq(70), eq(20));
        }
    }

    // ==================== First Join Tests ====================

    @Nested
    @DisplayName("First Join Tests")
    class FirstJoinTests {

        @Test
        @DisplayName("Should broadcast first-join message for new player")
        void shouldBroadcastFirstJoin() {
            when(player.hasPlayedBefore()).thenReturn(false);
            when(config.getFirstJoinMessage()).thenReturn("&6Welcome new player &e%player_name%&6!");

            Server mockServer = ChatTestHelper.getMockServer();

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockServer).broadcastMessage(captor.capture());
            assertThat(captor.getValue()).contains("TestPlayer");
        }

        @Test
        @DisplayName("Should not broadcast first-join for returning player")
        void shouldNotBroadcastForReturning() {
            when(player.hasPlayedBefore()).thenReturn(true);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(ChatTestHelper.getMockServer(), never()).broadcastMessage(anyString());
        }

        @Test
        @DisplayName("Should not broadcast when first-join message is empty")
        void shouldNotBroadcastWhenEmpty() {
            when(player.hasPlayedBefore()).thenReturn(false);
            when(config.getFirstJoinMessage()).thenReturn("");

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(ChatTestHelper.getMockServer(), never()).broadcastMessage(anyString());
        }

        @Test
        @DisplayName("Should not broadcast when first-join message is null")
        void shouldNotBroadcastWhenNull() {
            when(player.hasPlayedBefore()).thenReturn(false);
            when(config.getFirstJoinMessage()).thenReturn(null);

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            verify(ChatTestHelper.getMockServer(), never()).broadcastMessage(anyString());
        }
    }

    // ==================== Placeholder Parsing Tests ====================

    @Nested
    @DisplayName("Placeholder Parsing Tests")
    class PlaceholderParsingTests {

        @Test
        @DisplayName("Should replace %player_name% placeholder")
        void shouldReplacePlayerName() {
            String result = listener.parsePlaceholders(player, "Hello %player_name%");
            assertThat(result).isEqualTo("Hello TestPlayer");
        }

        @Test
        @DisplayName("Should replace {player} placeholder")
        void shouldReplaceBracePlayer() {
            String result = listener.parsePlaceholders(player, "Hello {player}");
            assertThat(result).isEqualTo("Hello TestPlayer");
        }

        @Test
        @DisplayName("Should replace {displayname} placeholder")
        void shouldReplaceDisplayname() {
            when(player.getDisplayName()).thenReturn("CoolName");
            String result = listener.parsePlaceholders(player, "Hello {displayname}");
            assertThat(result).isEqualTo("Hello CoolName");
        }

        @Test
        @DisplayName("Should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertThat(listener.parsePlaceholders(player, null)).isEmpty();
        }

        @Test
        @DisplayName("Should replace multiple placeholders in same text")
        void shouldReplaceMultiple() {
            String result = listener.parsePlaceholders(player, "{player}: %online_players%/%max_players%");
            assertThat(result).isEqualTo("TestPlayer: 0/100");
        }
    }

    // ==================== Colorize Tests ====================

    @Nested
    @DisplayName("Colorize Tests")
    class ColorizeTests {

        @Test
        @DisplayName("Should translate color codes")
        void shouldTranslateColorCodes() {
            String result = listener.colorize("&aGreen &cRed");
            assertThat(result).doesNotContain("&a");
            assertThat(result).doesNotContain("&c");
        }

        @Test
        @DisplayName("Should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertThat(listener.colorize(null)).isEmpty();
        }

        @Test
        @DisplayName("Should leave text without codes unchanged")
        void shouldLeaveUnchanged() {
            assertThat(listener.colorize("plain text")).isEqualTo("plain text");
        }
    }

    // ==================== Integration-like Tests ====================

    @Nested
    @DisplayName("Combined Feature Tests")
    class CombinedFeatureTests {

        @Test
        @DisplayName("Should handle all join features together")
        void shouldHandleAllJoinFeatures() {
            when(config.isWelcomeEnabled()).thenReturn(true);
            when(config.getWelcomeLines()).thenReturn(Collections.singletonList("Welcome!"));
            when(config.isTitleEnabled()).thenReturn(true);
            when(config.getTitleMain()).thenReturn("&6Welcome");
            when(config.getTitleSub()).thenReturn("&7Enjoy");
            when(player.hasPlayedBefore()).thenReturn(false);
            when(config.getFirstJoinMessage()).thenReturn("&eNew player!");

            PlayerJoinEvent event = createJoinEvent(player);
            listener.onPlayerJoin(event);

            // Join message was set
            assertThat(event.getJoinMessage()).contains("TestPlayer");
            // Welcome sent
            verify(player, atLeastOnce()).sendMessage(anyString());
            // Title sent
            verify(player).sendTitle(anyString(), anyString(), eq(10), eq(70), eq(20));
            // First join broadcast
            verify(ChatTestHelper.getMockServer()).broadcastMessage(anyString());
        }

        @Test
        @DisplayName("Should handle all features disabled")
        void shouldHandleAllDisabled() {
            when(config.isJoinMessageEnabled()).thenReturn(false);
            when(config.isQuitMessageEnabled()).thenReturn(false);
            when(config.isWelcomeEnabled()).thenReturn(false);
            when(config.isTitleEnabled()).thenReturn(false);

            PlayerJoinEvent joinEvent = createJoinEvent(player);
            listener.onPlayerJoin(joinEvent);
            assertThat(joinEvent.getJoinMessage()).isEqualTo("default join");

            PlayerQuitEvent quitEvent = createQuitEvent(player);
            listener.onPlayerQuit(quitEvent);
            assertThat(quitEvent.getQuitMessage()).isEqualTo("default quit");
        }
    }
}
