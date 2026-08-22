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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
import com.ultikits.ultitools.aop.chainy.C;
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

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelWithUnproxyable {
        private String privateHelper() { return "private"; }
        public static String staticHelper() { return "static"; }
        public final String finalHelper() { return "final"; }
        public String ordinary() { throw new IllegalStateException("ordinary-boom"); }
    }

    @Data
    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelWithLombok {
        private String name;
        public String own() { throw new IllegalStateException("own-boom"); }
    }

    public static class PlainAncestor {
        public String ancestorOnly() { return "ancestor"; }
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class AnnotatedSubclass extends PlainAncestor {
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

    /** The class-level annotation lives on GenericBase, so its own declarations are in scope. */
    public static class OverGeneric extends GenericBase<String> {
        @Override public void take(String value) { }
    }

    /** The class-level annotation lives on PackagePrivateBase, in another package. */
    public static class OverPackagePrivate extends PackagePrivateBase { }

    /** The annotation is on the inaccessible method itself, so the load must fail. */
    public static class OverAnnotatedPackagePrivate extends AnnotatedPackagePrivateBase { }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class OverSameName extends SameNameBase {
        public void shared() { }
    }

    public abstract static class GenericAnnotatedBase<T> {
        @ExceptionCatch(silent = true, defaultValue = "generic")
        public abstract void handle(T value);
    }

    public static class ConcreteHandler extends GenericAnnotatedBase<String> {
        @Override public void handle(String value) { }
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
    @Transactional
    public static class ClassLevelTransactionalBase {
        public void governedByClassLevel() { }
    }

    public static class InheritsClassLevelTransactional extends ClassLevelTransactionalBase { }

    // A class-level annotation on an ancestor governs that ancestor's methods, and the bean
    // inherits them - @ExceptionCatch is intercepted in exactly this shape. The refusal has to
    // agree, or the two annotations behave differently for identical code: one intercepted, the
    // other silently inert, which is the failure the refusal exists to eliminate.
    @Test
    @DisplayName("Should refuse a bean governed by a class-level @Transactional on a superclass")
    void shouldRefuseClassLevelTransactionalOnSuperclass() {
        AopProxyResolver bare = new AopProxyResolver();
        bare.addUnavailableAnnotation(Transactional.class, "not available in this release");

        ContainerException thrown = assertThrows(ContainerException.class,
                () -> bare.resolve(InheritsClassLevelTransactional.class));
        assertTrue(thrown.getMessage().contains(ClassLevelTransactionalBase.class.getName()),
                "the refusal must name the class that carries the annotation: "
                        + thrown.getMessage());
    }

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
                () -> exceptionCatchResolver().resolve(ClassLevelWithUnproxyable.class));
        assertTrue(ProxyFactory.isProxyClass(resolved));

        assertTrue(declares(resolved, "ordinary"),
                "the one proxyable method must still be intercepted");
        assertFalse(declares(resolved, "privateHelper"));
        assertFalse(declares(resolved, "staticHelper"));
        assertFalse(declares(resolved, "finalHelper"));
    }

    // The scope rule: a class-level annotation is a default for the class that declares it and
    // for its subclasses, never for its ancestors. Matches Spring's documented behaviour and is
    // what keeps coverage off every framework base class a module bean happens to extend.
    @Test
    @DisplayName("Should not let a class-level annotation reach up into an ancestor")
    void shouldNotCoverAncestorDeclarations() {
        Class<?> resolved = exceptionCatchResolver().resolve(AnnotatedSubclass.class);
        assertTrue(declares(resolved, "own"), "the class's own method is covered");
        assertFalse(declares(resolved, "ancestorOnly"),
                "the ancestor declared it, so the subclass's annotation must not reach it");
    }

    // Swallowing equals returns false and the caller's HashMap then misses a key it holds. A
    // propagating exception at least reaches someone. canEqual is in the list because the proxy
    // overrides it and the superclass equals reaches it by virtual dispatch, so excluding equals
    // alone excludes nothing. See issue #309.
    @Test
    @DisplayName("Should never let a class-level annotation cover equals, hashCode or canEqual")
    void shouldExcludeSilentWrongAnswerSignatures() {
        Class<?> resolved = exceptionCatchResolver().resolve(ClassLevelWithLombok.class);
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
        Class<?> resolved = exceptionCatchResolver().resolve(ClassLevelWithLombok.class);
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

    // An annotation on a method no proxy can reach is ignored rather than fatal, which is what
    // Spring does for the same situation. It is not ignored quietly: the method is named in a
    // warning, so the author still learns the annotation is doing nothing. Failing the load put
    // the stop on whoever extended the class rather than whoever wrote the annotation, and the
    // remedy named a file they may not own.
    @Test
    @DisplayName("Should ignore, not refuse, a method-level annotation on an unreachable method")
    void shouldIgnoreAnnotationOnUnreachableMethod() {
        Class<?> resolved = assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(OverAnnotatedPackagePrivate.class));
        // The class itself, not a proxy: asserting only that the proxy does not declare the method
        // would have passed no matter what, since no proxy is generated at all here.
        assertSame(OverAnnotatedPackagePrivate.class, resolved,
                "nothing is interceptable, so the bean class is returned unchanged");
    }

    public static class OwnPrivateAnnotated {
        @ExceptionCatch(silent = true)
        private void helper() { }
        public void touch() { helper(); }
    }

    // Same rule whether the author owns the declaration or not: one behaviour, one warning.
    @Test
    @DisplayName("Should ignore a method-level annotation on the bean's own private method")
    void shouldIgnoreAnnotationOnOwnPrivateMethod() {
        assertDoesNotThrow(() -> exceptionCatchResolver().resolve(OwnPrivateAnnotated.class));
    }

    // A final class is the one case that still fails: nothing can be subclassed, so interception
    // is impossible rather than merely out of reach. Spring throws AopConfigException here too.
    @Test
    @DisplayName("Should still refuse a final class, which cannot be subclassed at all")
    void shouldStillRefuseFinalClass() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> exceptionCatchResolver().resolve(FinalWithClassLevel.class));
        assertTrue(thrown.getMessage().contains("FINAL_CLASS"), thrown.getMessage());
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static final class FinalWithClassLevel {
        public String risky() { throw new IllegalStateException("boom"); }
    }

    // Cross-package plus package-private means the two declarations do not override one another,
    // so both survive the scan and both would map to trampoline ultitools$super$shared. The
    // annotation is on SameNameBase so that both are genuinely in class-level scope - without it
    // the scope rule alone would keep the superclass declaration out and this would pass while
    // testing nothing. Two independent rules now prevent the collision: the superclass
    // declaration is inaccessible from the bean's package, and it is shadowed by the subclass's.
    @Test
    @DisplayName("Should not collide trampolines when two declarations share a signature")
    void shouldNotCollideTrampolines() {
        Class<?> resolved = assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(OverSameName.class));
        assertTrue(ProxyFactory.isProxyClass(resolved));
        assertTrue(declares(resolved, "shared"));
    }
    // The erased half of a generic override is shadowed by the compiler's bridge, so it cannot be
    // intercepted - but it is a compiler artifact, not an author error. Reporting it as a
    // load-blocking problem told the author to move an annotation that already sits on the only
    // declaration they wrote. alpha loads this bean; refusing it here would be a regression.
    @Test
    @DisplayName("Should not fail the load over an annotation on an erased generic declaration")
    void shouldNotRefuseAnnotatedErasedDeclaration() {
        assertDoesNotThrow(() -> exceptionCatchResolver().resolve(ConcreteHandler.class));
    }
    public static class PlainHashCode {
        @Override public int hashCode() { return 7; }
    }

    // A pointcut advisor has no annotation type, so it cannot be a "bulk request the author never
    // vetted method by method" - it named the method in code. The exclusion list is about what a
    // class-level annotation sweeps up, and must not silently drop a hand-written pointcut.
    @Test
    @DisplayName("Should honour a pointcut advisor that deliberately targets an excluded signature")
    void shouldHonourPointcutOnExcludedSignature() {
        AopProxyResolver pointcutResolver = new AopProxyResolver();
        pointcutResolver.addAdvisor(new AopAdvisor() {
            @Override
            public boolean matches(java.lang.reflect.Method method, Class<?> targetClass) {
                return "hashCode".equals(method.getName());
            }

            @Override
            public MethodInterceptor getInterceptor() {
                return MethodInvocation::proceed;
            }
        });

        Class<?> resolved = pointcutResolver.resolve(PlainHashCode.class);
        assertTrue(ProxyFactory.isProxyClass(resolved));
        assertTrue(declares(resolved, "hashCode"),
                "the exclusion list governs class-level annotation coverage only");
    }
    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelOverOwnHashCode {
        @Override
        public int hashCode() { throw new IllegalStateException("hash-boom"); }
    }

    // The exclusion was enforced only while collecting, so a second advisor could pull hashCode
    // into the intercepted set and the ExceptionCatch advisor would then match it again at
    // invocation time - through its class-level branch, which never consulted the exclusion - and
    // swallow the exception into a 0. Spring routes equals/hashCode to callbacks that structurally
    // cannot run advice; the equivalent here is to make the pointcut itself say no, so collection
    // and invocation cannot answer differently.
    @Test
    @DisplayName("Should not swallow hashCode even when another advisor pulls it into the proxy")
    void shouldNotSwallowExcludedSignatureViaSecondAdvisor() throws Exception {
        AopProxyResolver two = new AopProxyResolver();
        two.addAdvisor(new AopAdvisor() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return "hashCode".equals(method.getName());
            }

            @Override
            public MethodInterceptor getInterceptor() {
                return MethodInvocation::proceed;
            }

            @Override
            public int getOrder() {
                return 0;
            }
        });
        two.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class,
                new ExceptionInterceptor(Collections.emptyList(), null), 200));

        Class<?> resolved = two.resolve(ClassLevelOverOwnHashCode.class);
        assertTrue(declares(resolved, "hashCode"),
                "precondition: the pointcut advisor must have pulled hashCode into the proxy, "
                        + "or this test asserts nothing");

        Object bean = resolved.getDeclaredConstructor().newInstance();
        java.lang.reflect.InvocationTargetException wrapped = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> resolved.getMethod("hashCode").invoke(bean));
        assertTrue(wrapped.getCause() instanceof IllegalStateException,
                "a class-level @ExceptionCatch must never turn hashCode into a silent 0, whichever "
                        + "advisor put it in the proxy: " + wrapped.getCause());
    }
    public static class UnannotatedAncestor {
        public String work() { throw new IllegalStateException("boom"); }
    }

    @Transactional
    public static class TransactionalDeclaringNothing extends UnannotatedAncestor { }

    @ExceptionCatch(silent = true, defaultValue = "ec")
    public static class ExceptionCatchDeclaringNothing extends UnannotatedAncestor { }

    // A class-level annotation on a class that declares no methods of its own governs nothing,
    // because class-level scope does not reach ancestors. The refusal has to agree with the
    // interception: previously @Transactional refused this shape while @ExceptionCatch quietly
    // covered nothing, so identical code behaved oppositely depending on the annotation - which
    // is exactly what COMPATIBILITY.md tells module authors cannot happen.
    @Test
    @DisplayName("Should treat a class-level annotation that governs nothing the same either way")
    void shouldTreatBothAnnotationsAlikeWhenNothingIsGoverned() {
        AopProxyResolver bare = new AopProxyResolver();
        bare.addUnavailableAnnotation(Transactional.class, "not available in this release");
        assertDoesNotThrow(() -> bare.resolve(TransactionalDeclaringNothing.class),
                "the annotation governs no method, so there is nothing to refuse");

        Class<?> resolved = exceptionCatchResolver().resolve(ExceptionCatchDeclaringNothing.class);
        assertSame(ExceptionCatchDeclaringNothing.class, resolved,
                "the same shape covers nothing for @ExceptionCatch, so it is not proxied");
    }
    public static class AnnotatedThenOverridden {
        @ExceptionCatch(silent = true, defaultValue = "from-base")
        public String load() { throw new IllegalStateException("base-boom"); }
    }

    public static class OverridesAnnotated extends AnnotatedThenOverridden {
        @Override public String load() { throw new IllegalStateException("child-boom"); }
    }

    public static class TransactionalThenOverridden {
        @Transactional public void save() { }
    }

    public static class OverridesTransactional extends TransactionalThenOverridden {
        @Override public void save() { }
    }

    // Java does not inherit method annotations and the scan keeps only the most derived
    // declaration, so an override used to hide the annotation entirely: no interception, and for
    // @Transactional no refusal either - the silent-inert shape this whole branch exists to close.
    // Spring's attribute lookup falls back from the target method to the declaring method for the
    // same reason. The override is the declaration that actually runs, so it is the one proxied.
    @Test
    @DisplayName("Should honour an annotation on a superclass declaration the bean overrides")
    void shouldHonourAnnotationOnAnOverriddenDeclaration() throws Exception {
        Class<?> resolved = exceptionCatchResolver().resolve(OverridesAnnotated.class);
        assertTrue(ProxyFactory.isProxyClass(resolved));

        Object bean = resolved.getDeclaredConstructor().newInstance();
        assertEquals("from-base", resolved.getMethod("load").invoke(bean),
                "the annotation on the overridden declaration governs the override that runs");
    }

    @Test
    @DisplayName("Should refuse an unavailable annotation on an overridden declaration too")
    void shouldRefuseUnavailableAnnotationOnAnOverriddenDeclaration() {
        AopProxyResolver bare = new AopProxyResolver();
        bare.addUnavailableAnnotation(Transactional.class, "not available in this release");

        ContainerException thrown = assertThrows(ContainerException.class,
                () -> bare.resolve(OverridesTransactional.class));
        assertTrue(thrown.getMessage().contains("save"), thrown.getMessage());
    }
    // resolve() runs inside SimpleContainer.createBean, which runs per instantiation - so for a
    // prototype-scoped bean this used to repeat the whole analysis, re-emit the "annotation
    // ignored" warning the javadoc calls a startup warning, and hand back a brand new ByteBuddy
    // class on every getBean(). Reusing the class is also what stops a prototype bean from
    // accumulating one generated class per instance.
    @Test
    @DisplayName("Should resolve a bean class once and reuse the answer")
    void shouldMemoizeResolution() {
        AopProxyResolver reused = exceptionCatchResolver();
        Class<?> first = reused.resolve(ClassLevelWithLombok.class);
        Class<?> second = reused.resolve(ClassLevelWithLombok.class);
        assertSame(first, second, "a second resolve must not generate another proxy class");
    }

    // The memo has to lose its answers when the advisors change, or a resolver reconfigured after
    // first use would keep handing out proxies built for the old configuration.
    @Test
    @DisplayName("Should discard the memo when the advisor set changes")
    void shouldDiscardMemoOnReconfiguration() {
        AopProxyResolver reused = exceptionCatchResolver();
        Class<?> proxied = reused.resolve(ClassLevelWithLombok.class);
        assertTrue(ProxyFactory.isProxyClass(proxied));

        reused.removeAdvisor(reused.getAdvisors().get(0));
        assertSame(ClassLevelWithLombok.class, reused.resolve(ClassLevelWithLombok.class),
                "with no advisor left there is nothing to intercept");
    }
    // Overriding is transitive (JLS 8.4.8.1). chainx.A declares a package-private annotated m(),
    // chainx.B widens it to public, and chainy.C overrides that from another package. C does not
    // override A directly - the packages differ - but does so through B, and getAllMethods folds
    // all three into one method. Testing candidates against the leaf alone lost A's annotation
    // entirely, which is the same trap ReflectionUtil.getAllMethods documents for its own slots.
    @Test
    @DisplayName("Should find an annotation reachable only through a transitive override chain")
    void shouldFollowATransitiveOverrideChain() throws Exception {
        Class<?> resolved = exceptionCatchResolver().resolve(C.class);
        assertTrue(ProxyFactory.isProxyClass(resolved),
                "the annotation on chainx.A governs chainy.C's override through chainx.B");

        Object bean = resolved.getDeclaredConstructor().newInstance();
        assertEquals("from-A", resolved.getMethod("m").invoke(bean));
    }

    @Transactional
    public static class TransactionalOverUnproxyableOnly {
        private void helper() { }
        public static void statik() { }
        public final void fin() { }
    }

    @ExceptionCatch(silent = true)
    public static class ExceptionCatchOverUnproxyableOnly {
        private void helper() { }
        public static void statik() { }
        public final void fin() { }
    }

    // The refusal answers a different question from interception, on purpose. Interception asks
    // what the proxy will cover; the refusal asks whether the module uses an annotation this
    // release cannot honour, and answers fail-closed. Narrowing it to coverage was tried and
    // reverted: it let @Transactional on a private method load and run its writes with no
    // transaction, while @ExceptionCatch degrading to "the exception propagates" stays visible.
    @Test
    @DisplayName("Should refuse on the presence of an unavailable annotation, not on its coverage")
    void shouldRefuseOnPresenceNotCoverage() {
        AopProxyResolver bare = new AopProxyResolver();
        bare.addUnavailableAnnotation(Transactional.class, "not available in this release");
        assertThrows(ContainerException.class,
                () -> bare.resolve(TransactionalOverUnproxyableOnly.class),
                "the annotation is written here and cannot work, which is what must be reported");

        assertSame(ExceptionCatchOverUnproxyableOnly.class,
                exceptionCatchResolver().resolve(ExceptionCatchOverUnproxyableOnly.class),
                "the wired annotation covers nothing and simply does not proxy");
    }

    public static final class FinalWithUnreachableAnnotation {
        @ExceptionCatch(silent = true)
        private void helper() { }
        public void touch() { helper(); }
    }

    // A final class blocks the load only when something would actually have been proxied. The
    // annotation here sits on a method no proxy could reach even if the class were not final, so
    // dropping 'final' would not make it work and refusing on that grounds sends the author after
    // the wrong thing. COMPATIBILITY.md tells authors this shape loads with a warning.
    @Test
    @DisplayName("Should not refuse a final class whose only annotation is on an unreachable method")
    void shouldNotRefuseFinalClassWhenNothingWouldBeProxied() {
        assertDoesNotThrow(
                () -> exceptionCatchResolver().resolve(FinalWithUnreachableAnnotation.class));
    }
    public static class OnlyUnreachableAnnotated {
        @ExceptionCatch(silent = true)
        private void helper() { }
        public void touch() { helper(); }
    }

    /** Captures WARNING records from the resolver's own logger for the duration of one call. */
    private static List<String> warningsWhile(Runnable body) {
        Logger target = Logger.getLogger(AopProxyResolver.class.getName());
        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(record.getMessage());
                }
            }

            @Override public void flush() { }

            @Override public void close() { }
        };
        target.addHandler(handler);
        try {
            body.run();
        } finally {
            target.removeHandler(handler);
        }
        return captured;
    }

    // Asserting on the log because the behaviour under test is "something is said". Gating the
    // diagnosis on what would actually be proxied - a fix for a final class being refused when
    // nothing was proxyable - silently took the warning with it, for exactly the shape
    // COMPATIBILITY.md promises a warning for. Nothing else would have caught that.
    @Test
    @DisplayName("Should warn when the only annotated method is one no proxy can reach")
    void shouldWarnAboutAnUnreachableAnnotatedMethod() {
        List<String> warnings = warningsWhile(
                () -> exceptionCatchResolver().resolve(OnlyUnreachableAnnotated.class));
        assertFalse(warnings.isEmpty(), "the annotation does nothing here and must be reported");
        assertTrue(warnings.toString().contains("helper"),
                "the warning must name the method: " + warnings);
    }

    public static class MethodAnnotatedBase {
        @ExceptionCatch(silent = true, defaultValue = "from-super-method")
        public String work() { throw new IllegalStateException("boom"); }
    }

    @ExceptionCatch(silent = true, defaultValue = "from-own-class")
    public static class OverridesWithOwnClassLevel extends MethodAnnotatedBase {
        @Override public String work() { throw new IllegalStateException("boom"); }
    }

    // Presence and precedence are different questions. Both annotations apply here, and Spring's
    // order decides which one supplies the attributes: the method itself, then the target class,
    // then the declaration it overrides. The subclass author is closer to the bean than whoever
    // wrote the superclass method, so their class-level default wins.
    @Test
    @DisplayName("Should prefer the target class over a superclass method declaration")
    void shouldPreferTargetClassOverInheritedMethodAnnotation() throws Exception {
        Class<?> resolved = exceptionCatchResolver().resolve(OverridesWithOwnClassLevel.class);
        Object bean = resolved.getDeclaredConstructor().newInstance();
        assertEquals("from-own-class", resolved.getMethod("work").invoke(bean));
    }
}
