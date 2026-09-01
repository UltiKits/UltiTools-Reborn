package com.ultikits.ultitools.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * {@link PanelMessageEvent}'s own shape (Task 1), and — added by later tasks in the same plan —
 * its bridging publish from {@code PluginInitiationUtils#handleInboundMessage} (Task 2) and the
 * slow-handler warning that publish produces (Task 3). Kept as one class because all three groups
 * exercise the same extension point end to end; see 06-07-PLAN.md.
 * <br>
 * {@link PanelMessageEvent} 自身的形状（Task 1），以及本计划后续任务在同一个类里补充的：
 * 从 {@code PluginInitiationUtils#handleInboundMessage} 发布该事件的桥接行为（Task 2），
 * 以及该发布产生的慢处理器告警（Task 3）。放在同一个类里是因为三组用例共同验证的是
 * 同一个扩展点的端到端行为。
 */
@DisplayName("PanelMessageEvent")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reach package-private test seams, same pattern as CapabilityGateTracerTest
class PanelMessageEventDispatchTest {

    @Nested
    @DisplayName("事件自身的形状")
    class EventShape {

        @Test
        @DisplayName("不可赋值给 Cancellable")
        void isNotCancellable() {
            assertThat(Cancellable.class.isAssignableFrom(PanelMessageEvent.class)).isFalse();
        }

        @Test
        @DisplayName("是 ModuleEvent 的子类")
        void isModuleEvent() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            PanelMessageEvent event = new PanelMessageEvent("ping", data, new JsonObject());

            assertThat(event).isInstanceOf(ModuleEvent.class);
        }

        @Test
        @DisplayName("构造后暴露 type 与 data")
        void exposesTypeAndData() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "ping");

            PanelMessageEvent event = new PanelMessageEvent("ping", data, raw);

            assertThat(event.getType()).isEqualTo("ping");
            assertThat(event.getData().get("k").getAsString()).isEqualTo("v");
        }

        @Test
        @DisplayName("构造之后修改传入的 JsonObject 不影响 accessor 的返回值")
        void mutatingConstructorInputDoesNotLeakIn() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "original");

            PanelMessageEvent event = new PanelMessageEvent("ping", data, new JsonObject());
            data.addProperty("k", "mutated-after-construction");

            assertThat(event.getData().get("k").getAsString()).isEqualTo("original");
        }

        @Test
        @DisplayName("修改 accessor 返回的 JsonObject 不影响下一次调用的结果")
        void mutatingAccessorResultDoesNotLeakOut() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "original");
            PanelMessageEvent event = new PanelMessageEvent("ping", data, new JsonObject());

            JsonObject firstCall = event.getData();
            firstCall.addProperty("k", "mutated-after-accessor-call");

            assertThat(event.getData().get("k").getAsString()).isEqualTo("original");
        }

        @Test
        @DisplayName("data 为 null 时 accessor 返回非 null 的空对象")
        void nullDataYieldsNonNullEmptyObject() {
            PanelMessageEvent event = new PanelMessageEvent("ping", null, new JsonObject());

            assertThat(event.getData()).isNotNull();
            assertThat(event.getData().entrySet()).isEmpty();
        }

        @Test
        @DisplayName("type 为 null 或空字符串时拒绝构造")
        void nullOrEmptyTypeIsRejected() {
            assertThatThrownBy(() -> new PanelMessageEvent(null, new JsonObject(), new JsonObject()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PanelMessageEvent("", new JsonObject(), new JsonObject()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rawMessage 与 data 分开暴露")
        void rawMessageExposedSeparatelyFromData() {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "ping");
            raw.addProperty("serverId", "test-server");
            raw.add("data", data);

            PanelMessageEvent event = new PanelMessageEvent("ping", data, raw);

            assertThat(event.getRawMessage().get("serverId").getAsString()).isEqualTo("test-server");
            assertThat(event.getData().has("serverId")).isFalse();
        }
    }

    /**
     * Reaches {@code PluginInitiationUtils#handleInboundMessage} and {@code panelWS} via
     * reflection: this class lives in {@code events}, a different package from {@code utils} —
     * same reason {@code CapabilityGateTracerTest} needs reflection (see its own javadoc).
     */
    private static void invokeHandleInboundMessage(JsonObject message) throws Exception {
        Method method = PluginInitiationUtils.class.getDeclaredMethod("handleInboundMessage", JsonObject.class);
        method.setAccessible(true);
        method.invoke(null, message);
    }

    private static Object setPanelWs(Object value) throws Exception {
        Field field = PluginInitiationUtils.class.getDeclaredField("panelWS");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    private static YamlConfiguration emptyConfig() {
        return new YamlConfiguration();
    }

    private static YamlConfiguration configWith(String path, boolean value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        return config;
    }

    private static JsonObject executeCommandMessage(String command, String commandId) {
        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("commandId", commandId);
        JsonObject message = new JsonObject();
        message.addProperty("type", "execute_command");
        message.add("data", data);
        return message;
    }

    private static JsonObject notificationMessage(String text) {
        JsonObject data = new JsonObject();
        data.addProperty("message", text);
        JsonObject message = new JsonObject();
        message.addProperty("type", "notification");
        message.add("data", data);
        return message;
    }

    /**
     * One added statement at the end of {@code handleInboundMessage}, on the main thread (Task 2).
     * Every case here needs a real {@link EventBus} and a real Bukkit scheduler — MockBukkit's —
     * to prove the publish actually reaches the main thread rather than merely getting queued.
     */
    @Nested
    @DisplayName("桥接发布 —— handleInboundMessage 末尾追加的那一条语句")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    class PublishBridge {

        private ServerMock server;
        private EventBus eventBus;
        private Logger mockLogger;
        private CommandExecutionManager mockCommandExecutionManager;
        private UltiPanelWebSocketClient mockPanelWs;
        private Object previousPanelWs;
        private final List<PanelMessageEvent> received = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            MockBukkitHelper.ensureCleanState();
            server = MockBukkit.mock();
            MockBukkit.createMockPlugin();

            eventBus = new EventBus();
            eventBus.subscribe(PanelMessageEvent.class, received::add);

            mockLogger = mock(Logger.class);
            mockCommandExecutionManager = mock(CommandExecutionManager.class);

            TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(ultiTools.getConfig()).thenReturn(emptyConfig());
                lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
                lenient().when(ultiTools.getCommandExecutionManager()).thenReturn(mockCommandExecutionManager);
            });

            mockPanelWs = mock(UltiPanelWebSocketClient.class);
            lenient().when(mockPanelWs.getServerId()).thenReturn("test-server-uuid");
            previousPanelWs = setPanelWs(mockPanelWs);
        }

        @AfterEach
        void tearDown() throws Exception {
            setPanelWs(previousPanelWs);
            eventBus.shutdown();
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
            MockBukkitHelper.safeUnmock();
        }

        @Test
        @DisplayName("被允许的消息恰好发布一次 PanelMessageEvent")
        void publishesExactlyOnceForAllowedGatedMessage() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith("ultipanel.capabilities.commands", true));

            invokeHandleInboundMessage(executeCommandMessage("say hi", "c1"));
            server.getScheduler().performOneTick();

            assertThat(received).hasSize(1);
            PanelMessageEvent event = received.get(0);
            assertThat(event.getType()).isEqualTo("execute_command");
            assertThat(event.getData().get("command").getAsString()).isEqualTo("say hi");
        }

        @Test
        @DisplayName("发布跑在主线程 —— 不只是被排进了调度队列")
        // PMD.AvoidThrowingRawExceptionTypes: wraps a checked Exception thrown inside a
        // Runnable-shaped lambda so it can cross the off-primary-thread boundary in
        // runOffPrimaryThread() — no checked-exception-friendly functional interface exists
        // for that boundary, so wrapping is the idiomatic adapter, not carelessness.
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        void publishRunsOnMainThread() throws Exception {
            AtomicBoolean observedPrimaryThread = new AtomicBoolean(false);
            AtomicBoolean invokedBeforeTick = new AtomicBoolean(false);
            eventBus.subscribe(PanelMessageEvent.class, event -> observedPrimaryThread.set(Bukkit.isPrimaryThread()));

            runOffPrimaryThread(() -> {
                try {
                    invokeHandleInboundMessage(notificationMessage("hi"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // The subscriber must not have fired yet — proves this is a scheduled hop, not an
            // in-line synchronous call from handleInboundMessage.
            if (!received.isEmpty()) {
                invokedBeforeTick.set(true);
            }
            assertThat(invokedBeforeTick).as("发布不应在 performOneTick 之前就已经发生").isFalse();

            server.getScheduler().performOneTick();

            assertThat(received).hasSize(1);
            assertThat(observedPrimaryThread).as("订阅者应当观察到主线程").isTrue();
        }

        @Test
        @DisplayName("type 缺失/为空时不发布任何事件")
        void malformedTypePublishesNothing() throws Exception {
            JsonObject message = new JsonObject();
            message.addProperty("type", "");

            invokeHandleInboundMessage(message);
            server.getScheduler().performOneTick();

            assertThat(received).isEmpty();
        }

        @Test
        @DisplayName("未知类型（表中无条目）仍然发布")
        void unknownTypeStillPublishes() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("k", "v");
            JsonObject message = new JsonObject();
            message.addProperty("type", "definitely_not_a_real_type");
            message.add("data", data);

            invokeHandleInboundMessage(message);
            server.getScheduler().performOneTick();

            assertThat(received).hasSize(1);
            assertThat(received.get(0).getType()).isEqualTo("definitely_not_a_real_type");
        }

        @Test
        @DisplayName("被能力网关拒绝的消息不发布任何事件")
        void capabilityDeniedMessagePublishesNothing() throws Exception {
            // commands 出厂默认关闭（D-08），无需额外打桩。
            invokeHandleInboundMessage(executeCommandMessage("say hi", "c1"));
            server.getScheduler().performOneTick();

            verify(mockPanelWs, times(1)).sendMessage(any());
            verify(mockCommandExecutionManager, never()).executeCommand(any());
            assertThat(received).isEmpty();
        }

        @Test
        @DisplayName("订阅者抛异常不会让 handleInboundMessage 的调用路径炸掉")
        // PMD.AvoidThrowingRawExceptionTypes: driving the subscriber's failure path IS the
        // test — it proves handleInboundMessage's call path survives a throwing subscriber.
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        void throwingHandlerDoesNotPropagate() throws Exception {
            eventBus.subscribe(PanelMessageEvent.class, event -> {
                throw new RuntimeException("boom — deliberately thrown by a test subscriber");
            });

            invokeHandleInboundMessage(notificationMessage("hi"));

            assertThatCode(() -> server.getScheduler().performOneTick()).doesNotThrowAnyException();
        }
    }

    /**
     * Task 3 — naming a slow subscriber, because the main-thread hop {@link PublishBridge} wires
     * up is what makes a slow subscriber able to cost tick rate at all. Kept as its own nested
     * group with its own MockBukkit/EventBus setup, mirroring {@link PublishBridge}, rather than
     * folded into it — the elapsed-time assertions here need control over which subscribers are
     * slow per test, which would otherwise interact with {@code PublishBridge}'s shared {@code
     * received} list.
     */
    @Nested
    @DisplayName("慢处理器告警")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    class SlowHandlerWarning {

        private ServerMock server;
        private EventBus eventBus;
        private Logger mockLogger;
        private UltiPanelWebSocketClient mockPanelWs;
        private Object previousPanelWs;

        @BeforeEach
        void setUp() throws Exception {
            MockBukkitHelper.ensureCleanState();
            server = MockBukkit.mock();
            MockBukkit.createMockPlugin();

            eventBus = new EventBus();

            mockLogger = mock(Logger.class);
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(ultiTools.getConfig()).thenReturn(emptyConfig());
                lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
            });

            mockPanelWs = mock(UltiPanelWebSocketClient.class);
            lenient().when(mockPanelWs.getServerId()).thenReturn("test-server-uuid");
            previousPanelWs = setPanelWs(mockPanelWs);
        }

        @AfterEach
        void tearDown() throws Exception {
            setPanelWs(previousPanelWs);
            eventBus.shutdown();
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
            MockBukkitHelper.safeUnmock();
        }

        /** Every {@code [PanelMessageEvent]}-tagged WARNING line logged so far. */
        private List<String> slowHandlerWarnings() {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(mockLogger, atLeast(0)).log(eq(Level.WARNING), captor.capture());
            List<String> tagged = new ArrayList<>();
            for (String line : captor.getAllValues()) {
                if (line.contains("[PanelMessageEvent]")) {
                    tagged.add(line);
                }
            }
            return tagged;
        }

        /** Sleeps comfortably past the threshold without hard-coding its exact value here. */
        private void sleepPastThreshold() {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        @DisplayName("快速订阅者：不记录任何告警")
        void fastSubscriberLogsNoWarning() throws Exception {
            eventBus.subscribe(PanelMessageEvent.class, event -> { /* fast — does nothing */ });

            invokeHandleInboundMessage(notificationMessage("hi"));
            server.getScheduler().performOneTick();

            assertThat(slowHandlerWarnings()).isEmpty();
        }

        @Test
        @DisplayName("慢订阅者：恰好记一条告警，命名消息类型")
        void slowSubscriberLogsExactlyOneWarningNamingType() throws Exception {
            eventBus.subscribe(PanelMessageEvent.class, event -> sleepPastThreshold());

            invokeHandleInboundMessage(notificationMessage("hi"));
            server.getScheduler().performOneTick();

            List<String> warnings = slowHandlerWarnings();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).contains("notification");
        }

        @Test
        @DisplayName("两个慢订阅者：只记一条告警，不是每个订阅者一条")
        void twoSlowSubscribersLogExactlyOneWarning() throws Exception {
            eventBus.subscribe(PanelMessageEvent.class, event -> sleepPastThreshold());
            eventBus.subscribe(PanelMessageEvent.class, event -> sleepPastThreshold());

            invokeHandleInboundMessage(notificationMessage("hi"));
            server.getScheduler().performOneTick();

            assertThat(slowHandlerWarnings()).hasSize(1);
        }
    }

    /** 在一条非主线程上跑给定动作。 */
    private static void runOffPrimaryThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "panel-message-event-test-thread");
        worker.start();
        worker.join(10_000);
        if (thrown.get() != null) {
            throw new AssertionError("非主线程上的动作抛异常了", thrown.get());
        }
    }

    /**
     * No {@link EventBus} available — the publish must be a logged no-op, never an exception.
     * Deliberately separate from {@link PublishBridge}: that group stubs {@code getEventBus()} to
     * return a real bus in its own {@code @BeforeEach}, so this case needs its own independent
     * setup rather than overriding a shared one mid-suite.
     */
    @Nested
    @DisplayName("没有 EventBus 时是一次被记录的空操作")
    class NoEventBusAvailable {

        private Logger mockLogger;

        @BeforeEach
        void setUp() {
            MockBukkitHelper.ensureCleanState();
            MockBukkit.mock();
            MockBukkit.createMockPlugin();

            mockLogger = mock(Logger.class);
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(ultiTools.getConfig()).thenReturn(emptyConfig());
                lenient().when(ultiTools.getEventBus()).thenReturn(null);
            });
        }

        @AfterEach
        void tearDown() throws Exception {
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
            MockBukkitHelper.safeUnmock();
        }

        @Test
        @DisplayName("getEventBus() 为 null 时 handleInboundMessage 正常返回，不抛异常")
        void nullEventBusIsSilentNoOp() {
            assertThatCode(() -> invokeHandleInboundMessage(notificationMessage("hi")))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * No Bukkit scheduler available (no server booted) — also a logged no-op. This is the case
     * a plain, non-MockBukkit unit test like {@code PluginInitiationUtilsInboundMessageTest} is
     * already in: {@code Bukkit.getServer()} is null there, so {@code Bukkit.getScheduler()}
     * would throw if it were ever reached.
     */
    @Nested
    @DisplayName("没有可用调度器时也是一次被记录的空操作")
    class NoSchedulerAvailable {

        private Logger mockLogger;
        private EventBus eventBus;

        @BeforeEach
        void setUp() {
            MockBukkitHelper.ensureCleanState();

            eventBus = new EventBus();
            mockLogger = mock(Logger.class);
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(ultiTools.getConfig()).thenReturn(emptyConfig());
                lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
            });
        }

        @AfterEach
        void tearDown() throws Exception {
            eventBus.shutdown();
            Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
            MockBukkitHelper.ensureCleanState();
        }

        @Test
        @DisplayName("没有 Bukkit server 时 handleInboundMessage 正常返回，不抛异常")
        void noBukkitServerIsSilentNoOp() {
            assertThatCode(() -> invokeHandleInboundMessage(notificationMessage("hi")))
                    .doesNotThrowAnyException();
        }
    }
}
