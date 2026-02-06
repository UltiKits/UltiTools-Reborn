package com.ultikits.plugins.trade;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiTrade Main Class Tests")
class UltiTradeTest {

    @BeforeEach
    void setUp() throws Exception {
        UltiTradeTestHelper.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiTradeTestHelper.tearDown();
    }

    @Test
    @DisplayName("registerSelf should set instance and return true")
    void registerSelf() throws Exception {
        UltiTrade plugin = mock(UltiTrade.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(plugin.registerSelf()).thenCallRealMethod();

        // Set instance field to null first
        UltiTradeTestHelper.setStaticField(UltiTrade.class, "instance", null);

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        assertThat(UltiTrade.getInstance()).isSameAs(plugin);
    }

    @Test
    @DisplayName("unregisterSelf should clear instance")
    void unregisterSelf() throws Exception {
        UltiTrade plugin = mock(UltiTrade.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer context = mock(SimpleContainer.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        doCallRealMethod().when(plugin).unregisterSelf();

        // Set instance first
        UltiTradeTestHelper.setStaticField(UltiTrade.class, "instance", plugin);

        plugin.unregisterSelf();

        assertThat(UltiTrade.getInstance()).isNull();
    }

    @Test
    @DisplayName("reloadSelf should log message")
    void reloadSelf() throws Exception {
        UltiTrade plugin = mock(UltiTrade.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        doCallRealMethod().when(plugin).reloadSelf();

        plugin.reloadSelf();

        verify(logger).info("UltiTrade 配置已重载！");
    }

    @Test
    @DisplayName("getInstance should return null when not registered")
    void getInstanceNull() throws Exception {
        UltiTradeTestHelper.setStaticField(UltiTrade.class, "instance", null);
        assertThat(UltiTrade.getInstance()).isNull();
    }
}
