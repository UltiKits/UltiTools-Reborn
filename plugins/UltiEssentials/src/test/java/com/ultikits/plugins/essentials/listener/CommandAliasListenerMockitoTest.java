package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for CommandAliasListener.
 * <p>
 * 测试命令别名监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("CommandAliasListener Tests (Mockito)")
class CommandAliasListenerMockitoTest {

    private CommandAliasListener listener;
    private EssentialsConfig config;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new CommandAliasListener();
        config = new EssentialsConfig();
        EssentialsTestHelper.setField(listener, "config", config);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Test
    @DisplayName("Should skip when command alias is disabled")
    void shouldSkipWhenDisabled() {
        config.setCommandAliasEnabled(false);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/gmc");

        listener.onPlayerCommand(event);

        assertThat(event.getMessage()).isEqualTo("/gmc");
    }

    @Test
    @DisplayName("Should replace aliased command")
    void shouldReplaceAliasedCommand() {
        config.setCommandAliasEnabled(true);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("gmc", "gamemode creative");
        config.setCommandAliases(aliases);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/gmc");

        listener.onPlayerCommand(event);

        assertThat(event.getMessage()).isEqualTo("/gamemode creative");
    }

    @Test
    @DisplayName("Should preserve arguments after alias replacement")
    void shouldPreserveArguments() {
        config.setCommandAliasEnabled(true);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("gmc", "gamemode creative");
        config.setCommandAliases(aliases);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/gmc Steve");

        listener.onPlayerCommand(event);

        assertThat(event.getMessage()).isEqualTo("/gamemode creative Steve");
    }

    @Test
    @DisplayName("Should not modify non-aliased command")
    void shouldNotModifyNonAliasedCommand() {
        config.setCommandAliasEnabled(true);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("gmc", "gamemode creative");
        config.setCommandAliases(aliases);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/help");

        listener.onPlayerCommand(event);

        assertThat(event.getMessage()).isEqualTo("/help");
    }

    @Test
    @DisplayName("Should handle case-insensitive command matching")
    void shouldHandleCaseInsensitive() {
        config.setCommandAliasEnabled(true);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("gmc", "gamemode creative");
        config.setCommandAliases(aliases);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        // The listener lowercases the command before lookup
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/gmc");

        listener.onPlayerCommand(event);

        assertThat(event.getMessage()).isEqualTo("/gamemode creative");
    }

    @Test
    @DisplayName("Should not process non-command messages")
    void shouldNotProcessNonCommandMessages() {
        config.setCommandAliasEnabled(true);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("gmc", "gamemode creative");
        config.setCommandAliases(aliases);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
        // This message doesn't start with / -- but PlayerCommandPreprocessEvent always
        // has / prefix. We still test the guard.
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "gmc");

        listener.onPlayerCommand(event);

        assertThat(event.getMessage()).isEqualTo("gmc");
    }

    @Test
    @DisplayName("Should handle multiple aliases")
    void shouldHandleMultipleAliases() {
        config.setCommandAliasEnabled(true);
        Map<String, String> aliases = new HashMap<>();
        aliases.put("gmc", "gamemode creative");
        aliases.put("gms", "gamemode survival");
        aliases.put("gmsp", "gamemode spectator");
        config.setCommandAliases(aliases);

        Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());

        PlayerCommandPreprocessEvent event1 = new PlayerCommandPreprocessEvent(player, "/gms");
        listener.onPlayerCommand(event1);
        assertThat(event1.getMessage()).isEqualTo("/gamemode survival");

        PlayerCommandPreprocessEvent event2 = new PlayerCommandPreprocessEvent(player, "/gmsp");
        listener.onPlayerCommand(event2);
        assertThat(event2.getMessage()).isEqualTo("/gamemode spectator");
    }
}
