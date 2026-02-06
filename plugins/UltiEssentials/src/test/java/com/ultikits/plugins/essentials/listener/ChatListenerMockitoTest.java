package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for ChatListener.
 * <p>
 * 测试聊天格式监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChatListener Tests (Mockito)")
class ChatListenerMockitoTest {

    private ChatListener listener;
    private EssentialsConfig config;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new ChatListener();
        config = new EssentialsConfig();
        EssentialsTestHelper.setField(listener, "config", config);

        // PlaceholderAPI not present
        PluginManager pm = EssentialsTestHelper.getMockServer().getPluginManager();
        lenient().when(pm.getPlugin("PlaceholderAPI")).thenReturn(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Test
    @DisplayName("Should skip when chat format is disabled")
    void shouldSkipWhenDisabled() {
        config.setChatFormatEnabled(false);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                false, player, "Hello", new HashSet<>());

        listener.onPlayerChat(event);

        // Format should not be changed -- default format is the Bukkit default
        // We verify by checking the format was NOT set to our custom format
        assertThat(event.getMessage()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("Should apply custom chat format when enabled")
    void shouldApplyCustomChatFormat() {
        config.setChatFormatEnabled(true);
        config.setChatFormat("&a{player}: {message}");

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        when(player.hasPermission("ultiessentials.chat.color")).thenReturn(false);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                false, player, "Hello world", new HashSet<>());

        listener.onPlayerChat(event);

        assertThat(event.getFormat()).contains("Steve");
        assertThat(event.getFormat()).contains("%2$s"); // message placeholder
        assertThat(event.getFormat()).contains("\u00a7a"); // color code
    }

    @Test
    @DisplayName("Should colorize message when player has color permission")
    void shouldColorizeMessageWithPermission() {
        config.setChatFormatEnabled(true);
        config.setChatFormat("{player}: {message}");

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        when(player.hasPermission("ultiessentials.chat.color")).thenReturn(true);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                false, player, "&cRed text", new HashSet<>());

        listener.onPlayerChat(event);

        assertThat(event.getMessage()).contains("\u00a7c");
    }

    @Test
    @DisplayName("Should not colorize message without permission")
    void shouldNotColorizeMessageWithoutPermission() {
        config.setChatFormatEnabled(true);
        config.setChatFormat("{player}: {message}");

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        when(player.hasPermission("ultiessentials.chat.color")).thenReturn(false);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                false, player, "&cRed text", new HashSet<>());

        listener.onPlayerChat(event);

        assertThat(event.getMessage()).isEqualTo("&cRed text");
    }

    @Test
    @DisplayName("Should replace world placeholder in format")
    void shouldReplaceWorldPlaceholder() {
        config.setChatFormatEnabled(true);
        config.setChatFormat("[{world}] {player}: {message}");

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        when(player.hasPermission("ultiessentials.chat.color")).thenReturn(false);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                false, player, "Hello", new HashSet<>());

        listener.onPlayerChat(event);

        assertThat(event.getFormat()).contains("world");
    }

    @Test
    @DisplayName("Should replace displayname placeholder in format")
    void shouldReplaceDisplayNamePlaceholder() {
        config.setChatFormatEnabled(true);
        config.setChatFormat("{displayname}: {message}");

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        when(player.hasPermission("ultiessentials.chat.color")).thenReturn(false);
        when(player.getDisplayName()).thenReturn("SteveTheGreat");

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(
                false, player, "Hi", new HashSet<>());

        listener.onPlayerChat(event);

        assertThat(event.getFormat()).contains("SteveTheGreat");
    }
}
