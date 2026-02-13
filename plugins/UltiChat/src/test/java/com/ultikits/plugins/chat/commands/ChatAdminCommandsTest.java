package com.ultikits.plugins.chat.commands;

import com.ultikits.plugins.chat.service.AutoReplyService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ChatAdminCommands — reload, autoreply list/add/remove.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChatAdminCommands Tests")
class ChatAdminCommandsTest {

    private UltiToolsPlugin mockPlugin;
    private AutoReplyService mockAutoReplyService;
    private ChatAdminCommands commands;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(UltiToolsPlugin.class);
        mockAutoReplyService = mock(AutoReplyService.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(mockPlugin.i18n("autoreply_added")).thenReturn("Rule '{0}' added.");
        when(mockPlugin.i18n("autoreply_removed")).thenReturn("Rule '{0}' removed.");
        when(mockPlugin.i18n("autoreply_not_found")).thenReturn("Rule '{0}' not found.");
        when(mockPlugin.i18n("autoreply_list_entry")).thenReturn("{0}: {1} [{2}]");

        commands = new ChatAdminCommands(mockPlugin, mockAutoReplyService);
    }

    private void assertSentMessageContaining(CommandSender sender, String substring) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(msg -> msg.contains(substring));
    }

    // ==================== Reload Tests ====================

    @Nested
    @DisplayName("Reload Command")
    class ReloadTests {

        @Test
        @DisplayName("Should call reloadConfigs on plugin")
        void shouldCallReloadConfigs() {
            CommandSender sender = mock(CommandSender.class);

            commands.onReload(sender);

            verify(mockPlugin).reloadSelf();
        }

        @Test
        @DisplayName("Should send reload success message")
        void shouldSendReloadMessage() {
            CommandSender sender = mock(CommandSender.class);

            commands.onReload(sender);

            assertSentMessageContaining(sender, "config_reloaded");
        }
    }

    // ==================== AutoReply List Tests ====================

    @Nested
    @DisplayName("AutoReply List Command")
    class AutoReplyListTests {

        @Test
        @DisplayName("Should show header and empty message when no rules")
        void shouldShowEmptyMessage() {
            CommandSender sender = mock(CommandSender.class);
            when(mockAutoReplyService.getRules()).thenReturn(Collections.<String, Map<String, Object>>emptyMap());

            commands.onAutoReplyList(sender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(sender, times(2)).sendMessage(captor.capture());
            assertThat(captor.getAllValues().get(0)).contains("autoreply_list_header");
            assertThat(captor.getAllValues().get(1)).contains("autoreply_list_empty");
        }

        @Test
        @DisplayName("Should list all rules with details")
        void shouldListAllRules() {
            CommandSender sender = mock(CommandSender.class);

            Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
            Map<String, Object> rule1 = new HashMap<>();
            rule1.put("keyword", "hello");
            rule1.put("mode", "contains");
            rules.put("greeting", rule1);

            Map<String, Object> rule2 = new HashMap<>();
            rule2.put("keyword", "rules");
            rule2.put("mode", "exact");
            rules.put("rules-info", rule2);

            when(mockAutoReplyService.getRules()).thenReturn(rules);

            commands.onAutoReplyList(sender);

            // header + 2 entries = 3 messages
            verify(sender, times(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should show header even with rules")
        void shouldShowHeader() {
            CommandSender sender = mock(CommandSender.class);

            Map<String, Map<String, Object>> rules = new HashMap<>();
            Map<String, Object> rule = new HashMap<>();
            rule.put("keyword", "test");
            rule.put("mode", "contains");
            rules.put("test-rule", rule);
            when(mockAutoReplyService.getRules()).thenReturn(rules);

            commands.onAutoReplyList(sender);

            assertSentMessageContaining(sender, "autoreply_list_header");
        }
    }

    // ==================== AutoReply Add Tests ====================

    @Nested
    @DisplayName("AutoReply Add Command")
    class AutoReplyAddTests {

        @Test
        @DisplayName("Should add rule and send confirmation")
        void shouldAddRule() {
            CommandSender sender = mock(CommandSender.class);

            commands.onAutoReplyAdd(sender, "greet", "Hello there!");

            verify(mockAutoReplyService).addRule("greet", "greet", "Hello there!");
            assertSentMessageContaining(sender, "greet");
        }

        @Test
        @DisplayName("Should include rule name in success message")
        void shouldIncludeNameInMessage() {
            CommandSender sender = mock(CommandSender.class);

            commands.onAutoReplyAdd(sender, "my-rule", "response");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(sender).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("my-rule");
        }
    }

    // ==================== AutoReply Remove Tests ====================

    @Nested
    @DisplayName("AutoReply Remove Command")
    class AutoReplyRemoveTests {

        @Test
        @DisplayName("Should remove existing rule")
        void shouldRemoveExistingRule() {
            CommandSender sender = mock(CommandSender.class);

            Map<String, Map<String, Object>> rules = new HashMap<>();
            rules.put("greet", new HashMap<String, Object>());
            when(mockAutoReplyService.getRules()).thenReturn(rules);

            commands.onAutoReplyRemove(sender, "greet");

            verify(mockAutoReplyService).removeRule("greet");
            assertSentMessageContaining(sender, "greet");
        }

        @Test
        @DisplayName("Should send not-found message for missing rule")
        void shouldSendNotFoundForMissing() {
            CommandSender sender = mock(CommandSender.class);
            when(mockAutoReplyService.getRules()).thenReturn(Collections.<String, Map<String, Object>>emptyMap());

            commands.onAutoReplyRemove(sender, "nonexistent");

            verify(mockAutoReplyService, never()).removeRule(anyString());
            assertSentMessageContaining(sender, "nonexistent");
        }

        @Test
        @DisplayName("Should not call removeRule when rule not found")
        void shouldNotRemoveWhenNotFound() {
            CommandSender sender = mock(CommandSender.class);
            when(mockAutoReplyService.getRules()).thenReturn(Collections.<String, Map<String, Object>>emptyMap());

            commands.onAutoReplyRemove(sender, "missing");

            verify(mockAutoReplyService, never()).removeRule(anyString());
        }
    }

    // ==================== Help Tests ====================

    @Nested
    @DisplayName("Help Command")
    class HelpTests {

        @Test
        @DisplayName("Should display help message with all commands")
        void shouldDisplayHelp() {
            CommandSender sender = mock(CommandSender.class);

            commands.handleHelp(sender);

            // Header + 4 command lines = 5 messages
            verify(sender, atLeast(4)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should mention reload in help")
        void shouldMentionReload() {
            CommandSender sender = mock(CommandSender.class);

            commands.handleHelp(sender);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(sender, atLeast(1)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(msg -> msg.contains("reload"));
        }
    }
}
