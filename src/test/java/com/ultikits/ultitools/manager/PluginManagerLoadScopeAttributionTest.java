package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * #506, completed after the Codex review of #564 (round 3): what a module registers through the
 * ordinary, name-only APIs <em>while the framework loads it</em> -- in its container refresh and
 * in {@code registerSelf()} -- is recorded against that module instance, so a superseded copy's
 * programmatic EventBus subscriptions, panel responders and completers are released with it and
 * its replacement's, filed under the same name, survive.
 * <p>
 * Registrations a module makes later, outside its load, are recorded against the copy listed under
 * the name they are filed under (UltiTools-Reborn#562 item 1, maintainer row 01:18 of 2026-10-06);
 * {@code PostLoadRegistrationAttributionTest} covers them.
 */
@DisplayName("Registrations made while a module loads are recorded against its instance (#506)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerLoadScopeAttributionTest {

    private static final int CURRENT_API_VERSION = 625;
    private static final String OLD_KEY = "@load-scope-old";
    private static final String NEW_KEY = "@load-scope-new";

    private PluginManager pluginManager;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private MockedStatic<UltiTools> ultiToolsStatic;

    /** The event both copies subscribe to. */
    public static class LoadScopeEvent extends ModuleEvent {
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
    }

    @AfterEach
    void tearDown() {
        TabCompletionManager.getInstance().unregister(OLD_KEY);
        TabCompletionManager.getInstance().unregister(NEW_KEY);
        eventBus.shutdown();
        responders.shutdown();
        if (ultiToolsStatic != null) {
            ultiToolsStatic.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String version) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("Dup");
        lenient().when(plugin.getMainClass()).thenReturn("com.example.Dup");
        lenient().when(plugin.getVersion()).thenReturn(version);
        lenient().when(plugin.getMinUltiToolsVersion()).thenReturn(CURRENT_API_VERSION);
        return plugin;
    }

    private static Function<JsonObject, CompletableFuture<JsonObject>> responder() {
        return data -> CompletableFuture.completedFuture(new JsonObject());
    }

    /** Stubs a copy's registerSelf() to register through the ordinary, name-only APIs only. */
    private void registersWhileLoading(UltiToolsPlugin copy, String responderType, String completerKey,
            AtomicInteger calls) throws Exception {
        when(copy.registerSelf()).thenAnswer(invocation -> {
            eventBus.subscribe(LoadScopeEvent.class, EventPriority.NORMAL, false, "Dup",
                    (LoadScopeEvent event) -> calls.incrementAndGet());
            responders.registerResponder(responderType, responder(), "Dup");
            TabCompletionManager.getInstance().register(completerKey, context -> Collections.singletonList("x"));
            return true;
        });
    }

    @Test
    @DisplayName("the superseded copy's load-time subscription, responder and completer go; the newer copy's survive")
    void loadTimeRegistrationsFollowTheirCopy() throws Exception {
        UltiToolsPlugin older = module("1.0.0");
        UltiToolsPlugin newer = module("2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        AtomicInteger olderCalls = new AtomicInteger();
        AtomicInteger newerCalls = new AtomicInteger();
        registersWhileLoading(older, "load-scope.old", OLD_KEY, olderCalls);
        registersWhileLoading(newer, "load-scope.new", NEW_KEY, newerCalls);

        assertThat(pluginManager.register(older)).isTrue();
        assertThat(pluginManager.register(newer)).isTrue();
        eventBus.publish(new LoadScopeEvent());

        assertThat(pluginManager.getPluginList()).containsExactly(newer);
        assertThat(olderCalls.get()).as("the superseded copy's subscription is released").isZero();
        assertThat(responders.hasResponder("load-scope.old")).as("the superseded copy's responder is released").isFalse();
        assertThat(TabCompletionManager.getInstance().getCompleter(OLD_KEY))
                .as("the superseded copy's completer, registered in registerSelf(), is released").isNull();
        assertThat(newerCalls.get()).as("the newer copy's subscription survives").isEqualTo(1);
        assertThat(responders.hasResponder("load-scope.new")).as("the newer copy's responder survives").isTrue();
        assertThat(TabCompletionManager.getInstance().getCompleter(NEW_KEY))
                .as("the newer copy's completer survives").isNotNull();
    }

    @Test
    @DisplayName("a registration made after the module loaded, without an instance, leaves with the listed copy (#562)")
    void registrationAfterLoadLeavesWithTheListedCopy() throws Exception {
        // Changed by #562 item 1 (maintainer row 01:18, contract batching): before, this registration
        // was filed under the name only and stayed until the last copy of "Dup" unloaded.
        UltiToolsPlugin older = module("1.0.0");
        UltiToolsPlugin newer = module("2.0.0");
        when(older.registerSelf()).thenReturn(true);
        when(newer.registerSelf()).thenReturn(true);
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        assertThat(pluginManager.register(older)).isTrue();
        responders.registerResponder("load-scope.later", responder(), "Dup");

        pluginManager.register(newer);

        assertThat(responders.hasResponder("load-scope.later"))
                .as("recorded against the older copy, the one listed as Dup, so it leaves with it").isFalse();
    }
}
