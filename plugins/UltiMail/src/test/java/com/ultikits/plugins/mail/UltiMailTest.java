package com.ultikits.plugins.mail;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UltiMail plugin main class.
 * <p>
 * Tests lifecycle methods and annotation configuration.
 */
@DisplayName("UltiMail 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class UltiMailTest {

    @Nested
    @DisplayName("registerSelf 测试")
    class RegisterSelfTests {

        @Test
        @DisplayName("registerSelf 应该返回 true")
        void shouldReturnTrue() {
            UltiMail plugin = mock(UltiMail.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(plugin.registerSelf()).thenCallRealMethod();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("registerSelf 应该记录启用消息")
        void shouldLogEnableMessage() {
            UltiMail plugin = mock(UltiMail.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            when(plugin.registerSelf()).thenCallRealMethod();

            plugin.registerSelf();

            verify(logger).info(anyString());
        }
    }

    @Nested
    @DisplayName("unregisterSelf 测试")
    class UnregisterSelfTests {

        @Test
        @DisplayName("unregisterSelf 应该记录禁用消息")
        void shouldLogDisableMessage() {
            UltiMail plugin = mock(UltiMail.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(logger).info(anyString());
        }
    }

    @Nested
    @DisplayName("reloadSelf 测试")
    class ReloadSelfTests {

        @Test
        @DisplayName("reloadSelf 应该记录重载消息")
        void shouldLogReloadMessage() {
            UltiMail plugin = mock(UltiMail.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).reloadSelf();

            plugin.reloadSelf();

            verify(logger).info(anyString());
        }

        @Test
        @DisplayName("reloadSelf 不应该抛出异常")
        void shouldNotThrowException() {
            UltiMail plugin = mock(UltiMail.class);
            PluginLogger logger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            doCallRealMethod().when(plugin).reloadSelf();

            // Should not throw
            plugin.reloadSelf();
        }
    }

    @Nested
    @DisplayName("注解配置测试")
    class AnnotationConfigTests {

        @Test
        @DisplayName("类应该有 @UltiToolsModule 注解")
        void shouldHaveUltiToolsModuleAnnotation() {
            boolean hasAnnotation = UltiMail.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.UltiToolsModule.class
            );

            assertThat(hasAnnotation).isTrue();
        }

        @Test
        @DisplayName("@UltiToolsModule 应该扫描正确的包")
        void shouldScanCorrectPackages() {
            com.ultikits.ultitools.annotations.UltiToolsModule annotation =
                UltiMail.class.getAnnotation(
                    com.ultikits.ultitools.annotations.UltiToolsModule.class
                );

            String[] packages = annotation.scanBasePackages();
            assertThat(packages).contains("com.ultikits.plugins.mail");
        }
    }

    @Nested
    @DisplayName("继承关系测试")
    class InheritanceTests {

        @Test
        @DisplayName("应该继承 UltiToolsPlugin")
        void shouldExtendUltiToolsPlugin() {
            assertThat(com.ultikits.ultitools.abstracts.UltiToolsPlugin.class)
                .isAssignableFrom(UltiMail.class);
        }
    }
}
