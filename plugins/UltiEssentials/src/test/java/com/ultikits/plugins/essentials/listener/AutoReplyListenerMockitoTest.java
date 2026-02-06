package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.AutoReplyConfig;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;

import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for AutoReplyListener.
 * <p>
 * 纯 Mockito 测试自动回复监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("AutoReplyListener Tests (Mockito)")
class AutoReplyListenerMockitoTest {

    private AutoReplyListener listener;
    private EssentialsConfig config;
    private AutoReplyConfig autoReplyConfig;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        config = new EssentialsConfig();
        autoReplyConfig = new AutoReplyConfig();

        listener = new AutoReplyListener();
        EssentialsTestHelper.setField(listener, "config", config);
        EssentialsTestHelper.setField(listener, "autoReplyConfig", autoReplyConfig);

        playerUuid = UUID.randomUUID();
        player = EssentialsTestHelper.createMockPlayer("TestPlayer", playerUuid);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);

        // Clear the static cooldown map
        clearCooldownMap();
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
        clearCooldownMap();
    }

    @SuppressWarnings("unchecked")
    private void clearCooldownMap() throws Exception {
        Field field = AutoReplyListener.class.getDeclaredField("LAST_REPLY_TIME");
        field.setAccessible(true);
        Map<UUID, Long> map = (Map<UUID, Long>) field.get(null);
        map.clear();
    }

    private AsyncPlayerChatEvent createChatEvent(String message) {
        return new AsyncPlayerChatEvent(true, player, message, new HashSet<>());
    }

    @Nested
    @DisplayName("Basic Auto-Reply")
    class BasicAutoReplyTests {

        @Test
        @DisplayName("Should reply when keyword matches")
        void shouldReplyWhenKeywordMatches() throws Exception {
            AsyncPlayerChatEvent event = createChatEvent("What is the IP?");

            // Default rules include "服务器IP" -> response
            // Let's use a custom rule for testing
            Map<String, String> rules = new HashMap<>();
            rules.put("help", "&aType /help for assistance");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCaseSensitive(false);

            AsyncPlayerChatEvent helpEvent = createChatEvent("I need help please");
            listener.onPlayerChat(helpEvent);

            verify(player).sendMessage(contains("Type /help for assistance"));
        }

        @Test
        @DisplayName("Should not reply when feature is disabled")
        void shouldNotReplyWhenDisabled() throws Exception {
            config.setAutoReplyEnabled(false);

            Map<String, String> rules = new HashMap<>();
            rules.put("help", "Response");
            autoReplyConfig.setRules(rules);

            AsyncPlayerChatEvent event = createChatEvent("help");
            listener.onPlayerChat(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not reply when player has bypass permission")
        void shouldNotReplyWhenBypassPermission() throws Exception {
            when(player.hasPermission("ultiessentials.autoreply.bypass")).thenReturn(true);

            Map<String, String> rules = new HashMap<>();
            rules.put("help", "Response");
            autoReplyConfig.setRules(rules);

            AsyncPlayerChatEvent event = createChatEvent("help");
            listener.onPlayerChat(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not reply when no keyword matches")
        void shouldNotReplyWhenNoMatch() throws Exception {
            Map<String, String> rules = new HashMap<>();
            rules.put("help", "Response");
            autoReplyConfig.setRules(rules);

            AsyncPlayerChatEvent event = createChatEvent("hello everyone");
            listener.onPlayerChat(event);

            verify(player, never()).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("Case Sensitivity")
    class CaseSensitivityTests {

        @Test
        @DisplayName("Should match case-insensitively by default")
        void shouldMatchCaseInsensitively() throws Exception {
            Map<String, String> rules = new HashMap<>();
            rules.put("HELP", "&aHelp response");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCaseSensitive(false);

            AsyncPlayerChatEvent event = createChatEvent("i need help");
            listener.onPlayerChat(event);

            verify(player).sendMessage(contains("Help response"));
        }

        @Test
        @DisplayName("Should respect case sensitivity when enabled")
        void shouldRespectCaseSensitivity() throws Exception {
            Map<String, String> rules = new HashMap<>();
            rules.put("Help", "Response");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCaseSensitive(true);

            // "help" should not match "Help" when case-sensitive
            AsyncPlayerChatEvent event = createChatEvent("help me");
            listener.onPlayerChat(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should match case-sensitively when exact match")
        void shouldMatchCaseSensitiveExact() throws Exception {
            Map<String, String> rules = new HashMap<>();
            rules.put("Help", "&aExact match");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCaseSensitive(true);

            AsyncPlayerChatEvent event = createChatEvent("I need Help now");
            listener.onPlayerChat(event);

            verify(player).sendMessage(contains("Exact match"));
        }
    }

    @Nested
    @DisplayName("Cooldown Tests")
    class CooldownTests {

        @Test
        @DisplayName("Should enforce cooldown between replies")
        void shouldEnforceCooldown() throws Exception {
            Map<String, String> rules = new HashMap<>();
            rules.put("test", "Response");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCooldown(60); // 60 seconds

            // First message should trigger reply
            AsyncPlayerChatEvent event1 = createChatEvent("test");
            listener.onPlayerChat(event1);
            verify(player, times(1)).sendMessage(anyString());

            // Second message within cooldown should not trigger reply
            AsyncPlayerChatEvent event2 = createChatEvent("test again");
            listener.onPlayerChat(event2);
            verify(player, times(1)).sendMessage(anyString()); // still 1
        }

        @Test
        @DisplayName("Should allow reply after cooldown expires")
        void shouldAllowReplyAfterCooldownExpires() throws Exception {
            Map<String, String> rules = new HashMap<>();
            rules.put("test", "Response");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCooldown(0); // 0 seconds = no cooldown

            AsyncPlayerChatEvent event1 = createChatEvent("test");
            listener.onPlayerChat(event1);

            AsyncPlayerChatEvent event2 = createChatEvent("test again");
            listener.onPlayerChat(event2);

            verify(player, times(2)).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("First Match Only")
    class FirstMatchTests {

        @Test
        @DisplayName("Should only reply to first matching keyword")
        void shouldOnlyReplyToFirstMatch() throws Exception {
            // Use LinkedHashMap to preserve order
            Map<String, String> rules = new LinkedHashMap<>();
            rules.put("hello", "Hello response");
            rules.put("hey", "Hey response");
            autoReplyConfig.setRules(rules);
            autoReplyConfig.setCaseSensitive(false);

            // Message contains both keywords
            AsyncPlayerChatEvent event = createChatEvent("hello hey there");
            listener.onPlayerChat(event);

            // Should only send one message
            verify(player, times(1)).sendMessage(anyString());
        }
    }
}
