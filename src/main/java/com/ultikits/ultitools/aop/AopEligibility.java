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
 * <p>
 * 这里只做诊断，不做决策。check 列出每一条不可代理的原因与补救说明，由 AopProxyResolver 决定
 * 如何处置：final 类让加载失败（根本无法生成子类），其余每一条都是「某个方法够不着」，改为忽略
 * 并打警告。这与 Spring 一致——它对 final 类抛异常，对织不进去的方法上的注解则忽略。忽略不等于
 * 静默：每一条都会被点名，因为一个悄悄不起作用的注解，正是让 @ExceptionCatch 从 6.2.0 到
 * 6.3.0 长期失效的那种失败方式。isProxyable 是同一组规则的纯谓词形式。见 issue #309。
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
        // One hierarchy walk, not one per annotation. getAllMethods builds an override-slot map
        // over every declared method of every superclass, and the result does not vary with the
        // annotation being looked for; calling it inside the loop repeated that work for each
        // entry in AOP_ANNOTATIONS, on the startup path, for every bean of every module.
        // getAllMethods already drops bridge and synthetic declarations.
        List<Method> methods = ReflectionUtil.getAllMethods(beanClass);
        for (Class<? extends Annotation> annotation : AOP_ANNOTATIONS) {
            for (Method method : methods) {
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
                // Package-private is only a valid remedy when the declaration is in the bean's own
                // package; suggesting it across packages would send the author straight into an
                // INACCESSIBLE_METHOD failure on the next startup.
                boolean samePackage = ReflectionUtil.packageNameOf(method.getDeclaringClass())
                        .equals(ReflectionUtil.packageNameOf(beanClass));
                problems.add(new Problem(Problem.Kind.PRIVATE_METHOD, location,
                        "Private methods are dispatched with invokespecial and cannot be "
                                + "intercepted by any inheritance-based AOP framework. Remove the "
                                + "AOP annotation, or widen the method to "
                                + (samePackage ? "package-private." : "protected or public - it is "
                                        + "declared in a different package than the bean, so "
                                        + "package-private would still be unreachable.")));
            } else if (Modifier.isFinal(modifiers)) {
                problems.add(new Problem(Problem.Kind.FINAL_METHOD, location,
                        "Remove the 'final' keyword from the method and mark it @Final instead, "
                                + "which keeps the non-overridable contract while allowing AOP."));
            } else if (isInaccessible(method, beanClass)) {
                problems.add(new Problem(Problem.Kind.INACCESSIBLE_METHOD, location,
                        "The generated proxy lives in "
                                + ReflectionUtil.packageNameOf(beanClass) + ", so it cannot "
                                + "override a package-private method declared in "
                                + ReflectionUtil.packageNameOf(method.getDeclaringClass())
                                + ". Widen the method to "
                                + "protected or public, move the two classes into one package, or "
                                + "remove the AOP annotation."));
            }
            // Shadowing is deliberately not a problem. What shadows a declaration is a bridge the
            // compiler generated, not anything the author wrote: an @ExceptionCatch on an abstract
            // Base<T>.handle(T) already sits on the only declaration that exists to annotate.
            // Refusing to load told the author to move an annotation that had nowhere else to go,
            // and it refused a bean that loads fine without this change. isProxyable still returns
            // false for these, so class-level coverage skips them. See issue #309.
        }
        return problems;
    }

    /**
     * Whether an inheritance-based proxy generated for {@code beanClass} can intercept the given
     * method - meaning it can both override it and reach the original through {@code super}.
     * <p>
     * Shares its rules with {@link #check(Class, Set)}, which is why the two must be changed
     * together. They exist separately because they serve opposite audiences. A method-level
     * annotation is an explicit request, so an unproxyable one becomes a startup failure. A
     * class-level annotation is a bulk request the author never vetted method by method, so the
     * class-level path filters with this predicate instead of failing the whole module.
     * <p>
     * The bean class is a parameter rather than being derived from the method because two of the
     * five rules are relative to it. A method is not intrinsically unproxyable; it is unproxyable
     * <em>from a particular subclass</em>. See issue #309.
     * <p>
     * 判断为 beanClass 生成的继承式代理能否拦截该方法——既能覆写，也能经 super 调到原实现。
     * 与 check 共用同一组规则，两者必须同步修改。bean 类是参数而非从方法推导，因为五条规则里
     * 有两条是相对于它的：一个方法不是天然不可代理，而是<em>相对某个子类</em>不可代理。
     *
     * @param method    the method to test, may be null
     * @param beanClass the class the proxy will extend, may be null
     * @return true if an inheritance-based proxy can intercept it
     */
    public static boolean isProxyable(Method method, Class<?> beanClass) {
        if (method == null || beanClass == null) {
            return false;
        }
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)
                || Modifier.isFinal(modifiers)) {
            return false;
        }
        return !isInaccessible(method, beanClass) && !isShadowed(method, beanClass);
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
     * <p>
     * 代理生成在 bean 所在的包里，别的包中的 package-private 声明既覆写不了也 super 不到。
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
     * <p>
     * <p>
     * 泛型覆写的擦除另一半：编译器为 Child 生成桥接方法 take(Object)，getAllMethods 会跳过桥接
     * 且按擦除参数比较，于是父类的 take(Object) 作为独立方法返回，但它已不可 super 调用。
     * 此处有意连桥接与合成方法一起扫描，因为遮蔽正是它们造成的。
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
            INACCESSIBLE_METHOD
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
