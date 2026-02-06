package com.ultikits.plugins.sidebar;

import com.ultikits.plugins.sidebar.service.SideBarService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;

import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiSideBar Main Class Tests")
class UltiSideBarTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiSideBarTestHelper.tearDown();
    }

    @Test
    @DisplayName("registerSelf should set instance and return true")
    void registerSelf() throws Exception {
        UltiSideBar plugin = mock(UltiSideBar.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        SideBarService service = mock(SideBarService.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(SideBarService.class)).thenReturn(service);
        when(plugin.i18n("sidebar_enabled")).thenReturn("sidebar_enabled");
        when(plugin.registerSelf()).thenCallRealMethod();

        // Set instance field to null first
        UltiSideBarTestHelper.setStaticField(UltiSideBar.class, "instance", null);

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        assertThat(UltiSideBar.getInstance()).isSameAs(plugin);
        verify(service).init();
        verify(logger).info("sidebar_enabled");
    }

    @Test
    @DisplayName("unregisterSelf should call service shutdown")
    void unregisterSelf() throws Exception {
        UltiSideBar plugin = mock(UltiSideBar.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        SideBarService service = mock(SideBarService.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(SideBarService.class)).thenReturn(service);
        when(plugin.i18n("sidebar_disabled")).thenReturn("sidebar_disabled");
        doCallRealMethod().when(plugin).unregisterSelf();

        // Set instance before unregister
        UltiSideBarTestHelper.setStaticField(UltiSideBar.class, "instance", plugin);

        plugin.unregisterSelf();

        verify(service).shutdown();
        verify(logger).info("sidebar_disabled");
        assertThat(UltiSideBar.getInstance()).isNull();
    }

    @Test
    @DisplayName("reloadSelf should reload config and service")
    void reloadSelf() throws Exception {
        UltiSideBar plugin = mock(UltiSideBar.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        SideBarService service = mock(SideBarService.class);
        ConfigManager configManager = mock(ConfigManager.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(SideBarService.class)).thenReturn(service);
        when(plugin.i18n("sidebar_reloaded")).thenReturn("sidebar_reloaded");
        doCallRealMethod().when(plugin).reloadSelf();

        try (MockedStatic<UltiToolsPlugin> pluginStatic = mockStatic(UltiToolsPlugin.class)) {
            pluginStatic.when(UltiToolsPlugin::getConfigManager).thenReturn(configManager);

            plugin.reloadSelf();

            pluginStatic.verify(UltiToolsPlugin::getConfigManager);
            verify(configManager).reloadConfigs(plugin);
            verify(service).reload();
            verify(logger).info("sidebar_reloaded");
        }
    }

    @Test
    @DisplayName("getInstance should return null when not registered")
    void getInstanceNull() throws Exception {
        UltiSideBarTestHelper.setStaticField(UltiSideBar.class, "instance", null);
        assertThat(UltiSideBar.getInstance()).isNull();
    }

    @Test
    @DisplayName("registerSelf should handle null service gracefully")
    void registerSelfNullService() throws Exception {
        UltiSideBar plugin = mock(UltiSideBar.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(SideBarService.class)).thenReturn(null);
        when(plugin.i18n("sidebar_enabled")).thenReturn("sidebar_enabled");
        when(plugin.registerSelf()).thenCallRealMethod();

        UltiSideBarTestHelper.setStaticField(UltiSideBar.class, "instance", null);

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        assertThat(UltiSideBar.getInstance()).isSameAs(plugin);
        verify(logger).info("sidebar_enabled");
    }

    @Test
    @DisplayName("unregisterSelf should handle null service gracefully")
    void unregisterSelfNullService() throws Exception {
        UltiSideBar plugin = mock(UltiSideBar.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(SideBarService.class)).thenReturn(null);
        when(plugin.i18n("sidebar_disabled")).thenReturn("sidebar_disabled");
        doCallRealMethod().when(plugin).unregisterSelf();

        UltiSideBarTestHelper.setStaticField(UltiSideBar.class, "instance", plugin);

        plugin.unregisterSelf();

        verify(logger).info("sidebar_disabled");
        assertThat(UltiSideBar.getInstance()).isNull();
    }

    @Test
    @DisplayName("reloadSelf should handle null service gracefully")
    void reloadSelfNullService() throws Exception {
        UltiSideBar plugin = mock(UltiSideBar.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        ConfigManager configManager = mock(ConfigManager.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getBean(SideBarService.class)).thenReturn(null);
        when(plugin.i18n("sidebar_reloaded")).thenReturn("sidebar_reloaded");
        doCallRealMethod().when(plugin).reloadSelf();

        try (MockedStatic<UltiToolsPlugin> pluginStatic = mockStatic(UltiToolsPlugin.class)) {
            pluginStatic.when(UltiToolsPlugin::getConfigManager).thenReturn(configManager);

            plugin.reloadSelf();

            pluginStatic.verify(UltiToolsPlugin::getConfigManager);
            verify(configManager).reloadConfigs(plugin);
            verify(logger).info("sidebar_reloaded");
        }
    }
}
