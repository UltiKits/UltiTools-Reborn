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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.aop.chainy.C;

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

    /** Declares nothing of its own between an annotated grandparent and an overriding leaf. */
    static class AnnotatedGrandparent {
        @TestAnnotation
        public void m() { }
    }

    static class MiddleDeclaringNothing extends AnnotatedGrandparent { }

    static class LeafOverridingThroughGap extends MiddleDeclaringNothing {
        @Override public void m() { }
    }

    // D-38's first half: findInheritedMethodAnnotation's superclass walk no longer branches on a
    // caught NoSuchMethodException. Only the transitive-override case (Test 1) and the
    // no-declaration-here case (Test 2) have an observable surface; the removal of the catch block
    // itself is evidenced structurally, not by a test - see AopEligibilityTest's sibling reasoning
    // and this plan's own SUMMARY.
    @Nested
    @DisplayName("findInheritedMethodAnnotation superclass walk (D-38)")
    class InheritedAnnotationWalk {

        @Test
        @DisplayName("Should find a transitively overridden annotation across a package boundary")
        void shouldFindTransitiveOverrideAcrossPackages() throws Exception {
            // chainx.A (package-private, annotated) -> chainx.B (widens to public) ->
            // chainy.C (different package, overrides again). C does not override A directly - the
            // packages differ - only through B, and all three are one method (JLS 8.4.8.1).
            Method leaf = C.class.getDeclaredMethod("m");

            ExceptionCatch found = AopAdvisor.findMethodLevelAnnotation(leaf, ExceptionCatch.class);

            assertNotNull(found, "the annotation on chainx.A must be found through chainx.B");
        }

        @Test
        @DisplayName("Should skip a superclass declaring nothing of the same name, without breaking the walk")
        void shouldSkipSuperclassDeclaringNothing() throws Exception {
            // MiddleDeclaringNothing declares no m() at all - the ordinary case on the startup
            // path, not an exceptional one. The walk must continue past it to AnnotatedGrandparent.
            Method leaf = LeafOverridingThroughGap.class.getDeclaredMethod("m");

            TestAnnotation found = AopAdvisor.findMethodLevelAnnotation(leaf, TestAnnotation.class);

            assertNotNull(found, "the annotation on the grandparent must still be found");
        }
    }

    @Nested
    @DisplayName("Shared AnnotationLookupCache (D-38)")
    class SharedLookupCache {

        @Test
        @DisplayName("An advisor built with an injected cache still collapses own+inherited into one presence check")
        void advisorMatchStillCollapsesOwnAndInherited() throws Exception {
            AnnotationLookupCache<TestAnnotation> cache =
                    new AnnotationLookupCache<>(TestAnnotation.class);
            AopAdvisor advisor = AopAdvisor.forAnnotation(TestAnnotation.class, mockInterceptor, 0, cache);

            // Own: UnannotatedClass#annotatedMethod carries the annotation directly.
            assertTrue(advisor.matches(
                    UnannotatedClass.class.getMethod("annotatedMethod"), UnannotatedClass.class));
            // Class-level: AnnotatedClass#method1 has none of its own, the class does.
            assertTrue(advisor.matches(
                    AnnotatedClass.class.getMethod("method1"), AnnotatedClass.class));
            // Neither: PlainClass#someMethod has nothing anywhere in the chain.
            assertFalse(advisor.matches(
                    PlainClass.class.getMethod("someMethod"), PlainClass.class));
        }

        @Test
        @DisplayName("The advisor and the exception interceptor use the same injected cache instance")
        void advisorAndInterceptorShareTheSameInstance() throws Exception {
            AnnotationLookupCache<ExceptionCatch> cache =
                    new AnnotationLookupCache<>(ExceptionCatch.class);

            AopAdvisor advisor = AopAdvisor.forAnnotation(
                    ExceptionCatch.class, mockInterceptor, 200, cache);
            ExceptionInterceptor interceptor =
                    new ExceptionInterceptor(Collections.emptyList(), null, cache);

            Object advisorCache = readLookupCache(advisor);
            Object interceptorCache = readLookupCache(interceptor);

            assertSame(cache, advisorCache, "the advisor must use the injected instance");
            assertSame(cache, interceptorCache, "the interceptor must use the injected instance");
            assertSame(advisorCache, interceptorCache,
                    "the advisor and the interceptor must share one instance, not two equal ones");
        }

        @Test
        @DisplayName("Neither the advisor nor the interceptor nor the cache class holds a static cache field")
        void noStaticCacheFieldAnywhere() {
            assertNoStaticFieldOfType(AopAdvisor.AnnotationAopAdvisor.class, AnnotationLookupCache.class);
            assertNoStaticFieldOfType(ExceptionInterceptor.class, AnnotationLookupCache.class);
        }

        private void assertNoStaticFieldOfType(Class<?> owner, Class<?> fieldType) {
            for (Field field : owner.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    assertFalse(Modifier.isStatic(field.getModifiers()),
                            owner.getSimpleName() + "#" + field.getName()
                                    + " must not be static - it would pin every module's classes "
                                    + "for the life of the JVM and block plugin ClassLoader unload");
                }
            }
        }

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        private Object readLookupCache(Object owner) throws Exception {
            Field field = owner.getClass().getDeclaredField("lookupCache");
            field.setAccessible(true);
            return field.get(owner);
        }
    }
}
