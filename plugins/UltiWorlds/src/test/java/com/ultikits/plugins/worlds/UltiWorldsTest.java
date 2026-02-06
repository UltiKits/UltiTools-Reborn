package com.ultikits.plugins.worlds;

import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for UltiWorlds main plugin class.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("UltiWorlds Main Class Tests")
class UltiWorldsTest {

    @AfterEach
    void tearDown() throws Exception {
        UltiWorldsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Plugin Lifecycle")
    class PluginLifecycle {

        @Test
        @DisplayName("registerSelf should set instance and return true")
        void registerSelf() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenReturn("worlds_enabled");
            when(plugin.registerSelf()).thenCallRealMethod();

            // Set instance field to null first
            UltiWorldsTestHelper.setStaticField(UltiWorlds.class, "instance", null);

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            assertThat(UltiWorlds.getInstance()).isSameAs(plugin);
            verify(logger).info("worlds_enabled");
        }

        @Test
        @DisplayName("unregisterSelf should shutdown services and log message")
        void unregisterSelf() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);
            WorldService worldService = mock(WorldService.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(plugin.i18n(anyString())).thenReturn("worlds_disabled");
            when(context.getBean(WorldService.class)).thenReturn(worldService);
            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(worldService).shutdown();
            verify(logger).info("worlds_disabled");
        }

        @Test
        @DisplayName("unregisterSelf should handle null WorldService gracefully")
        void unregisterSelfNullService() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);
            SimpleContainer context = mock(SimpleContainer.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.getContext()).thenReturn(context);
            when(plugin.i18n(anyString())).thenReturn("worlds_disabled");
            when(context.getBean(WorldService.class)).thenReturn(null);
            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(logger).info("worlds_disabled");
        }

        @Test
        @DisplayName("reloadSelf should log message")
        void reloadSelf() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            doCallRealMethod().when(plugin).reloadSelf();

            plugin.reloadSelf();

            verify(logger).info("UltiWorlds configuration reloaded!");
        }
    }

    @Nested
    @DisplayName("Plugin Metadata")
    class PluginMetadata {

        @Test
        @DisplayName("supported should return zh and en")
        void supported() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            when(plugin.supported()).thenCallRealMethod();

            List<String> langs = plugin.supported();

            assertThat(langs).containsExactly("zh", "en");
        }
    }

    @Nested
    @DisplayName("Singleton Access")
    class SingletonAccess {

        @Test
        @DisplayName("getInstance should return null when not registered")
        void getInstanceNull() throws Exception {
            UltiWorldsTestHelper.setStaticField(UltiWorlds.class, "instance", null);
            assertThat(UltiWorlds.getInstance()).isNull();
        }

        @Test
        @DisplayName("getInstance should return instance after registration")
        void getInstanceAfterRegister() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            UltiWorldsTestHelper.setStaticField(UltiWorlds.class, "instance", plugin);

            assertThat(UltiWorlds.getInstance()).isSameAs(plugin);
        }
    }
}
