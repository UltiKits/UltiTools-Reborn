package com.ultikits.plugins.social;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiSocial Main Class Tests")
class UltiSocialTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiSocialTestHelper.tearDown();
    }

    @Test
    @DisplayName("registerSelf should set instance and return true")
    void registerSelf() throws Exception {
        UltiSocial plugin = mock(UltiSocial.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.registerSelf()).thenCallRealMethod();

        // Set instance field to null first
        UltiSocialTestHelper.setStaticField(UltiSocial.class, "instance", null);

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        assertThat(UltiSocial.getInstance()).isSameAs(plugin);
    }

    @Test
    @DisplayName("unregisterSelf should log message")
    void unregisterSelf() throws Exception {
        UltiSocial plugin = mock(UltiSocial.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        verify(logger).info("UltiSocial has been disabled!");
    }

    @Test
    @DisplayName("reloadSelf should log message")
    void reloadSelf() throws Exception {
        UltiSocial plugin = mock(UltiSocial.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        verify(logger).info("UltiSocial configuration reloaded!");
    }

    @Test
    @DisplayName("supported should return zh and en")
    void supported() throws Exception {
        UltiSocial plugin = mock(UltiSocial.class);
        when(plugin.supported()).thenCallRealMethod();

        List<String> langs = plugin.supported();

        assertThat(langs).containsExactly("zh", "en");
    }

    @Test
    @DisplayName("getInstance should return null when not registered")
    void getInstanceNull() throws Exception {
        UltiSocialTestHelper.setStaticField(UltiSocial.class, "instance", null);
        assertThat(UltiSocial.getInstance()).isNull();
    }
}
