package com.ultikits.plugins.remotebag;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UltiRemoteBag Main Class Tests")
class UltiRemoteBagTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    @Test
    @DisplayName("registerSelf should set instance and return true")
    void registerSelf() throws Exception {
        UltiRemoteBag plugin = mock(UltiRemoteBag.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);

        // Mock getContext() to avoid NPE when registerSelf calls getContext().getBean()
        SimpleContainer mockContext = mock(SimpleContainer.class);
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(any(Class.class))).thenReturn(null);

        when(plugin.registerSelf()).thenCallRealMethod();

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("unregisterSelf should log message")
    void unregisterSelf() throws Exception {
        UltiRemoteBag plugin = mock(UltiRemoteBag.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);

        // Mock getContext() to avoid NPE
        SimpleContainer mockContext = mock(SimpleContainer.class);
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(any(Class.class))).thenReturn(null);

        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        verify(logger).info("UltiRemoteBag has been disabled!");
    }

    @Test
    @DisplayName("reloadSelf should log message")
    void reloadSelf() throws Exception {
        UltiRemoteBag plugin = mock(UltiRemoteBag.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        verify(logger).info("UltiRemoteBag configuration reloaded!");
    }

    @Test
    @DisplayName("supported should return zh and en")
    void supported() throws Exception {
        UltiRemoteBag plugin = mock(UltiRemoteBag.class);
        when(plugin.supported()).thenCallRealMethod();

        List<String> langs = plugin.supported();

        assertThat(langs).containsExactly("zh", "en");
    }

}
