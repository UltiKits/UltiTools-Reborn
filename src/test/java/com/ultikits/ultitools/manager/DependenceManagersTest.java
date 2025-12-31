package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
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

import cn.hutool.core.comparator.VersionComparator;
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
            VersionComparator comparator = managers.getVersionComparator();

            // Assert
            assertThat(comparator).isNotNull();
            assertThat(comparator).isInstanceOf(VersionComparator.class);
        }

        @Test
        @DisplayName("多次调用应该返回相同实例")
        void shouldReturnSameInstance() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();

            // Act
            VersionComparator comp1 = managers.getVersionComparator();
            VersionComparator comp2 = managers.getVersionComparator();

            // Assert
            assertThat(comp1).isSameAs(comp2);
        }

        @Test
        @DisplayName("VersionComparator 应该能正确比较版本")
        void versionComparatorShouldWork() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            VersionComparator comparator = managers.getVersionComparator();

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
            VersionComparator comparator = managers.getVersionComparator();

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
            managers.closeAdventure();
        }

        @Test
        @DisplayName("关闭 adventure 应该正常执行")
        void shouldNotThrowOnClose() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act & Assert - 不应该抛出异常
            managers.closeAdventure();
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
            managers.closeContext();
        }

        @Test
        @DisplayName("关闭 context 应该正常执行")
        void shouldNotThrowOnClose() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            SimpleContainer mockContext = mock(SimpleContainer.class);
            setField(managers, "context", mockContext);

            // Act & Assert - 不应该抛出异常
            managers.closeContext();
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
        @DisplayName("versionComparator 是实例字段")
        void versionComparatorIsInstanceField() throws Exception {
            // 使用反射验证字段
            Field field = DependenceManagers.class.getDeclaredField("versionComparator");
            field.setAccessible(true);

            // Assert - versionComparator 是实例字段，不是静态的
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isFalse();
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
            managers.closeContext();
            managers.closeContext();
            managers.closeContext();
        }

        @Test
        @DisplayName("多次关闭 adventure 不应抛出异常")
        void multipleCloseAdventureShouldNotThrow() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            BukkitAudiences mockAdventure = mock(BukkitAudiences.class);
            setField(managers, "adventure", mockAdventure);

            // Act & Assert - 多次调用不应该抛出异常
            managers.closeAdventure();
            managers.closeAdventure();
            managers.closeAdventure();
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

        @Test
        @DisplayName("versionComparator 字段应该存在")
        void versionComparatorFieldShouldExist() throws Exception {
            Field field = DependenceManagers.class.getDeclaredField("versionComparator");
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
        void closeContextShouldCallClose() throws Exception {
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
        void closeAdventureShouldCallClose() throws Exception {
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
    @DisplayName("VersionComparator 懒加载测试")
    class VersionComparatorLazyLoadingTests {

        @Test
        @DisplayName("首次调用应该创建新实例")
        void firstCallShouldCreateNewInstance() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            setField(managers, "versionComparator", null);

            // Act
            VersionComparator result = managers.getVersionComparator();

            // Assert
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("后续调用应该返回相同实例")
        void subsequentCallsShouldReturnSameInstance() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            setField(managers, "versionComparator", null);

            // Act
            VersionComparator first = managers.getVersionComparator();
            VersionComparator second = managers.getVersionComparator();
            VersionComparator third = managers.getVersionComparator();

            // Assert
            assertThat(first).isSameAs(second).isSameAs(third);
        }

        @Test
        @DisplayName("已设置的实例应该直接返回")
        void presetInstanceShouldBeReturned() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            VersionComparator preset = new VersionComparator();
            setField(managers, "versionComparator", preset);

            // Act
            VersionComparator result = managers.getVersionComparator();

            // Assert
            assertThat(result).isSameAs(preset);
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

        @Test
        @DisplayName("versionComparator 是私有字段")
        void versionComparatorIsPrivate() throws Exception {
            Field field = DependenceManagers.class.getDeclaredField("versionComparator");
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
            VersionComparator comparator = managers.getVersionComparator();

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
            VersionComparator comparator = managers.getVersionComparator();

            // Act & Assert
            int result = comparator.compare("1.0.0-beta", "1.0.0");
            assertThat(result).isNotEqualTo(0);
        }

        @Test
        @DisplayName("应该能比较带 SNAPSHOT 后缀的版本")
        void shouldCompareSnapshotVersions() throws Exception {
            // Arrange
            DependenceManagers managers = createManagersWithMockedFields();
            VersionComparator comparator = managers.getVersionComparator();

            // Act & Assert
            int result = comparator.compare("1.0.0-SNAPSHOT", "1.0.0");
            assertThat(result).isNotEqualTo(0);
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
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
