package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

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
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * UltiTools-Reborn#562 item 1: a registration a module makes after it loaded, through the name-only
 * {@code PanelResponderRegistry#registerResponder(type, responder, ownerModule)} or {@code
 * EventBus#subscribe(..., ownerModule, consumer)}, is recorded against a module instance -- the copy
 * the framework lists as loaded under that name; when the framework is loading a copy on the calling
 * thread, that copy (its registration scope, unchanged since #564); when more than one copy is listed
 * under the name, the first listed. Unloading or superseding a copy then releases exactly its own
 * registrations. A name with no loaded copy keeps the name-only behaviour.
 * <p>
 * Before the fix such a registration was filed under the name only, so while a second copy of the
 * module shared the name it could not be told apart from that copy's and stayed registered -- with
 * code from a closed container -- until the last copy of the name unloaded.
 * <p>
 * The "other thread" cases run their registration on a real second thread, which opens its own
 * static mock of {@link UltiTools}: Mockito's static mocks are per thread, and the registries look
 * the framework up through {@link UltiTools#getInstance()} on the thread that registers.
 */
@DisplayName("Registrations made after load through the name-only APIs belong to a module instance (#562)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PostLoadRegistrationAttributionTest {

    private static final int CURRENT_API_VERSION = 625;

    private PluginManager pluginManager;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private UltiTools ultiTools;
    private MockedStatic<UltiTools> ultiToolsStatic;

    /** The event late subscriptions listen to. */
    public static class LateEvent extends ModuleEvent {
    }

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        eventBus = new EventBus();
        responders = new PanelResponderRegistry();
        ultiTools = mock(UltiTools.class);
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
    }

    @AfterEach
    void tearDown() {
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

    private static Function<JsonObject, CompletableFuture<JsonObject>> responder() {
        return data -> CompletableFuture.completedFuture(new JsonObject());
    }

    private UltiToolsPlugin loaded(String version) throws Exception {
        UltiToolsPlugin copy = module(version);
        when(copy.registerSelf()).thenReturn(true);
        assertThat(pluginManager.register(copy)).isTrue();
        return copy;
    }

    /** Runs {@code registration} on a second thread, as a module's scheduled task would, and waits for it. */
    private void onAnotherThread(Runnable registration) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread task = new Thread(() -> {
            try (MockedStatic<UltiTools> perThread = mockStatic(UltiTools.class)) {
                perThread.when(UltiTools::getInstance).thenReturn(ultiTools);
                registration.run();
            } catch (Throwable t) { // NOPMD - rethrown on the test thread below
                failure.set(t);
            }
        }, "post-load-registration-task");
        task.start();
        task.join(10_000L);
        assertThat(task.isAlive()).isFalse();
        if (failure.get() != null) {
            throw new AssertionError("registration on the second thread failed", failure.get());
        }
    }

    @Test
    @DisplayName("a responder and a subscription registered by name after load leave with the copy that is superseded")
    void lateRegistrationsLeaveWithTheSupersededCopy() throws Exception {
        UltiToolsPlugin older = loaded("1.0.0");
        AtomicInteger lateCalls = new AtomicInteger();
        onAnotherThread(() -> {
            responders.registerResponder("m.late", responder(), "M");
            eventBus.subscribe(LateEvent.class, EventPriority.NORMAL, false, "M",
                    (LateEvent event) -> lateCalls.incrementAndGet());
        });
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenReturn(true);

        assertThat(pluginManager.register(newer)).isTrue();
        eventBus.publish(new LateEvent());

        assertThat(pluginManager.getPluginList()).containsExactly(newer);
        assertThat(responders.hasResponder("m.late"))
                .as("the older copy's late responder does not outlive it under the shared name").isFalse();
        assertThat(lateCalls.get()).as("the older copy's late subscription does not outlive it").isZero();
    }

    @Test
    @DisplayName("while a newer copy loads, the loading thread's registration is the newer copy's and another thread's the older copy's")
    void duringASupersedeTheScopeDecidesAndOtherwiseTheOlderCopy() throws Exception {
        UltiToolsPlugin older = loaded("1.0.0");
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenAnswer(invocation -> {
            responders.registerResponder("m.scope", responder(), "M");
            onAnotherThread(() -> responders.registerResponder("m.other-thread", responder(), "M"));
            return true;
        });

        assertThat(pluginManager.register(newer)).isTrue();

        assertThat(responders.hasResponder("m.scope")).as("registered in the newer copy's load scope").isTrue();
        assertThat(responders.hasResponder("m.other-thread"))
                .as("registered from another thread while the older copy was the listed one: released with it")
                .isFalse();
        pluginManager.unregister(newer);
        assertThat(responders.hasResponder("m.scope")).isFalse();
    }

    @Test
    @DisplayName("with two copies listed under one name, a late registration belongs to the first listed and leaves with it")
    void twoListedCopiesAttributeToTheFirstListed() throws Exception {
        UltiToolsPlugin first = loaded("1.0.0");
        UltiToolsPlugin second = loaded("1.0.0");
        assertThat(pluginManager.getPluginList()).containsExactly(first, second);
        onAnotherThread(() -> responders.registerResponder("m.late", responder(), "M"));

        pluginManager.unregister(first);

        assertThat(responders.hasResponder("m.late"))
                .as("released with the first listed copy although the second still carries the name").isFalse();
    }

    @Test
    @DisplayName("one loaded copy: a late registration is released when that copy unloads")
    void oneLoadedCopyReleasesItsLateRegistrationOnUnload() throws Exception {
        UltiToolsPlugin only = loaded("1.0.0");
        onAnotherThread(() -> responders.registerResponder("m.late", responder(), "M"));

        pluginManager.unregister(only);

        assertThat(responders.hasResponder("m.late")).isFalse();
    }

    @Test
    @DisplayName("a name with no loaded copy keeps the name-only behaviour")
    void aNameWithNoLoadedCopyStaysNameOnly() throws Exception {
        UltiToolsPlugin m = loaded("1.0.0");
        AtomicInteger calls = new AtomicInteger();
        responders.registerResponder("nobody.query", responder(), "Nobody");
        eventBus.subscribe(LateEvent.class, EventPriority.NORMAL, false, "Nobody",
                (LateEvent event) -> calls.incrementAndGet());

        pluginManager.unregister(m);
        eventBus.publish(new LateEvent());

        assertThat(responders.hasResponder("nobody.query")).as("not attributed to an unrelated module").isTrue();
        assertThat(calls.get()).isEqualTo(1);
        responders.unregisterAll("Nobody");
        eventBus.unregisterAll("Nobody");
        eventBus.publish(new LateEvent());
        assertThat(responders.hasResponder("nobody.query")).as("released by name as before").isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }
}
