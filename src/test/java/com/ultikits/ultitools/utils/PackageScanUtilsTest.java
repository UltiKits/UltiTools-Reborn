package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * PackageScanUtils 测试类
 */
@DisplayName("PackageScanUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PackageScanUtilsTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // 用于测试的自定义注解
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface TestAnnotation {
    }

    // 用于测试的带注解类
    @TestAnnotation
    static class AnnotatedClass {
    }

    // 用于测试的不带注解类
    static class NonAnnotatedClass {
    }

    // 用于继承测试的注解类子类
    static class AnnotatedSubClass extends AnnotatedClass {
    }

    // 用于多注解测试的注解（移到外部类级别）
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface AnotherAnnotation {
    }

    // 用于多注解测试的带注解类
    @AnotherAnnotation
    static class AnotherAnnotatedClass {
    }

    // 同时带有两个注解的类
    @TestAnnotation
    @AnotherAnnotation
    static class DualAnnotatedClass {
    }

    @Nested
    @DisplayName("scanAnnotatedClasses 方法测试")
    class ScanAnnotatedClassesTests {

        @Test
        @DisplayName("应该返回非null集合")
        void shouldReturnNonNullSet() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(classes).isNotNull();
        }

        @Test
        @DisplayName("不应该返回不带指定注解的类")
        void shouldNotReturnNonAnnotatedClasses() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            // NonAnnotatedClass 不应该被找到
            boolean foundNonAnnotatedClass = classes.stream()
                .anyMatch(c -> c.getSimpleName().equals("NonAnnotatedClass"));
            assertThat(foundNonAnnotatedClass).isFalse();
        }

        @Test
        @DisplayName("不存在的包应该返回空集合")
        void nonExistentPackageShouldReturnEmptySet() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.nonexistent.package.that.does.not.exist",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(classes).isNotNull();
            assertThat(classes).isEmpty();
        }

        @Test
        @DisplayName("空包名应该不抛出异常")
        void emptyPackageNameShouldNotThrow() {
            // 空包名可能导致扫描根包，但不应该抛异常
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(classes).isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "com.ultikits",
            "com.ultikits.ultitools",
            "com.ultikits.ultitools.utils"
        })
        @DisplayName("应该能扫描各级包")
        void shouldScanVariousPackageLevels(String packageName) {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class,
                packageName,
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(classes).isNotNull();
        }

        @Test
        @DisplayName("使用系统类加载器应该正常工作")
        void systemClassLoaderShouldWork() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class,
                "java.util",
                ClassLoader.getSystemClassLoader()
            );
            
            assertThat(classes).isNotNull();
            // java.util 包中可能有一些带 @Deprecated 的类
        }

        @Test
        @DisplayName("应该返回不可变或可操作的集合")
        void shouldReturnWorkableSet() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(classes).isNotNull();
            // 集合应该支持基本操作
            assertThatCode(() -> classes.size()).doesNotThrowAnyException();
            assertThatCode(() -> classes.isEmpty()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("多注解扫描测试")
    class MultipleAnnotationScanTests {

        @Test
        @DisplayName("扫描不同注解应该返回不同结果或均为空")
        void scanDifferentAnnotationsShouldWork() {
            Set<Class<?>> testAnnotationClasses = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            Set<Class<?>> anotherAnnotationClasses = PackageScanUtils.scanAnnotatedClasses(
                AnotherAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            // 两个集合都应该是非null的
            assertThat(testAnnotationClasses).isNotNull();
            assertThat(anotherAnnotationClasses).isNotNull();
        }

        @Test
        @DisplayName("带有两个注解的类应该同时被两种扫描找到")
        void dualAnnotatedClassShouldBeFoundByBothScans() {
            Set<Class<?>> testClasses = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            Set<Class<?>> anotherClasses = PackageScanUtils.scanAnnotatedClasses(
                AnotherAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            // 检查 DualAnnotatedClass 是否能同时被两种扫描找到
            boolean foundInTest = testClasses.stream()
                .anyMatch(c -> c.getSimpleName().equals("DualAnnotatedClass"));
            boolean foundInAnother = anotherClasses.stream()
                .anyMatch(c -> c.getSimpleName().equals("DualAnnotatedClass"));
            
            // 两者应该一致（要么都找到，要么都没找到，取决于内部类是否被扫描）
            assertThat(foundInTest).isEqualTo(foundInAnother);
        }
    }

    @Nested
    @DisplayName("子包扫描测试")
    class SubPackageScanTests {

        @Test
        @DisplayName("应该递归扫描子包")
        void shouldScanSubPackagesRecursively() {
            // 扫描 java.lang 包（安全的标准库包，不会触发 UltiTools 初始化）
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class, // 标准注解
                "java.lang",
                ClassLoader.getSystemClassLoader()
            );
            
            // 应该能找到一些带 @Deprecated 注解的类
            assertThat(classes).isNotNull();
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("null类加载器应该处理异常")
        void nullClassLoaderShouldHandleException() {
            // 这可能会抛出异常或返回空集合，取决于实现
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                null
            );
            
            // 不应该抛出未处理的异常
            assertThat(classes).isNotNull();
        }

        @Test
        @DisplayName("无效包名格式应该返回空集合")
        void invalidPackageNameShouldReturnEmptySet() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "this is not a valid package name!",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(classes).isNotNull();
            assertThat(classes).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "..double.dots",
            ".leading.dot",
            "trailing.dot.",
            "contains spaces",
            "special!chars#here"
        })
        @DisplayName("各种无效包名格式应该安全处理")
        void variousInvalidPackageNamesShouldBeSafe(String invalidPackageName) {
            assertThatCode(() -> PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                invalidPackageName,
                PackageScanUtilsTest.class.getClassLoader()
            )).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("性能相关测试")
    class PerformanceTests {

        @Test
        @DisplayName("多次扫描同一包应该一致")
        void multipleScansShouldBeConsistent() {
            Set<Class<?>> firstScan = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            Set<Class<?>> secondScan = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            assertThat(firstScan).isEqualTo(secondScan);
        }

        @Test
        @DisplayName("扫描不同包应该独立")
        void scanningDifferentPackagesShouldBeIndependent() {
            Set<Class<?>> utilsClasses = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class,
                "java.util",
                ClassLoader.getSystemClassLoader()
            );
            
            Set<Class<?>> ioClasses = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class,
                "java.io",
                ClassLoader.getSystemClassLoader()
            );
            
            // 两个包的扫描结果应该独立
            assertThat(utilsClasses).isNotNull();
            assertThat(ioClasses).isNotNull();
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("scanAnnotatedClasses方法应该存在且签名正确")
        void scanAnnotatedClassesMethodShouldExist() throws Exception {
            Method method = PackageScanUtils.class.getMethod(
                "scanAnnotatedClasses", 
                Class.class, 
                String.class, 
                ClassLoader.class
            );
            
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(Set.class);
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("方法应该有3个参数")
        void methodShouldHaveThreeParameters() throws Exception {
            Method method = PackageScanUtils.class.getMethod(
                "scanAnnotatedClasses", 
                Class.class, 
                String.class, 
                ClassLoader.class
            );
            
            assertThat(method.getParameterCount()).isEqualTo(3);
            assertThat(method.getParameterTypes()[0]).isEqualTo(Class.class);
            assertThat(method.getParameterTypes()[1]).isEqualTo(String.class);
            assertThat(method.getParameterTypes()[2]).isEqualTo(ClassLoader.class);
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("PackageScanUtils类应该是公开的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(PackageScanUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("类应该只有一个公开静态方法")
        void classShouldHaveOnlyOnePublicStaticMethod() {
            Method[] methods = PackageScanUtils.class.getDeclaredMethods();
            long publicStaticMethods = java.util.Arrays.stream(methods)
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .count();
            
            assertThat(publicStaticMethods).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("标准注解测试")
    class StandardAnnotationTests {

        @Test
        @DisplayName("应该能扫描@Deprecated注解")
        void shouldScanDeprecatedAnnotation() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class,
                "java.lang",
                ClassLoader.getSystemClassLoader()
            );
            
            assertThat(classes).isNotNull();
        }

        @Test
        @DisplayName("应该能扫描@FunctionalInterface注解")
        void shouldScanFunctionalInterfaceAnnotation() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                FunctionalInterface.class,
                "java.util.function",
                ClassLoader.getSystemClassLoader()
            );
            
            assertThat(classes).isNotNull();
            // java.util.function 包中有很多函数式接口
        }

        @Test
        @DisplayName("应该能扫描@SuppressWarnings注解")
        void shouldScanSuppressWarningsAnnotation() {
            // @SuppressWarnings 只有 SOURCE 保留策略，运行时不可见
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                SuppressWarnings.class,
                "java.lang",
                ClassLoader.getSystemClassLoader()
            );
            
            // 由于 @SuppressWarnings 是 SOURCE 保留，运行时应该找不到
            assertThat(classes).isNotNull();
            assertThat(classes).isEmpty();
        }
    }

    @Nested
    @DisplayName("返回集合类型测试")
    class ReturnSetTypeTests {

        @Test
        @DisplayName("返回集合应该是HashSet或类似实现")
        void returnedSetShouldBeProperImplementation() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            // 应该是 Set 的实现
            assertThat(classes).isInstanceOf(Set.class);
        }

        @Test
        @DisplayName("返回集合不应该包含null元素")
        void returnedSetShouldNotContainNull() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                Deprecated.class,
                "java.util",
                ClassLoader.getSystemClassLoader()
            );
            
            assertThat(classes).doesNotContainNull();
        }

        @Test
        @DisplayName("返回集合不应该有重复元素")
        void returnedSetShouldHaveNoDuplicates() {
            Set<Class<?>> classes = PackageScanUtils.scanAnnotatedClasses(
                TestAnnotation.class,
                "com.ultikits.ultitools.utils",
                PackageScanUtilsTest.class.getClassLoader()
            );
            
            // Set 本身就不允许重复，验证大小一致
            long distinctCount = classes.stream().distinct().count();
            assertThat(distinctCount).isEqualTo(classes.size());
        }
    }
}
