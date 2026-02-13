package com.ultikits.plugins.backup;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiBackup Main Class Tests")
class UltiBackupTest {

    @Test
    @DisplayName("registerSelf should return true")
    void registerSelf() throws Exception {
        UltiBackup plugin = mock(UltiBackup.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.registerSelf()).thenCallRealMethod();

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        verify(logger).info("UltiBackup has been enabled!");
    }

    @Test
    @DisplayName("unregisterSelf should log message")
    void unregisterSelf() throws Exception {
        UltiBackup plugin = mock(UltiBackup.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        verify(logger).info("UltiBackup has been disabled!");
    }

    @Test
    @DisplayName("reloadSelf should log message")
    void reloadSelf() throws Exception {
        UltiBackup plugin = mock(UltiBackup.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        verify(logger).info("UltiBackup configuration reloaded!");
    }

    @Test
    @DisplayName("supported should return zh and en")
    void supported() throws Exception {
        UltiBackup plugin = mock(UltiBackup.class);
        when(plugin.supported()).thenCallRealMethod();

        List<String> langs = plugin.supported();

        assertThat(langs).containsExactly("zh", "en");
    }
}
