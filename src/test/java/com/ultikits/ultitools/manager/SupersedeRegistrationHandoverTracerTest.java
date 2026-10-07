package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * UltiTools-Reborn#562 item 2, the tracer of follow-up 3 batch 5: when a newer copy of a loaded module
 * supersedes the older one through {@link PluginManager#register(UltiToolsPlugin)}, the framework
 * releases every registration recorded against the older copy -- panel responders, EventBus
 * subscriptions, tab completers -- before the newer copy's container refresh and {@code
 * registerSelf()}, so the newer copy can claim the panel message types the older copy held. When the
 * newer copy then fails to load, the released registrations go back to the older copy, which keeps
 * running exactly as before, and nothing the failed copy registered stays behind.
 * <p>
 * Before the fix the newer copy's {@code registerSelf()} ran while the older copy still held its
 * responder type, so the newer copy was refused with "already owned by module" and did not load; and
 * a newer copy that failed after registering a subscription or a completer left them registered,
 * answering from a closed container.
 */
@DisplayName("A newer copy takes over its older copy's registrations; a failed newer copy gives them back (#562)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SupersedeRegistrationHandoverTracerTest {

    private static final int CURRENT_API_VERSION = 625;
    private static final String TYPE = "m.query";
    private static final String COMPLETER = "@m-handover";

    private PluginManager pluginManager;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private MockedStatic<UltiTools> ultiToolsStatic;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;

    /** The event both copies subscribe to. */
    public static class HandoverEvent extends ModuleEvent {
    }

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        eventBus = new EventBus();
        responders = new PanelResponderRegistry();
        UltiTools ultiTools = mock(UltiTools.class);
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        lenient().when(ultiTools.getLogger()).thenReturn(mock(Logger.class));
        lenient().when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, Answers.CALLS_REAL_METHODS));
        lenient().when(ultiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
        lenient().when(ultiTools.getEventBus()).thenReturn(eventBus);
        lenient().when(ultiTools.getPanelResponderRegistry()).thenReturn(responders);
        ultiToolsStatic = mockStatic(UltiTools.class);
        ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);
        ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(CURRENT_API_VERSION);
        pluginManager = new PluginManager();
        lenient().when(ultiTools.getPluginManager()).thenReturn(pluginManager);

        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                bukkitLogs.add(logRecord);
            }

            @Override
            public void flush() {
                // records are appended straight to the list
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Bukkit.getLogger().addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(captureHandler);
        TabCompletionManager.getInstance().unregister(COMPLETER);
        eventBus.shutdown();
        responders.shutdown();
        if (ultiToolsStatic != null) {
            ultiToolsStatic.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String version) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("M");
        lenient().when(plugin.getMainClass()).thenReturn("com.example.M");
        lenient().when(plugin.getVersion()).thenReturn(version);
        lenient().when(plugin.getMinUltiToolsVersion()).thenReturn(CURRENT_API_VERSION);
        return plugin;
    }

    private static Function<JsonObject, CompletableFuture<JsonObject>> answering(String copy) {
        return data -> {
            JsonObject reply = new JsonObject();
            reply.addProperty("copy", copy);
            return CompletableFuture.completedFuture(reply);
        };
    }

    /** Registers, through the ordinary name-only APIs, what one copy of M registers while it loads. */
    private void registerAs(String copy, AtomicInteger calls) {
        eventBus.subscribe(HandoverEvent.class, EventPriority.NORMAL, false, "M",
                (HandoverEvent event) -> calls.incrementAndGet());
        TabCompletionManager.getInstance().register(COMPLETER, context -> Collections.singletonList(copy));
        responders.registerResponder(TYPE, answering(copy), "M");
    }

    private String answer() throws Exception {
        return responders.dispatch(TYPE, new JsonObject(), "r").get(5, TimeUnit.SECONDS).get("copy").getAsString();
    }

    private String completion() {
        return TabCompletionManager.getInstance().getCompleter(COMPLETER).complete(null).get(0);
    }

    private List<String> warnings() {
        List<String> messages = new ArrayList<>();
        for (LogRecord logRecord : bukkitLogs) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue() && logRecord.getMessage() != null) {
                messages.add(logRecord.getMessage());
            }
        }
        return messages;
    }

    private UltiToolsPlugin loadOlderCopy(AtomicInteger olderCalls) throws Exception {
        UltiToolsPlugin older = module("1.0.0");
        when(older.registerSelf()).thenAnswer(invocation -> {
            registerAs("v1", olderCalls);
            return true;
        });
        assertThat(pluginManager.register(older)).isTrue();
        return older;
    }

    @Test
    @DisplayName("the newer copy claims the responder type, subscription and completer its older copy held")
    void newerCopyClaimsWhatTheOlderCopyHeld() throws Exception {
        AtomicInteger olderCalls = new AtomicInteger();
        AtomicInteger newerCalls = new AtomicInteger();
        UltiToolsPlugin older = loadOlderCopy(olderCalls);
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        AtomicBoolean typeFreeWhenNewerRegisters = new AtomicBoolean();
        AtomicBoolean completerFreeWhenNewerRegisters = new AtomicBoolean();
        AtomicInteger olderCallsSeenByNewer = new AtomicInteger(-1);
        when(newer.registerSelf()).thenAnswer(invocation -> {
            typeFreeWhenNewerRegisters.set(!responders.hasResponder(TYPE));
            completerFreeWhenNewerRegisters.set(TabCompletionManager.getInstance().getCompleter(COMPLETER) == null);
            eventBus.publish(new HandoverEvent());
            olderCallsSeenByNewer.set(olderCalls.get());
            registerAs("v2", newerCalls);
            return true;
        });

        boolean loaded = pluginManager.register(newer);

        assertThat(loaded).as("the newer copy is not refused for the type its older copy held").isTrue();
        assertThat(pluginManager.getPluginList()).containsExactly(newer);
        assertThat(typeFreeWhenNewerRegisters.get())
                .as("the older copy's responder type is released before the newer copy's registerSelf()").isTrue();
        assertThat(completerFreeWhenNewerRegisters.get())
                .as("the older copy's completer is released before the newer copy's registerSelf()").isTrue();
        assertThat(olderCallsSeenByNewer.get())
                .as("the older copy's subscription no longer receives events while the newer copy loads").isZero();
        assertThat(answer()).as("the panel type answers with the newer copy's responder").isEqualTo("v2");
        assertThat(completion()).isEqualTo("v2");
        eventBus.publish(new HandoverEvent());
        assertThat(olderCalls.get()).as("the older copy's subscription is gone").isZero();
        assertThat(newerCalls.get()).isEqualTo(1);
        assertThat(warnings()).noneSatisfy(message -> assertThat(message).contains("load failed"));
    }

    @Test
    @DisplayName("a newer copy whose registerSelf() returns false gives every registration back to the older copy")
    void newerCopyReturningFalseGivesTheRegistrationsBack() throws Exception {
        AtomicInteger olderCalls = new AtomicInteger();
        AtomicInteger newerCalls = new AtomicInteger();
        UltiToolsPlugin older = loadOlderCopy(olderCalls);
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenAnswer(invocation -> {
            registerAs("v2", newerCalls);
            return false;
        });

        boolean loaded = pluginManager.register(newer);

        assertOlderCopyWhole(loaded, older, olderCalls, newerCalls);
    }

    @Test
    @DisplayName("a newer copy whose registerSelf() throws gives every registration back to the older copy")
    void newerCopyThrowingGivesTheRegistrationsBack() throws Exception {
        AtomicInteger olderCalls = new AtomicInteger();
        AtomicInteger newerCalls = new AtomicInteger();
        UltiToolsPlugin older = loadOlderCopy(olderCalls);
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenAnswer(invocation -> {
            registerAs("v2", newerCalls);
            throw new IllegalStateException("newer copy failed to start");
        });

        boolean loaded = pluginManager.register(newer);

        assertOlderCopyWhole(loaded, older, olderCalls, newerCalls);
    }

    private void assertOlderCopyWhole(boolean loaded, UltiToolsPlugin older, AtomicInteger olderCalls,
            AtomicInteger newerCalls) throws Exception {
        assertThat(loaded).isFalse();
        assertThat(pluginManager.getPluginList()).as("the older copy stays loaded").containsExactly(older);
        assertThat(answer()).as("the panel type answers with the older copy's responder, as before").isEqualTo("v1");
        assertThat(completion()).as("the completer is the older copy's again").isEqualTo("v1");
        eventBus.publish(new HandoverEvent());
        assertThat(olderCalls.get()).as("the older copy's subscription receives events again").isEqualTo(1);
        assertThat(newerCalls.get()).as("nothing the failed newer copy subscribed stays behind").isZero();
        assertThat(warnings()).as("the failure is logged as before")
                .anySatisfy(message -> assertThat(message).contains("M load failed"));

        pluginManager.unregister(older);
        assertThat(responders.hasResponder(TYPE)).as("the restored registrations still belong to the older copy")
                .isFalse();
        assertThat(TabCompletionManager.getInstance().getCompleter(COMPLETER)).isNull();
    }
}
