package com.ultikits.ultitools.aop;

import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * An advisor that combines a pointcut (when to apply) with advice (what to do).
 * <p>
 * Advisors determine which methods should be intercepted and provide the
 * interceptor to apply to those methods.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public interface AopAdvisor {

    /**
     * Finds the method-level annotation that governs a method, or null if none does.
     * <p>
     * Looks at the method's own declaration first, then at the declarations it overrides. Java does
     * not inherit method annotations, and the scan keeps only the most derived declaration of each
     * overridable method, so without the second step an override hides its superclass's annotation
     * completely. Spring's attribute lookup falls back from the target method to the declaring
     * method for the same reason.
     * <p>
     * Overriding is transitive, so the walk tests each candidate against the whole chain collected
     * so far rather than against the leaf - see the comment in the body for the case that makes the
     * difference.
     * <p>
     * Exposed and shared so that the match test and the interceptors that read the annotation's
     * attributes cannot answer differently. See issue #309.
     * <p>
     * 先看方法自身的声明，再看它所覆写的声明。Java 不继承方法注解，而扫描对每个可覆写方法只保留
     * 最派生的那条声明，缺了第二步，覆写就会把父类的注解完全遮住。覆写具有传递性，因此候选要与
     * 「已收集的整条链」比较，而不是只与叶子比较。
     *
     * @param method         the method being considered
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the governing annotation, or null
     */
    static <A extends Annotation> A findMethodLevelAnnotation(Method method, Class<A> annotationType) {
        if (method == null || annotationType == null) {
            return null;
        }
        A own = method.getAnnotation(annotationType);
        if (own != null) {
            return own;
        }
        return findInheritedMethodAnnotation(method, annotationType);
    }

    /**
     * Finds the annotation on a declaration this method overrides, ignoring its own declaration.
     * <p>
     * Split out because precedence differs from presence. Deciding <em>whether</em> to intercept
     * only asks whether an annotation exists anywhere; deciding <em>which</em> annotation supplies
     * the attributes has an order, and Spring's is: the method itself, then the target class, then
     * the declaration the method overrides, then that declaration's class. A subclass that writes
     * its own class-level annotation outranks a method-level one it inherited, because the subclass
     * author is the one closer to the bean.
     * <p>
     * 拆出来是因为「有没有」与「用哪一个」是两个问题。判断是否拦截只问存在性；决定用哪个注解的
     * 属性则有次序，Spring 的次序是：方法自身 → 目标类 → 被覆写的声明 → 该声明所在的类。
     *
     * @param method         the method being considered
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the annotation on an overridden declaration, or null
     */
    static <A extends Annotation> A findInheritedMethodAnnotation(Method method,
                                                                 Class<A> annotationType) {
        if (method == null || annotationType == null) {
            return null;
        }
        // Then the declarations this one overrides. Java does not inherit method annotations, and
        // the scan keeps only the most derived declaration of each overridable method, so without
        // this step an override hid its superclass's annotation completely: no interception, and
        // for an unavailable annotation no refusal either. Spring's attribute lookup falls back
        // from the target method to the declaring method for the same reason.
        //
        // Each candidate is tested against every declaration already in the chain, not against the
        // leaf alone, because overriding is transitive (JLS 8.4.8.1). In x.A#m (package-private)
        // -> x.B#m (public) -> y.C#m (public), the leaf does not override the root directly - the
        // packages differ - but does so through the middle declaration, and all three are one
        // method. Testing only against the leaf loses the root's annotation entirely. This is the
        // same rule, and the same reason, that ReflectionUtil.getAllMethods documents for its
        // override slots; deriving a second, subtly different version of it here is what made this
        // wrong the first time. See issue #309.
        List<Method> chain = new ArrayList<>();
        chain.add(method);
        for (Class<?> current = method.getDeclaringClass().getSuperclass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Method superDeclaration;
            try {
                superDeclaration = current.getDeclaredMethod(
                        method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException notDeclaredHere) {
                continue;
            }
            boolean partOfChain = false;
            for (Method member : chain) {
                if (ReflectionUtil.overrides(member, superDeclaration)) {
                    partOfChain = true;
                    break;
                }
            }
            if (!partOfChain) {
                continue;
            }
            A found = superDeclaration.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
            chain.add(superDeclaration);
        }
        return null;
    }

    /**
     * Finds the class-level annotation that governs a method, or null if none does.
     * <p>
     * The lookup is anchored at the method's <b>declaring class</b> and walks up from there. A
     * class-level annotation is a default for the class that declares it and for that class's
     * subclasses; it does not apply to ancestors. A bean therefore does not extend its own
     * annotation's reach over everything it inherits - notably not over the framework base classes
     * it extends, where swallowing an exception would turn into a null that resurfaces as an
     * unrelated failure far from its cause. This is the rule Spring documents for a class-level
     * {@code @Transactional}, and inherited methods must be locally redeclared to pick up a
     * subclass's annotation.
     * <p>
     * 类级注解的查找锚点是方法的<b>声明类</b>，并从那里向上走。类级注解是「声明它的那个类及其
     * 子类」的默认值，不作用于祖先类。这是 Spring 对类级 @Transactional 的既定规则。
     *
     * @param method         the method being considered
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the governing annotation, or null
     */
    static <A extends Annotation> A findClassLevelAnnotation(Method method, Class<A> annotationType) {
        Class<?> owner = findClassLevelAnnotationOwner(method, annotationType);
        return owner == null ? null : owner.getAnnotation(annotationType);
    }

    /**
     * The class whose type-level annotation governs the method, or null if none does.
     * <p>
     * Same walk as {@link #findClassLevelAnnotation(Method, Class)}, exposed separately because a
     * caller that has to name the offending class in a message needs the class rather than the
     * annotation. Deriving it a second time in the caller is how two copies of one rule start
     * disagreeing.
     * <p>
     * 与 findClassLevelAnnotation 是同一趟遍历，单独暴露是因为要在报错信息里点名那个类的调用方
     * 需要的是类而不是注解——让调用方自己再推一遍，正是「一条规则两份实现」的开端。
     *
     * @param method         the method being considered
     * @param annotationType the annotation to look for
     * @return the class carrying it, or null
     */
    static Class<?> findClassLevelAnnotationOwner(Method method,
                                                 Class<? extends Annotation> annotationType) {
        if (method == null || annotationType == null) {
            return null;
        }
        for (Class<?> current = method.getDeclaringClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (current.isAnnotationPresent(annotationType)) {
                return current;
            }
        }
        return null;
    }

    /**
     * Whether a class-level annotation must never cover this method.
     * <p>
     * Intercepting one of these swaps a visible exception for a silent wrong answer. An
     * {@code @ExceptionCatch} that swallows {@code equals} returns {@code false}, and the caller's
     * {@code HashMap} then fails to find a key it does hold; a propagating exception at least
     * reaches someone. {@code canEqual} is Lombok's {@code equals} collaborator - the proxy
     * overrides it and the superclass {@code equals} reaches it through virtual dispatch, so
     * excluding {@code equals} without excluding {@code canEqual} excludes nothing.
     * <p>
     * {@code toString} is deliberately absent: swallowing it costs a log line rather than a wrong
     * answer, and "the logging statement itself threw" is a case
     * {@code @ExceptionCatch(silent = true)} exists for. A method-level annotation on any of these
     * three is still honoured, because the author named it explicitly.
     * <p>
     * This lives in the pointcut rather than in the code that collects methods, so that the answer
     * given while building the proxy and the answer given on each invocation are the same answer.
     * Enforcing it only while collecting left a second advisor able to pull {@code hashCode} into
     * the proxy, after which this advisor's class-level branch matched it again at invocation time
     * and swallowed the exception into a {@code 0}. Spring reaches the same end differently, by
     * routing {@code equals} and {@code hashCode} to callbacks that structurally cannot run advice.
     * <p>
     * 类级注解绝不覆盖的三个签名：拦截它们会把一个可见的异常换成一个静默的错误结果。
     * toString 有意不在其中——吞掉它只损失一行日志，而「日志语句自身抛异常」正是该注解的用例。
     * 方法级标注在这三个签名上依然生效。该判定放在切点内而非收集代码里，是为了让「构建代理时」
     * 与「每次调用时」给出同一个答案。
     *
     * @param method the candidate method
     * @return true if class-level coverage must skip it
     */
    static boolean isExcludedFromClassLevel(Method method) {
        if (method == null) {
            return false;
        }
        // Name first: getParameterTypes() clones its array, and this runs on every intercepted
        // invocation through matches(). Only three names can ever match, so the clone is avoided
        // for every other method.
        String name = method.getName();
        boolean candidate = "hashCode".equals(name) || "equals".equals(name)
                || "canEqual".equals(name);
        if (!candidate) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return "hashCode".equals(name);
        }
        return params.length == 1 && params[0] == Object.class && !"hashCode".equals(name);
    }

    /**
     * Determines if this advisor applies to the given method.
     *
     * @param method      the candidate method
     * @param targetClass the target class - the bean class the proxy is built for, which is what
     *                    Spring's {@code MethodMatcher} means by the same parameter name. It is
     *                    <b>not</b> the method's declaring class; for an inherited method those
     *                    differ. Both call sites pass the same value at collection time and at
     *                    invocation time, so an advisor's two answers cannot disagree.
     *                    See issue #309.
     * @return true if this advisor should intercept the method
     */
    boolean matches(Method method, Class<?> targetClass);

    /**
     * Gets the interceptor to apply for matching methods.
     *
     * @return the method interceptor
     */
    MethodInterceptor getInterceptor();

    /**
     * Gets the order of this advisor. Lower values have higher priority.
     * <p>
     * Default order is 0. Transaction advisors typically use order 100,
     * exception handling uses order 200.
     *
     * @return the order value
     */
    default int getOrder() {
        return 0;
    }

    /**
     * The annotation this advisor serves, for annotation-coverage checks.
     * <p>
     * {@link AopProxyResolver#validateAnnotationCoverage()} uses this to prove that every
     * annotation {@link AopEligibility} recognises is either served by a registered advisor or
     * declared unavailable. An advisor whose pointcut is not annotation-based (a custom
     * {@code matches} implementation) has nothing truthful to report here and should leave the
     * default in place.
     *
     * @return the annotation type this advisor matches, or {@code null} if its pointcut logic is
     *         not annotation-based
     */
    default Class<? extends Annotation> getAnnotationType() {
        return null;
    }

    /**
     * Creates an advisor that matches methods or classes with the given annotation.
     *
     * @param annotationType the annotation to match
     * @param interceptor    the interceptor to apply
     * @param order          the advisor order
     * @return a new annotation-based advisor
     */
    static AopAdvisor forAnnotation(Class<? extends Annotation> annotationType,
                                    MethodInterceptor interceptor,
                                    int order) {
        return new AnnotationAopAdvisor(annotationType, interceptor, order);
    }

    /**
     * Internal implementation for annotation-based advisors.
     */
    class AnnotationAopAdvisor implements AopAdvisor {
        private final Class<? extends Annotation> annotationType;
        private final MethodInterceptor interceptor;
        private final int order;
        /** Instance-scoped on purpose - see AnnotationLookupCache's class javadoc. */
        private final AnnotationLookupCache<? extends Annotation> lookupCache;

        AnnotationAopAdvisor(Class<? extends Annotation> annotationType,
                            MethodInterceptor interceptor,
                            int order) {
            this.annotationType = annotationType;
            this.interceptor = interceptor;
            this.order = order;
            this.lookupCache = new AnnotationLookupCache<>(annotationType);
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            // Method-level first, including a declaration this method overrides: the author
            // named this method, wherever in the chain they wrote it.
            if (lookupCache.methodLevel(method) != null) {
                return true;
            }
            // Then class level, anchored at the declaring class rather than the bean - see
            // findClassLevelAnnotation for why the difference matters - minus the signatures a
            // bulk request must never sweep up.
            return lookupCache.classLevel(method) != null && !isExcludedFromClassLevel(method);
        }

        @Override
        public MethodInterceptor getInterceptor() {
            return interceptor;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public Class<? extends Annotation> getAnnotationType() {
            return annotationType;
        }
    }
}
