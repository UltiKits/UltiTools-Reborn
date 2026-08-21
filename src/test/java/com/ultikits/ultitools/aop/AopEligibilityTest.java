package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;
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

    public static class AnnotatedBase {
        @Transactional
        public void inheritedAnnotated() { }
    }

    /** Declares nothing of its own; the annotated method comes entirely from the superclass. */
    public static class InheritsAnnotation extends AnnotatedBase { }

    public static class FinalMethodBase {
        @Transactional
        public final void inheritedFinal() { }
    }

    public static class InheritsFinalMethod extends FinalMethodBase { }

    public abstract static class AnnotatedGenericBase<T> {
        @ExceptionCatch
        public abstract void handle(T value);
    }

    public static class ConcreteGeneric extends AnnotatedGenericBase<String> {
        @Override public void handle(String value) { }
    }

    @ExceptionCatch
    public static class ClassLevelOnly {
        public void plain() { }
    }

    @ExceptionCatch
    public static final class ClassLevelOnFinalClass {
        public void plain() { }
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

        @Test
        @DisplayName("Should find a method-level annotation declared on a superclass")
        void shouldFindInheritedMethodLevel() {
            Set<Method> found = AopEligibility.findAopAnnotatedMethods(InheritsAnnotation.class);
            assertEquals(1, found.size());
            assertEquals("inheritedAnnotated", found.iterator().next().getName());
        }

        // check() turns everything in this set into a load-blocking Problem, so a class-level
        // annotation must not contribute to it: the author never vetted those methods one by one
        // and a single private helper on a superclass would stop the module from loading.
        // Class-level coverage is resolved in AopProxyResolver instead. See issue #309.
        @Test
        @DisplayName("Should not report methods covered only by a class-level annotation")
        void shouldIgnoreClassLevelCoverage() {
            assertTrue(AopEligibility.findAopAnnotatedMethods(ClassLevelOnly.class).isEmpty());
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

        // The final-class branch used to sit behind an isEmpty() short-circuit on the second
        // argument. Once class-level annotations stopped contributing to that argument, a final
        // class carrying only a class-level annotation slipped past it and reached ByteBuddy,
        // which throws a bare exception naming neither the class nor a remedy. See issue #309.
        @Test
        @DisplayName("Should reject a final class even when no method-level annotation exists")
        void shouldRejectFinalClassWithoutMethodLevelAnnotations() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    ClassLevelOnFinalClass.class, Collections.<Method>emptySet());
            assertEquals(1, problems.size());
            assertEquals(AopEligibility.Problem.Kind.FINAL_CLASS, problems.get(0).getKind());
        }

        // wireAop's javadoc promises a method-level annotation is never skipped quietly. Shadowed
        // methods are skipped, so check() has to name them - it did not, and the warning loop in
        // AopProxyResolver had nothing to print for the one case it most needed to.
        @Test
        @DisplayName("Should report a shadowed method rather than let it be skipped in silence")
        void shouldReportShadowedMethod() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    ConcreteGeneric.class,
                    AopEligibility.findAopAnnotatedMethods(ConcreteGeneric.class));
            assertEquals(1, problems.size(), String.valueOf(problems));
            assertEquals(AopEligibility.Problem.Kind.SHADOWED_METHOD, problems.get(0).getKind());
            assertTrue(problems.get(0).getLocation().contains("handle"),
                    problems.get(0).getLocation());
        }

        // Now that the scan walks the hierarchy, an annotated method can be declared somewhere
        // other than the bean. Naming the bean would send the author to a file that does not
        // contain the annotation being complained about.
        @Test
        @DisplayName("Should name the declaring class, not the bean, for an inherited method")
        void shouldNameDeclaringClassForInheritedMethod() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    InheritsFinalMethod.class,
                    AopEligibility.findAopAnnotatedMethods(InheritsFinalMethod.class));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).getLocation().contains(FinalMethodBase.class.getName()),
                    problems.get(0).getLocation());
        }
    }
    @Nested
    @DisplayName("isProxyable")
    class Proxyable {

        // Calls both sides and compares them, rather than restating the modifiers. An earlier
        // version of this test claimed to do that but only ever called isProxyable, so it asserted
        // nothing about check() and would not have caught a fifth rule added to one side alone.
        // Shadowing is the documented exception: check deliberately does not report it, because
        // what shadows a declaration is a compiler-generated bridge rather than an author error.
        @Test
        @DisplayName("Should agree with check on every rule except the one check omits")
        void shouldAgreeWithCheck() throws Exception {
            Class<?>[] owners = {HasStaticMethod.class, HasPrivateMethod.class,
                    HasFinalMethod.class, Clean.class};
            String[] methods = {"staticMethod", "privateMethod", "finalMethod", "ok"};

            for (int i = 0; i < owners.length; i++) {
                Method method = owners[i].getDeclaredMethod(methods[i]);
                boolean proxyable = AopEligibility.isProxyable(method, owners[i]);
                boolean checkRejects = !AopEligibility.check(
                        owners[i], Collections.singleton(method)).isEmpty();
                assertEquals(checkRejects, !proxyable,
                        methods[i] + ": check and isProxyable disagree");
            }
        }

        @Test
        @DisplayName("Should accept an ordinary overridable method")
        void shouldAcceptOrdinaryMethod() throws Exception {
            assertTrue(AopEligibility.isProxyable(Clean.class.getDeclaredMethod("ok"), Clean.class));
        }

        @Test
        @DisplayName("Should reject null rather than throw")
        void shouldRejectNull() throws Exception {
            assertFalse(AopEligibility.isProxyable(null, Clean.class));
            assertFalse(AopEligibility.isProxyable(Clean.class.getDeclaredMethod("ok"), null));
        }
    }
}
