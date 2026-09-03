package com.ultikits.ultitools.aop;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides whether a class can be proxied, and explains why not when it cannot.
 * <p>
 * Diagnosis, not policy. {@link #check(Class, Set)} names every reason a class or one of its
 * annotated methods cannot be proxied, with a remedy for each; {@code AopProxyResolver} decides
 * what to do with them. Today it fails the load for {@code FINAL_CLASS} - nothing can subclass it,
 * so no proxy exists at all - and warns for the rest, which are single methods the proxy cannot
 * reach. That split follows Spring, which throws for a final class and ignores an annotation on a
 * method it cannot advise. Ignoring is not silence: every one is named in a warning, because an
 * annotation that quietly does nothing is what left {@code @ExceptionCatch} inert from 6.2.0 to
 * 6.3.0. {@link #isProxyable(Method, Class)} is the same rule set as a plain predicate, used to
 * decide what actually goes into the proxy. See issue #309.
 * <p>
 * {@code private} and {@code static} methods are not a capability gap but a usage error. The JVM
 * dispatches them with {@code invokespecial} and {@code invokestatic}, neither of which consults
 * the subclass, so no inheritance-based AOP framework can intercept them.
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class AopEligibility {

    /** Annotations that request interception. */
    private static final List<Class<? extends Annotation>> AOP_ANNOTATIONS =
            Collections.unmodifiableList(Arrays.asList(Transactional.class, ExceptionCatch.class));

    private AopEligibility() {
        // Utility class
    }

    /**
     * The annotations that request interception.
     * <p>
     * Exposed so that wiring code can prove every one of them is either served by an advisor or
     * explicitly declared unavailable. Without that check, an annotation nobody wired up fails
     * silently on beans that also carry an annotation that <em>is</em> wired. See issue #190.
     *
     * @return an unmodifiable view of the recognised AOP annotations
     */
    public static List<Class<? extends Annotation>> getAopAnnotations() {
        return AOP_ANNOTATIONS;
    }

    /**
     * Collects the methods that carry an AOP annotation <b>on the method itself</b>, walking the
     * whole inheritance hierarchy.
     * <p>
     * Class-level annotations are deliberately not represented here. Everything this method
     * returns is fed to {@link #check(Class, Set)}, which turns an unproxyable entry into a load
     * failure - correct for a method the author explicitly named, wrong for one a class-level
     * annotation happened to cover. Class-level coverage is resolved by
     * {@code AopProxyResolver.collectInterceptedMethods} instead. See issue #309.
     * <p>
     * Scans the class itself - callers on the startup path that already hold a
     * {@link MethodScan} should call {@link #findAopAnnotatedMethods(MethodScan)} instead, so the
     * hierarchy is not walked a second time (D-37).
     *
     * @param beanClass the class to scan
     * @return the methods carrying a method-level AOP annotation, subclass overrides first
     */
    public static Set<Method> findAopAnnotatedMethods(Class<?> beanClass) {
        if (beanClass == null) {
            return new LinkedHashSet<>();
        }
        return findAopAnnotatedMethods(MethodScan.of(beanClass));
    }

    /**
     * Same as {@link #findAopAnnotatedMethods(Class)}, but reads from a scan the caller already
     * has instead of walking the hierarchy again.
     * <p>
     * {@link AopProxyResolver#resolve(Class)} builds one {@link MethodScan} per resolve and hands
     * it to this method, the intercepted-method collector, and the diagnostic pass, instead of each
     * repeating the reflective hierarchy walk {@code MethodScan} already carries the result of
     * (D-37).
     *
     * @param scan the scan to read from
     * @return the methods carrying a method-level AOP annotation, subclass overrides first
     */
    static Set<Method> findAopAnnotatedMethods(MethodScan scan) {
        Set<Method> result = new LinkedHashSet<>();
        if (scan == null) {
            return result;
        }
        // One pass over the given scan, not one per annotation - the scan does not vary with the
        // annotation being looked for, so looping annotations on the outside avoids rescanning.
        for (Class<? extends Annotation> annotation : AOP_ANNOTATIONS) {
            for (Method method : scan.getMethods()) {
                // Includes a declaration this method overrides - that annotation governs the
                // override, which is the declaration that actually runs.
                if (AopAdvisor.findMethodLevelAnnotation(method, annotation) != null) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    /**
     * Checks whether the given class can be proxied.
     *
     * @param beanClass        the class to check
     * @param annotatedMethods the method-level annotated methods, from
     *                         {@link #findAopAnnotatedMethods(Class)}; may be empty when
     *                         interception was requested at class level only
     * @return the blocking problems, empty if the class can be proxied
     */
    public static List<Problem> check(Class<?> beanClass, Set<Method> annotatedMethods) {
        List<Problem> problems = new ArrayList<>();
        if (beanClass == null) {
            return problems;
        }

        // Checked before the empty-set short-circuit below, not after: a class carrying only a
        // class-level annotation contributes nothing to annotatedMethods, and returning early on
        // that empty set would hand a final class to ByteBuddy, which throws without naming the
        // class or a remedy. Callers must only call check(...) when the bean actually needs a
        // proxy. See issue #309.
        if (Modifier.isFinal(beanClass.getModifiers())) {
            problems.add(new Problem(Problem.Kind.FINAL_CLASS, beanClass.getName(),
                    "Remove the 'final' keyword from the class and mark it @Final instead, "
                            + "which keeps the non-extendable contract while allowing AOP. "
                            + "If the class is generated by Lombok @Value or @UtilityClass, "
                            + "switch to @Data - Lombok also emits 'final'."));
            // A final class blocks everything else; method-level findings would be noise.
            return problems;
        }

        if (annotatedMethods == null || annotatedMethods.isEmpty()) {
            return problems;
        }

        for (Method method : annotatedMethods) {
            // The declaring class, not the bean: now that the scan walks the hierarchy the
            // method may come from a superclass, and naming the bean would point the author
            // at a file that does not contain the annotation being complained about.
            String location = method.getDeclaringClass().getName() + "#" + method.getName();
            // Rule order matches the previous if/else-if chain exactly: the first rule that
            // violates wins, and every later rule is skipped for this method - see Rule's own
            // javadoc for why that order is fixed, not merely conventional.
            for (Rule rule : RULES) {
                if (rule.violates(method, beanClass)) {
                    problems.add(new Problem(rule.kind, location, rule.describe(method, beanClass)));
                    break;
                }
            }
        }
        return problems;
    }

    /**
     * Whether an inheritance-based proxy generated for {@code beanClass} can intercept the given
     * method - meaning it can both override it and reach the original through {@code super}.
     * <p>
     * Walks the same {@link Rule} enum as {@link #check(Class, Set)}, which is what replaces the
     * old javadoc sentence asking a human to keep the two in sync: adding a sixth rule fails to
     * compile until it supplies both {@code violates()} and {@code describe()}, so the two callers
     * cannot drift apart again. They exist separately because they serve opposite audiences. A
     * method-level annotation is an explicit request, so an unproxyable one becomes a startup
     * failure. A class-level annotation is a bulk request the author never vetted method by method,
     * so the class-level path filters with this predicate instead of failing the whole module.
     * <p>
     * Only {@link Rule#violates(Method, Class)} runs here - never {@link Rule#describe(Method,
     * Class)}, which builds the remediation string and is reserved for {@link #check}'s reporting
     * path. This method runs once per method per bean on the startup path, so it must cost nothing
     * to call even for a method that fails every rule. See issue #309 and issue #317.
     * <p>
     * The bean class is a parameter rather than being derived from the method because two of the
     * five rules are relative to it. A method is not intrinsically unproxyable; it is unproxyable
     * <em>from a particular subclass</em>. See issue #309.
     *
     * @param method    the method to test, may be null
     * @param beanClass the class the proxy will extend, may be null
     * @return true if an inheritance-based proxy can intercept it
     */
    public static boolean isProxyable(Method method, Class<?> beanClass) {
        if (method == null || beanClass == null) {
            return false;
        }
        for (Rule rule : RULES) {
            if (rule.violates(method, beanClass)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the proxy could not even see the method: it is package-private and declared in a
     * different package than the class the proxy extends.
     * <p>
     * The generated proxy lives in the bean's package, so a package-private declaration from
     * elsewhere is neither overridable nor reachable through {@code super}. The test delegates to
     * {@code ReflectionUtil} rather than re-deriving it, because {@code ReflectionUtil.overrides}
     * decides the same question when it collapses override slots. Two copies reading different
     * sources would let the scan treat two declarations as one method while this treats them as
     * different packages.
     */
    private static boolean isInaccessible(Method method, Class<?> beanClass) {
        return ReflectionUtil.isPackagePrivate(method.getModifiers())
                && !ReflectionUtil.packageNameOf(method.getDeclaringClass())
                        .equals(ReflectionUtil.packageNameOf(beanClass));
    }

    /**
     * Whether a class between the bean and the method's declaring class already declares that
     * exact name and parameter list, which makes the candidate unreachable through {@code super}.
     * <p>
     * This is the erased half of a generic override. Given {@code Base<T>.take(T)} and
     * {@code Child extends Base<String>} overriding {@code take(String)}, the compiler emits a
     * bridge {@code Child.take(Object)}. {@code ReflectionUtil.getAllMethods} skips bridges and
     * compares erased parameter types, so {@code take(String)} does not collapse
     * {@code take(Object)} and both come back as separate methods - but only the first is
     * {@code super}-invokable. Bridge and synthetic declarations are deliberately included in the
     * scan here, because they are exactly what does the shadowing.
     *
     * @param method    the method to test
     * @param beanClass the class the proxy will extend
     * @return true if a nearer declaration makes it unreachable through {@code super}
     */
    private static boolean isShadowed(Method method, Class<?> beanClass) {
        if (method == null || beanClass == null) {
            return false;
        }
        Class<?> declaring = method.getDeclaringClass();
        for (Class<?> current = beanClass;
             current != null && current != declaring && current != Object.class;
             current = current.getSuperclass()) {
            for (Method candidate : current.getDeclaredMethods()) {
                if (candidate.getName().equals(method.getName())
                        && Arrays.equals(candidate.getParameterTypes(),
                                         method.getParameterTypes())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Counts how many times a rule's remediation text has been built, process-wide.
     * <p>
     * Exists only so {@link #isProxyable(Method, Class)}'s own test can prove it never reaches
     * {@link Rule#describe(Method, Class)}: a counter that stays at zero across a call is stronger
     * evidence than reading the method body, because it also catches a future edit that
     * accidentally reintroduces the cost. Nothing in production code reads this value.
     */
    private static final AtomicInteger DESCRIBE_CALLS = new AtomicInteger();

    /**
     * Test-only read of {@link #DESCRIBE_CALLS}. Package-private and read-only: nothing outside
     * this package, and nothing in production code at all, depends on its value.
     *
     * @return how many times a rule's remediation text has been built since the JVM started
     */
    static int describeInvocationCountForTesting() {
        return DESCRIBE_CALLS.get();
    }

    /**
     * The five proxy-eligibility rules, unified per D-36.
     * <p>
     * {@link #check(Class, Set)} and {@link #isProxyable(Method, Class)} used to restate these
     * five conditions as two separately maintained {@code if}/{@code else if} chains, kept in sync
     * only by a javadoc sentence asking a human to remember. Each constant here supplies both
     * halves - {@link #violates(Method, Class)} and {@link #describe(Method, Class)} - as abstract
     * members, so a sixth rule fails to compile until both exist. That is what replaces the
     * sentence.
     * <p>
     * The two halves have deliberately different costs. {@code violates} does no string work at
     * all and is safe to call once per method per bean on the startup path. {@code describe} builds
     * the remediation text by concatenation and is called only when {@code violates} already
     * returned true, and only from {@link #check}'s reporting path - never from
     * {@link #isProxyable}, which is the boolean query. Issue #317's literal proposal, a single
     * method returning a {@link Problem} with {@code isProxyable} defined as that result being
     * absent, is rejected for exactly this reason: it would build and discard every rule's
     * diagnostic text on every boolean call. See issue #309.
     * <p>
     * Enum order is the previous chain's order, and {@link #check} and {@link #isProxyable} both
     * stop at the first violated rule - preserving the old chain's else-if semantics, where only
     * one problem is ever reported per method.
     */
    private enum Rule {
        /** Static methods are dispatched with {@code invokestatic}, which bypasses the proxy. */
        STATIC_METHOD(Problem.Kind.STATIC_METHOD) {
            @Override
            boolean violates(Method method, Class<?> beanClass) {
                return Modifier.isStatic(method.getModifiers());
            }

            @Override
            String describeViolation(Method method, Class<?> beanClass) {
                return "Static methods have no bean instance, so transactions and exception "
                        + "handling have nothing to apply to. Remove the AOP annotation, "
                        + "or make the method an instance method.";
            }
        },
        /** Private methods are dispatched with {@code invokespecial}, which bypasses the proxy. */
        PRIVATE_METHOD(Problem.Kind.PRIVATE_METHOD) {
            @Override
            boolean violates(Method method, Class<?> beanClass) {
                return Modifier.isPrivate(method.getModifiers());
            }

            @Override
            String describeViolation(Method method, Class<?> beanClass) {
                // Package-private is only a valid remedy when the declaration is in the bean's own
                // package; suggesting it across packages would send the author straight into an
                // INACCESSIBLE_METHOD failure on the next startup.
                boolean samePackage = ReflectionUtil.packageNameOf(method.getDeclaringClass())
                        .equals(ReflectionUtil.packageNameOf(beanClass));
                return "Private methods are dispatched with invokespecial and cannot be "
                        + "intercepted by any inheritance-based AOP framework. Remove the "
                        + "AOP annotation, or widen the method to "
                        + (samePackage ? "package-private." : "protected or public - it is "
                                + "declared in a different package than the bean, so "
                                + "package-private would still be unreachable.");
            }
        },
        /** Final methods cannot be overridden, so the proxy cannot intercept them. */
        FINAL_METHOD(Problem.Kind.FINAL_METHOD) {
            @Override
            boolean violates(Method method, Class<?> beanClass) {
                return Modifier.isFinal(method.getModifiers());
            }

            @Override
            String describeViolation(Method method, Class<?> beanClass) {
                // Same trap as the private rule above: dropping 'final' on a package-private
                // method declared elsewhere leaves it unreachable for a second, different reason.
                return "Remove the 'final' keyword from the method and mark it @Final instead, "
                        + "which keeps the non-overridable contract while allowing AOP."
                        + (isInaccessible(method, beanClass)
                                ? " It is also package-private and declared in a different "
                                        + "package than the bean, so it must be widened to "
                                        + "protected or public as well."
                                : "");
            }
        },
        /**
         * Package-private and declared in a different package than the bean - relative to the bean
         * class, not intrinsic to the method.
         */
        INACCESSIBLE_METHOD(Problem.Kind.INACCESSIBLE_METHOD) {
            @Override
            boolean violates(Method method, Class<?> beanClass) {
                return isInaccessible(method, beanClass);
            }

            @Override
            String describeViolation(Method method, Class<?> beanClass) {
                return "The generated proxy lives in "
                        + ReflectionUtil.packageNameOf(beanClass) + ", so it cannot "
                        + "override a package-private method declared in "
                        + ReflectionUtil.packageNameOf(method.getDeclaringClass())
                        + ". Widen the method to "
                        + "protected or public, move the two classes into one package, or "
                        + "remove the AOP annotation.";
            }
        },
        /**
         * Hidden by a nearer declaration - usually the bridge method a generic override generates
         * - also relative to the bean class.
         */
        SHADOWED_METHOD(Problem.Kind.SHADOWED_METHOD) {
            @Override
            boolean violates(Method method, Class<?> beanClass) {
                return isShadowed(method, beanClass);
            }

            @Override
            String describeViolation(Method method, Class<?> beanClass) {
                return "A nearer declaration - usually the bridge method a generic override "
                        + "generates - hides this one, so the proxy cannot reach it "
                        + "through super. Move the annotation onto the overriding method, "
                        + "which is the declaration that actually runs.";
            }
        };

        private final Problem.Kind kind;

        Rule(Problem.Kind kind) {
            this.kind = kind;
        }

        /**
         * Cheap, string-free predicate. Safe to call once per method per bean on the startup path.
         *
         * @param method    the annotated method
         * @param beanClass the class the proxy would extend
         * @return true if this method violates this rule
         */
        abstract boolean violates(Method method, Class<?> beanClass);

        /**
         * Builds this rule's remediation text. Only ever called after {@link #violates} already
         * returned true for the same arguments.
         *
         * @param method    the annotated method
         * @param beanClass the class the proxy would extend
         * @return actionable instructions for the module author
         */
        abstract String describeViolation(Method method, Class<?> beanClass);

        /**
         * Builds this rule's remediation text and records that it did so.
         * <p>
         * The only caller is {@link #check(Class, Set)}'s reporting path; {@link #isProxyable} must
         * never reach this method. {@link #DESCRIBE_CALLS} is what lets a test prove that directly
         * rather than only by reading the method body.
         */
        final String describe(Method method, Class<?> beanClass) {
            DESCRIBE_CALLS.incrementAndGet();
            return describeViolation(method, beanClass);
        }
    }

    /** Cached once; {@link Rule#values()} clones its backing array on every call. */
    private static final Rule[] RULES = Rule.values();

    /**
     * A single reason a class cannot be proxied.
     */
    public static final class Problem {

        /** The category of the problem. */
        public enum Kind {
            /** The class itself is final and cannot be subclassed. */
            FINAL_CLASS,
            /** An annotated method is final and cannot be overridden. */
            FINAL_METHOD,
            /** An annotated method is private and cannot be intercepted. */
            PRIVATE_METHOD,
            /** An annotated method is static and has no bean instance. */
            STATIC_METHOD,
            /** An annotated method is package-private and declared in another package. */
            INACCESSIBLE_METHOD,
            /** An annotated method is hidden by a nearer declaration, usually a bridge. */
            SHADOWED_METHOD
        }

        private final Kind kind;
        private final String location;
        private final String remedy;

        Problem(Kind kind, String location, String remedy) {
            this.kind = kind;
            this.location = location;
            this.remedy = remedy;
        }

        /**
         * @return the category of this problem
         */
        public Kind getKind() {
            return kind;
        }

        /**
         * @return the fully qualified class name, or {@code class#method} for method-level problems
         */
        public String getLocation() {
            return location;
        }

        /**
         * @return actionable instructions for the module author
         */
        public String getRemedy() {
            return remedy;
        }

        @Override
        public String toString() {
            return kind + " at " + location + ": " + remedy;
        }
    }
}
