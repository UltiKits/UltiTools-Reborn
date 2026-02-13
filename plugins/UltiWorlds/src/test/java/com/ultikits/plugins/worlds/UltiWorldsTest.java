package com.ultikits.plugins.worlds;

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

    @Nested
    @DisplayName("Plugin Lifecycle")
    class PluginLifecycle {

        @Test
        @DisplayName("registerSelf should return true")
        void registerSelf() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenReturn("worlds_enabled");
            when(plugin.registerSelf()).thenCallRealMethod();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            verify(logger).info("worlds_enabled");
        }

        @Test
        @DisplayName("unregisterSelf should log message")
        void unregisterSelf() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenReturn("worlds_disabled");
            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(logger).info("worlds_disabled");
        }

        @Test
        @DisplayName("unregisterSelf should not fail when called multiple times")
        void unregisterSelfMultipleTimes() throws Exception {
            UltiWorlds plugin = mock(UltiWorlds.class);
            PluginLogger logger = mock(PluginLogger.class);

            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenReturn("worlds_disabled");
            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();
            plugin.unregisterSelf(); // Call twice

            verify(logger, times(2)).info("worlds_disabled");
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
    @DisplayName("Annotation Tests")
    class AnnotationTests {

        @Test
        @DisplayName("should have @UltiToolsModule annotation")
        void shouldHaveModuleAnnotation() {
            assertThat(UltiWorlds.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.UltiToolsModule.class
            )).isTrue();
        }

        @Test
        @DisplayName("should scan correct packages")
        void shouldScanCorrectPackages() {
            com.ultikits.ultitools.annotations.UltiToolsModule annotation =
                UltiWorlds.class.getAnnotation(
                    com.ultikits.ultitools.annotations.UltiToolsModule.class
                );

            assertThat(annotation.scanBasePackages()).contains("com.ultikits.plugins.worlds");
        }
    }
}
