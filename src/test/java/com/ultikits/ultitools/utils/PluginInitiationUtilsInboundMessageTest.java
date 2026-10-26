package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * What remains of the protocol v1 inbound-message guards after plan 22-23 removed the v1 inbound dispatch
 * ({@code PluginInitiationUtils#handleInboundMessage} and its table): the legacy {@link UltiPanelWebSocketClient}
 * itself, which 6.3.1 keeps (unreachable from the framework, removed in 6.4.0, S-67), still forwards a message whose
 * {@code type} is not a primitive instead of throwing in its own FINE log line.
 * <p>
 * The malformed-type cases of the removed dispatch are covered on the v2 path by {@code AgentMessageRouterTest}
 * ({@code invalidAndUnsupportedFramesProduceCorrelatedSchemaValidErrors},
 * {@code malformedPayloadHasInvalidMessageRatherThanInternalError}).
 */
@DisplayName("PluginInitiationUtils 入站消息守卫")
class PluginInitiationUtilsInboundMessageTest {

    @Nested
    @DisplayName("客户端不提前吞掉畸形消息")
    class ClientForwarding {

        /**
         * {@code UltiPanelWebSocketClient.onMessage} 在调用 messageHandler 之前，会自己取一次
         * type 只为打一条 FINE 日志。那一行原本写的是 {@code !isJsonNull()}，于是 type 为对象或
         * 数组时 {@code getAsString()} 在那里就抛了，消息根本到不了处理器——处理器侧的守卫写得
         * 再对也没用，这条路径在生产上不可达。
         *
         * <p>本用例走真实的 {@code onMessage(String)} 入口，确认消息现在能到达处理器。
         */
        @Test
        @DisplayName("type 为对象的消息仍能到达 messageHandler")
        void nonPrimitiveTypeStillReachesHandler() throws Exception {
            UltiPanelWebSocketClient client =
                    new UltiPanelWebSocketClient("ws://localhost:1", "test-server", "test-token");
            try {
                List<JsonObject> received = new ArrayList<>();
                client.setMessageHandler(received::add);

                client.onMessage("{\"type\":{},\"data\":{}}");

                assertThat(received)
                        .as("type 为对象时消息也应到达 handler，而不是在客户端打日志那一行就抛掉")
                        .hasSize(1);
            } finally {
                // 构造函数里起了 heartbeatExecutor，不关就是一条泄漏线程。见 issue #250。
                client.disconnect();
            }
        }
    }
}
