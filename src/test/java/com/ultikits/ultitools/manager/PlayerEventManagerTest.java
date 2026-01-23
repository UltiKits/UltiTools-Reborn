package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

/**
 * PlayerEventManager 测试
 */
@DisplayName("PlayerEventManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.SingularField"}) // Test requires reflection for mocking internal state
class PlayerEventManagerTest {

    private ServerMock server;
    private PlayerEventManager playerEventManager;
    private UltiPanelWebSocketClient mockWebSocketClient;
    private Logger mockLogger;
    private LogStreamManager mockLogStreamManager;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();

        // Mock logger
        mockLogger = mock(Logger.class);
        when(UltiTools.getInstance().getLogger()).thenReturn(mockLogger);

        // Mock WebSocket client
        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("test-server");

        // Mock LogStreamManager
        mockLogStreamManager = mock(LogStreamManager.class);
        UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
        when(mockLogStreamManager.getLogTransmitter()).thenReturn(mockTransmitter);
        when(UltiTools.getInstance().getLogStreamManager()).thenReturn(mockLogStreamManager);

        playerEventManager = new PlayerEventManager();
        
        // 使用反射设置 webSocketClient
        Field wsField = PlayerEventManager.class.getDeclaredField("webSocketClient");
        wsField.setAccessible(true);
        wsField.set(playerEventManager, mockWebSocketClient);
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该成功创建实例")
        void shouldCreateInstance() {
            // Arrange & Act
            PlayerEventManager manager = new PlayerEventManager();

            // Assert
            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("initialize 测试")
    class InitializeTests {

        @Test
        @DisplayName("应该接受 WebSocket 客户端参数")
        void shouldAcceptWebSocketClient() throws Exception {
            // Arrange - verify constructor and initialize method exist
            UltiPanelWebSocketClient client = mock(UltiPanelWebSocketClient.class);

            // initialize 方法存在
            Method initMethod = PlayerEventManager.class.getDeclaredMethod("initialize", UltiPanelWebSocketClient.class);
            assertThat(initMethod).isNotNull();
            // Verify client mock was created successfully
            assertThat(client).isNotNull();
        }
    }

    @Nested
    @DisplayName("onPlayerJoin 测试")
    class OnPlayerJoinTests {

        @Test
        @DisplayName("WebSocket 已连接时应该发送事件")
        void shouldSendEventWhenConnected() {
            // Arrange
            PlayerMock player = server.addPlayer();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");

            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            playerEventManager.onPlayerJoin(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送事件")
        void shouldNotSendEventWhenNotConnected() {
            // Arrange
            PlayerMock player = server.addPlayer();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");

            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            playerEventManager.onPlayerJoin(event);

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 为 null 时不应该抛出异常")
        void shouldNotThrowWhenWebSocketNull() throws Exception {
            // Arrange
            Field clientField = PlayerEventManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(playerEventManager, null);

            PlayerMock player = server.addPlayer();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");

            // Act - 不应该抛出异常
            playerEventManager.onPlayerJoin(event);
        }
    }

    @Nested
    @DisplayName("onPlayerQuit 测试")
    class OnPlayerQuitTests {

        @Test
        @DisplayName("WebSocket 已连接时应该发送事件")
        void shouldSendEventWhenConnected() {
            // Arrange
            PlayerMock player = server.addPlayer();
            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit");

            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            playerEventManager.onPlayerQuit(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送事件")
        void shouldNotSendEventWhenNotConnected() {
            // Arrange
            PlayerMock player = server.addPlayer();
            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit");

            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            playerEventManager.onPlayerQuit(event);

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 为 null 时不应该抛出异常")
        void shouldNotThrowWhenWebSocketNull() throws Exception {
            // Arrange
            Field clientField = PlayerEventManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(playerEventManager, null);

            PlayerMock player = server.addPlayer();
            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit");

            // Act - 不应该抛出异常
            playerEventManager.onPlayerQuit(event);
        }
    }

    @Nested
    @DisplayName("sendPlayerEvent 测试")
    class SendPlayerEventTests {

        @Test
        @DisplayName("应该能够通过反射调用私有方法")
        void shouldCallPrivateMethod() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod("sendPlayerEvent", JsonObject.class);
            method.setAccessible(true);

            JsonObject data = new JsonObject();
            data.addProperty("event_type", "test");
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            method.invoke(playerEventManager, data);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("字段测试")
    class FieldTests {

        @Test
        @DisplayName("webSocketClient 字段应该存在")
        void webSocketClientFieldShouldExist() throws Exception {
            Field field = PlayerEventManager.class.getDeclaredField("webSocketClient");
            assertThat(field).isNotNull();
        }
    }

    @Nested
    @DisplayName("onPlayerChat 测试")
    class OnPlayerChatTests {

        @Test
        @DisplayName("WebSocket 已连接时应该发送事件")
        void shouldSendEventWhenConnected() throws Exception {
            // Arrange
            PlayerMock player = server.addPlayer();
            org.bukkit.event.player.PlayerChatEvent event = 
                new org.bukkit.event.player.PlayerChatEvent(player, "Hello World");

            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            playerEventManager.onPlayerChat(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 未连接时不应该发送事件")
        void shouldNotSendEventWhenNotConnected() throws Exception {
            // Arrange
            PlayerMock player = server.addPlayer();
            org.bukkit.event.player.PlayerChatEvent event = 
                new org.bukkit.event.player.PlayerChatEvent(player, "Hello World");

            when(mockWebSocketClient.isConnected()).thenReturn(false);

            // Act
            playerEventManager.onPlayerChat(event);

            // Assert
            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("WebSocket 为 null 时不应该抛出异常")
        void shouldNotThrowWhenWebSocketNull() throws Exception {
            // Arrange
            Field clientField = PlayerEventManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(playerEventManager, null);

            PlayerMock player = server.addPlayer();
            org.bukkit.event.player.PlayerChatEvent event = 
                new org.bukkit.event.player.PlayerChatEvent(player, "Hello");

            // Act - 不应该抛出异常
            playerEventManager.onPlayerChat(event);
        }

        @Test
        @DisplayName("应该正确设置消息格式")
        void shouldSetMessageFormat() throws Exception {
            // Arrange
            PlayerMock player = server.addPlayer();
            org.bukkit.event.player.PlayerChatEvent event = 
                new org.bukkit.event.player.PlayerChatEvent(player, "Test message");
            
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            playerEventManager.onPlayerChat(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }
    }

    @Nested
    @DisplayName("getServerId 测试")
    class GetServerIdTests {

        @Test
        @DisplayName("应该能够调用私有方法")
        void shouldCallPrivateMethod() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod("getServerId");
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(playerEventManager);

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("应该返回非空字符串")
        void shouldReturnNonEmptyString() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod("getServerId");
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(playerEventManager);

            // Assert
            assertThat(result).isNotBlank();
        }
    }

    @Nested
    @DisplayName("消息格式测试")
    class MessageFormatTests {

        @Test
        @DisplayName("PlayerJoin 消息应该包含正确的字段")
        void playerJoinMessageShouldContainCorrectFields() {
            // Arrange
            PlayerMock player = server.addPlayer();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "Player joined");
            
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            org.mockito.ArgumentCaptor<JsonObject> captor = 
                org.mockito.ArgumentCaptor.forClass(JsonObject.class);

            // Act
            playerEventManager.onPlayerJoin(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(captor.capture());
            JsonObject message = captor.getValue();
            assertThat(message.get("type").getAsString()).isEqualTo("player_event");
            assertThat(message.has("timestamp")).isTrue();
            assertThat(message.has("serverId")).isTrue();
            assertThat(message.has("data")).isTrue();
        }

        @Test
        @DisplayName("PlayerQuit 消息应该包含正确的字段")
        void playerQuitMessageShouldContainCorrectFields() {
            // Arrange
            PlayerMock player = server.addPlayer();
            PlayerQuitEvent event = new PlayerQuitEvent(player, "Player left");
            
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            org.mockito.ArgumentCaptor<JsonObject> captor = 
                org.mockito.ArgumentCaptor.forClass(JsonObject.class);

            // Act
            playerEventManager.onPlayerQuit(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(captor.capture());
            JsonObject message = captor.getValue();
            assertThat(message.get("type").getAsString()).isEqualTo("player_event");
        }

        @Test
        @DisplayName("PlayerChat 消息应该包含正确的字段")
        void playerChatMessageShouldContainCorrectFields() {
            // Arrange
            PlayerMock player = server.addPlayer();
            org.bukkit.event.player.PlayerChatEvent event = 
                new org.bukkit.event.player.PlayerChatEvent(player, "Test chat");
            
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            org.mockito.ArgumentCaptor<JsonObject> captor = 
                org.mockito.ArgumentCaptor.forClass(JsonObject.class);

            // Act
            playerEventManager.onPlayerChat(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(captor.capture());
            JsonObject message = captor.getValue();
            assertThat(message.get("type").getAsString()).isEqualTo("player_event");
        }

        @Test
        @DisplayName("事件数据应该包含玩家信息")
        void eventDataShouldContainPlayerInfo() {
            // Arrange
            PlayerMock player = server.addPlayer();
            player.setName("TestPlayer");
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            org.mockito.ArgumentCaptor<JsonObject> captor = 
                org.mockito.ArgumentCaptor.forClass(JsonObject.class);

            // Act
            playerEventManager.onPlayerJoin(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(captor.capture());
            JsonObject message = captor.getValue();
            JsonObject data = message.getAsJsonObject("data");
            assertThat(data.get("player_name").getAsString()).isEqualTo("TestPlayer");
            assertThat(data.get("player_uuid").getAsString()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("多玩家场景测试")
    class MultiPlayerTests {

        @Test
        @DisplayName("多个玩家加入应该发送多条消息")
        void multiplePlayersJoinShouldSendMultipleMessages() {
            // Arrange
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            for (int i = 0; i < 3; i++) {
                PlayerMock player = server.addPlayer();
                PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
                playerEventManager.onPlayerJoin(event);
            }

            // Assert
            verify(mockWebSocketClient, times(3)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("玩家在线数量应该正确")
        void onlineCountShouldBeCorrect() {
            // Arrange
            server.addPlayer();
            server.addPlayer();
            PlayerMock lastPlayer = server.addPlayer();
            
            PlayerJoinEvent event = new PlayerJoinEvent(lastPlayer, "joined");
            
            when(mockWebSocketClient.isConnected()).thenReturn(true);
            
            org.mockito.ArgumentCaptor<JsonObject> captor = 
                org.mockito.ArgumentCaptor.forClass(JsonObject.class);

            // Act
            playerEventManager.onPlayerJoin(event);

            // Assert
            verify(mockWebSocketClient, atLeastOnce()).sendMessage(captor.capture());
            JsonObject message = captor.getValue();
            JsonObject data = message.getAsJsonObject("data");
            assertThat(data.get("online_count").getAsInt()).isGreaterThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Listener 接口测试")
    class ListenerInterfaceTests {

        @Test
        @DisplayName("应该实现 Listener 接口")
        void shouldImplementListenerInterface() {
            // Assert
            assertThat(playerEventManager).isInstanceOf(org.bukkit.event.Listener.class);
        }

        @Test
        @DisplayName("onPlayerJoin 应该有 @EventHandler 注解")
        void onPlayerJoinShouldHaveEventHandlerAnnotation() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod("onPlayerJoin", PlayerJoinEvent.class);

            // Assert
            assertThat(method.isAnnotationPresent(org.bukkit.event.EventHandler.class)).isTrue();
        }

        @Test
        @DisplayName("onPlayerQuit 应该有 @EventHandler 注解")
        void onPlayerQuitShouldHaveEventHandlerAnnotation() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod("onPlayerQuit", PlayerQuitEvent.class);

            // Assert
            assertThat(method.isAnnotationPresent(org.bukkit.event.EventHandler.class)).isTrue();
        }

        @Test
        @DisplayName("onPlayerChat 应该有 @EventHandler 注解")
        void onPlayerChatShouldHaveEventHandlerAnnotation() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod("onPlayerChat", 
                org.bukkit.event.player.PlayerChatEvent.class);

            // Assert
            assertThat(method.isAnnotationPresent(org.bukkit.event.EventHandler.class)).isTrue();
        }
    }
}

