package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.testfixtures.configbinding531external.ConnectorPluginFixtureBoundCooldown;
import com.ultikits.testfixtures.externalunwind.UnwindOwnedEntity;
import com.ultikits.testfixtures.wr01contractgap.broken.ConnectorPluginFixtureBroken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ModuleEventHandler;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.TaskManager;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #537: an external plugin refused after its container was refreshed -- by the command-executor
 * contract check or by the config-binding refusal (#531) -- leaves no folder scope, entity
 * ownership, data scope or context behind, so a corrected connection of the same plugin in the
 * same process succeeds with exactly one scope: its own.
 * <p>
 * The corrected connection is the same {@link JavaPlugin} (same name, same data folder) whose
 * adapter scans the package of the passing fixture instead -- what a plugin author's fixed
 * build looks like to the framework.
 */
@DisplayName("#537: a refused external registration unwinds what it registered")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reads PluginManager's private registries; resets UltiTools.ultiTools
class ExternalPluginRefusalUnwindTest {

    private static final String PASSING_PACKAGE = "com.ultikits.testfixtures.wr01contractgap.ok";

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        MockBukkitHelper.safeUnmock();
    }

    private PluginManager newPluginManager() {
        SimpleContainer parentContext = new SimpleContainer();
        parentContext.refresh();
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        when(dependenceManagers.getContext()).thenReturn(parentContext);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
            // A mock: the corrected connection reaches Bukkit command registration, which needs a
            // real CommandMap MockBukkit does not expose to CommandManager (the pre-existing NPE
            // ExternalPluginAdapterTest documents). Every refusal happens before that step.
            when(ultiTools.getCommandManager()).thenReturn(mock(CommandManager.class));
            when(ultiTools.getListenerManager()).thenReturn(new ListenerManager());
            when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
            PluginDescriptionFile description = mock(PluginDescriptionFile.class);
            when(description.getName()).thenReturn("UltiTools");
            when(ultiTools.getDescription()).thenReturn(description);
        });
        return new PluginManager();
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<Object, V> registry(PluginManager pluginManager, String field) throws Exception {
        Field registry = PluginManager.class.getDeclaredField(field);
        registry.setAccessible(true);
        return (Map<Object, V>) registry.get(pluginManager);
    }

    private static long scopesHeldBy(PluginManager pluginManager, String pluginName) throws Exception {
        Map<Object, DataScope> scopes = registry(pluginManager, "externalScopesByFolder");
        return scopes.values().stream().filter(scope -> scope.getPluginName().equals(pluginName)).count();
    }

    private static long ownershipRecordsOf(PluginManager pluginManager, String pluginName) throws Exception {
        Map<Object, String> ownership = registry(pluginManager, "entityOwnership");
        return ownership.values().stream().filter(pluginName::equals).count();
    }

    private void assertRefusalLeftNothingAndCorrectedConnectionSucceeds(PluginManager pluginManager,
            JavaPlugin plugin, ExternalPluginAdapter refused) throws Exception {
        Throwable thrown = catchThrowable(() -> pluginManager.registerExternal(refused));
        assertThat(thrown).isInstanceOf(PluginModuleException.class);

        String name = plugin.getName();
        assertThat(pluginManager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
        assertThat(scopesHeldBy(pluginManager, name)).isZero();
        assertThat(ownershipRecordsOf(pluginManager, name)).isZero();
        assertThat(refused.getDataScope()).isNull();
        assertThat(refused.getContext()).isNull();

        ExternalPluginAdapter corrected = spy(new ExternalPluginAdapter(plugin));
        doReturn(PASSING_PACKAGE).when(corrected).getScanPackage();
        pluginManager.registerExternal(corrected);

        assertThat(scopesHeldBy(pluginManager, name)).isEqualTo(1);
        assertThat(pluginManager.findScopeForDataFolder(plugin.getDataFolder())).isSameAs(corrected.getDataScope());
        assertThat(ownershipRecordsOf(pluginManager, name))
                .isEqualTo(corrected.getDataScope().getOwnedEntities().size());
        assertThat(corrected.getContext()).isNotNull();
    }

    enum RegistrationStage {
        TASKS, CACHE, EVENTS, COMMANDS, LISTENERS
    }

    public static class EventBean {
        @ModuleEventHandler
        public void onEvent(ModuleEvent event) {
            // Registration alone is exercised; the test never dispatches an event.
        }
    }

    @ParameterizedTest
    @EnumSource(RegistrationStage.class)
    @DisplayName("#567: a failure in any Bukkit-facing stage unwinds attempted registrations and allows reconnect")
    void lateFailureUnwindsAndReconnects(RegistrationStage stage) throws Exception {
        PluginManager pluginManager = newPluginManager();
        TaskManager tasks = mock(TaskManager.class);
        PlayerCacheManager cache = mock(PlayerCacheManager.class);
        EventBus events = mock(EventBus.class);
        CommandManager commands = UltiTools.getInstance().getCommandManager();
        ListenerManager listeners = mock(ListenerManager.class);
        when(UltiTools.getInstance().getEventBus()).thenReturn(events);
        when(UltiTools.getInstance().getListenerManager()).thenReturn(listeners);
        Field taskField = PluginManager.class.getDeclaredField("taskManager");
        taskField.setAccessible(true);
        taskField.set(pluginManager, tasks);
        Field cacheField = PluginManager.class.getDeclaredField("playerCacheManager");
        cacheField.setAccessible(true);
        cacheField.set(pluginManager, cache);
        ConnectorPluginFixtureBroken plugin = MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class);
        String name = plugin.getName();
        ExternalPluginAdapter adapter = spy(new ExternalPluginAdapter(plugin));
        doReturn(PASSING_PACKAGE).when(adapter).getScanPackage();
        EventBean eventBean = new EventBean();
        doAnswer(call -> {
            call.callRealMethod();
            SimpleContainer context = call.getArgument(0);
            if (context != null) {
                context.registerSingleton("failureInjectionEventBean", eventBean);
            }
            return null;
        }).when(adapter).setContext(any());
        IllegalStateException failure = new IllegalStateException("injected " + stage);
        switch (stage) {
            case TASKS:
                doThrow(failure).when(tasks).registerScheduledMethodsExternal(eq(name), any());
                break;
            case CACHE:
                doThrow(failure).when(cache).registerBean(any());
                break;
            case EVENTS:
                doThrow(failure).when(events).register(any(), any(), anyBoolean(), eq(name), any(), any());
                break;
            case COMMANDS:
                doThrow(failure).when(commands).registerAllExternal(adapter);
                break;
            case LISTENERS:
                doThrow(failure).when(listeners).registerAllExternal(adapter);
                break;
            default:
                throw new AssertionError(stage);
        }

        Throwable thrown = catchThrowable(() -> pluginManager.registerExternal(adapter,
                new Class<?>[]{UnwindOwnedEntity.class}));

        assertThat(thrown).isSameAs(failure);
        assertThat(pluginManager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
        assertThat(ownershipRecordsOf(pluginManager, name)).isZero();
        assertThat(adapter.getContext()).isNull();
        assertThat(adapter.getDataScope()).isNull();
        assertThat(pluginManager.getConnectedExternalScanPackages()).doesNotContainKey(name);
        verify(tasks).cancelAllExternal(name);
        if (stage.ordinal() >= RegistrationStage.CACHE.ordinal()) {
            verify(cache, org.mockito.Mockito.atLeastOnce()).unregisterBean(any());
        }
        if (stage.ordinal() >= RegistrationStage.EVENTS.ordinal()) {
            verify(events).unregisterAll(name);
        }
        if (stage.ordinal() >= RegistrationStage.COMMANDS.ordinal()) {
            verify(commands).unregisterAllExternal(name);
        }
        if (stage == RegistrationStage.LISTENERS) {
            verify(listeners).unregisterAllExternal(name);
        }
        reset(tasks, cache, events, commands, listeners);
        pluginManager.registerExternal(adapter, new Class<?>[]{UnwindOwnedEntity.class});
        assertThat(pluginManager.findScopeForDataFolder(plugin.getDataFolder())).isSameAs(adapter.getDataScope());
        assertThat(pluginManager.findOwningPlugin(UnwindOwnedEntity.class)).isEqualTo(name);
        assertThat(pluginManager.getConnectedExternalScanPackages()).containsKey(name);
        pluginManager.unregisterExternal(adapter);
    }

    @Test
    @DisplayName("#567: cleanup failure does not replace the registration failure or skip scope unwind")
    void cleanupFailureIsSuppressedAndScopeStillUnwinds() throws Exception {
        PluginManager pluginManager = newPluginManager();
        TaskManager tasks = mock(TaskManager.class);
        Field taskField = PluginManager.class.getDeclaredField("taskManager");
        taskField.setAccessible(true);
        taskField.set(pluginManager, tasks);
        ConnectorPluginFixtureBroken plugin = MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class);
        ExternalPluginAdapter adapter = spy(new ExternalPluginAdapter(plugin));
        doReturn(PASSING_PACKAGE).when(adapter).getScanPackage();
        IllegalStateException failure = new IllegalStateException("injected scheduling failure");
        IllegalStateException cleanupFailure = new IllegalStateException("injected cancellation failure");
        doThrow(failure).when(tasks).registerScheduledMethodsExternal(eq(plugin.getName()), any());
        doThrow(cleanupFailure).when(tasks).cancelAllExternal(plugin.getName());

        Throwable thrown = catchThrowable(() -> pluginManager.registerExternal(adapter,
                new Class<?>[]{UnwindOwnedEntity.class}));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(pluginManager.findScopeForDataFolder(plugin.getDataFolder())).isNull();
        assertThat(ownershipRecordsOf(pluginManager, plugin.getName())).isZero();
        assertThat(adapter.getContext()).isNull();
        assertThat(adapter.getDataScope()).isNull();
    }

    @Test
    @DisplayName("refused by the command-executor contract check: nothing left behind, the corrected connection succeeds")
    void contractRefusalUnwindsAndCorrectedConnectionSucceeds() throws Exception {
        PluginManager pluginManager = newPluginManager();
        ConnectorPluginFixtureBroken plugin = MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class);

        assertRefusalLeftNothingAndCorrectedConnectionSucceeds(pluginManager, plugin, new ExternalPluginAdapter(plugin));
    }

    @Test
    @DisplayName("refused by the config-binding check (#531): nothing left behind, the corrected connection succeeds")
    void configBindingRefusalUnwindsAndCorrectedConnectionSucceeds() throws Exception {
        PluginManager pluginManager = newPluginManager();
        ConnectorPluginFixtureBoundCooldown plugin = MockBukkit.loadSimple(ConnectorPluginFixtureBoundCooldown.class);

        assertRefusalLeftNothingAndCorrectedConnectionSucceeds(pluginManager, plugin, new ExternalPluginAdapter(plugin));
    }

    @Test
    @DisplayName("a refused UltiToolsAPI.connect leaves no scope and no connected adapter")
    void refusedConnectLeavesNoScope() throws Exception {
        PluginManager pluginManager = newPluginManager();
        when(UltiTools.getInstance().getPluginManager()).thenReturn(pluginManager);
        ConnectorPluginFixtureBroken plugin = MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class);

        Throwable thrown = catchThrowable(() -> UltiToolsAPI.connect(plugin));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(UltiToolsAPI.isConnected(plugin)).isFalse();
        assertThat(scopesHeldBy(pluginManager, plugin.getName())).isZero();
    }

    @Test
    @DisplayName("a refused second registration of a connected plugin leaves the live registration's ownership and scope")
    void refusedSecondRegistrationKeepsTheLiveRegistration() throws Exception {
        PluginManager pluginManager = newPluginManager();
        ConnectorPluginFixtureBroken plugin = MockBukkit.loadSimple(ConnectorPluginFixtureBroken.class);
        Class<?>[] owned = {UnwindOwnedEntity.class};
        ExternalPluginAdapter live = spy(new ExternalPluginAdapter(plugin));
        doReturn(PASSING_PACKAGE).when(live).getScanPackage();
        pluginManager.registerExternal(live, owned);
        DataScope liveScope = pluginManager.findScopeForDataFolder(plugin.getDataFolder());

        Throwable thrown = catchThrowable(() -> pluginManager.registerExternal(new ExternalPluginAdapter(plugin), owned));

        assertThat(thrown).isInstanceOf(PluginModuleException.class);
        assertThat(pluginManager.findOwningPlugin(UnwindOwnedEntity.class)).isEqualTo(plugin.getName());
        assertThat(pluginManager.findScopeForDataFolder(plugin.getDataFolder())).isSameAs(liveScope);
        assertThat(live.getContext()).isNotNull();
    }
}

