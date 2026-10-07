package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Tracer for UltiTools-Reborn#621: a panel message that arrives while UltiTools is disabling must
 * not make the WebSocket thread schedule work for the disabled plugin. Bukkit flips
 * {@code isEnabled()} to {@code false} before it calls {@code onDisable()}, and Paper's
 * {@code CraftScheduler} then rejects every task with {@link IllegalPluginAccessException}; the
 * scheduler used here does exactly that, so the base's behaviour (a WARNING with the full stack in
 * the stop log) is reproduced deterministically.
 * <br>
 * #621 的示踪测试：UltiTools 正在禁用时到达的面板消息不得让 WebSocket 线程为已禁用的插件调度任务。
 */
@DisplayName("#621 panel messages while UltiTools is disabling")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reaches the instance field and the accepting-flag seam, as PanelMessageEventDispatchTest does
class ShutdownPanelMessageTracerTest {

    private static final String MODULE_TYPE = "fixture_module_query";

    private ServerMock server;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private UltiPanelWebSocketClient panelWs;
    private UltiPanelWebSocketClient previousPanelWs;
    private MockedStatic<Bukkit> bukkit;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final List<PanelMessageEvent> published = new CopyOnWriteArrayList<>();
    private final List<String> disabledScheduling = new CopyOnWriteArrayList<>();
    private final List<String> enabledScheduling = new CopyOnWriteArrayList<>();
    private final List<String> respondedTypes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();

        Logger logger = Logger.getLogger("UltiTools-ShutdownTracer-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        });

        eventBus = new EventBus();
        eventBus.subscribe(PanelMessageEvent.class, published::add);
        responders = new PanelResponderRegistry();
        responders.registerResponder(MODULE_TYPE, data -> {
            respondedTypes.add(MODULE_TYPE);
            return CompletableFuture.completedFuture(new JsonObject());
        }, "FixtureModule");

        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
            lenient().when(ultiTools.getCommandExecutionManager()).thenReturn(mock(CommandExecutionManager.class));
            lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(responders);
        });

        panelWs = mock(UltiPanelWebSocketClient.class);
        lenient().when(panelWs.getServerId()).thenReturn("tracer-server");
        previousPanelWs = PluginInitiationUtils.setWebSocketClientForTesting(panelWs);

        // A scheduler that behaves like Paper's CraftScheduler#validate: a task for a disabled
        // plugin is refused with IllegalPluginAccessException; otherwise MockBukkit runs it.
        BukkitScheduler real = server.getScheduler();
        Answer<Object> validating = invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length > 0 && args[0] instanceof Plugin) {
                String call = invocation.getMethod().getName();
                if (!((Plugin) args[0]).isEnabled()) {
                    disabledScheduling.add(call);
                    throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
                }
                enabledScheduling.add(call);
            }
            try {
                return invocation.getMethod().invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        BukkitScheduler scheduler = mock(BukkitScheduler.class, validating);
        bukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        bukkit.close();
        try {
            setAccepting(true);
        } catch (NoSuchMethodException absentOnTheBase) {
            // the base has no flag; nothing to restore
        }
        PluginInitiationUtils.setWebSocketClientForTesting(previousPanelWs);
        responders.shutdown();
        eventBus.shutdown();
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        MockBukkitHelper.safeUnmock();
    }

    /**
     * The accepting flag is reached reflectively so that this class compiles on the base, where
     * the flag does not exist yet; there the call fails and the test that needs it errors.
     */
    private static void setAccepting(boolean accepting) throws Exception {
        Method method = PluginInitiationUtils.class.getDeclaredMethod(
                accepting ? "startAcceptingPanelMessages" : "stopAcceptingPanelMessages");
        method.invoke(null);
    }

    private static void disabling() {
        when(UltiTools.getInstance().isEnabled()).thenReturn(false);
    }

    private static JsonObject message(String type) {
        JsonObject data = new JsonObject();
        data.addProperty("requestId", "r-" + type);
        if ("subscribe".equals(type)) {
            // the reply the panel sends after a successful subscription
            data.addProperty("subscribed", true);
            data.addProperty("serverId", "tracer-server");
            data.addProperty("message", "Subscribed to the server");
        }
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.add("data", data);
        return message;
    }

    private List<LogRecord> at(Level level) {
        return records.stream().filter(r -> r.getLevel().equals(level)).collect(Collectors.toList());
    }

    private List<String> messages(Level level) {
        return at(level).stream().map(LogRecord::getMessage).collect(Collectors.toList());
    }

    private void assertDroppedQuietly(String type) {
        assertThat(at(Level.WARNING)).as("WARNING lines (%s)", type)
                .extracting(r -> r.getMessage() + " | thrown: " + r.getThrown()).isEmpty();
        assertThat(messages(Level.SEVERE)).as("SEVERE lines (%s)", type).isEmpty();
        assertThat(disabledScheduling).as("scheduler calls for the disabled plugin (%s)", type).isEmpty();
        List<LogRecord> fine = at(Level.FINE);
        assertThat(fine).as("FINE lines (%s): %s", type, messages(Level.FINE)).hasSize(1);
        assertThat(fine.get(0).getMessage()).contains(type);
        assertThat(fine.get(0).getThrown()).isNull();
    }

    @Nested
    @DisplayName("tracer: the subscribe reply seen on the real server")
    class Tracer {

        @Test
        @DisplayName("a subscribe reply while disabling: nothing scheduled, one FINE line, no WARNING")
        void subscribeReplyWhileDisablingIsDroppedQuietly() {
            disabling();

            PluginInitiationUtils.handleInboundMessage(message("subscribe"));

            assertDroppedQuietly("subscribe");
            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("the accepting flag alone, cleared first in onDisable, drops the message too")
        void clearedFlagDropsEvenBeforeBukkitReportsDisabled() throws Exception {
            setAccepting(false);

            PluginInitiationUtils.handleInboundMessage(message("subscribe"));
            server.getScheduler().performOneTick();

            assertDroppedQuietly("subscribe");
            assertThat(enabledScheduling).isEmpty();
            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("control: while enabled the same reply is handled and published exactly as before")
        void enabledReplyIsHandledAsBefore() {
            PluginInitiationUtils.handleInboundMessage(message("subscribe"));
            server.getScheduler().performOneTick();

            assertThat(enabledScheduling).containsExactly("runTask");
            assertThat(published).hasSize(1);
            assertThat(published.get(0).getType()).isEqualTo("subscribe");
            assertThat(messages(Level.WARNING)).isEmpty();
        }
    }

    @Nested
    @DisplayName("sweep: every inbound type behind the same check")
    class Sweep {

        @Test
        @DisplayName("each of the framework-owned types while disabling is dropped quietly")
        void everyFrameworkOwnedTypeIsDropped() {
            disabling();
            List<String> types = new ArrayList<>(PluginInitiationUtils.inboundDispatchTable().keySet());
            assertThat(types).as("control: the dispatch table is populated").contains("subscribe", "execute_command");

            for (String type : types) {
                records.clear();
                PluginInitiationUtils.handleInboundMessage(message(type));
                assertDroppedQuietly(type);
            }
            assertThat(published).isEmpty();
            verify(panelWs, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("a module-claimed responder type while disabling is dropped before its responder runs")
        void moduleResponderTypeIsDropped() {
            disabling();

            PluginInitiationUtils.handleInboundMessage(message(MODULE_TYPE));

            assertDroppedQuietly(MODULE_TYPE);
            assertThat(respondedTypes).isEmpty();
        }

        @Test
        @DisplayName("control: a module-claimed responder type while enabled reaches its responder")
        void moduleResponderTypeWhileEnabledIsServed() {
            PluginInitiationUtils.handleInboundMessage(message(MODULE_TYPE));
            server.getScheduler().performOneTick();

            assertThat(respondedTypes).containsExactly(MODULE_TYPE);
            assertThat(published).hasSize(1);
        }

        @Test
        @DisplayName("a handshake completing while disabling wires nothing and schedules nothing")
        void handshakeWhileDisablingWiresNothing() {
            disabling();

            PluginInitiationUtils.onWebSocketOpened(panelWs);

            assertThat(disabledScheduling).isEmpty();
            verify(panelWs, never()).subscribeToServer(anyString());
            assertThat(messages(Level.WARNING)).isEmpty();
            assertThat(messages(Level.SEVERE)).isEmpty();
        }
    }
}
