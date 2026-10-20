package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import com.ultikits.testfixtures.economyattribution.external.ExternalEconomyPlugin;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * #462: the economy honest-reporting warning names an External Plugin API consumer. A plain Bukkit
 * plugin connected through {@code UltiToolsAPI.connect} is not a module, so attribution used to
 * find it nowhere and report "an unknown caller"; connected plugins now take part in attribution
 * through their scan package, and stop taking part once disconnected.
 */
@DisplayName("#462: an External Plugin API consumer is named in the economy warning")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EconomyUtilsExternalAttributionTest {

    private Logger logger;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        logger = mock(Logger.class);
        pluginManager = new PluginManager();
        SimpleContainer parentContext = new SimpleContainer();
        parentContext.refresh();
        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        when(dependenceManagers.getContext()).thenReturn(parentContext);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(logger);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
            when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
            when(ultiTools.getCommandManager()).thenReturn(mock(CommandManager.class));
            when(ultiTools.getListenerManager()).thenReturn(new ListenerManager());
            when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
            PluginDescriptionFile description = mock(PluginDescriptionFile.class);
            when(description.getName()).thenReturn("UltiTools");
            when(ultiTools.getDescription()).thenReturn(description);
        });
        EconomyUtils.reset();
    }

    @AfterEach
    void tearDown() {
        EconomyUtils.reset();
        MockBukkitHelper.safeUnmock();
    }

    private String onlyWarning() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(logger, times(1)).warning(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a connected external plugin's economy request is attributed to that plugin's name")
    void connectedExternalPluginIsNamed() {
        ExternalEconomyPlugin plugin = MockBukkit.loadSimple(ExternalEconomyPlugin.class);
        pluginManager.registerExternal(new ExternalPluginAdapter(plugin));

        ExternalEconomyPlugin.requestEconomy();

        assertThat(onlyWarning()).contains("Module '" + plugin.getName() + "'")
                .doesNotContain(EconomyUtils.UNKNOWN_MODULE);
    }

    @Test
    @DisplayName("after the plugin disconnects, the same request is no longer attributed to it")
    void disconnectedExternalPluginIsNoLongerNamed() {
        ExternalEconomyPlugin plugin = MockBukkit.loadSimple(ExternalEconomyPlugin.class);
        ExternalPluginAdapter adapter = new ExternalPluginAdapter(plugin);
        pluginManager.registerExternal(adapter);
        pluginManager.unregisterExternal(adapter);

        ExternalEconomyPlugin.requestEconomy();

        assertThat(onlyWarning()).contains(EconomyUtils.UNKNOWN_MODULE);
    }
}
