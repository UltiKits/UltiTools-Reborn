package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
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
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.testfixtures.configbinding531external.ConnectorPluginFixtureBoundCooldown;
import com.ultikits.testfixtures.wr01contractgap.broken.ConnectorPluginFixtureBroken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
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
}
