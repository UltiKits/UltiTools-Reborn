package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AnnouncementService.
 * <p>
 * 测试公告服务（聊天、Boss栏、标题）。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("AnnouncementService Tests")
class AnnouncementServiceTest {

    private AnnouncementService service;
    private EssentialsConfig mockConfig;
    private Plugin mockBukkitPlugin;
    private Server mockServer;
    private BukkitScheduler mockScheduler;
    private PluginManager mockPluginManager;

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();

        mockConfig = mock(EssentialsConfig.class);
        mockBukkitPlugin = mock(Plugin.class);
        mockServer = EssentialsTestHelper.getMockServer();
        mockScheduler = mockServer.getScheduler();
        mockPluginManager = mockServer.getPluginManager();

        service = new AnnouncementService();
        setField(service, "config", mockConfig);
        setField(service, "bukkitPlugin", mockBukkitPlugin);
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    // --- Reflection helpers ---

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); // NOPMD - test reflection
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, BossBar> getActiveBossBars() throws Exception {
        Field field = AnnouncementService.class.getDeclaredField("activeBossBars");
        field.setAccessible(true); // NOPMD - test reflection
        return (Map<UUID, BossBar>) field.get(service);
    }

    private int getIndex(String fieldName) throws Exception {
        Field field = AnnouncementService.class.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD - test reflection
        return (int) field.get(service);
    }

    private void setIndex(String fieldName, int value) throws Exception {
        Field field = AnnouncementService.class.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD - test reflection
        field.set(service, value);
    }

    private void invokePrivateMethod(String methodName) throws Exception {
        Method method = AnnouncementService.class.getDeclaredMethod(methodName);
        method.setAccessible(true); // NOPMD - test reflection
        method.invoke(service);
    }

    @Nested
    @DisplayName("Chat Announcements")
    class ChatAnnouncements {

        @Test
        @DisplayName("broadcastSendsMessageToAllOnlinePlayers")
        void broadcastSendsMessageToAllOnlinePlayers() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementChatMessages())
                    .thenReturn(Arrays.asList("Welcome to the server!"));
            when(mockConfig.getAnnouncementChatPrefix()).thenReturn("&6[Announcement] &f");

            Player player1 = EssentialsTestHelper.createMockPlayer("Player1", UUID.randomUUID());
            Player player2 = EssentialsTestHelper.createMockPlayer("Player2", UUID.randomUUID());
            Collection<Player> onlinePlayers = Arrays.asList(player1, player2);
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) onlinePlayers);

            // Act
            invokePrivateMethod("broadcastChatAnnouncement");

            // Assert
            verify(player1, times(1)).sendMessage(anyString());
            verify(player2, times(1)).sendMessage(anyString());
        }

        @Test
        @DisplayName("broadcastRotatesThroughMessages")
        void broadcastRotatesThroughMessages() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementChatMessages())
                    .thenReturn(Arrays.asList("Message 1", "Message 2", "Message 3"));
            when(mockConfig.getAnnouncementChatPrefix()).thenReturn("[A] ");

            Player player = EssentialsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            Collection<Player> onlinePlayers = Collections.singletonList(player);
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) onlinePlayers);

            // Mock PlaceholderAPI not present
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act - call twice
            invokePrivateMethod("broadcastChatAnnouncement");
            int firstIndex = getIndex("chatIndex");
            invokePrivateMethod("broadcastChatAnnouncement");
            int secondIndex = getIndex("chatIndex");

            // Assert - index should increment
            assertThat(firstIndex).isEqualTo(1);
            assertThat(secondIndex).isEqualTo(2);
        }

        @Test
        @DisplayName("broadcastAppliesChatPrefix")
        void broadcastAppliesChatPrefix() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementChatMessages())
                    .thenReturn(Collections.singletonList("Test message"));
            when(mockConfig.getAnnouncementChatPrefix()).thenReturn("[PREFIX] ");

            Player player = EssentialsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("broadcastChatAnnouncement");

            // Assert - capture the message
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(player, times(1)).sendMessage(messageCaptor.capture());
            assertThat(messageCaptor.getValue()).contains("[PREFIX]");
        }

        @Test
        @DisplayName("broadcastAppliesColorCodes")
        void broadcastAppliesColorCodes() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementChatMessages())
                    .thenReturn(Collections.singletonList("&aGreen text"));
            when(mockConfig.getAnnouncementChatPrefix()).thenReturn("&6[A] ");

            Player player = EssentialsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("broadcastChatAnnouncement");

            // Assert - color codes should be translated (§a for &a, §6 for &6)
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(player, times(1)).sendMessage(messageCaptor.capture());
            assertThat(messageCaptor.getValue()).contains("\u00a7a").contains("\u00a76");
        }

        @Test
        @DisplayName("broadcastReplacesPlayerNamePlaceholder")
        void broadcastReplacesPlayerNamePlaceholder() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementChatMessages())
                    .thenReturn(Collections.singletonList("Hello %player_name%!"));
            when(mockConfig.getAnnouncementChatPrefix()).thenReturn("");

            Player player = EssentialsTestHelper.createMockPlayer("Steve", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("broadcastChatAnnouncement");

            // Assert - %player_name% should be replaced with actual name
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(player, times(1)).sendMessage(messageCaptor.capture());
            assertThat(messageCaptor.getValue()).contains("Steve");
        }

        @Test
        @DisplayName("broadcastSkipsWhenEmptyMessageList")
        void broadcastSkipsWhenEmptyMessageList() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementChatMessages()).thenReturn(Collections.emptyList());

            Player player = EssentialsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));

            // Act
            invokePrivateMethod("broadcastChatAnnouncement");

            // Assert - no messages should be sent
            verify(player, never()).sendMessage(anyString());
        }
    }

    @Nested
    @DisplayName("BossBar Announcements")
    class BossBarAnnouncements {

        @Test
        @DisplayName("showBossBarCreatesBarForAllPlayers")
        void showBossBarCreatesBarForAllPlayers() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementBossBarMessages())
                    .thenReturn(Collections.singletonList("Test BossBar"));
            when(mockConfig.getAnnouncementBossBarColor()).thenReturn("BLUE");
            when(mockConfig.getAnnouncementBossBarDuration()).thenReturn(10);

            Player player1 = EssentialsTestHelper.createMockPlayer("Player1", UUID.randomUUID());
            Player player2 = EssentialsTestHelper.createMockPlayer("Player2", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Arrays.asList(player1, player2));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Mock Bukkit.createBossBar
            BossBar mockBossBar1 = mock(BossBar.class);
            BossBar mockBossBar2 = mock(BossBar.class);
            when(mockServer.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
                    .thenReturn(mockBossBar1, mockBossBar2);

            // Mock scheduler for the delayed removal task
            BukkitTask mockTask = mock(BukkitTask.class);
            when(mockScheduler.runTaskLater(eq(mockBukkitPlugin), any(Runnable.class), anyLong()))
                    .thenReturn(mockTask);

            // Act
            invokePrivateMethod("showBossBarAnnouncement");

            // Assert - boss bars created for both players
            verify(mockBossBar1, times(1)).addPlayer(player1);
            verify(mockBossBar2, times(1)).addPlayer(player2);
            assertThat(getActiveBossBars()).hasSize(2);
        }

        @Test
        @DisplayName("showBossBarRemovesPreviousBars")
        void showBossBarRemovesPreviousBars() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementBossBarMessages())
                    .thenReturn(Collections.singletonList("BossBar 1"));
            when(mockConfig.getAnnouncementBossBarColor()).thenReturn("GREEN");
            when(mockConfig.getAnnouncementBossBarDuration()).thenReturn(5);

            Player player = EssentialsTestHelper.createMockPlayer("Player", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            BossBar oldBossBar = mock(BossBar.class);
            BossBar newBossBar = mock(BossBar.class);
            when(mockServer.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
                    .thenReturn(newBossBar);

            BukkitTask mockTask = mock(BukkitTask.class);
            when(mockScheduler.runTaskLater(eq(mockBukkitPlugin), any(Runnable.class), anyLong()))
                    .thenReturn(mockTask);

            // Manually add an old boss bar
            getActiveBossBars().put(player.getUniqueId(), oldBossBar);

            // Act
            invokePrivateMethod("showBossBarAnnouncement");

            // Assert - old boss bar should be removed
            verify(oldBossBar, times(1)).removeAll();
        }

        @Test
        @DisplayName("showBossBarUsesConfigColor")
        void showBossBarUsesConfigColor() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementBossBarMessages())
                    .thenReturn(Collections.singletonList("Red BossBar"));
            when(mockConfig.getAnnouncementBossBarColor()).thenReturn("RED");
            when(mockConfig.getAnnouncementBossBarDuration()).thenReturn(10);

            Player player = EssentialsTestHelper.createMockPlayer("Player", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            BossBar mockBossBar = mock(BossBar.class);
            when(mockServer.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
                    .thenReturn(mockBossBar);

            BukkitTask mockTask = mock(BukkitTask.class);
            when(mockScheduler.runTaskLater(eq(mockBukkitPlugin), any(Runnable.class), anyLong()))
                    .thenReturn(mockTask);

            // Act
            invokePrivateMethod("showBossBarAnnouncement");

            // Assert - verify RED color was used
            verify(mockServer, times(1)).createBossBar(anyString(), eq(BarColor.RED), eq(BarStyle.SOLID));
        }
    }

    @Nested
    @DisplayName("Title Announcements")
    class TitleAnnouncements {

        @Test
        @DisplayName("showTitleSendsToAllPlayers")
        void showTitleSendsToAllPlayers() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementTitleMessages())
                    .thenReturn(Collections.singletonList("Welcome Title"));
            when(mockConfig.getAnnouncementTitleFadeIn()).thenReturn(10);
            when(mockConfig.getAnnouncementTitleStay()).thenReturn(70);
            when(mockConfig.getAnnouncementTitleFadeOut()).thenReturn(20);

            Player player1 = EssentialsTestHelper.createMockPlayer("Player1", UUID.randomUUID());
            Player player2 = EssentialsTestHelper.createMockPlayer("Player2", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Arrays.asList(player1, player2));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("showTitleAnnouncement");

            // Assert - both players should receive the title
            verify(player1, times(1)).sendTitle(anyString(), anyString(), eq(10), eq(70), eq(20));
            verify(player2, times(1)).sendTitle(anyString(), anyString(), eq(10), eq(70), eq(20));
        }

        @Test
        @DisplayName("showTitleSplitsSubtitleByDoublePipe")
        void showTitleSplitsSubtitleByDoublePipe() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementTitleMessages())
                    .thenReturn(Collections.singletonList("Main Title||Subtitle Text"));
            when(mockConfig.getAnnouncementTitleFadeIn()).thenReturn(10);
            when(mockConfig.getAnnouncementTitleStay()).thenReturn(70);
            when(mockConfig.getAnnouncementTitleFadeOut()).thenReturn(20);

            Player player = EssentialsTestHelper.createMockPlayer("Player", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("showTitleAnnouncement");

            // Assert - verify title and subtitle are split correctly
            verify(player, times(1)).sendTitle(
                    argThat(title -> title != null && title.contains("Main Title")),
                    argThat(subtitle -> subtitle != null && subtitle.contains("Subtitle Text")),
                    eq(10), eq(70), eq(20));
        }

        @Test
        @DisplayName("showTitleEmptySubtitleWhenNoPipe")
        void showTitleEmptySubtitleWhenNoPipe() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementTitleMessages())
                    .thenReturn(Collections.singletonList("Only Title"));
            when(mockConfig.getAnnouncementTitleFadeIn()).thenReturn(10);
            when(mockConfig.getAnnouncementTitleStay()).thenReturn(70);
            when(mockConfig.getAnnouncementTitleFadeOut()).thenReturn(20);

            Player player = EssentialsTestHelper.createMockPlayer("Player", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("showTitleAnnouncement");

            // Assert - subtitle should be empty string
            verify(player, times(1)).sendTitle(
                    argThat(title -> title != null && title.contains("Only Title")),
                    eq(""),
                    eq(10), eq(70), eq(20));
        }

        @Test
        @DisplayName("showTitleAppliesTimingFromConfig")
        void showTitleAppliesTimingFromConfig() throws Exception {
            // Arrange
            when(mockConfig.getAnnouncementTitleMessages())
                    .thenReturn(Collections.singletonList("Test"));
            when(mockConfig.getAnnouncementTitleFadeIn()).thenReturn(15);
            when(mockConfig.getAnnouncementTitleStay()).thenReturn(100);
            when(mockConfig.getAnnouncementTitleFadeOut()).thenReturn(25);

            Player player = EssentialsTestHelper.createMockPlayer("Player", UUID.randomUUID());
            when(mockServer.getOnlinePlayers()).thenReturn((Collection) Collections.singletonList(player));
            when(mockPluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);

            // Act
            invokePrivateMethod("showTitleAnnouncement");

            // Assert - verify exact timing values from config
            verify(player, times(1)).sendTitle(anyString(), anyString(), eq(15), eq(100), eq(25));
        }
    }

    @Nested
    @DisplayName("Player Quit")
    class PlayerQuit {

        @Test
        @DisplayName("onPlayerQuitRemovesBossBar")
        void onPlayerQuitRemovesBossBar() throws Exception {
            // Arrange
            UUID playerId = UUID.randomUUID();
            BossBar mockBossBar = mock(BossBar.class);
            getActiveBossBars().put(playerId, mockBossBar);

            Player player = EssentialsTestHelper.createMockPlayer("TestPlayer", playerId);

            // Act
            service.onPlayerQuit(player);

            // Assert
            verify(mockBossBar, times(1)).removePlayer(player);
            assertThat(getActiveBossBars()).doesNotContainKey(playerId);
        }

        @Test
        @DisplayName("onPlayerQuitIgnoresPlayerWithoutBar")
        void onPlayerQuitIgnoresPlayerWithoutBar() throws Exception {
            // Arrange
            Player player = EssentialsTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());

            // Act & Assert - should not throw exception
            assertThatCode(() -> service.onPlayerQuit(player))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Task Lifecycle")
    class TaskLifecycle {

        @Test
        @DisplayName("startTasksDoesNothingWhenAllDisabled")
        void startTasksDoesNothingWhenAllDisabled() {
            // Arrange
            when(mockConfig.isAnnouncementChatEnabled()).thenReturn(false);
            when(mockConfig.isAnnouncementBossBarEnabled()).thenReturn(false);
            when(mockConfig.isAnnouncementTitleEnabled()).thenReturn(false);

            // Act
            service.startTasks();

            // Assert - no tasks should be scheduled
            verify(mockScheduler, never()).runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong());
        }

        @Test
        @DisplayName("shutdownCancelsAllTasks")
        void shutdownCancelsAllTasks() throws Exception {
            // Arrange - manually set up tasks
            BukkitTask chatTask = mock(BukkitTask.class);
            BukkitTask bossBarTask = mock(BukkitTask.class);
            BukkitTask titleTask = mock(BukkitTask.class);

            setField(service, "chatTask", chatTask);
            setField(service, "bossBarTask", bossBarTask);
            setField(service, "titleTask", titleTask);

            // Act
            service.shutdown();

            // Assert - all tasks should be cancelled
            verify(chatTask, times(1)).cancel();
            verify(bossBarTask, times(1)).cancel();
            verify(titleTask, times(1)).cancel();
        }

        @Test
        @DisplayName("shutdownClearsActiveBossBars")
        void shutdownClearsActiveBossBars() throws Exception {
            // Arrange - add a mock boss bar
            UUID playerId = UUID.randomUUID();
            BossBar mockBossBar = mock(BossBar.class);
            getActiveBossBars().put(playerId, mockBossBar);

            // Act
            service.shutdown();

            // Assert
            verify(mockBossBar, times(1)).removeAll();
            assertThat(getActiveBossBars()).isEmpty();
        }

        @Test
        @DisplayName("reloadResetsIndicesAndRestarts")
        void reloadResetsIndicesAndRestarts() throws Exception {
            // Arrange - set indices to non-zero values
            setIndex("chatIndex", 5);
            setIndex("bossBarIndex", 3);
            setIndex("titleIndex", 7);

            when(mockConfig.isAnnouncementChatEnabled()).thenReturn(false);
            when(mockConfig.isAnnouncementBossBarEnabled()).thenReturn(false);
            when(mockConfig.isAnnouncementTitleEnabled()).thenReturn(false);

            // Act
            service.reload();

            // Assert - indices should be reset to 0
            assertThat(getIndex("chatIndex")).isEqualTo(0);
            assertThat(getIndex("bossBarIndex")).isEqualTo(0);
            assertThat(getIndex("titleIndex")).isEqualTo(0);
        }
    }
}
