package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import com.ultikits.ultitools.commands.tabcomplete.TabCompleter;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * #506: when a newer copy of a module supersedes a loaded older copy, the older copy is unloaded
 * through the full {@link PluginManager#unregister(UltiToolsPlugin)} path -- its tasks cancelled,
 * its container closed, its completers, EventBus handlers and panel responders released, and it
 * is delisted -- while every registration the newer copy already made under the same module name
 * survives.
 * <p>
 * Before the fix the older copy got only {@code unregisterSelf()}, and routing it through the full
 * path was unsafe because three registries released by module NAME, which both copies share.
 */
@DisplayName("A superseded copy is unloaded through the full path and releases only its own registrations (#506)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // injects a mock TaskManager, as the sibling close tests do
class PluginManagerSupersedeUnloadTest {

    private static final int CURRENT_API_VERSION = 625;
    private static final String OLD_KEY = "@supersede-test-old";
    private static final String NEW_KEY = "@supersede-test-new";

    private PluginManager pluginManager;
    private TaskManager taskManager;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private MockedStatic<UltiTools> ultiToolsStatic;

    /** A module event the older copy's annotated handler listens for. */
    public static class SupersedeTestEvent extends ModuleEvent {
    }

    /** The older copy's annotated handler bean. */
    public static class HandlerBean {
        int calls;

        public void on(SupersedeTestEvent event) {
            calls++;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
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
        taskManager = mock(TaskManager.class);
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(pluginManager, taskManager);
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

    private static UltiToolsPlugin module(String name, String version) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn(name);
        lenient().when(plugin.getMainClass()).thenReturn("com.example." + name);
        lenient().when(plugin.getVersion()).thenReturn(version);
        lenient().when(plugin.getMinUltiToolsVersion()).thenReturn(CURRENT_API_VERSION);
        return plugin;
    }

    private static Function<JsonObject, CompletableFuture<JsonObject>> responder() {
        return data -> CompletableFuture.completedFuture(new JsonObject());
    }

    private static TabCompleter completer() {
        return context -> Collections.singletonList("x");
    }

    /** What the framework records for a module's completers while it loads: the scope carries the instance. */
    private static void registerCompleterInScope(String key, UltiToolsPlugin owner) {
        TabCompletionManager completions = TabCompletionManager.getInstance();
        completions.beginRegistrationScope(owner.getPluginName(), owner);
        try {
            completions.register(key, completer());
        } finally {
            completions.endRegistrationScope();
        }
    }

    @Test
    @DisplayName("the superseded copy is fully unloaded, and the newer copy's completer and same-name responder survive")
    void supersedeUnloadsTheOlderCopyAndKeepsTheNewerCopysRegistrations() throws Exception {
        UltiToolsPlugin older = module("Dup", "1.0.0");
        SimpleContainer olderContext = mock(SimpleContainer.class);
        when(older.getContext()).thenReturn(olderContext);
        UltiToolsPlugin newer = module("Dup", "2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);

        // What the older copy holds from its own load.
        registerCompleterInScope(OLD_KEY, older);
        HandlerBean olderHandler = new HandlerBean();
        Method onEvent = HandlerBean.class.getMethod("on", SupersedeTestEvent.class);
        eventBus.register(SupersedeTestEvent.class, EventPriority.NORMAL, false, "Dup", older, onEvent, olderHandler);
        responders.registerResponder("supersede.old", responder(), "Dup", older);
        PluginListSeeding.add(pluginManager, older);

        // The newer copy registers under the shared name while it activates -- before the older
        // copy is unloaded -- a completer inside its registration scope and a name-only responder.
        when(newer.registerSelf()).thenAnswer(invocation -> {
            registerCompleterInScope(NEW_KEY, newer);
            responders.registerResponder("supersede.new", responder(), "Dup");
            return true;
        });

        boolean registered = pluginManager.register(newer);

        assertThat(registered).isTrue();
        verify(older).unregisterSelf();
        verify(taskManager).cancelAll(older);
        verify(taskManager, never()).cancelAll(newer);
        verify(olderContext).close();
        assertThat(pluginManager.getPluginList())
                .as("the superseded copy must not stay listed next to its replacement")
                .containsExactly(newer);
        assertThat(TabCompletionManager.getInstance().getCompleter(OLD_KEY))
                .as("the superseded copy's completer is released").isNull();
        assertThat(responders.hasResponder("supersede.old"))
                .as("the superseded copy's responder is released").isFalse();
        eventBus.publish(new SupersedeTestEvent());
        assertThat(olderHandler.calls).as("the superseded copy's EventBus handler is released").isZero();

        assertThat(TabCompletionManager.getInstance().getCompleter(NEW_KEY))
                .as("the newer copy's completer, registered under the same module name, survives").isNotNull();
        assertThat(responders.hasResponder("supersede.new"))
                .as("the newer copy's responder, registered under the same module name, survives").isTrue();
    }

    @Test
    @DisplayName("a registration recorded against a module instance is released even when it was made under another name")
    void instanceRecordedRegistrationUnderAnotherNameIsReleased() {
        UltiToolsPlugin alpha = module("Alpha", "1.0.0");
        responders.registerResponder("alpha.addon", responder(), "AlphaAddon", alpha);
        PluginListSeeding.add(pluginManager, alpha);

        pluginManager.unregister(alpha);

        assertThat(responders.hasResponder("alpha.addon"))
                .as("the owner instance, not the name it was filed under, decides the release").isFalse();
    }

    @Test
    @DisplayName("with no other copy of the name loaded, name-only registrations are still released by name")
    void nameOnlyRegistrationsAreReleasedWhenNoOtherCopyIsLoaded() {
        UltiToolsPlugin solo = module("Solo", "1.0.0");
        HandlerBean handler = new HandlerBean();
        eventBus.subscribe(SupersedeTestEvent.class, EventPriority.NORMAL, false, "Solo", event -> handler.calls++);
        responders.registerResponder("solo.type", responder(), "Solo");
        PluginListSeeding.add(pluginManager, solo);

        pluginManager.unregister(solo);

        eventBus.publish(new SupersedeTestEvent());
        assertThat(handler.calls).as("a name-only subscription is released as before").isZero();
        assertThat(responders.hasResponder("solo.type")).as("a name-only responder is released as before").isFalse();
    }

    @Test
    @DisplayName("a superseded copy's name-only registration stays with the surviving copy and goes when that copy unloads")
    void nameOnlyRegistrationOfTheSupersededCopyIsReleasedWithTheSurvivor() throws Exception {
        UltiToolsPlugin older = module("Legacy", "1.0.0");
        UltiToolsPlugin newer = module("Legacy", "2.0.0");
        when(newer.isNewerVersionThan(older)).thenReturn(true);
        when(newer.registerSelf()).thenReturn(true);
        responders.registerResponder("legacy.type", responder(), "Legacy");
        PluginListSeeding.add(pluginManager, older);

        pluginManager.register(newer);

        assertThat(responders.hasResponder("legacy.type"))
                .as("filed under the shared name only, it cannot be told apart from the newer copy's, so it stays")
                .isTrue();

        pluginManager.unregister(newer);

        assertThat(responders.hasResponder("legacy.type"))
                .as("once no copy of the name is loaded, the name-only registration is released").isFalse();
    }
}
