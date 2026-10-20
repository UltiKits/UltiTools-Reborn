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
import com.ultikits.ultitools.aop.crosspackage.PackagePrivateBase;

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
        // nothing about check(). check() reports all five rules today - including shadowing, which
        // it briefly did not - so agreement is total and the fixtures below cover every one.
        @Test
        @DisplayName("Should agree with check on every rule except the one check omits")
        void shouldAgreeWithCheck() throws Exception {
            Class<?>[] owners = {HasStaticMethod.class, HasPrivateMethod.class,
                    HasFinalMethod.class, Clean.class, ConcreteGeneric.class};
            String[] methods = {"staticMethod", "privateMethod", "finalMethod", "ok", "handle"};
            Class<?>[][] params = {{}, {}, {}, {}, {String.class}};

            for (int i = 0; i < owners.length; i++) {
                // The shadowed case is reached through the annotated superclass declaration, which
                // is the one both sides have to agree about.
                Method method = i == 4
                        ? AnnotatedGenericBase.class.getDeclaredMethod("handle", Object.class)
                        : owners[i].getDeclaredMethod(methods[i], params[i]);
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

    // D-36: the five proxy-eligibility rules exist once, as a rule enum check() and isProxyable()
    // both walk. These tests exercise the property the enum is supposed to guarantee - kind
    // agreement between the two paths, bean-class relativity, the final-class short circuit, and
    // that the boolean path never pays for a description string it never returns.
    @Nested
    @DisplayName("Rule enum consolidation (D-36)")
    class RuleConsolidation {

        @Test
        @DisplayName("Should report each of the five rules with its own kind, and isProxyable agrees")
        void shouldReportEachRuleWithItsOwnKind() throws Exception {
            Method staticMethod = HasStaticMethod.class.getDeclaredMethod("staticMethod");
            Method privateMethod = HasPrivateMethod.class.getDeclaredMethod("privateMethod");
            Method finalMethod = HasFinalMethod.class.getDeclaredMethod("finalMethod");
            Method shadowed = AnnotatedGenericBase.class.getDeclaredMethod("handle", Object.class);
            Method inaccessible = PackagePrivateBase.class.getDeclaredMethod("packagePrivateHelper");

            assertRuleAgreement(staticMethod, HasStaticMethod.class,
                    AopEligibility.Problem.Kind.STATIC_METHOD);
            assertRuleAgreement(privateMethod, HasPrivateMethod.class,
                    AopEligibility.Problem.Kind.PRIVATE_METHOD);
            assertRuleAgreement(finalMethod, HasFinalMethod.class,
                    AopEligibility.Problem.Kind.FINAL_METHOD);
            assertRuleAgreement(shadowed, ConcreteGeneric.class,
                    AopEligibility.Problem.Kind.SHADOWED_METHOD);
            // A different package than the one the method is declared in - the bean-class-relative
            // rule, not the method-intrinsic ones above.
            assertRuleAgreement(inaccessible, AopEligibilityTest.class,
                    AopEligibility.Problem.Kind.INACCESSIBLE_METHOD);
        }

        private void assertRuleAgreement(Method method, Class<?> beanClass,
                                          AopEligibility.Problem.Kind expectedKind) {
            List<AopEligibility.Problem> problems =
                    AopEligibility.check(beanClass, Collections.singleton(method));
            assertEquals(1, problems.size(), String.valueOf(problems));
            assertEquals(expectedKind, problems.get(0).getKind());
            assertFalse(AopEligibility.isProxyable(method, beanClass),
                    expectedKind + ": check and isProxyable disagree");
        }

        @Test
        @DisplayName("Should agree a method violating none of the five rules is eligible")
        void shouldAgreeNoViolation() throws Exception {
            Method ok = Clean.class.getDeclaredMethod("ok");
            assertTrue(AopEligibility.check(Clean.class, Collections.singleton(ok)).isEmpty());
            assertTrue(AopEligibility.isProxyable(ok, Clean.class));
        }

        // The boolean path must cost nothing to call: no rule's remediation text is ever built
        // from isProxyable, even for a method that violates one. describeInvocationCountForTesting
        // is a package-private instrumentation hook that only the rule enum's diagnostic path
        // increments, so a stuck-at-zero counter proves the boolean path never reaches it - and the
        // second half of this test proves the counter is not vacuously stuck at zero regardless.
        @Test
        @DisplayName("isProxyable should never build remediation text, even for a violating method")
        void shouldBuildNoRemediationTextOnBooleanPath() throws Exception {
            Method staticMethod = HasStaticMethod.class.getDeclaredMethod("staticMethod");
            int before = AopEligibility.describeInvocationCountForTesting();

            boolean proxyable = AopEligibility.isProxyable(staticMethod, HasStaticMethod.class);

            assertFalse(proxyable);
            assertEquals(before, AopEligibility.describeInvocationCountForTesting(),
                    "isProxyable must not build any rule's remediation text");

            // Sanity: the counter does move on the diagnostic path, so the assertion above is not
            // vacuously true against a counter that never increments at all.
            AopEligibility.check(HasStaticMethod.class, Collections.singleton(staticMethod));
            assertTrue(AopEligibility.describeInvocationCountForTesting() > before,
                    "the counter must increment on the diagnostic path, or the assertion above "
                            + "proves nothing");
        }

        @Test
        @DisplayName("The two bean-class-relative rules answer relative to the bean class")
        void shouldAnswerRelativeToBeanClass() throws Exception {
            Method packagePrivate = PackagePrivateBase.class.getDeclaredMethod("packagePrivateHelper");

            // Same package as the declaration: reachable through super.
            assertTrue(AopEligibility.isProxyable(packagePrivate, PackagePrivateBase.class));
            // A different package: the identical Method, unreachable from this beanClass instead.
            assertFalse(AopEligibility.isProxyable(packagePrivate, AopEligibilityTest.class));
        }

        @Test
        @DisplayName("A final class remains a class-level short circuit before any per-method rule runs")
        void shouldShortCircuitOnFinalClass() {
            List<AopEligibility.Problem> problems = AopEligibility.check(
                    FinalClass.class, AopEligibility.findAopAnnotatedMethods(FinalClass.class));
            assertEquals(1, problems.size());
            assertEquals(AopEligibility.Problem.Kind.FINAL_CLASS, problems.get(0).getKind());
        }
    }
}
