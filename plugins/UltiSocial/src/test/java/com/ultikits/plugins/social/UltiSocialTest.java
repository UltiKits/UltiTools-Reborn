package com.ultikits.plugins.social;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiSocial Main Class Tests")
class UltiSocialTest {

    @Test
    @DisplayName("registerSelf should return true")
    void registerSelf() throws Exception {
        UltiSocial plugin = mock(UltiSocial.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.registerSelf()).thenCallRealMethod();

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        verify(logger).info("UltiSocial v1.1.0 has been enabled!");
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

    @Nested
    @DisplayName("Annotation Tests")
    class AnnotationTests {

        @Test
        @DisplayName("should have @UltiToolsModule annotation")
        void shouldHaveModuleAnnotation() {
            assertThat(UltiSocial.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.UltiToolsModule.class
            )).isTrue();
        }

        @Test
        @DisplayName("should scan correct packages")
        void shouldScanCorrectPackages() {
            com.ultikits.ultitools.annotations.UltiToolsModule annotation =
                UltiSocial.class.getAnnotation(
                    com.ultikits.ultitools.annotations.UltiToolsModule.class
                );

            assertThat(annotation.scanBasePackages()).contains("com.ultikits.plugins.social");
        }
    }
}
