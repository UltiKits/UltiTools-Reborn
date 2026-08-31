package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;

/**
 * WIRE-16's single-owner panel responder registry (06-08-PLAN.md). Registration/collision
 * semantics (Task 1), timeout-bounded dispatch (Task 2), and module-unload wiring (Task 3) all
 * exercise the same registry, so all three live in one class.
 */
@DisplayName("PanelResponderRegistry")
class PanelResponderRegistryTest {

    private static Function<JsonObject, CompletableFuture<JsonObject>> echoResponder() {
        return data -> CompletableFuture.completedFuture(data != null ? data : new JsonObject());
    }

    @Nested
    @DisplayName("registerResponder 的单一所有者语义")
    class Registration {

        @Test
        @DisplayName("注册一个框架未占用的类型成功，hasResponder 返回 true")
        void registeringUnownedTypeSucceeds() {
            PanelResponderRegistry registry = new PanelResponderRegistry();

            registry.registerResponder("my_module_stats", echoResponder(), "module-a");

            assertThat(registry.hasResponder("my_module_stats")).isTrue();
        }

        @Test
        @DisplayName("注册框架已占用的类型抛出 PluginModuleException，命名类型并说明是框架占用")
        void shouldRefuseFrameworkOwnedType() {
            PanelResponderRegistry registry = new PanelResponderRegistry();

            assertThatThrownBy(() -> registry.registerResponder("execute_command", echoResponder(), "module-a"))
                    .isInstanceOfSatisfying(PluginModuleException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSOCKET_RESPONDER_TYPE_OWNED_BY_FRAMEWORK);
                        assertThat(e.getMessage())
                                .contains("execute_command")
                                .containsIgnoringCase("framework");
                    });
        }

        @Test
        @DisplayName("第二个模块争抢同一类型抛出异常，消息同时命名类型与第一个所有者")
        void secondModuleCollisionNamesTypeAndFirstOwner() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("my_module_stats", echoResponder(), "module-a");

            assertThatThrownBy(() -> registry.registerResponder("my_module_stats", echoResponder(), "module-b"))
                    .isInstanceOfSatisfying(PluginModuleException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSOCKET_RESPONDER_TYPE_ALREADY_OWNED);
                        assertThat(e.getMessage())
                                .contains("my_module_stats")
                                .contains("module-a");
                    });
        }

        @Test
        @DisplayName("同一模块对同一类型重复注册也抛出异常")
        void sameOwnerRegisteringTwiceAlsoThrows() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("my_module_stats", echoResponder(), "module-a");

            assertThatThrownBy(() -> registry.registerResponder("my_module_stats", echoResponder(), "module-a"))
                    .isInstanceOf(PluginModuleException.class);
        }

        @Test
        @DisplayName("hasResponder 精确区分大小写不同的类型字符串")
        void hasResponderIsCaseSensitive() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("execute_command_ext", echoResponder(), "module-a");

            assertThat(registry.hasResponder("Execute_Command_Ext")).isFalse();
            assertThat(registry.hasResponder("execute_command_ext")).isTrue();
        }

        @Test
        @DisplayName("null 或空的 messageType 在注册时被拒绝")
        void nullOrEmptyMessageTypeIsRejected() {
            PanelResponderRegistry registry = new PanelResponderRegistry();

            assertThatThrownBy(() -> registry.registerResponder(null, echoResponder(), "module-a"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.registerResponder("", echoResponder(), "module-a"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 的 responder 在注册时被拒绝")
        void nullResponderIsRejected() {
            PanelResponderRegistry registry = new PanelResponderRegistry();

            assertThatThrownBy(() -> registry.registerResponder("my_module_stats", null, "module-a"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 或空的 ownerModule 在注册时被拒绝")
        void nullOrEmptyOwnerIsRejected() {
            PanelResponderRegistry registry = new PanelResponderRegistry();

            assertThatThrownBy(() -> registry.registerResponder("my_module_stats", echoResponder(), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.registerResponder("my_module_stats", echoResponder(), ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("unregisterAll")
    class Unregistration {

        @Test
        @DisplayName("unregisterAll 移除该模块拥有的每一个条目，不抛出异常")
        void removesEveryEntryOwnedByModule() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("type-a", echoResponder(), "module-a");
            registry.registerResponder("type-b", echoResponder(), "module-a");

            registry.unregisterAll("module-a");

            assertThat(registry.hasResponder("type-a")).isFalse();
            assertThat(registry.hasResponder("type-b")).isFalse();
        }

        @Test
        @DisplayName("对没有注册过任何东西的模块调用 unregisterAll 不移除任何东西也不抛出异常")
        void unregisteringUnknownOwnerRemovesNothing() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("type-a", echoResponder(), "module-a");

            assertThatCode(() -> registry.unregisterAll("never-registered")).doesNotThrowAnyException();
            assertThat(registry.hasResponder("type-a")).isTrue();
        }

        @Test
        @DisplayName("unregisterAll(null) 是空操作")
        void unregisterAllWithNullIsNoOp() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("type-a", echoResponder(), "module-a");

            assertThatCode(() -> registry.unregisterAll(null)).doesNotThrowAnyException();
            assertThat(registry.hasResponder("type-a")).isTrue();
        }

        @Test
        @DisplayName("unregisterAll 之后释放的类型可以被另一个模块注册")
        void freedTypeCanBeRegisteredByDifferentModuleAfterUnregister() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("type-a", echoResponder(), "module-a");
            registry.unregisterAll("module-a");

            assertThatCode(() -> registry.registerResponder("type-a", echoResponder(), "module-b"))
                    .doesNotThrowAnyException();
            assertThat(registry.hasResponder("type-a")).isTrue();
        }
    }
}
