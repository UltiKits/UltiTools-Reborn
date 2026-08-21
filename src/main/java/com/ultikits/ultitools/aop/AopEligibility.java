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

/**
 * Decides whether a class can be proxied, and explains why not when it cannot.
 * <p>
 * Every rejection here becomes an explicit startup failure naming the class and method. None of
 * these cases may be skipped silently: a silently unproxied bean is exactly the failure mode that
 * left {@code @Transactional} inert since 6.2.0.
 * <p>
 * {@code private} and {@code static} methods are not a capability gap but a usage error. The JVM
 * dispatches them with {@code invokespecial} and {@code invokestatic}, neither of which consults
 * the subclass, so no inheritance-based AOP framework can intercept them.
 * <p>
 * 这里的每一条拒绝都会变成一次点名到类和方法的启动期失败，任何一条都不得静默跳过。
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
     * 只收集<b>方法本身</b>带 AOP 注解的方法，并遍历整个继承层级。类级注解有意不在此体现：
     * 本方法的结果会送进 check，而 check 会把不可代理的条目变成加载失败——对作者显式点名的方法
     * 是对的，对被类级注解顺带覆盖到的方法则是误伤。类级覆盖改由 AopProxyResolver 处理。
     *
     * @param beanClass the class to scan
     * @return the methods carrying a method-level AOP annotation, subclass overrides first
     */
    public static Set<Method> findAopAnnotatedMethods(Class<?> beanClass) {
        Set<Method> result = new LinkedHashSet<>();
        if (beanClass == null) {
            return result;
        }
        for (Class<? extends Annotation> annotation : AOP_ANNOTATIONS) {
            for (Method method : ReflectionUtil.getAllMethods(beanClass)) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (method.isAnnotationPresent(annotation)) {
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
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                problems.add(new Problem(Problem.Kind.STATIC_METHOD, location,
                        "Static methods have no bean instance, so transactions and exception "
                                + "handling have nothing to apply to. Remove the AOP annotation, "
                                + "or make the method an instance method."));
            } else if (Modifier.isPrivate(modifiers)) {
                problems.add(new Problem(Problem.Kind.PRIVATE_METHOD, location,
                        "Private methods are dispatched with invokespecial and cannot be "
                                + "intercepted by any inheritance-based AOP framework. Remove the "
                                + "AOP annotation, or widen the method to package-private."));
            } else if (Modifier.isFinal(modifiers)) {
                problems.add(new Problem(Problem.Kind.FINAL_METHOD, location,
                        "Remove the 'final' keyword from the method and mark it @Final instead, "
                                + "which keeps the non-overridable contract while allowing AOP."));
            }
        }
        return problems;
    }

    /**
     * Whether an inheritance-based proxy can intercept a single method.
     * <p>
     * Shares its rules with {@link #check(Class, Set)}, which is why the two must be changed
     * together. They exist separately because they serve opposite audiences. A method-level
     * annotation is an explicit request, so an unproxyable one becomes a startup failure. A
     * class-level annotation is a bulk request the author never vetted method by method, so the
     * class-level path filters with this predicate instead of failing the whole module. See
     * issue #309.
     * <p>
     * 与 check 共用同一组规则，两者必须同步修改。分开存在是因为受众相反：方法级注解是显式点名，
     * 不可代理即启动失败；类级注解是批量覆盖，作者从未逐个过目，因此改为过滤而不是让模块加载失败。
     *
     * @param method the method to test, may be null
     * @return true if an inheritance-based proxy can intercept it
     */
    public static boolean isProxyable(Method method) {
        if (method == null) {
            return false;
        }
        int modifiers = method.getModifiers();
        return !Modifier.isStatic(modifiers)
                && !Modifier.isPrivate(modifiers)
                && !Modifier.isFinal(modifiers);
    }

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
            STATIC_METHOD
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
