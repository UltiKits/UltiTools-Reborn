package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Final;
import com.ultikits.ultitools.annotations.Transactional;

@DisplayName("AopEligibility Tests")
class AopEligibilityTest {

    public static class Clean {
        @Transactional
        public void ok() { }
        public void notAnnotated() { }
    }

    public static final class FinalClass {
        @Transactional
        public void ok() { }
    }

    @Final
    public static class AnnotatedFinalClass {
        @Transactional
        public void ok() { }
    }

    public static class HasFinalMethod {
        @Transactional
        public final void finalMethod() { }
    }

    public static class HasPrivateMethod {
        @Transactional
        private void privateMethod() { }
        public void touch() { privateMethod(); }
    }

    public static class HasStaticMethod {
        @Transactional
        public static void staticMethod() { }
    }

    public static class HasExceptionCatch {
        @ExceptionCatch
        public void guarded() { }
    }

    @Nested
    @DisplayName("findAopAnnotatedMethods")
    class Finding {

        @Test
        @DisplayName("Should find methods carrying @Transactional")
        void shouldFindTransactional() {
            Set<Method> found = AopEligibility.findAopAnnotatedMethods(Clean.class);
            assertEquals(1, found.size());
            assertEquals("ok", found.iterator().next().getName());
        }

        @Test
        @DisplayName("Should find methods carrying @ExceptionCatch")
        void shouldFindExceptionCatch() {
            Set<Method> found = AopEligibility.findAopAnnotatedMethods(HasExceptionCatch.class);
            assertEquals(1, found.size());
            assertEquals("guarded", found.iterator().next().getName());
        }

        @Test
        @DisplayName("Should return empty set for a class with no AOP annotations")
        void shouldReturnEmptyForPlainClass() {
            assertTrue(AopEligibility.findAopAnnotatedMethods(String.class).isEmpty());
        }
    }

    @Nested
    @DisplayName("check")
    class Checking {

        @Test
        @DisplayName("Should report no problems for a proxyable class")
        void shouldAcceptCleanClass() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    Clean.class, AopEligibility.findAopAnnotatedMethods(Clean.class));
            assertTrue(problems.isEmpty(), "unexpected: " + problems);
        }

        @Test
        @DisplayName("Should reject a final class and mention Lombok unconditionally")
        void shouldRejectFinalClass() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    FinalClass.class, AopEligibility.findAopAnnotatedMethods(FinalClass.class));
            assertEquals(1, problems.size());
            assertEquals(AopEligibility.Problem.Kind.FINAL_CLASS, problems.get(0).getKind());
            assertTrue(problems.get(0).getRemedy().contains("@Final"),
                    "remedy must point at @Final");
            assertTrue(problems.get(0).getRemedy().contains("@Data"),
                    "Lombok @Value cannot be detected at runtime, so the hint is unconditional");
        }

        @Test
        @DisplayName("Should accept a class marked @Final instead of final")
        void shouldAcceptAnnotatedFinalClass() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    AnnotatedFinalClass.class,
                    AopEligibility.findAopAnnotatedMethods(AnnotatedFinalClass.class));
            assertTrue(problems.isEmpty(), "unexpected: " + problems);
        }

        @Test
        @DisplayName("Should reject a final method")
        void shouldRejectFinalMethod() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    HasFinalMethod.class,
                    AopEligibility.findAopAnnotatedMethods(HasFinalMethod.class));
            assertEquals(1, problems.size());
            assertEquals(AopEligibility.Problem.Kind.FINAL_METHOD, problems.get(0).getKind());
            assertTrue(problems.get(0).getLocation().contains("finalMethod"));
        }

        @Test
        @DisplayName("Should reject a private method")
        void shouldRejectPrivateMethod() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    HasPrivateMethod.class,
                    AopEligibility.findAopAnnotatedMethods(HasPrivateMethod.class));
            assertEquals(1, problems.size());
            assertEquals(AopEligibility.Problem.Kind.PRIVATE_METHOD, problems.get(0).getKind());
        }

        @Test
        @DisplayName("Should reject a static method")
        void shouldRejectStaticMethod() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    HasStaticMethod.class,
                    AopEligibility.findAopAnnotatedMethods(HasStaticMethod.class));
            assertEquals(1, problems.size());
            assertEquals(AopEligibility.Problem.Kind.STATIC_METHOD, problems.get(0).getKind());
        }

        @Test
        @DisplayName("Should name the class and method in the problem location")
        void shouldNameLocation() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    HasFinalMethod.class,
                    AopEligibility.findAopAnnotatedMethods(HasFinalMethod.class));
            String location = problems.get(0).getLocation();
            assertTrue(location.contains(HasFinalMethod.class.getName()), location);
            assertTrue(location.contains("finalMethod"), location);
        }
    }
    @Nested
    @DisplayName("isProxyable")
    class Proxyable {

        // Asserting agreement with check(...) rather than restating the three modifiers is what
        // keeps the two from drifting when a fourth rule is added to either one. A method check()
        // reports a Problem for must be a method isProxyable() rejects, and vice versa.
        @Test
        @DisplayName("Should agree with check on every rejection kind")
        void shouldAgreeWithCheck() throws Exception {
            assertFalse(AopEligibility.isProxyable(
                    HasStaticMethod.class.getDeclaredMethod("staticMethod")));
            assertFalse(AopEligibility.isProxyable(
                    HasPrivateMethod.class.getDeclaredMethod("privateMethod")));
            assertFalse(AopEligibility.isProxyable(
                    HasFinalMethod.class.getDeclaredMethod("finalMethod")));
        }

        @Test
        @DisplayName("Should accept an ordinary overridable method")
        void shouldAcceptOrdinaryMethod() throws Exception {
            assertTrue(AopEligibility.isProxyable(Clean.class.getDeclaredMethod("ok")));
        }

        @Test
        @DisplayName("Should reject null rather than throw")
        void shouldRejectNull() {
            assertFalse(AopEligibility.isProxyable(null));
        }
    }
}
