package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 玩家事件管理器的身份与注册幂等性（issue #180）。
 *
 * <p>两件事在这里被钉住：
 * <ul>
 *   <li>发出去的 {@code serverId} 是本服真实 UUID，不是常量 {@code "minecraft-server-1"} ——
 *       在 #180 之前全球每台服务器都自称同一身份，面板侧无法路由；</li>
 *   <li>{@code initialize()} 挂在 WebSocket 的 {@code onConnectHandler} 上，<b>每次重连都会被调</b>，
 *       所以注册动作必须幂等，否则断线 N 次后每个玩家事件被发 N 份。</li>
 * </ul>
 *
 * <p>与既有的 {@code PlayerEventManagerTest} 的区别：那边用反射直接塞 {@code webSocketClient} 字段、
 * 手工调处理器方法，绕过了 Bukkit 的事件分发；重复注册这种缺陷只有<b>让事件真的走一遍 Bukkit
 * 的 HandlerList</b> 才看得见，所以这里必须造一个名叫 {@code UltiTools} 的插件、走 {@code callEvent}。
 */
@DisplayName("玩家事件管理器：身份与注册幂等")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PlayerEventManagerRegistrationTest {

    private static final String SERVER_UUID = "8f14e45f-ceea-467a-9575-9d1e1c1c1c1c";

    private ServerMock server;
    private PlayerEventManager manager;
    private UltiPanelWebSocketClient mockClient;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        // 名字必须是 UltiTools —— registerEvents 拿的就是 getPlugin("UltiTools")
        MockBukkit.createMockPlugin("UltiTools");

        Logger mockLogger = mock(Logger.class);

        LogStreamManager mockLogStreamManager = mock(LogStreamManager.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
            when(ultiTools.getLogStreamManager()).thenReturn(mockLogStreamManager);
        });

        mockClient = mock(UltiPanelWebSocketClient.class);
        when(mockClient.isConnected()).thenReturn(true);
        when(mockClient.getServerId()).thenReturn(SERVER_UUID);

        manager = new PlayerEventManager();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * 让一个玩家加入事件真的走一遍 Bukkit 的事件分发。
     * <p>
     * {@code ServerMock.addPlayer()} 模拟的是完整的加入流程，<b>它自己就会派发 PlayerJoinEvent</b>，
     * 所以不能再手工 {@code callEvent} 一次——那会变成两个事件，看上去和「监听器注册了两份」
     * 一模一样，正是这个测试要区分的东西。
     */
    private void fireJoinEvent() {
        server.addPlayer();
    }

    @Nested
    @DisplayName("服务器身份")
    class ServerIdentity {

        @Test
        @DisplayName("serverId 取自 WebSocket 客户端，不是硬编码常量")
        void serverIdComesFromWebSocketClient() {
            manager.initialize(mockClient);

            fireJoinEvent();

            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockClient).sendMessage(captor.capture());

            JsonObject message = captor.getValue();
            assertThat(message.get("serverId").getAsString())
                    .as("面板靠这个字段路由；一个写死的常量会让所有服务器撞在一起")
                    .isEqualTo(SERVER_UUID)
                    .isNotEqualTo("minecraft-server-1");
        }

        @Test
        @DisplayName("重连换了新客户端后，身份跟着新客户端走")
        void serverIdFollowsTheLatestClient() {
            // 每次重连 PluginInitiationUtils 都会造一个新的客户端实例，
            // 幂等守卫只能挡住重复注册，不能顺带把引用也挡住不更新。
            UltiPanelWebSocketClient secondClient = mock(UltiPanelWebSocketClient.class);
            when(secondClient.isConnected()).thenReturn(true);
            when(secondClient.getServerId()).thenReturn("second-client-uuid");

            manager.initialize(mockClient);
            manager.initialize(secondClient);

            fireJoinEvent();

            verify(mockClient, never()).sendMessage(any(JsonObject.class));
            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(secondClient).sendMessage(captor.capture());
            assertThat(captor.getValue().get("serverId").getAsString()).isEqualTo("second-client-uuid");
        }
    }

    @Nested
    @DisplayName("注册幂等性")
    class RegistrationIdempotency {

        @Test
        @DisplayName("两次 onOpen 之后监听器只注册一次")
        void twoInitializationsRegisterListenerOnce() {
            manager.initialize(mockClient);
            manager.initialize(mockClient);

            fireJoinEvent();

            verify(mockClient, times(1))
                    .sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("重连五次也只发一份")
        void manyReconnectsStillSendOne() {
            for (int i = 0; i < 5; i++) {
                manager.initialize(mockClient);
            }

            fireJoinEvent();

            verify(mockClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("首次 initialize 之后守卫置位")
        void guardIsSetAfterFirstInitialize() {
            assertThat(manager.isListenerRegistered()).isFalse();

            manager.initialize(mockClient);

            assertThat(manager.isListenerRegistered()).isTrue();
        }
    }

    @Nested
    @DisplayName("注销")
    class Unregistration {

        @Test
        @DisplayName("shutdown 之后不再收到事件")
        void shutdownStopsListening() {
            manager.initialize(mockClient);
            manager.shutdown();

            fireJoinEvent();

            verify(mockClient, never()).sendMessage(any(JsonObject.class));
            assertThat(manager.isListenerRegistered()).isFalse();
        }

        @Test
        @DisplayName("shutdown 之后可以重新 initialize，且仍然只注册一份")
        void reinitializeAfterShutdownIsStillSingle() {
            manager.initialize(mockClient);
            manager.shutdown();
            manager.initialize(mockClient);

            fireJoinEvent();

            verify(mockClient, times(1)).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("重复 shutdown 不抛异常")
        void repeatedShutdownIsSafe() {
            manager.initialize(mockClient);

            assertThatCode(() -> {
                manager.shutdown();
                manager.shutdown();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("initialize 与 shutdown 互斥")
        void initializeAndShutdownShareTheSameLock() throws Exception {
            // 只让 registerEvents 单独同步是不够的：initialize 里「赋值客户端」与「注册监听器」
            // 之间会留下一个窗口，logout 恰好挤进去的话，shutdown 摘掉的监听器会被紧随其后的
            // registerEvents 又装回去。见 PR #264 的评审。
            //
            // 真实竞态无法确定性复现，这里退而钉住结构：两个方法必须落在同一把锁上。
            assertThat(java.lang.reflect.Modifier.isSynchronized(
                    PlayerEventManager.class.getDeclaredMethod("initialize", UltiPanelWebSocketClient.class)
                            .getModifiers()))
                    .as("initialize 必须整体同步，而不是只同步内部的 registerEvents")
                    .isTrue();
            assertThat(java.lang.reflect.Modifier.isSynchronized(
                    PlayerEventManager.class.getDeclaredMethod("shutdown").getModifiers()))
                    .isTrue();
        }
    }
}
