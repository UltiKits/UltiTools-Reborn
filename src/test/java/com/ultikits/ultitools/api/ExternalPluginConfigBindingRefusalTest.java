package com.ultikits.ultitools.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.testfixtures.configbinding531external.ConnectorPluginFixtureBoundCooldown;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #531: {@code PluginManager.registerExternal(...)} refuses a config-bound {@code @CmdCD}
 * declared by an external plugin -- which has no module config registry to bind against --
 * before any side effect. Drives the genuine registration path with the same MockBukkit harness
 * as {@code ExternalPluginAdapterTest.Wr01ContractEnforcementTests}.
 */
@DisplayName("registerExternal refuses config bindings (#531)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective reset of UltiTools.ultiTools between tests
class ExternalPluginConfigBindingRefusalTest {

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
            when(ultiTools.getCommandManager()).thenReturn(new CommandManager());
            when(ultiTools.getListenerManager()).thenReturn(new ListenerManager());
            when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
            PluginDescriptionFile description = mock(PluginDescriptionFile.class);
            when(description.getName()).thenReturn("UltiTools");
            when(ultiTools.getDescription()).thenReturn(description);
        });
        return new PluginManager();
    }

    @Test
    @DisplayName("an external executor with a config-bound @CmdCD is refused, naming the executor")
    void anExternalBoundCooldownIsRefused() {
        PluginManager pluginManager = newPluginManager();
        ConnectorPluginFixtureBoundCooldown connector = MockBukkit.loadSimple(ConnectorPluginFixtureBoundCooldown.class);
        ExternalPluginAdapter adapter = new ExternalPluginAdapter(connector);
        assertThat(adapter.getScanPackage()).isEqualTo("com.ultikits.testfixtures.configbinding531external");

        Throwable thrown = catchThrowable(() -> pluginManager.registerExternal(adapter));

        assertThat(thrown).isInstanceOf(PluginModuleException.class)
                .hasMessageContaining("BoundCooldownExternalCommandExecutor")
                .hasMessageContaining("config-bound @CmdCD");
    }
}
