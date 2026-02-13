package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.AnnouncementConfig;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AnnouncementService — chat, boss bar, and title broadcasts.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("AnnouncementService Tests")
class AnnouncementServiceTest {

    private AnnouncementService service;
    private AnnouncementConfig config;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();

        config = new AnnouncementConfig();
        service = new AnnouncementService(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    // ==================== Chat Broadcast Tests ====================

    @Nested
    @DisplayName("Chat Broadcast")
    class ChatBroadcastTests {

        @Test
        @DisplayName("Should not broadcast when chat announcements disabled")
        void shouldSkipWhenDisabled() {
            config.setChatEnabled(false);
            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());

            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastChat();

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not broadcast when no players online")
        void shouldSkipWhenNoPlayers() {
            config.setChatEnabled(true);
            config.setChatMessages(Arrays.asList("Hello"));
            doReturn(Collections.emptyList()).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            // Should not throw
            service.broadcastChat();
        }

        @Test
        @DisplayName("Should not broadcast when messages list is empty")
        void shouldSkipWhenNoMessages() {
            config.setChatEnabled(true);
            config.setChatMessages(Collections.<String>emptyList());

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastChat();

            verify(player, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should broadcast with prefix")
        void shouldBroadcastWithPrefix() {
            config.setChatEnabled(true);
            config.setChatPrefix("&6[Announcement] &f");
            config.setChatMessages(Arrays.asList("Welcome!"));

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastChat();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("Welcome!");
            // Color codes should be translated
            assertThat(captor.getValue()).contains("\u00a76");
        }

        @Test
        @DisplayName("Should rotate through messages")
        void shouldRotateMessages() {
            config.setChatEnabled(true);
            config.setChatPrefix("");
            config.setChatMessages(Arrays.asList("Msg1", "Msg2", "Msg3"));

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastChat();  // Msg1
            service.broadcastChat();  // Msg2
            service.broadcastChat();  // Msg3
            service.broadcastChat();  // wrap around → Msg1

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player, times(4)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).containsExactly("Msg1", "Msg2", "Msg3", "Msg1");
        }

        @Test
        @DisplayName("Should broadcast to all online players")
        void shouldBroadcastToAll() {
            config.setChatEnabled(true);
            config.setChatPrefix("");
            config.setChatMessages(Arrays.asList("Hello"));

            Player p1 = ChatTestHelper.createMockPlayer("Player1", java.util.UUID.randomUUID());
            Player p2 = ChatTestHelper.createMockPlayer("Player2", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Arrays.asList(p1, p2);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastChat();

            verify(p1).sendMessage("Hello");
            verify(p2).sendMessage("Hello");
        }
    }

    // ==================== BossBar Broadcast Tests ====================

    @Nested
    @DisplayName("BossBar Broadcast")
    class BossBarBroadcastTests {

        @Test
        @DisplayName("Should skip when boss bar disabled")
        void shouldSkipWhenDisabled() {
            config.setBossBarEnabled(false);

            service.broadcastBossBar();

            // No BossBar should be created
            verify(ChatTestHelper.getMockServer(), never()).getOnlinePlayers();
        }

        @Test
        @DisplayName("Should skip when no messages")
        void shouldSkipWhenNoMessages() {
            config.setBossBarEnabled(true);
            config.setBossBarMessages(Collections.<String>emptyList());

            service.broadcastBossBar();
        }

        @Test
        @DisplayName("Should skip when no players online")
        void shouldSkipWhenNoPlayers() {
            config.setBossBarEnabled(true);
            config.setBossBarMessages(Arrays.asList("Test"));
            doReturn(Collections.emptyList()).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastBossBar();
        }

        @Test
        @DisplayName("Should create boss bar and schedule removal")
        void shouldCreateBossBarAndScheduleRemoval() {
            config.setBossBarEnabled(true);
            config.setBossBarMessages(Arrays.asList("BossBar Message"));
            config.setBossBarDuration(10);
            config.setBossBarColor("BLUE");

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            BossBar mockBossBar = mock(BossBar.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
                bukkit.when(() -> Bukkit.createBossBar(anyString(), any(), any())).thenReturn(mockBossBar);

                Plugin mockBukkitPlugin = mock(Plugin.class);
                org.bukkit.plugin.PluginManager pm = mock(org.bukkit.plugin.PluginManager.class);
                bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
                when(pm.getPlugin("UltiTools")).thenReturn(mockBukkitPlugin);

                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

                service.broadcastBossBar();

                verify(mockBossBar).addPlayer(player);
                verify(scheduler).runTaskLater(eq(mockBukkitPlugin), any(Runnable.class), eq(200L));
            }
        }
    }

    // ==================== Title Broadcast Tests ====================

    @Nested
    @DisplayName("Title Broadcast")
    class TitleBroadcastTests {

        @Test
        @DisplayName("Should skip when title disabled")
        void shouldSkipWhenDisabled() {
            config.setTitleEnabled(false);

            service.broadcastTitle();
        }

        @Test
        @DisplayName("Should skip when no messages")
        void shouldSkipWhenNoMessages() {
            config.setTitleEnabled(true);
            config.setTitleMessages(Collections.<String>emptyList());

            service.broadcastTitle();
        }

        @Test
        @DisplayName("Should send title with separator")
        void shouldSendTitleWithSeparator() {
            config.setTitleEnabled(true);
            config.setTitleMessages(Arrays.asList("&6Welcome!||&7Enjoy your stay"));
            config.setTitleFadeIn(10);
            config.setTitleStay(70);
            config.setTitleFadeOut(20);

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastTitle();

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subtitleCaptor = ArgumentCaptor.forClass(String.class);
            verify(player).sendTitle(titleCaptor.capture(), subtitleCaptor.capture(), eq(10), eq(70), eq(20));

            assertThat(titleCaptor.getValue()).contains("\u00a76");
            assertThat(titleCaptor.getValue()).contains("Welcome!");
            assertThat(subtitleCaptor.getValue()).contains("Enjoy your stay");
        }

        @Test
        @DisplayName("Should handle message without separator")
        void shouldHandleNoSeparator() {
            config.setTitleEnabled(true);
            config.setTitleMessages(Arrays.asList("&6Title Only"));
            config.setTitleFadeIn(10);
            config.setTitleStay(70);
            config.setTitleFadeOut(20);

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastTitle();

            ArgumentCaptor<String> subtitleCaptor = ArgumentCaptor.forClass(String.class);
            verify(player).sendTitle(anyString(), subtitleCaptor.capture(), eq(10), eq(70), eq(20));
            assertThat(subtitleCaptor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("Should rotate through title messages")
        void shouldRotateTitles() {
            config.setTitleEnabled(true);
            config.setTitleMessages(Arrays.asList("Title1||Sub1", "Title2||Sub2"));
            config.setTitleFadeIn(10);
            config.setTitleStay(70);
            config.setTitleFadeOut(20);

            Player player = ChatTestHelper.createMockPlayer("TestPlayer", java.util.UUID.randomUUID());
            List<Player> onlinePlayers = Collections.singletonList(player);
            doReturn(onlinePlayers).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastTitle();
            service.broadcastTitle();

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            verify(player, times(2)).sendTitle(titleCaptor.capture(), anyString(), eq(10), eq(70), eq(20));

            assertThat(titleCaptor.getAllValues().get(0)).contains("Title1");
            assertThat(titleCaptor.getAllValues().get(1)).contains("Title2");
        }

        @Test
        @DisplayName("Should skip when no players online")
        void shouldSkipWhenNoPlayers() {
            config.setTitleEnabled(true);
            config.setTitleMessages(Arrays.asList("Test||Msg"));
            doReturn(Collections.emptyList()).when(ChatTestHelper.getMockServer()).getOnlinePlayers();

            service.broadcastTitle();
        }
    }
}
