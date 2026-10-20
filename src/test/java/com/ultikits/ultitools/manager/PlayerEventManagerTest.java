package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.event.entity.PlayerDeathEvent;
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

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

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

        // Mock logger
        mockLogger = mock(Logger.class);

        // Mock WebSocket client
        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("test-server");

        // Mock LogStreamManager
        mockLogStreamManager = mock(LogStreamManager.class);
        UltiPanelLogTransmitter mockTransmitter = mock(UltiPanelLogTransmitter.class);
        when(mockLogStreamManager.getLogTransmitter()).thenReturn(mockTransmitter);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
            when(ultiTools.getLogStreamManager()).thenReturn(mockLogStreamManager);
        });

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
    @DisplayName("拆线与在途事件的竞态")
    class ShutdownRace {

        /**
         * 精确复现竞态窗口：{@code isConnected()} 返回 true 之后、真正发送之前，另一条线程
         * 走完了 {@code shutdown()} 并把 {@code webSocketClient} 置空。
         * <p>
         * 真实触发路径是重连预算耗尽——{@code disableCloud()} 跑在 WebSocket 线程上，
         * 而事件处理器跑在 Bukkit 主线程。{@code HandlerList.unregisterAll()} 只拦得住
         * 未来的派发，拦不住已经在栈上跑着的这一次。
         */
        private void shutdownDuringConnectivityCheck() {
            when(mockWebSocketClient.isConnected()).thenAnswer(invocation -> {
                playerEventManager.shutdown();
                return true;
            });
        }

        @Test
        @DisplayName("join：检查通过之后被 shutdown，不得抛 NPE")
        void joinSurvivesConcurrentShutdown() {
            PlayerMock player = server.addPlayer();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");
            shutdownDuringConnectivityCheck();

            assertThatCode(() -> playerEventManager.onPlayerJoin(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("quit：检查通过之后被 shutdown，不得抛 NPE")
        void quitSurvivesConcurrentShutdown() {
            PlayerMock player = server.addPlayer();
            PlayerQuitEvent event = new PlayerQuitEvent(player, "left");
            shutdownDuringConnectivityCheck();

            assertThatCode(() -> playerEventManager.onPlayerQuit(event))
                    .doesNotThrowAnyException();
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

        // GATE-06 (issue #345): the previous body called onPlayerJoin() with no assertion (the
        // "// NOPMD - uses Mockito verify()" comment was stale -- no verify() call was present).
        // With webSocketClient nulled by reflection, mockWebSocketClient is now an orphaned mock:
        // verifyNoInteractions on it is a real check that the null-client path never reaches the
        // client at all, rather than merely completing without throwing.
        @Test
        @DisplayName("WebSocket 为 null 时不应该抛出异常")
        void shouldNotThrowWhenWebSocketNull() throws Exception {
            // Arrange
            Field clientField = PlayerEventManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(playerEventManager, null);

            PlayerMock player = server.addPlayer();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "joined");

            // Act & Assert
            playerEventManager.onPlayerJoin(event);
            verifyNoInteractions(mockWebSocketClient);
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

        // GATE-06 (issue #345): named site (manager/PlayerEventManagerTest.java:249). The previous
        // body called onPlayerQuit() with no assertion. With webSocketClient nulled by reflection,
        // mockWebSocketClient is now an orphaned mock: verifyNoInteractions on it is a real check
        // that the null-client path never reaches the client at all, rather than merely completing
        // without throwing.
        @Test
        @DisplayName("WebSocket 为 null 时不应该抛出异常")
        void shouldNotThrowWhenWebSocketNull() throws Exception {
            // Arrange
            Field clientField = PlayerEventManager.class.getDeclaredField("webSocketClient");
            clientField.setAccessible(true);
            clientField.set(playerEventManager, null);

            PlayerMock player = server.addPlayer();
            PlayerQuitEvent event = new PlayerQuitEvent(player, "quit");

            // Act & Assert
            playerEventManager.onPlayerQuit(event);
            verifyNoInteractions(mockWebSocketClient);
        }
    }

    @Nested
    @DisplayName("sendPlayerEvent 测试")
    class SendPlayerEventTests {

        @Test
        @DisplayName("应该能够通过反射调用私有方法")
        void shouldCallPrivateMethod() throws Exception {
            // Arrange
            // 客户端现在由调用方传入：三个事件处理器各自把 volatile 字段单次读进局部
            // 变量再一路传下来，避免检查与发送之间被 shutdown() 置空。
            Method method = PlayerEventManager.class.getDeclaredMethod(
                    "sendPlayerEvent", UltiPanelWebSocketClient.class, JsonObject.class);
            method.setAccessible(true);

            JsonObject data = new JsonObject();
            data.addProperty("event_type", "test");
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            // Act
            method.invoke(playerEventManager, mockWebSocketClient, data);

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

        // GATE-06 (issue #345): the same assert-nothing shape as the join/quit siblings, found in
        // this file during re-resolution and fixed for consistency though not itself one of the
        // 13 named sites -- see 08-GATE06-TRIAGE.md. With webSocketClient nulled by reflection,
        // mockWebSocketClient is now an orphaned mock: verifyNoInteractions on it is a real check
        // that the null-client path never reaches the client at all, rather than merely completing
        // without throwing.
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

            // Act & Assert
            playerEventManager.onPlayerChat(event);
            verifyNoInteractions(mockWebSocketClient);
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
            Method method = PlayerEventManager.class.getDeclaredMethod(
                    "getServerId", UltiPanelWebSocketClient.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(playerEventManager, mockWebSocketClient);

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("应该返回非空字符串")
        void shouldReturnNonEmptyString() throws Exception {
            // Arrange
            Method method = PlayerEventManager.class.getDeclaredMethod(
                    "getServerId", UltiPanelWebSocketClient.class);
            method.setAccessible(true);

            // Act
            String result = (String) method.invoke(playerEventManager, mockWebSocketClient);

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

    /**
     * 从已删除的 {@code EnhancedPlayerEventListener} 迁移过来的四个处理器（#387）。
     * <p>
     * 原类带着 {@code @EventListener} 却从未被注册，七个处理器一次也没触发过。它自己的 16 个单元测试
     * 全绿——因为那些测试直接调用方法，从不检验方法会不会被调用。这里改测它们在真正注册的
     * {@code PlayerEventManager} 上的行为，并沿用同一套连接性守卫与竞态断言。
     * <p>
     * 其中 death 用 Mockito 造事件而非真实构造：1.21 的
     * {@code PlayerDeathEvent} 构造函数要求一个 {@code DamageSource}，与本组要验证的东西无关。
     */
    @Nested
    @DisplayName("从 EnhancedPlayerEventListener 迁移的处理器")
    class MigratedHandlers {

        private void shutdownDuringConnectivityCheck() {
            when(mockWebSocketClient.isConnected()).thenAnswer(invocation -> {
                playerEventManager.shutdown();
                return true;
            });
        }

        private PlayerDeathEvent deathEventFor(PlayerMock player) {
            PlayerDeathEvent event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(player);
            when(event.getDeathMessage()).thenReturn("died");
            return event;
        }

        @Test
        @DisplayName("death：已连接时发送")
        void deathSendsWhenConnected() {
            PlayerMock player = server.addPlayer();
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            playerEventManager.onPlayerDeath(deathEventFor(player));

            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("death：未连接时不发送")
        void deathDoesNotSendWhenDisconnected() {
            PlayerMock player = server.addPlayer();
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            playerEventManager.onPlayerDeath(deathEventFor(player));

            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("death：检查通过之后被 shutdown，不得抛 NPE")
        void deathSurvivesConcurrentShutdown() {
            PlayerMock player = server.addPlayer();
            PlayerDeathEvent event = deathEventFor(player);
            shutdownDuringConnectivityCheck();

            assertThatCode(() -> playerEventManager.onPlayerDeath(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("kick：已连接时发送")
        void kickSendsWhenConnected() {
            PlayerMock player = server.addPlayer();
            PlayerKickEvent event = new PlayerKickEvent(player, "left", "kicked");
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            playerEventManager.onPlayerKick(event);

            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("kick：未连接时不发送")
        void kickDoesNotSendWhenDisconnected() {
            PlayerMock player = server.addPlayer();
            PlayerKickEvent event = new PlayerKickEvent(player, "left", "kicked");
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            playerEventManager.onPlayerKick(event);

            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("kick：检查通过之后被 shutdown，不得抛 NPE")
        void kickSurvivesConcurrentShutdown() {
            PlayerMock player = server.addPlayer();
            PlayerKickEvent event = new PlayerKickEvent(player, "left", "kicked");
            shutdownDuringConnectivityCheck();

            assertThatCode(() -> playerEventManager.onPlayerKick(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("command：已连接时发送")
        void commandSendsWhenConnected() {
            PlayerMock player = server.addPlayer();
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/spawn");
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            playerEventManager.onPlayerCommandPreprocess(event);

            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("command：未连接时不发送")
        void commandDoesNotSendWhenDisconnected() {
            PlayerMock player = server.addPlayer();
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/spawn");
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            playerEventManager.onPlayerCommandPreprocess(event);

            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("command：检查通过之后被 shutdown，不得抛 NPE")
        void commandSurvivesConcurrentShutdown() {
            PlayerMock player = server.addPlayer();
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/spawn");
            shutdownDuringConnectivityCheck();

            assertThatCode(() -> playerEventManager.onPlayerCommandPreprocess(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("world_change：已连接时发送")
        void worldChangeSendsWhenConnected() {
            PlayerMock player = server.addPlayer();
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, player.getWorld());
            when(mockWebSocketClient.isConnected()).thenReturn(true);

            playerEventManager.onPlayerChangedWorld(event);

            verify(mockWebSocketClient, atLeastOnce()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("world_change：未连接时不发送")
        void worldChangeDoesNotSendWhenDisconnected() {
            PlayerMock player = server.addPlayer();
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, player.getWorld());
            when(mockWebSocketClient.isConnected()).thenReturn(false);

            playerEventManager.onPlayerChangedWorld(event);

            verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("world_change：检查通过之后被 shutdown，不得抛 NPE")
        void worldChangeSurvivesConcurrentShutdown() {
            PlayerMock player = server.addPlayer();
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(player, player.getWorld());
            shutdownDuringConnectivityCheck();

            assertThatCode(() -> playerEventManager.onPlayerChangedWorld(event))
                    .doesNotThrowAnyException();
        }
    }
}
