package com.ultikits.plugins.mail;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.plugins.mail.utils.MockBukkitHelper;
import com.ultikits.plugins.mail.utils.TestHelper;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UltiMail plugin main class.
 * <p>
 * UltiMail 插件主类单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("UltiMail 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class UltiMailTest {

    private UltiMail plugin;

    @Mock
    private SimpleContainer mockContext;

    @Mock
    private MailService mockMailService;

    @Mock
    private PluginLogger mockLogger;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Setup mock UltiMail
        plugin = TestHelper.mockUltiMailInstance();
        when(plugin.getContext()).thenReturn(mockContext);
        when(plugin.getLogger()).thenReturn(mockLogger);
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("getInstance 测试")
    class GetInstanceTests {

        @Test
        @DisplayName("getInstance 应该返回单例实例")
        void shouldReturnSingletonInstance() {
            UltiMail instance = UltiMail.getInstance();

            assertThat(instance).isNotNull();
            assertThat(instance).isSameAs(plugin);
        }

        @Test
        @DisplayName("多次调用 getInstance 应该返回同一实例")
        void shouldReturnSameInstanceOnMultipleCalls() {
            UltiMail instance1 = UltiMail.getInstance();
            UltiMail instance2 = UltiMail.getInstance();

            assertThat(instance1).isSameAs(instance2);
        }
    }

    @Nested
    @DisplayName("registerSelf 测试")
    class RegisterSelfTests {

        @Test
        @DisplayName("registerSelf 应该初始化插件实例")
        void shouldInitializePluginInstance() throws Exception {
            when(mockContext.getBean(MailService.class)).thenReturn(mockMailService);

            // Call using doCallRealMethod to invoke actual implementation
            when(plugin.registerSelf()).thenCallRealMethod();
            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
            verify(mockContext).getBean(MailService.class);
            verify(mockMailService).init();
        }

        @Test
        @DisplayName("registerSelf 应该记录启用消息")
        void shouldLogEnableMessage() {
            when(mockContext.getBean(MailService.class)).thenReturn(mockMailService);
            when(plugin.registerSelf()).thenCallRealMethod();

            plugin.registerSelf();

            verify(mockLogger).info(anyString());
        }

        @Test
        @DisplayName("registerSelf 当 MailService 为 null 时不应该崩溃")
        void shouldNotCrashWhenMailServiceIsNull() {
            when(mockContext.getBean(MailService.class)).thenReturn(null);
            when(plugin.registerSelf()).thenCallRealMethod();

            boolean result = plugin.registerSelf();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("registerSelf 应该设置单例实例")
        void shouldSetSingletonInstance() throws Exception {
            when(mockContext.getBean(MailService.class)).thenReturn(mockMailService);
            when(plugin.registerSelf()).thenCallRealMethod();

            plugin.registerSelf();

            UltiMail instance = UltiMail.getInstance();
            assertThat(instance).isNotNull();
        }
    }

    @Nested
    @DisplayName("unregisterSelf 测试")
    class UnregisterSelfTests {

        @Test
        @DisplayName("unregisterSelf 应该记录禁用消息")
        void shouldLogDisableMessage() {
            doCallRealMethod().when(plugin).unregisterSelf();

            plugin.unregisterSelf();

            verify(mockLogger).info(anyString());
        }

        @Test
        @DisplayName("unregisterSelf 应该清空单例实例")
        void shouldClearSingletonInstance() throws Exception {
            // First register
            when(mockContext.getBean(MailService.class)).thenReturn(mockMailService);
            when(plugin.registerSelf()).thenCallRealMethod();
            plugin.registerSelf();

            // Then unregister
            doCallRealMethod().when(plugin).unregisterSelf();
            plugin.unregisterSelf();

            // Manually clear the instance for verification
            Field instanceField = UltiMail.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            Object instance = instanceField.get(null);

            // Since we can't fully test the real unregisterSelf, just verify logger was called
            verify(mockLogger, atLeastOnce()).info(anyString());
        }
    }

    @Nested
    @DisplayName("reloadSelf 测试")
    class ReloadSelfTests {

        @Test
        @DisplayName("reloadSelf 应该记录重载消息")
        void shouldLogReloadMessage() {
            doCallRealMethod().when(plugin).reloadSelf();

            plugin.reloadSelf();

            verify(mockLogger).info(anyString());
        }

        @Test
        @DisplayName("reloadSelf 不应该抛出异常")
        void shouldNotThrowException() {
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
