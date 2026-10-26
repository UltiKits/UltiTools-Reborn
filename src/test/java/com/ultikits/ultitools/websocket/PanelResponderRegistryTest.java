package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

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

    /**
     * Kept at the top level, not nested under {@link Registration}: Surefire's bare
     * {@code -Dtest=Class#method} filter does not descend into {@code @Nested} classes, and
     * 06-VALIDATION.md's automated command names this method exactly.
     */
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
        registry.shutdown();
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

    /**
     * {@code dispatch} — one timeout in one place (D-27). Registers directly against a bare
     * {@link PanelResponderRegistry} instance; no Bukkit/MockBukkit needed since dispatch itself
     * never touches Bukkit API.
     */
    @Nested
    @DisplayName("dispatch —— 单一超时点，单一回复形状")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    class Dispatch {

        @Test
        @DisplayName("已完成的 responder：dispatch 返回的 future 携带同一个结果")
        void resolvesResponderResult() throws Exception {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            registry.registerResponder("stats", data -> CompletableFuture.completedFuture(result), "module-a");

            CompletableFuture<JsonElement> outcome = registry.dispatch("stats", new JsonObject(), "req-1");

            assertThat(outcome.get(2, TimeUnit.SECONDS)).isSameAs(result);
        }

        @Test
        @DisplayName("永不完成的 responder：在有界时间窗口内产出一个命名类型与耗时的失败")
        void neverCompletingResponderProducesTimeoutError() {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("stalls", data -> new CompletableFuture<>(), "module-a");

            CompletableFuture<JsonElement> outcome = registry.dispatch("stalls", new JsonObject(), "req-1");

            assertThatThrownBy(() -> outcome.get(PanelResponderRegistry.RESPONDER_TIMEOUT_MILLIS + 5000,
                    TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(PluginModuleException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSOCKET_RESPONDER_TIMEOUT);
                        assertThat(e.getMessage()).contains("stalls");
                    });
        }

        @Test
        @DisplayName("同步抛出的 responder：产出同样形状的失败回复")
        void synchronouslyThrowingResponderProducesFailure() throws Exception {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            RuntimeException boom = new RuntimeException("boom — deliberately thrown by a test responder");
            registry.registerResponder("throws", data -> {
                throw boom;
            }, "module-a");

            CompletableFuture<JsonElement> outcome = registry.dispatch("throws", new JsonObject(), "req-1");
            awaitSettled(outcome);

            assertThat(outcome).isCompletedExceptionally();
            assertThatThrownBy(outcome::join).hasCause(boom);
        }

        @Test
        @DisplayName("返回 null 的 responder：产出明确的失败而不是空指针")
        void nullReturningResponderProducesExplicitFailure() throws Exception {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("returns-null", data -> null, "module-a");

            CompletableFuture<JsonElement> outcome = registry.dispatch("returns-null", new JsonObject(), "req-1");
            awaitSettled(outcome);

            assertThat(outcome).isCompletedExceptionally();
        }

        @Test
        @DisplayName("快速 responder 不留下任何挂起的超时任务")
        void fastResponderLeavesNoPendingTimeoutTask() throws Exception {
            PanelResponderRegistry registry = new PanelResponderRegistry();
            registry.registerResponder("fast", data -> CompletableFuture.completedFuture(new JsonObject()),
                    "module-a");

            registry.dispatch("fast", new JsonObject(), "req-1").get(2, TimeUnit.SECONDS);

            assertThat(registry.pendingTimeoutTaskCountForTesting())
                    .as("已完成的 responder 应当在 whenComplete 中同步取消其超时任务")
                    .isZero();
        }

        /**
         * Waits, bounded, until {@code outcome} settles either way: the responder now runs on a
         * registry worker, so the outcome is not settled yet when {@code dispatch} returns. A
         * missed settlement surfaces as a {@code TimeoutException}.
         */
        private void awaitSettled(CompletableFuture<JsonElement> outcome) throws Exception {
            outcome.handle((value, failure) -> null).get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * A module's responders die with it, at both existing unload sites (Task 3) —
     * {@code PluginManager.unregister} (in-process) and {@code PluginManager.unregisterExternal}
     * (external Bukkit plugin). Mirrors the minimal stubbing shape
     * {@code PluginManagerTest.UnregisterTests}/{@code ExternalPluginIntegrationTest} already use
     * for the same two methods, extended with {@code getPanelResponderRegistry()}.
     */
    @Nested
    @DisplayName("模块卸载时其 responder 一并消失（两个既有卸载点）")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reset UltiTools.ultiTools, same pattern as sibling nested groups
    class Unload {

        private Logger mockLogger;
        private EventBus eventBus;
        private ListenerManager listenerManager;
        private CommandManager commandManager;
        private PanelResponderRegistry registry;
        private PluginManager pluginManager;

        @BeforeEach
        void setUp() throws Exception {
            registry = new PanelResponderRegistry();
            mockLogger = mock(Logger.class);
            eventBus = new EventBus();
            listenerManager = new ListenerManager();
            commandManager = new CommandManager();

            // unregisterExternal's trailing log line calls the static Bukkit.getLogger(), which
            // needs Bukkit.getServer() to be non-null -- mirrors ExternalPluginIntegrationTest's
            // own setUp rather than pulling in full MockBukkit for one log line.
            Server mockServer = mock(Server.class);
            lenient().when(mockServer.getLogger()).thenReturn(Logger.getLogger("MockServer"));
            lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            setStaticField(Bukkit.class, "server", mockServer);

            TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
                lenient().when(ultiTools.getListenerManager()).thenReturn(listenerManager);
                lenient().when(ultiTools.getCommandManager()).thenReturn(commandManager);
                lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(registry);
            });
            pluginManager = new PluginManager();
        }

        @AfterEach
        void tearDown() throws Exception {
            eventBus.shutdown();
            setStaticField(UltiTools.class, "ultiTools", null);
            setStaticField(Bukkit.class, "server", null);
        }

        // PMD.AvoidAccessibilityAlteration: resets private static fields (UltiTools.ultiTools,
        // Bukkit.server) between tests — same visibility-boundary rationale as tearDown() above.
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        private void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, value);
        }

        @Test
        @DisplayName("卸载一个注册了两个 responder 的内部模块会全部移除，释放的类型可以被别的模块注册")
        void unregisteringInProcessModuleRemovesBothResponders() {
            registry.registerResponder("type-a", echoResponder(), "ModuleOne");
            registry.registerResponder("type-b", echoResponder(), "ModuleOne");

            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("ModuleOne");

            pluginManager.unregister(plugin);

            assertThat(registry.hasResponder("type-a")).isFalse();
            assertThat(registry.hasResponder("type-b")).isFalse();
            assertThatCode(() -> registry.registerResponder("type-a", echoResponder(), "ModuleTwo"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("卸载没有注册任何 responder 的内部模块正常完成，不抛出异常")
        void unregisteringInProcessModuleWithNoResponderCompletesNormally() {
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("ModuleWithNoResponders");

            assertThatCode(() -> pluginManager.unregister(plugin)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("卸载一个模块不影响另一个仍在加载的模块的 responder")
        void unrelatedModuleResponderSurvivesUnload() {
            registry.registerResponder("type-a", echoResponder(), "ModuleOne");
            registry.registerResponder("type-c", echoResponder(), "ModuleTwo");

            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("ModuleOne");

            pluginManager.unregister(plugin);

            assertThat(registry.hasResponder("type-c")).isTrue();
        }

        /**
         * Rewritten in plan 22-23: the protocol v1 unknown-type branch is gone. On protocol v2 a
         * {@code module.request} for an unloaded module's key finds no owner and no responder, which
         * {@code ModuleRequestHandler} answers {@code module_not_found}
         * ({@code ModuleRequestEndToEndTest#requestWithoutResponderRepliesModuleNotFound}).
         */
        @Test
        @DisplayName("卸载后该类型不再有 owner，分发立即以失败结束（v2 回 module_not_found）")
        void afterUnloadTheTypeHasNoOwnerAndDispatchFails() throws Exception {
            registry.registerResponder("type-a", echoResponder(), "ModuleOne");
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("ModuleOne");
            pluginManager.unregister(plugin);

            assertThat(registry.ownerModuleOf("type-a")).isNull();
            CompletableFuture<JsonElement> outcome = registry.dispatch("type-a", new JsonObject(), "r-1");
            assertThat(outcome).isCompletedExceptionally();
            assertThatThrownBy(outcome::get).hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No responder registered for 'type-a'");
        }

        @Test
        @DisplayName("外部插件卸载通过第二个既有调用点移除它的 responder")
        void unregisteringExternalPluginRemovesItsResponder() {
            registry.registerResponder("type-ext", echoResponder(), "ExtPlugin");

            JavaPlugin javaPlugin = createMockJavaPlugin("ExtPlugin");
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(javaPlugin);

            pluginManager.unregisterExternal(adapter);

            assertThat(registry.hasResponder("type-ext")).isFalse();
        }

        private JavaPlugin createMockJavaPlugin(String name) {
            JavaPlugin plugin = mock(JavaPlugin.class);
            PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
            when(plugin.getName()).thenReturn(name);
            when(plugin.getDescription()).thenReturn(desc);
            when(desc.getVersion()).thenReturn("1.0.0");
            when(desc.getAuthors()).thenReturn(Arrays.asList("Author"));
            when(desc.getMain()).thenReturn("com.example." + name + ".Main");
            when(plugin.getDataFolder()).thenReturn(new File("/tmp/" + name));
            when(plugin.getLogger()).thenReturn(Logger.getLogger(name));
            return plugin;
        }
    }

    @Nested
    @DisplayName("WR-01: shutdown 必须真正终止 timeoutScheduler")
    class Shutdown {

        @Test
        @DisplayName("shutdown() 之后 timeoutScheduler 已终止 —— 而不仅仅是方法存在且不抛异常")
        void shutdownTerminatesTimeoutScheduler() {
            PanelResponderRegistry registry = new PanelResponderRegistry();

            registry.shutdown();

            assertThat(registry.isTimeoutSchedulerShutdownForTesting())
                    .as("shutdown() must stop the dedicated timeout scheduler thread, or every "
                            + "plugin /reload leaks one more UltiTools-PanelResponderRegistry-Timeout "
                            + "thread for the life of the process (06-REVIEW.md WR-01)")
                    .isTrue();
        }
    }
}
