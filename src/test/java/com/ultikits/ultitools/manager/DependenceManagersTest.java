package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.services.EmailService;
import com.ultikits.ultitools.services.NotificationService;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import java.util.Comparator;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

/**
 * DependenceManagers 测试
 */
@DisplayName("DependenceManagers 测试")
class DependenceManagersTest {

    private AutoCloseable mocks;

    @Mock
    private JavaPlugin mockPlugin;

    @Mock
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockPlugin.getLogger()).thenReturn(mockLogger);
        when(mockPlugin.getName()).thenReturn("TestPlugin");
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Nested
    @DisplayName("VersionComparator 测试")
    class VersionComparatorTests {

        @Test
        @DisplayName("getVersionComparator 应该返回 VersionComparator")
        void shouldReturnVersionComparator() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();

            // Act
            Comparator<String> comparator = managers.getVersionComparator();

            // Assert
            assertThat(comparator).isNotNull();
            assertThat(comparator).isInstanceOf(Comparator.class);
        }

        @Test
        @DisplayName("多次调用应该返回相同实例")
        void shouldReturnSameInstance() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();

            // Act
            Comparator<String> comp1 = managers.getVersionComparator();
            Comparator<String> comp2 = managers.getVersionComparator();

            // Assert
            assertThat(comp1).isSameAs(comp2);
        }

        @Test
        @DisplayName("VersionComparator 应该能正确比较版本")
        void versionComparatorShouldWork() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            Comparator<String> comparator = managers.getVersionComparator();

            // Act & Assert
            assertThat(comparator.compare("1.0.0", "2.0.0")).isLessThan(0);
            assertThat(comparator.compare("2.0.0", "1.0.0")).isGreaterThan(0);
            assertThat(comparator.compare("1.0.0", "1.0.0")).isEqualTo(0);
        }

        @Test
        @DisplayName("VersionComparator 应该能比较复杂版本号")
        void shouldCompareComplexVersions() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            Comparator<String> comparator = managers.getVersionComparator();

            // Act & Assert
            assertThat(comparator.compare("1.0.0", "1.0.1")).isLessThan(0);
            assertThat(comparator.compare("1.1.0", "1.0.9")).isGreaterThan(0);
            assertThat(comparator.compare("2.0.0", "1.9.9")).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("closeAdventure 测试")
    class CloseAdventureTests {

        @Test
        @DisplayName("adventure 为 null 时不应抛出异常")
        void shouldNotThrowWhenAdventureIsNull() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            setField(managers, "adventure", null);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> managers.closeAdventure());
        }

        @Test
        @DisplayName("关闭 adventure 应该正常执行")
        void shouldNotThrowOnClose() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> managers.closeAdventure());
        }
    }

    @Nested
    @DisplayName("closeContext 测试")
    class CloseContextTests {

        @Test
        @DisplayName("context 为 null 时不应抛出异常")
        void shouldNotThrowWhenContextIsNull() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            setField(managers, "context", null);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> managers.closeContext());
        }

        @Test
        @DisplayName("关闭 context 应该正常执行")
        void shouldNotThrowOnClose() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            SimpleContainer mockContext = mock(SimpleContainer.class);
            setField(managers, "context", mockContext);

            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> managers.closeContext());
        }
    }

    @Nested
    @DisplayName("getContext 测试")
    class GetContextTests {

        @Test
        @DisplayName("应该返回设置的 context")
        void shouldReturnContext() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            SimpleContainer mockContext = mock(SimpleContainer.class);
            setField(managers, "context", mockContext);

            // Act
            SimpleContainer result = managers.getContext();

            // Assert
            assertThat(result).isEqualTo(mockContext);
        }

        @Test
        @DisplayName("context 为 null 时应返回 null")
        void shouldReturnNullWhenContextIsNull() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            setField(managers, "context", null);

            // Act
            SimpleContainer result = managers.getContext();

            // Assert
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getAdventure 测试")
    class GetAdventureTests {

        @Test
        @DisplayName("应该返回设置的 adventure")
        void shouldReturnAdventure() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act
            BukkitAudiences result = managers.getAdventure();

            // Assert
            assertThat(result).isEqualTo(mockAdventure);
        }
    }

    @Nested
    @DisplayName("静态字段测试")
    class StaticFieldTests {

        @Test
        @DisplayName("getVersionComparator 返回静态 COMPARATOR")
        void versionComparatorIsStaticConstant() throws Exception {
            // getVersionComparator 现在直接返回 VersionComparatorUtil.COMPARATOR
            DependenceManagers managers = createManagersWithMockedFields();
            Comparator<String> comparator = managers.getVersionComparator();
            assertThat(comparator).isSameAs(VersionComparatorUtil.COMPARATOR);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("多次关闭 context 不应抛出异常")
        void multipleCloseContextShouldNotThrow() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            SimpleContainer mockContext = mock(SimpleContainer.class);
            setField(managers, "context", mockContext);

            // Act & Assert - 多次调用不应该抛出异常
            assertDoesNotThrow(() -> {
                managers.closeContext();
                managers.closeContext();
                managers.closeContext();
            });
        }

        @Test
        @DisplayName("多次关闭 adventure 不应抛出异常")
        void multipleCloseAdventureShouldNotThrow() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act & Assert - 多次调用不应该抛出异常
            assertDoesNotThrow(() -> {
                managers.closeAdventure();
                managers.closeAdventure();
                managers.closeAdventure();
            });
        }
    }

    @Nested
    @DisplayName("字段初始化测试")
    class FieldInitializationTests {

        @Test
        @DisplayName("adventure 字段应该存在")
        void adventureFieldShouldExist() throws Exception {
            Field field = DependenceManagers.class.getDeclaredField("adventure");
            assertThat(field).isNotNull();
        }

        @Test
        @DisplayName("context 字段应该存在")
        void contextFieldShouldExist() throws Exception {
            Field field = DependenceManagers.class.getDeclaredField("context");
            assertThat(field).isNotNull();
        }
    }

    @Nested
    @DisplayName("Getter 注解测试")
    class GetterAnnotationTests {

        @Test
        @DisplayName("getAdventure 方法应该存在")
        void getAdventureMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("getAdventure");
            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("getContext 方法应该存在")
        void getContextMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("getContext");
            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("getVersionComparator 方法应该存在")
        void getVersionComparatorMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("getVersionComparator");
            assertThat(method).isNotNull();
        }
    }

    @Nested
    @DisplayName("SimpleContainer 测试")
    class SimpleContainerTests {

        @Test
        @DisplayName("context 设置为 null 后 getContext 应该返回 null")
        void contextSetToNullShouldReturnNull() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            setField(managers, "context", null);

            // Act
            SimpleContainer result = managers.getContext();

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("closeContext 应该调用 context.close()")
        void closeContextShouldCallClose() throws Exception { // NOPMD - uses Mockito verify()
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            SimpleContainer mockContext = mock(SimpleContainer.class);
            setField(managers, "context", mockContext);

            // Act
            managers.closeContext();

            // Assert
            org.mockito.Mockito.verify(mockContext).close();
        }
    }

    @Nested
    @DisplayName("BukkitAudiences 测试")
    class BukkitAudiencesTests {

        @Test
        @DisplayName("closeAdventure 应该调用 adventure.close()")
        void closeAdventureShouldCallClose() throws Exception { // NOPMD - uses Mockito verify()
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act
            managers.closeAdventure();

            // Assert
            org.mockito.Mockito.verify(mockAdventure).close();
        }

        @Test
        @DisplayName("getAdventure 返回正确的实例")
        void getAdventureReturnsCorrectInstance() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act
            BukkitAudiences result = managers.getAdventure();

            // Assert
            assertThat(result).isSameAs(mockAdventure);
        }
    }

    @Nested
    @DisplayName("VersionComparator 测试")
    class VersionComparatorStaticTests {

        @Test
        @DisplayName("应该返回非空的比较器")
        void firstCallShouldReturnNonNull() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();

            // Act
            Comparator<String> result = managers.getVersionComparator();

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("多次调用应该返回相同实例")
        void subsequentCallsShouldReturnSameInstance() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();

            // Act
            Comparator<String> first = managers.getVersionComparator();
            Comparator<String> second = managers.getVersionComparator();
            Comparator<String> third = managers.getVersionComparator();

            // Assert
            assertThat(first).isSameAs(second).isSameAs(third);
        }

        @Test
        @DisplayName("应该返回 VersionComparatorUtil.COMPARATOR")
        void shouldReturnStaticComparator() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();

            // Act
            Comparator<String> result = managers.getVersionComparator();

            // Assert
            assertThat(result).isSameAs(VersionComparatorUtil.COMPARATOR);
        }
    }

    @Nested
    @DisplayName("方法可访问性测试")
    class MethodAccessibilityTests {

        @Test
        @DisplayName("initAdventure 是公开方法")
        void initAdventureIsPublic() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("initAdventure", com.ultikits.ultitools.UltiTools.class);
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("initInventoryAPI 是公开方法")
        void initInventoryAPIIsPublic() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("initInventoryAPI", com.ultikits.ultitools.UltiTools.class);
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("closeAdventure 是公开方法")
        void closeAdventureIsPublic() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("closeAdventure");
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("closeContext 是公开方法")
        void closeContextIsPublic() throws Exception {
            java.lang.reflect.Method method = DependenceManagers.class.getMethod("closeContext");
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("字段修饰符测试")
    class FieldModifierTests {

        @Test
        @DisplayName("adventure 是私有字段")
        void adventureIsPrivate() throws Exception {
            Field field = DependenceManagers.class.getDeclaredField("adventure");
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("context 是私有字段")
        void contextIsPrivate() throws Exception {
            Field field = DependenceManagers.class.getDeclaredField("context");
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("VersionComparator 功能测试")
    class VersionComparatorFunctionalTests {

        @Test
        @DisplayName("应该能比较带 alpha 后缀的版本")
        void shouldCompareAlphaVersions() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            Comparator<String> comparator = managers.getVersionComparator();

            // Act & Assert - VersionComparator 按字典序比较，不特殊处理后缀
            // "1.0.0-alpha" 与 "1.0.0" 比较结果取决于实现
            int result = comparator.compare("1.0.0-alpha", "1.0.0");
            assertThat(result).isNotEqualTo(0); // 不相等即可
        }

        @Test
        @DisplayName("应该能比较带 beta 后缀的版本")
        void shouldCompareBetaVersions() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            Comparator<String> comparator = managers.getVersionComparator();

            // Act & Assert
            int result = comparator.compare("1.0.0-beta", "1.0.0");
            assertThat(result).isNotEqualTo(0);
        }

        @Test
        @DisplayName("应该能比较带 SNAPSHOT 后缀的版本")
        void shouldCompareSnapshotVersions() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            Comparator<String> comparator = managers.getVersionComparator();

            // Act & Assert
            int result = comparator.compare("1.0.0-SNAPSHOT", "1.0.0");
            assertThat(result).isNotEqualTo(0);
        }
    }

    @Nested
    @DisplayName("initCoreServices real registration shape (regression: PR #352 UAT)")
    class InitCoreServicesRegistrationTests {

        /**
         * Drives the ACTUAL {@code initCoreServices()} private method -- not a reproduction of
         * lines 70-81 -- so this test fails the moment the real registration shape in
         * {@code DependenceManagers} changes, not just when a copy of it drifts out of sync.
         *
         * <p>Before the identity-dedup fix in {@code SimpleContainer#getOrderedBeansOfType},
         * each of these three {@code getBean(Interface.class)} calls threw
         * {@code ContainerException.ambiguousBeanType}, because the same singleton instance is
         * deliberately registered under two names each ({@code :70-81}) so both a short internal
         * name and the interface FQN resolve it. A test covering only {@code NotificationService}
         * would leave {@code TeleportService} and {@code EmailService} unproven -- all three are
         * broken by the same defect.
         */
        @Test
        @DisplayName("TeleportService, NotificationService and EmailService all resolve by type without a false ambiguity")
        void allThreeAliasedCoreServicesResolveByType() throws Exception {
            // Arrange: a real DependenceManagers instance (constructor bypassed via Unsafe, same
            // as every other test in this file) with a real, un-mocked SimpleContainer -- the
            // ambiguity adjudication under test lives in SimpleContainer, so it must be real.
            DependenceManagers managers = createManagersWithMockedFields();
            SimpleContainer context = new SimpleContainer();
            setField(managers, "context", context);

            // Act: invoke the real private initCoreServices() method -- the exact code path
            // DependenceManagers' own constructor runs, not a hand-copied reproduction of it.
            java.lang.reflect.Method initCoreServices =
                    DependenceManagers.class.getDeclaredMethod("initCoreServices");
            initCoreServices.setAccessible(true); // NOPMD
            initCoreServices.invoke(managers);

            // Assert: each interface-typed lookup resolves without throwing.
            TeleportService teleportService = assertDoesNotThrow(
                    () -> context.getBean(TeleportService.class),
                    "TeleportService is aliased under two names (:70-71) -- must not be seen as "
                            + "two ambiguous candidates");
            assertThat(teleportService).isNotNull();

            NotificationService notificationService = assertDoesNotThrow(
                    () -> context.getBean(NotificationService.class),
                    "NotificationService is aliased under two names (:75-76) -- must not be seen "
                            + "as two ambiguous candidates");
            assertThat(notificationService).isNotNull();

            EmailService emailService = assertDoesNotThrow(
                    () -> context.getBean(EmailService.class),
                    "EmailService is aliased under two names (:80-81) -- must not be seen as two "
                            + "ambiguous candidates");
            assertThat(emailService).isNotNull();
        }
    }

    /**
     * 创建一个带有 mocked 字段的 DependenceManagers 实例
     * 使用 Unsafe 绕过构造函数
     */
    private DependenceManagers createManagersWithMockedFields() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        return (DependenceManagers) unsafe.allocateInstance(DependenceManagers.class);
    }

    private sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true); // NOPMD
        return (sun.misc.Unsafe) f.get(null);
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true); // NOPMD
        field.set(obj, value);
    }
}
