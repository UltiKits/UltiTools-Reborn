package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.finalviolation.validator.IllegalOverride;
import com.ultikits.testfixtures.finalviolation.validator.IllegalSubclass;
import com.ultikits.testfixtures.finalviolation.validator.IllegalSubclassWithMissingTypeMethod;
import com.ultikits.testfixtures.finalviolation.validator.OpenBase;
import com.ultikits.testfixtures.finalviolation.validator.PackagePrivateSealedBase;
import com.ultikits.testfixtures.finalviolation.validator.SealedBase;
import com.ultikits.testfixtures.finalviolation.validator.WideningOverrideOfSealedPackageMethod;
import com.ultikits.testfixtures.missingdependency.HasMethodReferencingMissingType;
import com.ultikits.testfixtures.missingdependency.MissingDependencyType;
import com.ultikits.ultitools.annotations.Final;
import com.ultikits.ultitools.aop.ProxyFactory;

@DisplayName("FinalContractValidator Tests")
class FinalContractValidatorTest {

    // SealedBase, OpenBase, IllegalSubclass and IllegalOverride are not nested here: they are
    // real, live @Final violations, and this test class lives in com.ultikits.ultitools.context -
    // a package other tests scan broadly (ComponentScannerTest, ContextConfigTest). A violation
    // compiled into this package would trip those unrelated scans for a reason that has nothing to
    // do with what they're testing. See com.ultikits.testfixtures.finalviolation.validator's
    // package-info for the full story (issue #190). LegalSubclass and Unrelated below never
    // violate anything, so they can stay colocated with the tests that use them as usual.

    public static class LegalSubclass extends OpenBase {
        @Override
        public void openMethod() { }
    }

    public static class Unrelated { }

    // --- False-positive fixtures (Minor 8 / Item 4 of the 2026-08-20 final review) --------------
    //
    // Each of these shares a method name+parameter list with a @Final method somewhere in its
    // hierarchy, but per JLS 8.4.8.1 none of them actually overrides it - they are all legal,
    // non-violating Java. None needs quarantining: only a live violation does (see the package-info
    // this file already points to above).

    public static class HasFinalMethods {
        // private, not public: a subclass declaring its own public/protected/package-private
        // method of the same name would be a compile-time "attempting to assign weaker access
        // privileges" error if this were inherited - but private members are never inherited at
        // all, so a subclass is free to declare its own, unrelated private method with the same
        // signature. That is exactly the shape this fixture pair needs to test.
        @Final
        private void sealedPrivateMethod() { }

        @Final
        public static void sealedStaticMethod() { }
    }

    /**
     * {@code private} methods never participate in overriding (invokespecial dispatch, not vtable);
     * {@link HasFinalMethods#sealedPrivateMethod()} is not even inherited here. This is a brand new,
     * unrelated private method that merely shares a name - not an override of anything.
     */
    public static class PrivateShadowsSealedMethod extends HasFinalMethods {
        private void sealedPrivateMethod() { }
    }

    /**
     * Both methods are {@code static}, so this <em>hides</em>
     * {@link HasFinalMethods#sealedStaticMethod()} rather than overriding it - a legal, distinct
     * relationship {@code @Final} was never meant to police.
     */
    public static class StaticHidesSealedMethod extends HasFinalMethods {
        public static void sealedStaticMethod() { }
    }

    /**
     * Lives in {@code com.ultikits.ultitools.context} - a <b>different</b> package than
     * {@link PackagePrivateSealedBase} ({@code com.ultikits.testfixtures.finalviolation.validator}).
     * Per JLS 8.4.8.1 its own package-private {@code sealedPackageMethod()} therefore does not
     * override the parent's: it is an unrelated method that merely shares a name and (empty)
     * parameter list.
     */
    public static class CrossPackageShadowsSealedMethod extends PackagePrivateSealedBase {
        void sealedPackageMethod() { }
    }

    @Test
    @DisplayName("Should accept a class with no @Final ancestry")
    void shouldAcceptUnrelated() {
        assertTrue(FinalContractValidator.validate(Unrelated.class).isEmpty());
    }

    @Test
    @DisplayName("Should accept the @Final class itself")
    void shouldAcceptTheSealedClassItself() {
        assertTrue(FinalContractValidator.validate(SealedBase.class).isEmpty());
    }

    @Test
    @DisplayName("Should reject extending a @Final class")
    void shouldRejectExtendingSealedClass() {
        List<String> violations = FinalContractValidator.validate(IllegalSubclass.class);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains(IllegalSubclass.class.getName()), violations.get(0));
        assertTrue(violations.get(0).contains(SealedBase.class.getName()), violations.get(0));
    }

    @Test
    @DisplayName("Should accept overriding a method that is not @Final")
    void shouldAcceptOverridingOpenMethod() {
        assertTrue(FinalContractValidator.validate(LegalSubclass.class).isEmpty());
    }

    @Test
    @DisplayName("Should reject overriding a @Final method")
    void shouldRejectOverridingSealedMethod() {
        List<String> violations = FinalContractValidator.validate(IllegalOverride.class);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("sealedMethod"), violations.get(0));
    }

    @Test
    @DisplayName("Should not flag a private method that merely shares a signature with a @Final "
            + "instance method")
    void shouldNotFlagPrivateMethodSharingSignature() {
        // private methods cannot override anything (JLS 8.4.8.1) - a same-signature private
        // method in a subclass is a distinct, unrelated method, not the override @Final forbids.
        assertTrue(FinalContractValidator.validate(PrivateShadowsSealedMethod.class).isEmpty());
    }

    @Test
    @DisplayName("Should not flag a static method that hides, rather than overrides, a @Final "
            + "static method")
    void shouldNotFlagStaticMethodHidingSealedStaticMethod() {
        // static methods are hidden, not overridden - a different, legal relationship @Final was
        // never meant to police.
        assertTrue(FinalContractValidator.validate(StaticHidesSealedMethod.class).isEmpty());
    }

    @Test
    @DisplayName("Should not flag a package-private method sharing a signature with a @Final "
            + "method declared in a different package")
    void shouldNotFlagCrossPackagePackagePrivateMethod() {
        // A package-private method is overridden only by a subclass in the SAME package
        // (JLS 8.4.8.1); CrossPackageShadowsSealedMethod is in a different package than
        // PackagePrivateSealedBase, so its own sealedPackageMethod() is an unrelated method.
        assertTrue(FinalContractValidator.validate(CrossPackageShadowsSealedMethod.class).isEmpty());
    }

    @Test
    @DisplayName("Should reject widening a @Final package-private method to public from the same "
            + "package")
    void shouldRejectSamePackageWideningOfSealedPackageMethod() {
        // JLS 8.4.8.1 puts no condition on the OVERRIDING method's access: a same-package subclass
        // may widen a package-private method to public, and that is still an override - exactly
        // what @Final forbids. Comparing the two declarations by a symmetric name-based key gives
        // them different keys (the package-private one carries its package, the public one does
        // not), so the violation used to walk straight through. See issue #190.
        List<String> violations =
                FinalContractValidator.validate(WideningOverrideOfSealedPackageMethod.class);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.get(0).contains("sealedPackageMethod"), violations.get(0));
        assertTrue(violations.get(0).contains(PackagePrivateSealedBase.class.getName()),
                violations.get(0));
    }

    @Test
    @DisplayName("Should not flag a real ByteBuddy proxy generated over a @Final class")
    void shouldIgnoreRealGeneratedProxyOfSealedClass() {
        // A proxy of a @Final class is generated by the framework itself and is legitimate:
        // @Final constrains module authors, not the container. Exercise the ProxyFactory.
        // isProxyClass branch for real by generating an actual subclass through the same factory
        // the container uses: once AOP is wired (Task 11), the container proxies @Final beans
        // exactly like this, and the proxy class IS a direct subclass of the @Final type - the
        // same shape as IllegalSubclass above.
        ProxyFactory factory = new ProxyFactory(Collections.emptyList());
        Class<? extends SealedBase> proxyClass =
                factory.createProxyClass(SealedBase.class, Collections.emptySet());

        // Sanity check: confirm the fixture is actually what it claims to be before trusting the
        // validator's answer about it.
        assertTrue(ProxyFactory.isProxyClass(proxyClass),
                "fixture must be a real generated proxy for this test to mean anything");

        assertTrue(FinalContractValidator.validate(proxyClass).isEmpty());
    }

    // --- NoClassDefFoundError tolerance (Important 2 of the 2026-08-20 final review) -------------
    //
    // getDeclaredMethods() resolves every parameter/return type, so a class whose method signature
    // references a type absent from its defining class loader throws NoClassDefFoundError right
    // there - the canonical shape of a soft-dependency integration class (Vault, PlaceholderAPI)
    // with the optional type in a method signature. Reproducing "absent from the classpath" needs a
    // class loader that genuinely cannot resolve the type, not merely a class that never references
    // it; a same-JVM, same-classpath test can't fake that by omission.
    //
    // BlockingClassLoader is deliberately NOT the bootstrap-parented style ProxyFactoryIsolationTest
    // uses: that would also cut off com.ultikits.ultitools.annotations.Final and friends, and a
    // Class loaded by a *different* loader than the one FinalContractValidator itself was loaded by
    // is - by JVM identity rules - a different type, so superclass.isAnnotationPresent(Final.class)
    // would silently return false instead of finding the real @Final. Instead, the parent is this
    // test's own (normal) class loader, so every ordinary type resolves exactly as it does anywhere
    // else in the JVM; only two names are treated specially - one explicitly blocked, and one forced
    // to be *defined by this loader* rather than quietly resolved by the parent (which, being the
    // normal test loader, would otherwise happily find it and hand back a class whose method
    // resolution never touches this loader at all).

    /**
     * Delegates to {@code parent} for everything except: {@code blockedClassName}, which it never
     * resolves (simulating a type genuinely absent from the classpath), and
     * {@code selfDefinedClassName}, which it always defines itself via {@code findClass} rather than
     * asking the parent - otherwise the parent, which really does have that class, would define it
     * first and this loader's block would never come into play.
     */
    private static final class BlockingClassLoader extends URLClassLoader {
        private final String blockedClassName;
        private final String selfDefinedClassName;

        BlockingClassLoader(URL[] urls, ClassLoader parent, String blockedClassName,
                             String selfDefinedClassName) {
            super(urls, parent);
            this.blockedClassName = blockedClassName;
            this.selfDefinedClassName = selfDefinedClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (blockedClassName.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            if (!selfDefinedClassName.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private BlockingClassLoader newLoaderHiding(Class<?> hiddenType, Class<?> targetType) {
        URL classesRoot = targetType.getProtectionDomain().getCodeSource().getLocation();
        return new BlockingClassLoader(new URL[]{classesRoot},
                FinalContractValidatorTest.class.getClassLoader(),
                hiddenType.getName(), targetType.getName());
    }

    @Test
    @DisplayName("Should not crash and should log a warning when a method signature references a "
            + "type absent from the classpath")
    void shouldToleratesMissingTypeInMethodSignature() throws Exception {
        try (BlockingClassLoader loader =
                     newLoaderHiding(MissingDependencyType.class, HasMethodReferencingMissingType.class)) {
            Class<?> isolatedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());
            assertTrue(isolatedClass.getClassLoader() == loader,
                    "fixture must actually be defined by the blocking loader, or its block is a no-op");

            // Sanity check: the fixture must actually reproduce the failure this test guards
            // against, or a green result below would mean nothing.
            assertThrows(NoClassDefFoundError.class, isolatedClass::getDeclaredMethods,
                    "fixture must actually throw NoClassDefFoundError for this test to mean anything");

            Logger validatorLogger = Logger.getLogger(FinalContractValidator.class.getName());
            List<LogRecord> capturedLogs = new ArrayList<>();
            Handler testHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    capturedLogs.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            validatorLogger.addHandler(testHandler);
            try {
                List<String> violations = FinalContractValidator.validate(isolatedClass);

                assertTrue(violations.isEmpty(),
                        "a class whose method contract could not be checked must not be reported "
                                + "as violating it: " + violations);

                List<LogRecord> warnings = new ArrayList<>();
                for (LogRecord record : capturedLogs) {
                    if (Level.WARNING.equals(record.getLevel())) {
                        warnings.add(record);
                    }
                }
                assertTrue(warnings.size() >= 1, "Expected a WARNING log when the method contract "
                        + "cannot be fully checked");
                assertTrue(warnings.stream().anyMatch(r ->
                                r.getMessage().contains(HasMethodReferencingMissingType.class.getName())),
                        "Expected the warning to name the class whose contract could not be fully "
                                + "checked");
            } finally {
                validatorLogger.removeHandler(testHandler);
            }
        }
    }

    @Test
    @DisplayName("Should still report a superclass-level violation found before the method loop "
            + "throws")
    void shouldStillReportSuperclassViolationWhenMethodLoopThrows() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                IllegalSubclassWithMissingTypeMethod.class)) {
            Class<?> isolatedClass =
                    loader.loadClass(IllegalSubclassWithMissingTypeMethod.class.getName());
            assertTrue(isolatedClass.getClassLoader() == loader,
                    "fixture must actually be defined by the blocking loader, or its block is a no-op");

            // SealedBase itself is NOT forced through this loader - it resolves via the normal
            // parent delegation, exactly as it does everywhere else, so it is the same Class object
            // FinalContractValidator's own Final.class literal was resolved against. Forcing it
            // through this loader too would give it a distinct, unrelated Final annotation type by
            // JVM classloader-identity rules, and the "extends a sealed class" check would silently
            // never match.
            assertThrows(NoClassDefFoundError.class, isolatedClass::getDeclaredMethods,
                    "fixture must actually throw NoClassDefFoundError for this test to mean anything");

            List<String> violations = FinalContractValidator.validate(isolatedClass);

            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains(IllegalSubclassWithMissingTypeMethod.class.getName()),
                    violations.get(0));
            assertTrue(violations.get(0).contains(SealedBase.class.getName()), violations.get(0));
        }
    }
}
