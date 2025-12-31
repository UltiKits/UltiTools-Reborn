package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * AnnotationUtils 测试类
 */
@DisplayName("AnnotationUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AnnotationUtilsTest {

    // 测试用注解
    @Retention(RetentionPolicy.RUNTIME)
    @interface TestAnnotation {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface AnotherAnnotation {
        int count() default 0;
    }

    // 测试用类
    @TestAnnotation("parent")
    static class ParentClass {
    }

    static class ChildClass extends ParentClass {
    }

    static class GrandChildClass extends ChildClass {
    }

    @TestAnnotation("direct")
    static class DirectAnnotatedClass {
    }

    @AnotherAnnotation(count = 5)
    static class DifferentAnnotatedClass {
    }

    static class NoAnnotationClass {
    }

    @Nested
    @DisplayName("findAnnotation 方法测试")
    class FindAnnotationTests {

        @Test
        @DisplayName("应该找到直接标注的注解")
        void shouldFindDirectAnnotation() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                DirectAnnotatedClass.class, TestAnnotation.class);
            
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("direct");
        }

        @Test
        @DisplayName("应该找到父类上的注解")
        void shouldFindAnnotationFromParent() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                ChildClass.class, TestAnnotation.class);
            
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("parent");
        }

        @Test
        @DisplayName("应该递归查找祖父类上的注解")
        void shouldFindAnnotationFromGrandParent() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                GrandChildClass.class, TestAnnotation.class);
            
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("parent");
        }

        @Test
        @DisplayName("没有注解时应该返回null")
        void shouldReturnNullWhenNoAnnotation() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                NoAnnotationClass.class, TestAnnotation.class);
            
            assertThat(annotation).isNull();
        }

        @Test
        @DisplayName("查找不存在的注解类型应该返回null")
        void shouldReturnNullForNonExistentAnnotationType() {
            AnotherAnnotation annotation = AnnotationUtils.findAnnotation(
                DirectAnnotatedClass.class, AnotherAnnotation.class);
            
            assertThat(annotation).isNull();
        }

        @Test
        @DisplayName("null类参数应该返回null")
        void nullClassShouldReturnNull() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                null, TestAnnotation.class);
            
            assertThat(annotation).isNull();
        }

        @Test
        @DisplayName("应该能找到不同类型的注解")
        void shouldFindDifferentAnnotationType() {
            AnotherAnnotation annotation = AnnotationUtils.findAnnotation(
                DifferentAnnotatedClass.class, AnotherAnnotation.class);
            
            assertThat(annotation).isNotNull();
            assertThat(annotation.count()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("AnnotationUtils类应该存在")
        void classShouldExist() {
            assertThat(AnnotationUtils.class).isNotNull();
        }

        @Test
        @DisplayName("findAnnotation方法应该是公开静态的")
        void findAnnotationMethodShouldBePublicStatic() throws Exception {
            java.lang.reflect.Method method = AnnotationUtils.class.getMethod(
                "findAnnotation", Class.class, Class.class);
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("构造函数应该是私有的")
        void constructorShouldBePrivate() throws Exception {
            java.lang.reflect.Constructor<?> constructor = 
                AnnotationUtils.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }
    }

    // 继承链测试辅助类 - 移到外部以避免 static 修饰符问题
    @TestAnnotation("level1")
    static class Level1 {
    }

    static class Level2 extends Level1 {
    }

    static class Level3 extends Level2 {
    }

    static class Level4 extends Level3 {
    }

    @Nested
    @DisplayName("继承链测试")
    class InheritanceChainTests {

        @Test
        @DisplayName("应该能在深层继承链中找到注解")
        void shouldFindAnnotationInDeepInheritanceChain() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                Level4.class, TestAnnotation.class);
            
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("level1");
        }
    }

    // 注解覆盖测试辅助类 - 移到外部以避免 static 修饰符问题
    @TestAnnotation("parent-value")
    static class OverrideParent {
    }

    @TestAnnotation("child-value")
    static class OverrideChild extends OverrideParent {
    }

    @Nested
    @DisplayName("注解覆盖测试")
    class AnnotationOverrideTests {

        @Test
        @DisplayName("子类的注解应该优先于父类")
        void childAnnotationShouldOverrideParent() {
            TestAnnotation annotation = AnnotationUtils.findAnnotation(
                OverrideChild.class, TestAnnotation.class);
            
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("child-value");
        }
    }
}
