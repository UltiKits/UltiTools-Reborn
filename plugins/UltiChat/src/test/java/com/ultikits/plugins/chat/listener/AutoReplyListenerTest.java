package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.AutoReplyConfig;
import com.ultikits.plugins.chat.service.AutoReplyService;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AutoReplyListener — match dispatch, cooldown, bypass,
 * multi-line response, placeholder replacement, and command execution.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("AutoReplyListener Tests")
class AutoReplyListenerTest {

    private AutoReplyListener listener;
    private AutoReplyConfig config;
    private AutoReplyService autoReplyService;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();

        config = new AutoReplyConfig();
        config.setEnabled(true);
        config.setCooldown(10);

        autoReplyService = mock(AutoReplyService.class);

        listener = new AutoReplyListener();
        ChatTestHelper.setField(listener, "config", config);
        ChatTestHelper.setField(listener, "autoReplyService", autoReplyService);

        playerUuid = UUID.randomUUID();
        player = ChatTestHelper.createMockPlayer("TestPlayer", playerUuid);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);

        // Clear static cooldown map
        clearCooldownMap();
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
        clearCooldownMap();
    }

    @SuppressWarnings("unchecked")
    private void clearCooldownMap() throws Exception {
        AutoReplyListener.LAST_REPLY_TIME.clear();
    }

    private AsyncPlayerChatEvent createChatEvent(String message) {
        return new AsyncPlayerChatEvent(true, player, message, new HashSet<Player>());
    }

    private Map.Entry<String, Map<String, Object>> createMatchEntry(String name, Map<String, Object> rule) {
        return new AbstractMap.SimpleEntry<>(name, rule);
    }

    private Map<String, Object> createSimpleRule(String response) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("keyword", "test");
        rule.put("response", response);
        rule.put("mode", "contains");
        return rule;
    }

    // ============================
    // Basic matching
    // ============================

    @Nested
    @DisplayName("Basic Auto-Reply")
    class BasicAutoReplyTests {

        @Test
        @DisplayName("Should send reply when match found")
        void shouldSendReplyOnMatch() {
            Map<String, Object> rule = createSimpleRule("Hello, welcome!");
            when(autoReplyService.findMatch("test message")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Hello, welcome!");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test message"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("Hello, welcome!");
        }

        @Test
        @DisplayName("Should not send reply when no match")
        void shouldNotSendReplyWhenNoMatch() {
            when(autoReplyService.findMatch(anyString())).thenReturn(null);

            listener.onPlayerChat(createChatEvent("random message"));

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should translate color codes in response")
        void shouldTranslateColorCodes() {
            Map<String, Object> rule = createSimpleRule("&aGreen &cRed");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("&aGreen &cRed");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            // ChatColor.translateAlternateColorCodes converts & to section sign
            assertThat(captor.getValue()).contains("\u00a7aGreen");
            assertThat(captor.getValue()).contains("\u00a7cRed");
        }
    }

    // ============================
    // Disabled feature
    // ============================

    @Nested
    @DisplayName("Disabled Feature")
    class DisabledFeatureTests {

        @Test
        @DisplayName("Should skip when auto-reply is disabled")
        void shouldSkipWhenDisabled() {
            config.setEnabled(false);

            listener.onPlayerChat(createChatEvent("test message"));

            verify(autoReplyService, never()).findMatch(anyString());
            verify(player, never()).sendMessage(anyString());
        }
    }

    // ============================
    // Bypass permission
    // ============================

    @Nested
    @DisplayName("Bypass Permission")
    class BypassPermissionTests {

        @Test
        @DisplayName("Should skip when player has bypass permission")
        void shouldSkipWhenBypass() {
            when(player.hasPermission("ultichat.autoreply.bypass")).thenReturn(true);

            listener.onPlayerChat(createChatEvent("test message"));

            verify(autoReplyService, never()).findMatch(anyString());
            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should proceed when player does not have bypass permission")
        void shouldProceedWithoutBypass() {
            when(player.hasPermission("ultichat.autoreply.bypass")).thenReturn(false);
            when(autoReplyService.findMatch(anyString())).thenReturn(null);

            listener.onPlayerChat(createChatEvent("test message"));

            verify(autoReplyService).findMatch("test message");
        }
    }

    // ============================
    // Cooldown
    // ============================

    @Nested
    @DisplayName("Cooldown")
    class CooldownTests {

        @Test
        @DisplayName("Should enforce cooldown between replies")
        void shouldEnforceCooldown() {
            config.setCooldown(60);

            Map<String, Object> rule = createSimpleRule("Response");
            when(autoReplyService.findMatch(anyString())).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            // First message should reply
            listener.onPlayerChat(createChatEvent("test 1"));
            verify(player, times(1)).sendMessage(anyString());

            // Second message within cooldown should not reply
            listener.onPlayerChat(createChatEvent("test 2"));
            verify(player, times(1)).sendMessage(anyString()); // still 1
        }

        @Test
        @DisplayName("Should allow reply when cooldown is 0")
        void shouldAllowReplyWhenCooldownZero() {
            config.setCooldown(0);

            Map<String, Object> rule = createSimpleRule("Response");
            when(autoReplyService.findMatch(anyString())).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test 1"));
            listener.onPlayerChat(createChatEvent("test 2"));

            verify(player, times(2)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should track cooldown per player")
        void shouldTrackCooldownPerPlayer() {
            config.setCooldown(60);

            Map<String, Object> rule = createSimpleRule("Response");
            when(autoReplyService.findMatch(anyString())).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            // Player 1 triggers
            listener.onPlayerChat(createChatEvent("test"));
            verify(player, times(1)).sendMessage(anyString());

            // Player 2 should also trigger (different UUID)
            Player player2 = ChatTestHelper.createMockPlayer("Player2", UUID.randomUUID());
            when(player2.hasPermission(anyString())).thenReturn(false);
            AsyncPlayerChatEvent event2 = new AsyncPlayerChatEvent(true, player2, "test", new HashSet<Player>());
            listener.onPlayerChat(event2);
            verify(player2, times(1)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should record cooldown timestamp after reply")
        void shouldRecordCooldownTimestamp() {
            config.setCooldown(60);

            Map<String, Object> rule = createSimpleRule("Response");
            when(autoReplyService.findMatch(anyString())).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            assertThat(AutoReplyListener.LAST_REPLY_TIME).containsKey(playerUuid);
        }

        @Test
        @DisplayName("Should not record cooldown when no match")
        void shouldNotRecordCooldownWhenNoMatch() {
            when(autoReplyService.findMatch(anyString())).thenReturn(null);

            listener.onPlayerChat(createChatEvent("no match"));

            assertThat(AutoReplyListener.LAST_REPLY_TIME).doesNotContainKey(playerUuid);
        }
    }

    // ============================
    // Multi-line response
    // ============================

    @Nested
    @DisplayName("Multi-line Response")
    class MultiLineResponseTests {

        @Test
        @DisplayName("Should send multiple lines for list response")
        void shouldSendMultipleLines() {
            Map<String, Object> rule = createSimpleRule("placeholder");
            List<String> lines = Arrays.asList("Line 1", "Line 2", "Line 3");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn(lines);
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(3)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).containsExactly("Line 1", "Line 2", "Line 3");
        }

        @Test
        @DisplayName("Should translate color codes in multi-line response")
        void shouldTranslateColorsInMultiLine() {
            Map<String, Object> rule = createSimpleRule("placeholder");
            List<String> lines = Arrays.asList("&aLine 1", "&bLine 2");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn(lines);
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(2)).sendMessage(captor.capture());
            assertThat(captor.getAllValues().get(0)).contains("\u00a7a");
            assertThat(captor.getAllValues().get(1)).contains("\u00a7b");
        }
    }

    // ============================
    // {player} placeholder
    // ============================

    @Nested
    @DisplayName("Player Placeholder")
    class PlayerPlaceholderTests {

        @Test
        @DisplayName("Should replace {player} with player name in response")
        void shouldReplacePlaceholder() {
            Map<String, Object> rule = createSimpleRule("Hello, {player}!");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Hello, {player}!");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).isEqualTo("Hello, TestPlayer!");
        }

        @Test
        @DisplayName("Should replace {player} in multi-line response")
        void shouldReplacePlaceholderInMultiLine() {
            Map<String, Object> rule = createSimpleRule("placeholder");
            List<String> lines = Arrays.asList("Welcome {player}", "Enjoy {player}");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn(lines);
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(2)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).allMatch(msg -> msg.contains("TestPlayer"));
        }
    }

    // ============================
    // Rule permissions
    // ============================

    @Nested
    @DisplayName("Rule Permissions")
    class RulePermissionTests {

        @Test
        @DisplayName("Should skip rule when player lacks rule permission")
        void shouldSkipWhenLacksPermission() {
            Map<String, Object> rule = createSimpleRule("VIP response");
            rule.put("permission", "ultichat.vip");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(player.hasPermission("ultichat.vip")).thenReturn(false);

            listener.onPlayerChat(createChatEvent("test"));

            verify(player, never()).sendMessage(contains("VIP response"));
        }

        @Test
        @DisplayName("Should send response when player has rule permission")
        void shouldSendWhenHasPermission() {
            Map<String, Object> rule = createSimpleRule("VIP response");
            rule.put("permission", "ultichat.vip");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("VIP response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());
            when(player.hasPermission("ultichat.vip")).thenReturn(true);
            // bypass must be false
            when(player.hasPermission("ultichat.autoreply.bypass")).thenReturn(false);

            listener.onPlayerChat(createChatEvent("test"));

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not check permission when not set in rule")
        void shouldNotCheckWhenNoPermission() {
            Map<String, Object> rule = createSimpleRule("Public response");
            // no "permission" key
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Public response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should treat empty permission string as no permission required")
        void shouldTreatEmptyPermissionAsNone() {
            Map<String, Object> rule = createSimpleRule("Response");
            rule.put("permission", "");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            verify(player).sendMessage(anyString());
        }
    }

    // ============================
    // Console commands
    // ============================

    @Nested
    @DisplayName("Console Commands")
    class ConsoleCommandTests {

        @Test
        @DisplayName("Should dispatch commands via scheduler when UltiTools plugin present")
        void shouldDispatchCommands() {
            Map<String, Object> rule = createSimpleRule("Response");
            List<String> commands = Arrays.asList("say hello", "give {player} diamond 1");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(commands);

            Plugin mockBukkitPlugin = mock(Plugin.class);
            PluginManager pm = Bukkit.getPluginManager();
            when(pm.getPlugin("UltiTools")).thenReturn(mockBukkitPlugin);

            BukkitScheduler scheduler = Bukkit.getScheduler();

            listener.onPlayerChat(createChatEvent("test"));

            verify(scheduler).runTask(eq(mockBukkitPlugin), any(Runnable.class));
        }

        @Test
        @DisplayName("Should not dispatch when UltiTools plugin is not present")
        void shouldNotDispatchWhenNoPlugin() {
            Map<String, Object> rule = createSimpleRule("Response");
            List<String> commands = Arrays.asList("say hello");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(commands);

            // Plugin returns null (default from ChatTestHelper)
            BukkitScheduler scheduler = Bukkit.getScheduler();

            listener.onPlayerChat(createChatEvent("test"));

            verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
        }

        @Test
        @DisplayName("Should not schedule when commands list is empty")
        void shouldNotScheduleWhenNoCommands() {
            Map<String, Object> rule = createSimpleRule("Response");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn("Response");
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            BukkitScheduler scheduler = Bukkit.getScheduler();

            listener.onPlayerChat(createChatEvent("test"));

            verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
        }
    }

    // ============================
    // Null response
    // ============================

    @Nested
    @DisplayName("Null Response")
    class NullResponseTests {

        @Test
        @DisplayName("Should not send message when response is null")
        void shouldNotSendWhenResponseNull() {
            Map<String, Object> rule = createSimpleRule("irrelevant");
            when(autoReplyService.findMatch("test")).thenReturn(createMatchEntry("r1", rule));
            when(autoReplyService.getResponse(rule)).thenReturn(null);
            when(autoReplyService.getCommands(rule)).thenReturn(Collections.<String>emptyList());

            listener.onPlayerChat(createChatEvent("test"));

            verify(player, never()).sendMessage(anyString());
        }
    }
}
