package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
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

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;

/**
 * Integration tests for the External Plugin API.
 * Tests the full connect/disconnect lifecycle and manager interactions.
 */
@DisplayName("External Plugin Integration Tests")
public class ExternalPluginIntegrationTest {

    private JavaPlugin mockExternalPlugin;
    private UltiTools mockUltiTools;
    private CommandManager commandManager;
    private ListenerManager listenerManager;
    private EventBus eventBus;

    @BeforeEach
    void setUp() throws Exception {
        UltiToolsAPI.reset();

        mockExternalPlugin = createMockPlugin("TestExternalPlugin", "com.example.test.TestPlugin");

        // Set up mock Bukkit server
        Server mockServer = mock(Server.class);
        when(mockServer.getLogger()).thenReturn(Logger.getLogger("MockServer"));
        setStaticField(Bukkit.class, "server", mockServer);

        // Set up mock UltiTools with real managers
        mockUltiTools = mock(UltiTools.class);
        commandManager = new CommandManager();
        listenerManager = new ListenerManager();
        eventBus = new EventBus();

        when(mockUltiTools.getCommandManager()).thenReturn(commandManager);
        when(mockUltiTools.getListenerManager()).thenReturn(listenerManager);
        when(mockUltiTools.getEventBus()).thenReturn(eventBus);
        when(mockUltiTools.getPluginManager()).thenReturn(new PluginManager());
        // wireAop (02-01) resolves a DataSource through UltiTools.getInstance().getDataStore() for
        // the @Transactional advisor. CALLS_REAL_METHODS lets the interface's own default
        // getDataSource(DataScope) run and throw UnsupportedOperationException - wireAop's existing
        // graceful "declare unavailable" fallback - instead of a bare mock's null surfacing as an NPE.
        when(mockUltiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));

        setStaticField(UltiTools.class, "ultiTools", mockUltiTools);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiToolsAPI.reset();
        setStaticField(UltiTools.class, "ultiTools", null);
        setStaticField(Bukkit.class, "server", null);
    }

    private static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD
        field.set(null, value);
    }

    @Nested
    @DisplayName("UltiToolsAPI Connection Lifecycle")
    class ConnectionLifecycle {

        @Test
        void isConnected_returnsFalseBeforeConnect() {
            assertThat(UltiToolsAPI.isConnected(mockExternalPlugin)).isFalse();
        }

        @Test
        void connect_withNullUltiTools_throwsIllegalState() throws Exception {
            setStaticField(UltiTools.class, "ultiTools", null);
            assertThatThrownBy(() -> UltiToolsAPI.connect(mockExternalPlugin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UltiTools is not loaded");
        }

        @Test
        void disconnect_nonConnectedPlugin_doesNotThrow() {
            assertThatCode(() -> UltiToolsAPI.disconnect(mockExternalPlugin))
                    .doesNotThrowAnyException();
        }

        @Test
        void onPluginDisable_ignoresNonConnectedPlugin() {
            assertThatCode(() -> UltiToolsAPI.onPluginDisable(mockExternalPlugin))
                    .doesNotThrowAnyException();
        }

        @Test
        void disconnectAll_withNoPlugins_doesNotThrow() {
            assertThatCode(UltiToolsAPI::disconnectAll).doesNotThrowAnyException();
        }

        @Test
        void onPluginDisable_disconnectsRegisteredPlugin() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            adapter.setConnected(true);
            UltiToolsAPI.registerAdapter(mockExternalPlugin, adapter);

            UltiToolsAPI.onPluginDisable(mockExternalPlugin);

            assertThat(UltiToolsAPI.isConnected(mockExternalPlugin)).isFalse();
            assertThat(adapter.isConnected()).isFalse();
        }

        @Test
        void disconnectAll_disconnectsMultiplePlugins() {
            JavaPlugin plugin1 = createMockPlugin("Plugin1", "com.test.one.Main");
            JavaPlugin plugin2 = createMockPlugin("Plugin2", "com.test.two.Main");

            ExternalPluginAdapter a1 = new ExternalPluginAdapter(plugin1);
            ExternalPluginAdapter a2 = new ExternalPluginAdapter(plugin2);
            a1.setConnected(true);
            a2.setConnected(true);
            UltiToolsAPI.registerAdapter(plugin1, a1);
            UltiToolsAPI.registerAdapter(plugin2, a2);

            UltiToolsAPI.disconnectAll();

            assertThat(UltiToolsAPI.isConnected(plugin1)).isFalse();
            assertThat(UltiToolsAPI.isConnected(plugin2)).isFalse();
        }
    }

    @Nested
    @DisplayName("Adapter Map Operations")
    class AdapterMapOperations {

        @Test
        void registerAdapter_makesPluginConnected() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            UltiToolsAPI.registerAdapter(mockExternalPlugin, adapter);

            assertThat(UltiToolsAPI.isConnected(mockExternalPlugin)).isTrue();
            assertThat(UltiToolsAPI.getAdapter(mockExternalPlugin)).isSameAs(adapter);
        }

        @Test
        void removeAdapter_disconnectsPlugin() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            UltiToolsAPI.registerAdapter(mockExternalPlugin, adapter);
            UltiToolsAPI.removeAdapter(mockExternalPlugin);

            assertThat(UltiToolsAPI.isConnected(mockExternalPlugin)).isFalse();
        }

        @Test
        void getAdapter_returnsNull_whenNotRegistered() {
            assertThat(UltiToolsAPI.getAdapter(mockExternalPlugin)).isNull();
        }

        @Test
        void reset_clearsAllAdapters() {
            UltiToolsAPI.registerAdapter(mockExternalPlugin, new ExternalPluginAdapter(mockExternalPlugin));
            UltiToolsAPI.reset();

            assertThat(UltiToolsAPI.isConnected(mockExternalPlugin)).isFalse();
        }
    }

    @Nested
    @DisplayName("Manager External Methods")
    class ManagerExternalMethods {

        @Test
        void commandManager_unregisterAllExternal_withNoCommands_doesNotThrow() {
            assertThatCode(() -> commandManager.unregisterAllExternal("NonExistent"))
                    .doesNotThrowAnyException();
        }

        @Test
        void listenerManager_unregisterAllExternal_withNoListeners_doesNotThrow() {
            assertThatCode(() -> listenerManager.unregisterAllExternal("NonExistent"))
                    .doesNotThrowAnyException();
        }

        @Test
        void commandManager_registerAllExternal_withNullContext_doesNotThrow() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            adapter.setContext(null);

            assertThatCode(() -> commandManager.registerAllExternal(adapter))
                    .doesNotThrowAnyException();
        }

        @Test
        void listenerManager_registerAllExternal_withNullContext_doesNotThrow() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            adapter.setContext(null);

            assertThatCode(() -> listenerManager.registerAllExternal(adapter))
                    .doesNotThrowAnyException();
        }

        @Test
        void commandManager_registerAllExternal_withEmptyContainer_registersNothing() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            SimpleContainer emptyContext = new SimpleContainer();
            emptyContext.refresh();
            adapter.setContext(emptyContext);

            commandManager.registerAllExternal(adapter);
            commandManager.unregisterAllExternal(adapter.getPluginName());
        }

        @Test
        void listenerManager_registerAllExternal_withEmptyContainer_registersNothing() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            SimpleContainer emptyContext = new SimpleContainer();
            emptyContext.refresh();
            adapter.setContext(emptyContext);

            listenerManager.registerAllExternal(adapter);
            listenerManager.unregisterAllExternal(adapter.getPluginName());
        }
    }

    @Nested
    @DisplayName("PlayerCacheManager External Tests")
    class PlayerCacheTests {

        @Test
        void registerBean_withNoAnnotatedFields_doesNotTrack() {
            PlayerCacheManager pcm = new PlayerCacheManager();
            pcm.registerBean(new Object());
            assertThat(pcm.getTrackedBeanCount()).isZero();
        }

        @Test
        void unregisterBean_withNonTrackedBean_doesNotThrow() {
            PlayerCacheManager pcm = new PlayerCacheManager();
            assertThatCode(() -> pcm.unregisterBean(new Object())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("EventBus External Tests")
    class EventBusTests {

        @Test
        void unregisterAll_withNonExistentModule_doesNotThrow() {
            assertThatCode(() -> eventBus.unregisterAll("NonExistentPlugin"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("PluginManager registerExternal/unregisterExternal")
    class PluginManagerExternalTests {

        @Test
        void registerExternal_withEmptyPackage_createsContext() {
            // Adapter with no package to scan (top-level class)
            JavaPlugin topLevelPlugin = createMockPlugin("TopLevel", "TopLevelPlugin");
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(topLevelPlugin);
            assertThat(adapter.getScanPackage()).isEmpty();

            DependenceManagers mockDepManagers = mock(DependenceManagers.class);
            SimpleContainer parentContext = new SimpleContainer();
            parentContext.refresh();
            when(mockDepManagers.getContext()).thenReturn(parentContext);
            when(mockUltiTools.getDependenceManagers()).thenReturn(mockDepManagers);

            PluginManager pm = new PluginManager();
            when(mockUltiTools.getPluginManager()).thenReturn(pm);

            pm.registerExternal(adapter);

            assertThat(adapter.getContext()).isNotNull();
            // Pins call site 3 (registerExternal): if PluginManager.wireAop(context) is ever
            // removed from that path, external plugins silently lose @ExceptionCatch/@Transactional
            // again while modules keep it, breaking the parity this task establishes. See issue #190.
            assertThat(adapter.getContext().getAopProxyResolver()).isNotNull();
        }

        @Test
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void registerExternal_setsContainerDisplayName_forModuleScanDiagnostics()
                throws Exception {
            JavaPlugin plugin = createMockPlugin("DiagName", "com.ext.diag.Main");
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(plugin);

            DependenceManagers mockDepManagers = mock(DependenceManagers.class);
            SimpleContainer parentContext = new SimpleContainer();
            parentContext.refresh();
            when(mockDepManagers.getContext()).thenReturn(parentContext);
            when(mockUltiTools.getDependenceManagers()).thenReturn(mockDepManagers);

            PluginManager pm = new PluginManager();
            when(mockUltiTools.getPluginManager()).thenReturn(pm);

            pm.registerExternal(adapter);

            // Pins call site 3 (registerExternal) for the 07-21 D-19 diagnostic identifier,
            // exactly as the wireAop assertion above pins call site 3 for AOP parity.
            // assemblePluginContainer sets it for both UltiToolsPlugin load paths;
            // registerExternal does not go through that method, so it must set it itself.
            // With displayName null, ModuleScanDiagnostics' isBlank guard drops every
            // recordSkippedClass call AND suppresses emitSummary, so a bean-creation-time
            // linkage failure on this path would emit no SEVERE module summary at all --
            // the operator-matchable signature compatibility/records/6.3.0.md documents.
            // displayName has no getter, so the field is read directly.
            Field displayName = SimpleContainer.class.getDeclaredField("displayName");
            displayName.setAccessible(true);
            assertThat(displayName.get(adapter.getContext())).isEqualTo("DiagName");
        }

        @Test
        void unregisterExternal_closesContext() {
            JavaPlugin plugin = createMockPlugin("ExtTest", "com.ext.test.Main");
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(plugin);

            DependenceManagers mockDepManagers = mock(DependenceManagers.class);
            SimpleContainer parentContext = new SimpleContainer();
            parentContext.refresh();
            when(mockDepManagers.getContext()).thenReturn(parentContext);
            when(mockUltiTools.getDependenceManagers()).thenReturn(mockDepManagers);

            PluginManager pm = new PluginManager();
            when(mockUltiTools.getPluginManager()).thenReturn(pm);

            pm.registerExternal(adapter);
            assertThat(adapter.getContext()).isNotNull();

            pm.unregisterExternal(adapter);
            assertThat(adapter.getContext()).isNull();
        }

        @Test
        void unregisterExternal_withNullContext_doesNotThrow() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            adapter.setContext(null);

            DependenceManagers mockDepManagers = mock(DependenceManagers.class);
            when(mockUltiTools.getDependenceManagers()).thenReturn(mockDepManagers);

            PluginManager pm = new PluginManager();
            when(mockUltiTools.getPluginManager()).thenReturn(pm);

            assertThatCode(() -> pm.unregisterExternal(adapter)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ExternalPluginAdapter Detailed Tests")
    class AdapterDetailTests {

        @Test
        void adapter_extractsAllFieldsFromJavaPlugin() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);

            assertThat(adapter.getPluginName()).isEqualTo("TestExternalPlugin");
            assertThat(adapter.getVersion()).isEqualTo("1.0.0");
            assertThat(adapter.getAuthors()).containsExactly("Author");
            assertThat(adapter.getMainClass()).isEqualTo("com.example.test.TestPlugin");
            assertThat(adapter.getScanPackage()).isEqualTo("com.example.test");
            assertThat(adapter.getDataFolder()).isEqualTo(new File("/tmp/TestExternalPlugin"));
            assertThat(adapter.isConnected()).isFalse();
            assertThat(adapter.getContext()).isNull();
        }

        @Test
        void adapter_classLoaderDelegatesToPlugin() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            assertThat(adapter.getPluginClassLoader()).isSameAs(mockExternalPlugin.getClass().getClassLoader());
        }

        @Test
        void adapter_loggerDelegatesToPlugin() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            assertThat(adapter.getLogger()).isNotNull();
        }

        @Test
        void adapter_connectedStateIsSettable() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            assertThat(adapter.isConnected()).isFalse();
            adapter.setConnected(true);
            assertThat(adapter.isConnected()).isTrue();
        }

        @Test
        void adapter_contextIsSettable() {
            ExternalPluginAdapter adapter = new ExternalPluginAdapter(mockExternalPlugin);
            SimpleContainer ctx = new SimpleContainer();
            adapter.setContext(ctx);
            assertThat(adapter.getContext()).isSameAs(ctx);
        }
    }

    private JavaPlugin createMockPlugin(String name, String mainClass) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PluginDescriptionFile desc = mock(PluginDescriptionFile.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getDescription()).thenReturn(desc);
        when(desc.getVersion()).thenReturn("1.0.0");
        when(desc.getAuthors()).thenReturn(Arrays.asList("Author"));
        when(desc.getMain()).thenReturn(mainClass);
        when(plugin.getDataFolder()).thenReturn(new File("/tmp/" + name));
        when(plugin.getLogger()).thenReturn(Logger.getLogger(name));
        return plugin;
    }
}
