package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * A module's {@code onUnregister()} may throw a checked exception it does not declare (Lombok
 * {@code @SneakyThrows}, or a rethrow helper). Unloading that module through the real
 * {@link PluginManager#unregister(UltiToolsPlugin)} path must still release everything the framework
 * registered for it -- above all its Bukkit listener and its command, which would otherwise stay
 * callable against a closed container -- and must hand the caller the very exception the hook threw.
 * <p>
 * Every registry here is real: the framework's {@link ListenerManager} and {@link CommandManager},
 * Bukkit's {@link HandlerList}s and a real {@link SimpleCommandMap} behind a real
 * {@link SimplePluginManager} (MockBukkit's own plugin manager is not a {@code SimplePluginManager},
 * so {@code CommandManager} could not reach a command map under it -- see
 * {@code CommandManagerKnownCommandsCleanupTest}), the {@link TabCompletionManager} singleton, a real
 * {@link EventBus} and a real {@link PanelResponderRegistry}. Only the scheduler-backed
 * {@link TaskManager} and the module's container are doubles.
 * <p>
 * Every module JAR is loaded by one shared class loader, so an unload can never release a loader;
 * "class loader cleanup" is therefore checked as "no framework or Bukkit registry still references the
 * module's objects or classes".
 *
 * @since 6.3.0
 */
@DisplayName("Unloading a module whose unload hook throws an undeclared checked exception still releases it")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerUnloadHookCheckedFailureTest {

    private static final String FALLBACK_PREFIX = "ultitools";
    private static final String MODULE_NAME = "HookModule";
    private static final String COMMAND_LABEL = "hookprobe";
    private static final String COMPLETER_KEY = "@hookprobe";
    private static final String RESPONDER_TYPE = "hookmodule.probe";

    private ServerMock server;
    private SimpleCommandMap realCommandMap;
    private ListenerManager listenerManager;
    private CommandManager commandManager;
    private EventBus eventBus;
    private PanelResponderRegistry responders;
    private TaskManager taskManager;
    private PluginManager pluginManager;
    private MockedStatic<Bukkit> bukkit;
    private final List<Listener> registeredListeners = new ArrayList<>();

    /** A Bukkit event only the module's listener handles. */
    public static class ProbeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** A module event only the module's EventBus subscription handles. */
    public static class ModuleProbeEvent extends ModuleEvent {
    }

    /** The module's Bukkit listener; counts the probe events that reach it. */
    @EventListener
    public static class HookModuleListener implements Listener {
        final AtomicInteger calls = new AtomicInteger();

        @EventHandler
        public void onProbe(ProbeEvent event) {
            calls.incrementAndGet();
        }
    }

    /** The module's command. */
    @CmdExecutor(alias = {COMMAND_LABEL}, permission = "test.hookprobe", description = "Hook probe")
    public static class HookModuleCommand extends BaseCommandExecutor {
        @CmdMapping(format = "")
        public void execute(@CmdSender CommandSender sender) {
            sender.sendMessage("hook probe executed");
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("help");
        }
    }

    /**
     * The module; mocked so its constructor is bypassed and its real final unregisterSelf() runs. The
     * hook is redeclared here only so this package can stub it.
     */
    abstract static class HookModule extends UltiToolsPlugin {
        @Override
        protected void onUnregister() {
            super.onUnregister();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        PluginMock loaderSource = MockBukkit.createMockPlugin();
        realCommandMap = new SimpleCommandMap(server, new HashMap<>());
        SimplePluginManager realPluginManager = new SimplePluginManager(server, realCommandMap);
        listenerManager = new ListenerManager();
        commandManager = new CommandManager();
        eventBus = new EventBus();
        responders = new PanelResponderRegistry();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDescription()).thenReturn(
                    new PluginDescriptionFile(FALLBACK_PREFIX, "1.0", "com.ultikits.ultitools.UltiTools"));
            // A real loader, so Bukkit builds real RegisteredListeners for the framework plugin.
            when(ultiTools.getPluginLoader()).thenReturn(loaderSource.getPluginLoader());
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
            when(ultiTools.getCommandManager()).thenReturn(commandManager);
            when(ultiTools.getEventBus()).thenReturn(eventBus);
            when(ultiTools.getPanelResponderRegistry()).thenReturn(responders);
        });
        pluginManager = new PluginManager();
        taskManager = mock(TaskManager.class);
        injectTaskManager(taskManager);
        // Everything else on Bukkit stays real; only the plugin manager is the one owning the real command map.
        bukkit = mockStatic(Bukkit.class, Answers.CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getPluginManager).thenReturn(realPluginManager);
    }

    @AfterEach
    void tearDown() {
        for (Listener listener : registeredListeners) {
            HandlerList.unregisterAll(listener);
        }
        TabCompletionManager.getInstance().unregister(COMPLETER_KEY);
        if (bukkit != null) {
            bukkit.close();
        }
        eventBus.shutdown();
        responders.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // injecting the TaskManager double, as PluginManagerCloseFailureIsolationTest does
    private void injectTaskManager(TaskManager replacement) throws Exception {
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(pluginManager, replacement);
    }

    /**
     * A copy of the list {@code manager} tracks for {@code module} in its private per-module map, or an
     * empty list when it tracks none.
     */
    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"}) // test seam: the per-module maps are private by design
    private static <T> List<T> trackedFor(Object manager, String mapField, UltiToolsPlugin module) throws Exception {
        Field field = manager.getClass().getDeclaredField(mapField);
        field.setAccessible(true);
        List<T> tracked = ((Map<UltiToolsPlugin, List<T>>) field.get(manager)).get(module);
        return tracked == null ? new ArrayList<>() : new ArrayList<>(tracked);
    }

    /** The modules {@code manager}'s private per-module map still has a key for. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // test seam: the per-module maps are private by design
    private static List<Object> modulesTrackedBy(Object manager, String mapField) throws Exception {
        Field field = manager.getClass().getDeclaredField(mapField);
        field.setAccessible(true);
        return new ArrayList<>(((Map<?, ?>) field.get(manager)).keySet());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /** Every listener object Bukkit still dispatches to, across every handler list. */
    private static List<Listener> listenersBukkitStillHolds() {
        List<Listener> held = new ArrayList<>();
        for (HandlerList handlerList : HandlerList.getHandlerLists()) {
            for (RegisteredListener registered : handlerList.getRegisteredListeners()) {
                held.add(registered.getListener());
            }
        }
        return held;
    }

    /** A loaded module with a real listener, command, completer, EventBus handler and panel responder. */
    private static final class LoadedModule {
        HookModule module;
        SimpleContainer context;
        HookModuleListener listener;
        AtomicInteger moduleEventCalls;
    }

    private LoadedModule loadModule(Throwable hookFailure) {
        LoadedModule loaded = new LoadedModule();
        HookModule module = mock(HookModule.class);
        when(module.getPluginName()).thenReturn(MODULE_NAME);
        when(module.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        SimpleContainer context = mock(SimpleContainer.class);
        when(module.getContext()).thenReturn(context);
        HookModuleListener listener = new HookModuleListener();
        when(context.getBean(HookModuleListener.class)).thenReturn(listener);
        when(context.getBean(HookModuleCommand.class)).thenReturn(new HookModuleCommand());
        doCallRealMethod().when(module).unregisterSelf();
        if (hookFailure != null) {
            doAnswer(invocation -> {
                sneakyThrow(hookFailure);
                return null;
            }).when(module).onUnregister();
        }

        listenerManager.register(module, HookModuleListener.class);
        registeredListeners.add(listener);
        commandManager.register(module, HookModuleCommand.class);
        TabCompletionManager completions = TabCompletionManager.getInstance();
        completions.beginRegistrationScope(MODULE_NAME, module);
        try {
            completions.register(COMPLETER_KEY, completionContext -> new ArrayList<>());
        } finally {
            completions.endRegistrationScope();
        }
        AtomicInteger moduleEventCalls = new AtomicInteger();
        eventBus.subscribe(ModuleProbeEvent.class, EventPriority.NORMAL, false, MODULE_NAME, module,
                (ModuleProbeEvent event) -> moduleEventCalls.incrementAndGet());
        responders.registerResponder(RESPONDER_TYPE,
                (JsonObject data) -> CompletableFuture.completedFuture(new JsonObject()), MODULE_NAME, module);
        PluginListSeeding.add(pluginManager, module);

        loaded.module = module;
        loaded.context = context;
        loaded.listener = listener;
        loaded.moduleEventCalls = moduleEventCalls;
        return loaded;
    }

    @Test
    @DisplayName("an undeclared checked unload-hook failure still releases the module's listener, command, "
            + "tasks, completers, EventBus handlers, panel responders and container, and reaches the caller unchanged")
    void undeclaredCheckedHookFailureStillReleasesListenerAndCommand() throws Exception {
        IOException hookFailure = new IOException("hook could not flush its state");
        LoadedModule loaded = loadModule(hookFailure);
        HookModule module = loaded.module;

        // The registrations are real and live before the unload.
        server.getPluginManager().callEvent(new ProbeEvent());
        assertThat(loaded.listener.calls.get()).as("the listener receives events while loaded").isEqualTo(1);
        assertThat(realCommandMap.getCommand(COMMAND_LABEL)).as("the command is dispatchable while loaded").isNotNull();
        List<Listener> trackedListeners = trackedFor(listenerManager, "listenerListMap", module);
        List<Command> trackedCommands = trackedFor(commandManager, "commandListMap", module);
        assertThat(trackedListeners).as("ListenerManager tracks the module's listener").containsExactly(loaded.listener);
        assertThat(trackedCommands).as("CommandManager tracks the module's command").hasSize(1);

        Throwable thrown = catchThrowable(() -> pluginManager.unregister(module));

        assertThat(thrown).as("the caller receives the very exception the hook threw").isSameAs(hookFailure);

        // Bukkit side: the listener no longer receives events and the command is gone.
        server.getPluginManager().callEvent(new ProbeEvent());
        assertThat(loaded.listener.calls.get())
                .as("an unloaded module's listener receives no further events").isEqualTo(1);
        assertThat(listenersBukkitStillHolds())
                .as("no handler list still holds an instance of the module's listener class")
                .noneMatch(HookModuleListener.class::isInstance);
        assertThat(realCommandMap.getCommand(COMMAND_LABEL))
                .as("the module's command is no longer in the command map").isNull();
        assertThat(realCommandMap.getCommand(FALLBACK_PREFIX + ":" + COMMAND_LABEL))
                .as("nor under its namespaced key").isNull();

        // Framework side: everything the two managers tracked for the module is released from Bukkit.
        List<Listener> stillHeld = listenersBukkitStillHolds();
        for (Listener tracked : trackedListeners) {
            assertThat(stillHeld).as("tracked listener %s is released from Bukkit", tracked).doesNotContain(tracked);
        }
        for (Command tracked : trackedCommands) {
            assertThat(tracked.isRegistered()).as("tracked command %s reports itself unregistered", tracked.getName())
                    .isFalse();
            assertThat(realCommandMap.getKnownCommands().values())
                    .as("tracked command %s is under no known-commands key", tracked.getName())
                    .doesNotContain(tracked);
        }
        verify(taskManager, times(1)).cancelAll(module);
        assertThat(TabCompletionManager.getInstance().getCompleter(COMPLETER_KEY))
                .as("the module's completer is released").isNull();
        eventBus.publish(new ModuleProbeEvent());
        assertThat(loaded.moduleEventCalls.get()).as("the module's EventBus handler is released").isZero();
        assertThat(responders.hasResponder(RESPONDER_TYPE)).as("the module's panel responder is released").isFalse();
        verify(loaded.context).close();
        assertThat(pluginManager.getPluginList()).as("the module is delisted").doesNotContain(module);
    }

    @Test
    @DisplayName("an unload drops the module from both managers' per-module lists, whether or not its hook threw")
    void unloadReleasesTheManagersPerModuleEntries() throws Exception {
        LoadedModule failing = loadModule(new IOException("hook could not flush its state"));
        assertThat(modulesTrackedBy(listenerManager, "listenerListMap")).contains(failing.module);
        assertThat(modulesTrackedBy(commandManager, "commandListMap")).contains(failing.module);

        assertThat(catchThrowable(() -> pluginManager.unregister(failing.module))).isInstanceOf(IOException.class);

        assertThat(modulesTrackedBy(listenerManager, "listenerListMap"))
                .as("ListenerManager no longer references a module whose unload hook threw").doesNotContain(failing.module);
        assertThat(modulesTrackedBy(commandManager, "commandListMap"))
                .as("CommandManager no longer references a module whose unload hook threw").doesNotContain(failing.module);

        LoadedModule normal = loadModule(null);
        assertThat(modulesTrackedBy(listenerManager, "listenerListMap")).contains(normal.module);
        assertThat(modulesTrackedBy(commandManager, "commandListMap")).contains(normal.module);

        pluginManager.unregister(normal.module);

        assertThat(modulesTrackedBy(listenerManager, "listenerListMap"))
                .as("ListenerManager no longer references a normally unloaded module").doesNotContain(normal.module);
        assertThat(modulesTrackedBy(commandManager, "commandListMap"))
                .as("CommandManager no longer references a normally unloaded module").doesNotContain(normal.module);
        assertThat(realCommandMap.getCommand(COMMAND_LABEL)).as("and its command is gone too").isNull();
    }
}
