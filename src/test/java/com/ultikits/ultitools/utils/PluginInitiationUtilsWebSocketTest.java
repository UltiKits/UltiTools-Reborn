package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginInitiationUtils}.
 * These tests verify method signatures, class structure, and message handling logic
 * without requiring actual network connectivity or UltiTools instance.
 */
@DisplayName("PluginInitiationUtils 测试")
class PluginInitiationUtilsWebSocketTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("PluginInitiationUtils 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(PluginInitiationUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("v2 连接属于 CloudSession，而非静态全局字段")
        void shouldHavePanelWSField() throws NoSuchFieldException {
            Field field = CloudSession.class.getDeclaredField("agentConnection");

            assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("v2 服务器凭据属于 CloudSession")
        void shouldHaveTokenField() throws NoSuchFieldException {
            Field field = CloudSession.class.getDeclaredField("credential");

            assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("公共方法签名测试")
    class PublicMethodSignatureTests {

        @Test
        @DisplayName("initWebsocket 方法应该存在且签名正确")
        void initWebsocketMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("initWebsocket");
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("stopWebsocket 方法应该存在且签名正确")
        void stopWebsocketMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("stopWebsocket");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("reinitWebSocket 方法应该存在且签名正确")
        void reinitWebSocketMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("reinitWebSocket");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("私有方法签名测试")
    class PrivateMethodSignatureTests {

        @Test
        @DisplayName("initializeManagers 方法应该存在")
        void initializeManagersMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("initializeManagers");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            // 包级可见（PR #264 起）：cloudEnabled 闸门需要被单元测试直接驱动。
            // 仍然不是 public —— 它不是对外 API。
            assertThat(Modifier.isPrivate(method.getModifiers())).isFalse();
            assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
            assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("v2 建连入口为包私有，公开静态方法保持兼容")
        void sessionStartConnectionIsPackagePrivate() throws NoSuchMethodException {
            Method method = CloudSession.class.getDeclaredMethod("startConnection");
            assertThat(Modifier.isStatic(method.getModifiers())).isFalse();
            assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

    }

    /**
     * Plan 22-23 (D-01, checker B-W6): the protocol v1 inbound dispatch is gone from this class, by reference, not
     * renamed. Replaces the former "every v1 handler exists" coverage, which pinned the opposite.
     */
    @Nested
    @DisplayName("protocol v1 inbound dispatch removed (22-23)")
    class V1InboundDispatchRemovedTests {

        @Test
        @DisplayName("no v1 inbound handler, dispatch table, gate or handshake callback remains")
        void noV1InboundMemberRemains() {
            List<String> methods = Arrays.stream(PluginInitiationUtils.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toList());
            assertThat(methods).doesNotContain("handleInboundMessage", "buildInboundHandlers",
                "inboundDispatchTable", "dispatchWithCapabilityGate", "sendCapabilityRefusal", "dispatchToResponder",
                "responderReplyPayload", "handlePing", "handlePong", "handleSubscribe", "handleUnsubscribe",
                "handleNotification", "handleError", "handlePlayerEvent", "handleCommandResult",
                "handleFileOperationResult", "handleBackupOperation", "handleBackupProgress", "handleConfigUpload",
                "handleConfigUploadLogic", "handleConfigUpdate", "handlePluginListRequest",
                "handleServerStatusRequest", "handleMetricsRequest", "onWebSocketOpened", "onWebSocketConnected",
                "uploadConfig", "uploadServerProperties", "currentWebSocketClient");
            assertThat(methods).as("control: the reflection sees this class's members")
                .contains("isFrameworkOwnedType", "publishPanelMessageEvent");
            List<String> nested = Arrays.stream(PluginInitiationUtils.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toList());
            assertThat(nested).doesNotContain("InboundHandlerEntry", "VerdictRecorder");
            List<String> fields = Arrays.stream(PluginInitiationUtils.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());
            assertThat(fields).doesNotContain("INBOUND_HANDLERS", "SERVER_PROPERTIES_FILE");
        }
    }

    @Nested
    @DisplayName("reinitWebSocket 方法测试")
    class ReinitWebSocketTests {

        @Test
        @DisplayName("reinitWebSocket 方法应该存在且签名正确")
        void reinitWebSocketMethodShouldExistWithCorrectSignature() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("reinitWebSocket");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("reinitWebSocket 方法应该是公共方法")
        void reinitWebSocketMethodShouldBePublic() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("reinitWebSocket");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("重新建连使用会话拥有的 v2 连接")
        void panelWSFieldShouldBeAccessibleByReinitWebSocket() throws NoSuchFieldException {
            Field field = CloudSession.class.getDeclaredField("agentConnection");

            assertThat(field.getType().getName())
                .isEqualTo("com.ultikits.ultitools.utils.AgentConnection");
            assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("重新建连使用会话拥有的 v2 服务器凭据")
        void tokenFieldShouldBeUsableByReinitWebSocket() throws NoSuchFieldException {
            Field field = CloudSession.class.getDeclaredField("credential");

            assertThat(field.getType().getName())
                .isEqualTo("com.ultikits.ultitools.utils.ServerCredential");
            assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
        }
    }

}
