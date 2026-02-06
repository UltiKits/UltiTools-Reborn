package com.ultikits.plugins.cleaner;

import com.ultikits.plugins.cleaner.service.ChunkUnloadService;
import com.ultikits.plugins.cleaner.service.CleanerService;
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
    @DisplayName("registerSelf should set instance and return true")
    void registerSelf() throws Exception {
        UltiCleaner plugin = mock(UltiCleaner.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer mockContext = mock(SimpleContainer.class);
        CleanerService mockCleanerService = mock(CleanerService.class);
        ChunkUnloadService mockChunkService = mock(ChunkUnloadService.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("cleaner_enabled");
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(CleanerService.class)).thenReturn(mockCleanerService);
        when(mockContext.getBean(ChunkUnloadService.class)).thenReturn(mockChunkService);
        when(plugin.registerSelf()).thenCallRealMethod();

        // Set instance field to null first
        UltiCleanerTestHelper.setStaticField(UltiCleaner.class, "instance", null);

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        assertThat(UltiCleaner.getInstance()).isSameAs(plugin);
        verify(mockCleanerService).init();
        verify(mockChunkService).init();
    }

    @Test
    @DisplayName("unregisterSelf should clear instance")
    void unregisterSelf() throws Exception {
        UltiCleaner plugin = mock(UltiCleaner.class);
        PluginLogger logger = mock(PluginLogger.class);
        SimpleContainer mockContext = mock(SimpleContainer.class);
        CleanerService mockCleanerService = mock(CleanerService.class);
        ChunkUnloadService mockChunkService = mock(ChunkUnloadService.class);

        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.i18n(anyString())).thenReturn("cleaner_disabled");
        when(plugin.getContext()).thenReturn(mockContext);
        when(mockContext.getBean(CleanerService.class)).thenReturn(mockCleanerService);
        when(mockContext.getBean(ChunkUnloadService.class)).thenReturn(mockChunkService);
        doCallRealMethod().when(plugin).unregisterSelf();

        // Set instance
        UltiCleanerTestHelper.setStaticField(UltiCleaner.class, "instance", plugin);

        plugin.unregisterSelf();

        verify(logger).info("cleaner_disabled");
        verify(mockCleanerService).shutdown();
        verify(mockChunkService).shutdown();
    }

    @Test
    @DisplayName("getInstance should return null when not registered")
    void getInstanceNull() throws Exception {
        UltiCleanerTestHelper.setStaticField(UltiCleaner.class, "instance", null);
        assertThat(UltiCleaner.getInstance()).isNull();
    }
}
