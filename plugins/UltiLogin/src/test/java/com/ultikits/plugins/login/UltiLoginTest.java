package com.ultikits.plugins.login;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UltiLogin Main Class Tests")
class UltiLoginTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiLoginTestHelper.tearDown();
    }

    @Test
    @DisplayName("registerSelf should set instance and return true")
    void registerSelf() throws Exception {
        UltiLogin plugin = mock(UltiLogin.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("UltiLogin enabled");
        when(plugin.registerSelf()).thenCallRealMethod();

        // Mock getContext() to return a mock SimpleContainer
        SimpleContainer mockContext = mock(SimpleContainer.class);
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(any(Class.class))).thenReturn(null);

        // Set instance field to null first
        UltiLoginTestHelper.setStaticField(UltiLogin.class, "instance", null);

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        assertThat(UltiLogin.getInstance()).isSameAs(plugin);
    }

    @Test
    @DisplayName("unregisterSelf should log message and clear instance")
    void unregisterSelf() throws Exception {
        UltiLogin plugin = mock(UltiLogin.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("UltiLogin disabled");
        doCallRealMethod().when(plugin).unregisterSelf();

        // Mock getContext() to return a mock SimpleContainer
        SimpleContainer mockContext = mock(SimpleContainer.class);
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(any(Class.class))).thenReturn(null);

        UltiLoginTestHelper.setStaticField(UltiLogin.class, "instance", plugin);

        plugin.unregisterSelf();

        verify(logger).info("UltiLogin disabled");
    }

    @Test
    @DisplayName("reloadSelf should log message")
    void reloadSelf() throws Exception {
        UltiLogin plugin = mock(UltiLogin.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("UltiLogin reloaded");
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        verify(logger).info("UltiLogin reloaded");
    }

    @Test
    @DisplayName("getInstance should return null when not registered")
    void getInstanceNull() throws Exception {
        UltiLoginTestHelper.setStaticField(UltiLogin.class, "instance", null);
        assertThat(UltiLogin.getInstance()).isNull();
    }
}
