package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.Data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Final;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.crosspackage.AnnotatedPackagePrivateBase;
import com.ultikits.ultitools.aop.crosspackage.GenericBase;
import com.ultikits.ultitools.aop.crosspackage.PackagePrivateBase;
import com.ultikits.ultitools.aop.crosspackage.SameNameBase;
import com.ultikits.ultitools.exceptions.ContainerException;

@DisplayName("AopProxyResolver Tests")
class AopProxyResolverTest {

    private AopProxyResolver resolver;
    private List<String> log;

    public static class Plain {
        public String work() { return "plain"; }
    }

    public static class Managed {
        @Transactional
        public String work() { return "managed:" + helper(); }
        @Transactional
        public String helper() { return "helper"; }
    }

    public static final class FinalManaged {
        @Transactional
        public String work() { return "nope"; }
    }

    @Final
    public static class AnnotatedFinalManaged {
        @Transactional
        public String work() { return "ok"; }
    }

    public static class TransactionalBase {
        @Transactional
        public void transactionalOnBase() { }
    }

    /** Inherits the annotated method without declaring anything of its own. */
    public static class InheritsTransactional extends TransactionalBase { }

    public static class GuardedBase {
        @ExceptionCatch(silent = true, defaultValue = "from-base")
        public String guardedOnBase() { throw new IllegalStateException("base-boom"); }
    }

    public static class InheritsGuarded extends GuardedBase { }

    private static AopProxyResolver exceptionCatchResolver() {
        AopProxyResolver resolver = new AopProxyResolver();
        resolver.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class,
                new ExceptionInterceptor(Collections.emptyList(), null), 200));
        return resolver;
    }

    public static class UnproxyableBase {
        private String privateHelper() { return "private"; }
        public static String staticHelper() { return "static"; }
        public final String finalHelper() { return "final"; }
        public String ordinary() { throw new IllegalStateException("ordinary-boom"); }
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelOverUnproxyable extends UnproxyableBase { }

    @Data
    public static class LombokBase {
        private String name;
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelOverLombok extends LombokBase {
        public String own() { throw new IllegalStateException("own-boom"); }
    }

    public static class MethodLevelOnHashCode {
        @Override
        @ExceptionCatch(silent = true)
        public int hashCode() { return 1; }
    }

    /**
     * Whether the generated proxy declares an override for the given signature. Checking the
     * generated type rather than merely calling the method is what distinguishes "excluded from
     * interception" from "intercepted but happened not to throw".
     */
    private static boolean declares(Class<?> clazz, String name, Class<?>... params) {
        try {
            clazz.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class OverGeneric extends GenericBase<String> {
        @Override public void take(String value) { }
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class OverPackagePrivate extends PackagePrivateBase { }

    /** The annotation is on the inaccessible method itself, so the load must fail. */
    public static class OverAnnotatedPackagePrivate extends AnnotatedPackagePrivateBase { }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class OverSameName extends SameNameBase {
        public void shared() { }
    }

    @BeforeEach
    void setUp() {
        log = new ArrayList<>();
        resolver = new AopProxyResolver();
        MethodInterceptor recorder = inv -> {
            log.add(inv.getMethod().getName());
            return inv.proceed();
        };
        resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, recorder, 100));
    }

    @Nested
    @DisplayName("Advisor registry")
    class Registry {

        @Test
        @DisplayName("Should expose registered advisors")
        void shouldExposeAdvisors() {
            assertEquals(1, resolver.getAdvisors().size());
        }

        @Test
        @DisplayName("Should remove a registered advisor")
        void shouldRemoveAdvisor() {
            AopAdvisor advisor = resolver.getAdvisors().get(0);
            assertTrue(resolver.removeAdvisor(advisor));
            assertTrue(resolver.getAdvisors().isEmpty());
        }

        @Test
        @DisplayName("Should report false when removing an advisor that was never added")
        void shouldReportFalseWhenRemovingUnknownAdvisor() {
            AopAdvisor neverAdded = AopAdvisor.forAnnotation(Transactional.class,
                    MethodInvocation::proceed, 50);
            assertFalse(resolver.removeAdvisor(neverAdded));
            assertEquals(1, resolver.getAdvisors().size());
        }

        @Test
        @DisplayName("Should keep advisors sorted by order")
        void shouldSortByOrder() {
            MethodInterceptor noop = MethodInvocation::proceed;
            resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, noop, 10));
            assertEquals(10, resolver.getAdvisors().get(0).getOrder());
            assertEquals(100, resolver.getAdvisors().get(1).getOrder());
        }

        @Test
        @DisplayName("getAdvisors() should return a defensive copy")
        void shouldReturnDefensiveCopy() {
            List<AopAdvisor> exposed = resolver.getAdvisors();
            exposed.clear();
            exposed.add(AopAdvisor.forAnnotation(Transactional.class, MethodInvocation::proceed, 1));

            // Mutating the returned list must not leak back into the resolver's internal state.
            assertEquals(1, resolver.getAdvisors().size());
            assertEquals(100, resolver.getAdvisors().get(0).getOrder());
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolving {

        @Test
        @DisplayName("Should return the original class when no advisor matches")
        void shouldReturnOriginalForPlainClass() {
            assertSame(Plain.class, resolver.resolve(Plain.class));
        }

        @Test
        @DisplayName("Should return the original class when no advisors are registered")
        void shouldReturnOriginalWhenNoAdvisors() {
            AopProxyResolver empty = new AopProxyResolver();
            assertSame(Managed.class, empty.resolve(Managed.class));
        }

        @Test
        @DisplayName("Should return a proxy subclass for a managed class")
        void shouldReturnProxyForManagedClass() {
            Class<?> resolved = resolver.resolve(Managed.class);
            assertTrue(ProxyFactory.isProxyClass(resolved));
            assertTrue(Managed.class.isAssignableFrom(resolved));
        }

        @Test
        @DisplayName("Should intercept self-invocation on the resolved class")
        void shouldInterceptSelfInvocation() throws Exception {
            Class<?> resolved = resolver.resolve(Managed.class);
            Managed bean = (Managed) resolved.getDeclaredConstructor().newInstance();

            assertEquals("managed:helper", bean.work());
            assertEquals(Arrays.asList("work", "helper"), log);
        }

        @Test
        @DisplayName("Should reject a final class with an actionable message")
        void shouldRejectFinalClass() {
            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> resolver.resolve(FinalManaged.class));
            assertTrue(thrown.getMessage().contains(FinalManaged.class.getName()),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains("@Final"), thrown.getMessage());
        }

        @Test
        @DisplayName("Should accept a class marked @Final")
        void shouldAcceptAnnotatedFinalClass() {
            Class<?> resolved = resolver.resolve(AnnotatedFinalManaged.class);
            assertTrue(ProxyFactory.isProxyClass(resolved));
        }
    }

    @Nested
    @DisplayName("Annotations declared unavailable")
    class Unavailable {

        @Test
        @DisplayName("Should reject a bean whose method uses an unavailable annotation")
        void shouldRejectMethodLevelUnavailable() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addUnavailableAnnotation(Transactional.class,
                    "requires a TransactionManager bound to a DataSource, see #195/#196");

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> bare.resolve(Managed.class));

            assertTrue(thrown.getMessage().contains("Transactional"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("#195"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("work"),
                    "the message must name the offending method: " + thrown.getMessage());
        }

        @Test
        @DisplayName("Should check unavailable annotations even with no advisors registered")
        void shouldCheckBeforeAdvisorShortCircuit() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addUnavailableAnnotation(Transactional.class, "unavailable");
            assertThrows(ContainerException.class, () -> bare.resolve(Managed.class));
        }

        @Test
        @DisplayName("Should leave beans not using the unavailable annotation alone")
        void shouldIgnoreUnrelatedBeans() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addUnavailableAnnotation(Transactional.class, "unavailable");
            assertSame(Plain.class, bare.resolve(Plain.class));
        }
    }

    @Nested
    @DisplayName("validateAnnotationCoverage")
    class Coverage {

        @Test
        @DisplayName("Should reject when a recognised annotation is neither served nor declared unavailable")
        void shouldRejectUncoveredAnnotation() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class, MethodInvocation::proceed, 200));
            // Transactional is registered by neither an advisor nor addUnavailableAnnotation.

            ContainerException thrown = assertThrows(ContainerException.class,
                    bare::validateAnnotationCoverage);
            assertTrue(thrown.getMessage().contains("Transactional"), thrown.getMessage());
        }

        @Test
        @DisplayName("Should pass when every recognised annotation is served or declared unavailable")
        void shouldAcceptFullCoverage() {
            AopProxyResolver bare = new AopProxyResolver();
            bare.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class, MethodInvocation::proceed, 200));
            bare.addUnavailableAnnotation(Transactional.class, "see #195/#196");

            assertDoesNotThrow(bare::validateAnnotationCoverage);
        }
    }
    // Before this change the refusal only saw getDeclaredMethods(), so an author who factored a
    // @Transactional method into an abstract base class got neither interception nor refusal:
    // the module loaded and the annotation did nothing, which is the exact failure the refusal
    // exists to prevent. See issue #309.
    @Test
    @DisplayName("Should refuse a bean whose @Transactional method is declared on a superclass")
    void shouldRefuseInheritedTransactional() {
        AopProxyResolver bare = new AopProxyResolver();
        bare.addUnavailableAnnotation(Transactional.class, "not available in this release");

        ContainerException thrown = assertThrows(ContainerException.class,
                () -> bare.resolve(InheritsTransactional.class));
        assertTrue(thrown.getMessage().contains("transactionalOnBase"),
                "the message must name the offending method, not just the bean: "
                        + thrown.getMessage());
    }
    @Test
    @DisplayName("Should proxy a bean whose only annotated method is inherited")
    void shouldProxyInheritedGuarded() throws Exception {
        Class<?> resolved = exceptionCatchResolver().resolve(InheritsGuarded.class);
        assertNotSame(InheritsGuarded.class, resolved,
                "an inherited @ExceptionCatch must still produce a proxy");
        assertTrue(ProxyFactory.isProxyClass(resolved));

        Object bean = resolved.getDeclaredConstructor().newInstance();
        assertEquals("from-base",
                resolved.getMethod("guardedOnBase").invoke(bean),
                "assert the swallowed return value, not merely that a proxy was created");
    }
    // A class-level annotation is a bulk request. One private helper on a superclass must not
    // stop the module from loading - the author never named that method. See issue #309.
    @Test
    @DisplayName("Should skip unproxyable methods a class-level annotation covers, not fail")
    void shouldSkipUnproxyableUnderClassLevel() {
        Class<?> resolved = assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(ClassLevelOverUnproxyable.class));
        assertTrue(ProxyFactory.isProxyClass(resolved));

        assertTrue(declares(resolved, "ordinary"),
                "the one proxyable method must still be intercepted");
        assertFalse(declares(resolved, "privateHelper"));
        assertFalse(declares(resolved, "staticHelper"));
        assertFalse(declares(resolved, "finalHelper"));
    }

    // Swallowing equals returns false and the caller's HashMap then misses a key it holds. A
    // propagating exception at least reaches someone. canEqual is in the list because the proxy
    // overrides it and the superclass equals reaches it by virtual dispatch, so excluding equals
    // alone excludes nothing. See issue #309.
    @Test
    @DisplayName("Should never let a class-level annotation cover equals, hashCode or canEqual")
    void shouldExcludeSilentWrongAnswerSignatures() {
        Class<?> resolved = exceptionCatchResolver().resolve(ClassLevelOverLombok.class);
        assertFalse(declares(resolved, "equals", Object.class));
        assertFalse(declares(resolved, "hashCode"));
        assertFalse(declares(resolved, "canEqual", Object.class));
    }

    // The other half of the assertion above. Without it, an implementation that excluded every
    // superclass-generated method - or every method whatsoever - would pass just as well.
    // toString is kept off the exclusion list on purpose: swallowing it costs a log line, and
    // "the logging statement itself threw" is what @ExceptionCatch(silent = true) is for.
    @Test
    @DisplayName("Should still cover toString and the class's own methods")
    void shouldStillCoverToStringAndOwnMethods() {
        Class<?> resolved = exceptionCatchResolver().resolve(ClassLevelOverLombok.class);
        assertTrue(declares(resolved, "toString"),
                "toString is deliberately NOT excluded");
        assertTrue(declares(resolved, "own"));
    }

    // Method-level beats the exclusion list: the author named the method explicitly.
    @Test
    @DisplayName("Should honour a method-level annotation on an otherwise excluded signature")
    void shouldHonourMethodLevelOnExcludedSignature() {
        Class<?> resolved = exceptionCatchResolver().resolve(MethodLevelOnHashCode.class);
        assertTrue(declares(resolved, "hashCode"),
                "the exclusion list governs class-level coverage only");
    }
    // getAllMethods returns both GenericBase.take(Object) and OverGeneric.take(String): overrides()
    // compares erased parameter types, and those differ. The erased declaration is not
    // super-invokable - the compiler put a bridge over it - so handing it to ProxyFactory throws
    // "Cannot invoke ... as a super method" and the whole module fails to load. Reproduced against
    // alpha, where the narrower getDeclaredMethods() scan never surfaced the twin.
    @Test
    @DisplayName("Should skip an erased superclass declaration a subclass bridges over")
    void shouldSkipBridgedErasedDeclaration() {
        Class<?> resolved = assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(OverGeneric.class));
        assertTrue(ProxyFactory.isProxyClass(resolved));
        assertTrue(declares(resolved, "take", String.class),
                "the concrete override is proxyable and must still be intercepted");

        // take(Object) does appear on the proxy, but as ByteBuddy's own bridge preserving the
        // generic contract - not as an interception override. Asserting it is absent would fail
        // for the wrong reason; asserting it is a bridge is what distinguishes the two.
        Method erasedTwin = assertDoesNotThrow(
                () -> resolved.getDeclaredMethod("take", Object.class));
        assertTrue(erasedTwin.isBridge(),
                "the erased twin must be a bridge, not an intercepted override");
    }

    // An inheritance proxy is generated in the bean's package, so a package-private method
    // declared in another package can be neither overridden nor super-invoked. Nothing in the
    // static/private/final rules catches it.
    @Test
    @DisplayName("Should skip a package-private superclass method from another package")
    void shouldSkipCrossPackagePackagePrivate() {
        Class<?> resolved = assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(OverPackagePrivate.class));
        assertTrue(ProxyFactory.isProxyClass(resolved));
        assertTrue(declares(resolved, "ordinary"),
                "the public inherited method must still be intercepted");
        assertFalse(declares(resolved, "packagePrivateHelper"));
    }

    // The class-level path skips silently; the method-level path must fail the load instead, and
    // must name the method rather than letting ByteBuddy throw a message that names none.
    @Test
    @DisplayName("Should refuse a method-level annotation on an inaccessible inherited method")
    void shouldRefuseAnnotatedCrossPackagePackagePrivate() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> exceptionCatchResolver().resolve(OverAnnotatedPackagePrivate.class));
        assertTrue(thrown.getMessage().contains("annotatedPackagePrivate"),
                "the refusal must name the method: " + thrown.getMessage());
    }

    // Cross-package plus package-private means the two declarations do not override one another,
    // so both survive the scan and both map to trampoline ultitools$super$shared.
    @Test
    @DisplayName("Should not collide trampolines when two declarations share a signature")
    void shouldNotCollideTrampolines() {
        Class<?> resolved = assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(OverSameName.class));
        assertTrue(ProxyFactory.isProxyClass(resolved));
        assertTrue(declares(resolved, "shared"));
    }
}
