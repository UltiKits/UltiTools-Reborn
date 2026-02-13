package com.ultikits.plugins.cleaner;

import com.ultikits.plugins.cleaner.service.ChunkUnloadService;
import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.plugins.cleaner.service.TpsAwareScheduler;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiCleaner Main Class Tests")
class UltiCleanerTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiCleanerTestHelper.tearDown();
    }

    @Test
    @DisplayName("registerSelf should return true and init services")
    void registerSelf() throws Exception {
        UltiCleaner plugin = mock(UltiCleaner.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer mockContext = mock(SimpleContainer.class);
        CleanerService mockCleanerService = mock(CleanerService.class);
        ChunkUnloadService mockChunkService = mock(ChunkUnloadService.class);
        TpsAwareScheduler mockTpsScheduler = mock(TpsAwareScheduler.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("cleaner_enabled");
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(CleanerService.class)).thenReturn(mockCleanerService);
        when(mockContext.getBean(ChunkUnloadService.class)).thenReturn(mockChunkService);
        when(mockContext.getBean(TpsAwareScheduler.class)).thenReturn(mockTpsScheduler);
        when(plugin.registerSelf()).thenCallRealMethod();

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        verify(mockCleanerService).init();
        verify(mockTpsScheduler).init();
        verify(mockChunkService).init();
    }

    @Test
    @DisplayName("unregisterSelf should log disabled message")
    void unregisterSelf() throws Exception {
        UltiCleaner plugin = mock(UltiCleaner.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer mockContext = mock(SimpleContainer.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("cleaner_disabled");
        when(plugin.getContext()).thenReturn(mockContext);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        verify(logger).info("cleaner_disabled");
    }
}
