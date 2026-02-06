package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.plugins.essentials.config.WelcomeConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for JoinQuitListener.
 * <p>
 * 测试加入/退出消息监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("JoinQuitListener Tests (Mockito)")
class JoinQuitListenerMockitoTest {

    private JoinQuitListener listener;
    private EssentialsConfig config;
    private WelcomeConfig welcomeConfig;
    private SpawnConfig spawnConfig;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        listener = new JoinQuitListener();
        config = new EssentialsConfig();
        welcomeConfig = new WelcomeConfig();
        spawnConfig = new SpawnConfig();

        EssentialsTestHelper.setField(listener, "config", config);
        EssentialsTestHelper.setField(listener, "welcomeConfig", welcomeConfig);
        EssentialsTestHelper.setField(listener, "spawnConfig", spawnConfig);

        // PlaceholderAPI not present
        PluginManager pm = EssentialsTestHelper.getMockServer().getPluginManager();
        lenient().when(pm.getPlugin("PlaceholderAPI")).thenReturn(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("onPlayerJoin")
    class JoinTests {

        @Test
        @DisplayName("Should set custom join message when enabled")
        void shouldSetCustomJoinMessage() {
            config.setJoinMessageEnabled(true);
            config.setJoinMessageFormat("&a%player_name% joined!");
            config.setJoinWelcomeEnabled(false);
            welcomeConfig.setTitleEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "Steve joined");

            listener.onPlayerJoin(event);

            assertThat(event.getJoinMessage()).contains("Steve");
            assertThat(event.getJoinMessage()).contains("\u00a7a"); // color code
        }

        @Test
        @DisplayName("Should not modify join message when disabled")
        void shouldNotModifyJoinMessageWhenDisabled() {
            config.setJoinMessageEnabled(false);
            config.setJoinWelcomeEnabled(false);
            welcomeConfig.setTitleEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "Original message");

            listener.onPlayerJoin(event);

            assertThat(event.getJoinMessage()).isEqualTo("Original message");
        }

        @Test
        @DisplayName("Should send welcome message when enabled")
        void shouldSendWelcomeMessage() {
            config.setJoinMessageEnabled(false);
            config.setJoinWelcomeEnabled(true);
            config.setWelcomeMessageLines(Arrays.asList("&6Welcome %player_name%!", "&7Enjoy!"));
            welcomeConfig.setTitleEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player, times(2)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not send welcome message when disabled")
        void shouldNotSendWelcomeMessageWhenDisabled() {
            config.setJoinMessageEnabled(false);
            config.setJoinWelcomeEnabled(false);
            welcomeConfig.setTitleEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should send welcome title when enabled")
        void shouldSendWelcomeTitle() {
            config.setJoinMessageEnabled(false);
            config.setJoinWelcomeEnabled(false);
            welcomeConfig.setTitleEnabled(true);
            welcomeConfig.setTitleMain("&6Welcome");
            welcomeConfig.setTitleSub("&7%player%");

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player).sendTitle(anyString(), anyString(), eq(10), eq(70), eq(20));
        }

        @Test
        @DisplayName("Should teleport first-time player to spawn when enabled")
        void shouldTeleportFirstTimePlayerToSpawn() throws Exception {
            config.setJoinMessageEnabled(false);
            config.setJoinWelcomeEnabled(false);
            config.setSpawnEnabled(true);
            welcomeConfig.setTitleEnabled(false);
            spawnConfig.setTeleportOnFirstJoin(true);

            World world = EssentialsTestHelper.createMockWorld("world");
            when(EssentialsTestHelper.getMockServer().getWorld("world")).thenReturn(world);

            Player player = EssentialsTestHelper.createMockPlayer("NewPlayer", UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(false);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should not teleport returning player to spawn")
        void shouldNotTeleportReturningPlayer() throws Exception {
            config.setJoinMessageEnabled(false);
            config.setJoinWelcomeEnabled(false);
            config.setSpawnEnabled(true);
            welcomeConfig.setTitleEnabled(false);
            spawnConfig.setTeleportOnFirstJoin(true);

            Player player = EssentialsTestHelper.createMockPlayer("OldPlayer", UUID.randomUUID());
            when(player.hasPlayedBefore()).thenReturn(true);
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            verify(player, never()).teleport(any(Location.class));
        }

        @Test
        @DisplayName("Should replace placeholders in join message")
        void shouldReplacePlaceholdersInJoinMessage() {
            config.setJoinMessageEnabled(true);
            config.setJoinMessageFormat("%player_name% joined (Online: %online_players%/%max_players%)");
            config.setJoinWelcomeEnabled(false);
            welcomeConfig.setTitleEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Alex", UUID.randomUUID());
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            listener.onPlayerJoin(event);

            assertThat(event.getJoinMessage()).contains("Alex");
            assertThat(event.getJoinMessage()).contains("0");
            assertThat(event.getJoinMessage()).contains("100");
        }
    }

    @Nested
    @DisplayName("onPlayerQuit")
    class QuitTests {

        @Test
        @DisplayName("Should set custom quit message when enabled")
        void shouldSetCustomQuitMessage() {
            config.setQuitMessageEnabled(true);
            config.setQuitMessageFormat("&c%player_name% left!");

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerQuitEvent event = new PlayerQuitEvent(player, "Steve left");

            listener.onPlayerQuit(event);

            assertThat(event.getQuitMessage()).contains("Steve");
            assertThat(event.getQuitMessage()).contains("\u00a7c");
        }

        @Test
        @DisplayName("Should not modify quit message when disabled")
        void shouldNotModifyQuitMessageWhenDisabled() {
            config.setQuitMessageEnabled(false);

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            PlayerQuitEvent event = new PlayerQuitEvent(player, "Original quit");

            listener.onPlayerQuit(event);

            assertThat(event.getQuitMessage()).isEqualTo("Original quit");
        }

        @Test
        @DisplayName("Should replace player placeholder in quit message")
        void shouldReplacePlayerPlaceholder() {
            config.setQuitMessageEnabled(true);
            config.setQuitMessageFormat("{player} has left the server");

            Player player = EssentialsTestHelper.createMockPlayer("Alex", UUID.randomUUID());
            PlayerQuitEvent event = new PlayerQuitEvent(player, "");

            listener.onPlayerQuit(event);

            assertThat(event.getQuitMessage()).contains("Alex");
        }
    }
}
