package com.ultikits.ultitools.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ServerMonitorManager;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

/**
 * EnhancedPlayerEventListener 测试 - 使用 MockBukkit
 */
@DisplayName("EnhancedPlayerEventListener 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EnhancedPlayerEventListenerTest {

    private ServerMock server;
    private EnhancedPlayerEventListener listener;
    private ServerMonitorManager mockMonitorManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock ServerMonitorManager
        mockMonitorManager = mock(ServerMonitorManager.class);
        when(UltiTools.getInstance().getServerMonitorManager()).thenReturn(mockMonitorManager);

        listener = new EnhancedPlayerEventListener();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("onPlayerJoin 测试")
    class OnPlayerJoinTests {

        @Test
        @DisplayName("监控开启时应该发送玩家加入事件")
        void shouldSendJoinEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined the game");

            // Act
            listener.onPlayerJoin(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("join"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("监控关闭时不应该发送事件")
        void shouldNotSendEventWhenNotMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(false);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined the game");

            // Act
            listener.onPlayerJoin(event);

            // Assert
            verify(mockMonitorManager, never()).sendPlayerEvent(any(), any(), any());
        }

        @Test
        @DisplayName("ServerMonitorManager 为 null 时不应该抛出异常")
        void shouldNotThrowWhenManagerIsNull() { // NOPMD - uses Mockito verify()
            // Arrange
            when(UltiTools.getInstance().getServerMonitorManager()).thenReturn(null);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "TestPlayer joined the game");

            // Act & Assert - 不应该抛出异常
            listener.onPlayerJoin(event);
        }
    }

    @Nested
    @DisplayName("onPlayerQuit 测试")
    class OnPlayerQuitTests {

        @Test
        @DisplayName("监控开启时应该发送玩家离开事件")
        void shouldSendQuitEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerQuitEvent event = new PlayerQuitEvent(player, "TestPlayer left the game");

            // Act
            listener.onPlayerQuit(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("leave"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("监控关闭时不应该发送事件")
        void shouldNotSendEventWhenNotMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(false);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerQuitEvent event = new PlayerQuitEvent(player, "TestPlayer left the game");

            // Act
            listener.onPlayerQuit(event);

            // Assert
            verify(mockMonitorManager, never()).sendPlayerEvent(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("onPlayerChat 测试")
    class OnPlayerChatTests {

        @Test
        @DisplayName("监控开启时应该发送聊天事件")
        void shouldSendChatEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "Hello World", 
                    new java.util.HashSet<>());

            // Act
            listener.onPlayerChat(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("chat"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("应该包含聊天消息和取消状态")
        void shouldIncludeMessageAndCancelledStatus() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, "Test Message", 
                    new java.util.HashSet<>());
            event.setCancelled(true);

            // Capture the JSONObject
            final JsonObject[] capturedJson = new JsonObject[1];
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            org.mockito.Mockito.doAnswer(invocation -> {
                capturedJson[0] = invocation.getArgument(2);
                return null;
            }).when(mockMonitorManager).sendPlayerEvent(any(), any(), any());

            // Act
            listener.onPlayerChat(event);

            // Assert
            assertThat(capturedJson[0]).isNotNull();
            assertThat(capturedJson[0].get("message").getAsString()).isEqualTo("Test Message");
            assertThat(capturedJson[0].get("cancelled").getAsBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("onPlayerDeath 测试")
    class OnPlayerDeathTests {

        @Test
        @DisplayName("监控开启时应该发送死亡事件")
        void shouldSendDeathEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerDeathEvent event = new PlayerDeathEvent(player, new java.util.ArrayList<>(), 0, "TestPlayer died");

            // Act
            listener.onPlayerDeath(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("death"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("有击杀者时应该包含击杀者信息")
        void shouldIncludeKillerInfo() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            server.addPlayer("Victim");
            PlayerMock killer = server.addPlayer("Killer");
            
            // Mock the killer
            PlayerMock mockedVictim = mock(PlayerMock.class);
            when(mockedVictim.getKiller()).thenReturn(killer);
            when(mockedVictim.getName()).thenReturn("Victim");
            
            PlayerDeathEvent event = new PlayerDeathEvent(mockedVictim, new java.util.ArrayList<>(), 0, "Victim was slain by Killer");

            // Capture the JSONObject
            final JsonObject[] capturedJson = new JsonObject[1];
            org.mockito.Mockito.doAnswer(invocation -> {
                capturedJson[0] = invocation.getArgument(2);
                return null;
            }).when(mockMonitorManager).sendPlayerEvent(any(), any(), any());

            // Act
            listener.onPlayerDeath(event);

            // Assert
            assertThat(capturedJson[0]).isNotNull();
            assertThat(capturedJson[0].get("killer").getAsString()).isEqualTo("Killer");
        }
    }

    @Nested
    @DisplayName("onPlayerKick 测试")
    class OnPlayerKickTests {

        @Test
        @DisplayName("监控开启时应该发送踢出事件")
        void shouldSendKickEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerKickEvent event = new PlayerKickEvent(player, "Kicked for testing", "You were kicked");

            // Act
            listener.onPlayerKick(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("kick"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("应该包含踢出原因")
        void shouldIncludeKickReason() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerKickEvent event = new PlayerKickEvent(player, "Bad behavior", "You were kicked");

            // Capture the JSONObject
            final JsonObject[] capturedJson = new JsonObject[1];
            org.mockito.Mockito.doAnswer(invocation -> {
                capturedJson[0] = invocation.getArgument(2);
                return null;
            }).when(mockMonitorManager).sendPlayerEvent(any(), any(), any());

            // Act
            listener.onPlayerKick(event);

            // Assert
            assertThat(capturedJson[0]).isNotNull();
            assertThat(capturedJson[0].get("reason").getAsString()).isEqualTo("Bad behavior");
        }
    }

    @Nested
    @DisplayName("onPlayerCommandPreprocess 测试")
    class OnPlayerCommandPreprocessTests {

        @Test
        @DisplayName("监控开启时应该发送命令事件")
        void shouldSendCommandEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/help");

            // Act
            listener.onPlayerCommandPreprocess(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("command"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("应该包含命令内容")
        void shouldIncludeCommand() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/gamemode creative");

            // Capture the JSONObject
            final JsonObject[] capturedJson = new JsonObject[1];
            org.mockito.Mockito.doAnswer(invocation -> {
                capturedJson[0] = invocation.getArgument(2);
                return null;
            }).when(mockMonitorManager).sendPlayerEvent(any(), any(), any());

            // Act
            listener.onPlayerCommandPreprocess(event);

            // Assert
            assertThat(capturedJson[0]).isNotNull();
            assertThat(capturedJson[0].get("command").getAsString()).isEqualTo("/gamemode creative");
        }
    }

    @Nested
    @DisplayName("onPlayerChangedWorld 测试")
    class OnPlayerChangedWorldTests {

        @Test
        @DisplayName("监控开启时应该发送世界切换事件")
        void shouldSendWorldChangeEventWhenMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            WorldMock fromWorld = server.addSimpleWorld("world");
            WorldMock toWorld = server.addSimpleWorld("world_nether");
            player.setLocation(toWorld.getSpawnLocation());
            
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, fromWorld);

            // Act
            listener.onPlayerChangedWorld(event);

            // Assert
            verify(mockMonitorManager).sendPlayerEvent(eq("world_change"), eq(player), any(JsonObject.class));
        }

        @Test
        @DisplayName("应该包含源世界和目标世界信息")
        void shouldIncludeWorldInfo() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(true);
            PlayerMock player = server.addPlayer("TestPlayer");
            WorldMock fromWorld = server.addSimpleWorld("overworld");
            WorldMock toWorld = server.addSimpleWorld("the_end");
            player.setLocation(toWorld.getSpawnLocation());
            
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, fromWorld);

            // Capture the JSONObject
            final JsonObject[] capturedJson = new JsonObject[1];
            org.mockito.Mockito.doAnswer(invocation -> {
                capturedJson[0] = invocation.getArgument(2);
                return null;
            }).when(mockMonitorManager).sendPlayerEvent(any(), any(), any());

            // Act
            listener.onPlayerChangedWorld(event);

            // Assert
            assertThat(capturedJson[0]).isNotNull();
            assertThat(capturedJson[0].get("fromWorld").getAsString()).isEqualTo("overworld");
            assertThat(capturedJson[0].get("toWorld").getAsString()).isEqualTo("the_end");
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("所有事件在监控关闭时都不应该发送")
        void allEventsShouldNotSendWhenNotMonitoring() {
            // Arrange
            when(mockMonitorManager.isMonitoring()).thenReturn(false);
            PlayerMock player = server.addPlayer("TestPlayer");

            // Act - 触发所有事件类型
            listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
            listener.onPlayerQuit(new PlayerQuitEvent(player, "left"));
            listener.onPlayerChat(new AsyncPlayerChatEvent(false, player, "msg", new java.util.HashSet<>()));
            listener.onPlayerDeath(new PlayerDeathEvent(player, new java.util.ArrayList<>(), 0, "died"));
            listener.onPlayerKick(new PlayerKickEvent(player, "reason", "msg"));
            listener.onPlayerCommandPreprocess(new PlayerCommandPreprocessEvent(player, "/cmd"));
            
            WorldMock world = server.addSimpleWorld("world");
            player.setLocation(world.getSpawnLocation());
            listener.onPlayerChangedWorld(new PlayerChangedWorldEvent(player, world));

            // Assert - 不应该调用任何 sendPlayerEvent
            verify(mockMonitorManager, never()).sendPlayerEvent(any(), any(), any());
        }
    }
}
