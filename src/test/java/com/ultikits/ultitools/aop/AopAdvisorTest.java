package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AopAdvisor interface and AnnotationAopAdvisor implementation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AopAdvisor Tests")
class AopAdvisorTest {

    @Mock
    private MethodInterceptor mockInterceptor;

    // Test annotation for method-level testing
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface TestAnnotation {}
    
    // Another test annotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface OtherAnnotation {}

    // Test classes
    @TestAnnotation
    static class AnnotatedClass {
        public void method1() {}
        public void method2() {}
    }

    static class UnannotatedClass {
        @TestAnnotation
        public void annotatedMethod() {}
        
        public void unannotatedMethod() {}
    }
    
    static class PlainClass {
        public void someMethod() {}
    }

    @Nested
    @DisplayName("forAnnotation Factory Method Tests")
    class ForAnnotationTests {

        @Test
        @DisplayName("Should create advisor for annotation type")
        void shouldCreateAdvisorForAnnotationType() {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 100);
            
            assertNotNull(advisor);
            assertEquals(mockInterceptor, advisor.getInterceptor());
            assertEquals(100, advisor.getOrder());
        }

        @Test
        @DisplayName("Should create advisor with order 0")
        void shouldCreateAdvisorWithOrder0() {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0);
            
            assertEquals(0, advisor.getOrder());
        }

        @Test
        @DisplayName("Should create advisor with negative order")
        void shouldCreateAdvisorWithNegativeOrder() {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, -50);
            
            assertEquals(-50, advisor.getOrder());
        }

        @Test
        @DisplayName("Should create advisor with maximum order")
        void shouldCreateAdvisorWithMaxOrder() {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, Integer.MAX_VALUE);
            
            assertEquals(Integer.MAX_VALUE, advisor.getOrder());
        }
    }

    @Nested
    @DisplayName("matches Method Tests")
    class MatchesTests {

        @Test
        @DisplayName("Should match method with annotation")
        void shouldMatchMethodWithAnnotation() throws Exception {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0);
            Method method = UnannotatedClass.class.getMethod("annotatedMethod");
            
            assertTrue(advisor.matches(method, UnannotatedClass.class));
        }

        @Test
        @DisplayName("Should not match method without annotation")
        void shouldNotMatchMethodWithoutAnnotation() throws Exception {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0);
            Method method = UnannotatedClass.class.getMethod("unannotatedMethod");
            
            assertFalse(advisor.matches(method, UnannotatedClass.class));
        }

        @Test
        @DisplayName("Should match method on annotated class")
        void shouldMatchMethodOnAnnotatedClass() throws Exception {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0);
            Method method = AnnotatedClass.class.getMethod("method1");
            
            assertTrue(advisor.matches(method, AnnotatedClass.class));
        }

        @Test
        @DisplayName("Should match all methods on annotated class")
        void shouldMatchAllMethodsOnAnnotatedClass() throws Exception {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0);
            
            assertTrue(advisor.matches(AnnotatedClass.class.getMethod("method1"), AnnotatedClass.class));
            assertTrue(advisor.matches(AnnotatedClass.class.getMethod("method2"), AnnotatedClass.class));
        }

        @Test
        @DisplayName("Should not match method on plain class")
        void shouldNotMatchMethodOnPlainClass() throws Exception {
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0);
            Method method = PlainClass.class.getMethod("someMethod");
            
            assertFalse(advisor.matches(method, PlainClass.class));
        }

        @Test
        @DisplayName("Should not match with different annotation type")
        void shouldNotMatchWithDifferentAnnotationType() throws Exception {
            AopAdvisor advisor = AopAdvisor.forAnnotation(OtherAnnotation.class, mockInterceptor, 0);
            Method method = UnannotatedClass.class.getMethod("annotatedMethod");
            
            assertFalse(advisor.matches(method, UnannotatedClass.class));
        }
    }

    @Nested
    @DisplayName("Default Order Tests")
    class DefaultOrderTests {

        @Test
        @DisplayName("Default order should be 0")
        void defaultOrderShouldBe0() {
            // Create a custom advisor implementation to test default order
            AopAdvisor advisor = new AopAdvisor() {
                @Override
                public boolean matches(Method method, Class<?> targetClass) {
                    return false;
                }

                @Override
                public MethodInterceptor getInterceptor() {
                    return mockInterceptor;
                }
                // Uses default getOrder() implementation
            };
            
            assertEquals(0, advisor.getOrder());
        }
    }

    @Nested
    @DisplayName("AnnotationAopAdvisor Direct Tests")
    class AnnotationAopAdvisorDirectTests {

        @Test
        @DisplayName("Should store interceptor correctly")
        void shouldStoreInterceptorCorrectly() {
            AopAdvisor.AnnotationAopAdvisor advisor = new AopAdvisor.AnnotationAopAdvisor(
                    TestAnnotation.class, mockInterceptor, 50);
            
            assertSame(mockInterceptor, advisor.getInterceptor());
        }

        @Test
        @DisplayName("Should store order correctly")
        void shouldStoreOrderCorrectly() {
            AopAdvisor.AnnotationAopAdvisor advisor = new AopAdvisor.AnnotationAopAdvisor(
                    TestAnnotation.class, mockInterceptor, 150);
            
            assertEquals(150, advisor.getOrder());
        }

        @Test
        @DisplayName("Should check method-level annotation first")
        void shouldCheckMethodLevelAnnotationFirst() throws Exception {
            AopAdvisor.AnnotationAopAdvisor advisor = new AopAdvisor.AnnotationAopAdvisor(
                    TestAnnotation.class, mockInterceptor, 0);
            
            // Method has annotation, class doesn't
            Method method = UnannotatedClass.class.getMethod("annotatedMethod");
            assertTrue(advisor.matches(method, UnannotatedClass.class));
        }

        @Test
        @DisplayName("Should fall back to class-level annotation")
        void shouldFallBackToClassLevelAnnotation() throws Exception {
            AopAdvisor.AnnotationAopAdvisor advisor = new AopAdvisor.AnnotationAopAdvisor(
                    TestAnnotation.class, mockInterceptor, 0);
            
            // Method doesn't have annotation, but class does
            Method method = AnnotatedClass.class.getMethod("method1");
            assertTrue(advisor.matches(method, AnnotatedClass.class));
        }
    }

    @Nested
    @DisplayName("Multiple Advisors Tests")
    class MultipleAdvisorsTests {

        @Test
        @DisplayName("Should support multiple advisors for same annotation")
        void shouldSupportMultipleAdvisorsForSameAnnotation() {
            MethodInterceptor interceptor1 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor2 = mock(MethodInterceptor.class);
            
            AopAdvisor advisor1 = AopAdvisor.forAnnotation(TestAnnotation.class, interceptor1, 100);
            AopAdvisor advisor2 = AopAdvisor.forAnnotation(TestAnnotation.class, interceptor2, 200);
            
            assertNotSame(advisor1, advisor2);
            assertEquals(interceptor1, advisor1.getInterceptor());
            assertEquals(interceptor2, advisor2.getInterceptor());
        }

        @Test
        @DisplayName("Should support advisors for different annotations")
        void shouldSupportAdvisorsForDifferentAnnotations() {
            MethodInterceptor interceptor1 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor2 = mock(MethodInterceptor.class);
            
            AopAdvisor advisor1 = AopAdvisor.forAnnotation(TestAnnotation.class, interceptor1, 100);
            AopAdvisor advisor2 = AopAdvisor.forAnnotation(OtherAnnotation.class, interceptor2, 100);
            
            assertNotSame(advisor1, advisor2);
        }
    }
}
