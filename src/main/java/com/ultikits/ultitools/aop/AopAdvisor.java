package com.ultikits.ultitools.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

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
     * Exposed and shared so that the match test and the interceptors that read the annotation's
     * attributes cannot answer differently. See issue #309.
     * <p>
     * 类级注解的查找锚点是方法的<b>声明类</b>，并从那里向上走。类级注解是「声明它的那个类及其
     * 子类」的默认值，不作用于祖先类，因此 bean 不会把自己注解的作用范围扩张到它继承来的一切
     * ——尤其不会扩张到它继承的框架基类上，在那里吞掉异常会变成一个 null，并在远离原因的地方
     * 以不相干的故障重新浮现。这是 Spring 对类级 @Transactional 的既定规则。
     *
     * @param method         the method being considered
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the governing annotation, or null
     */
    static <A extends Annotation> A findClassLevelAnnotation(Method method, Class<A> annotationType) {
        return findClassLevelAnnotation0(method, annotationType);
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
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0) {
            return "hashCode".equals(method.getName());
        }
        if (params.length == 1 && params[0] == Object.class) {
            return "equals".equals(method.getName()) || "canEqual".equals(method.getName());
        }
        return false;
    }

    /** The uncached computation; {@code ClassLevelAnnotationCache} is its caching decorator. */
    static <A extends Annotation> A findClassLevelAnnotation0(Method method, Class<A> annotationType) {
        if (method == null || annotationType == null) {
            return null;
        }
        for (Class<?> current = method.getDeclaringClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            A found = current.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
        }
        return null;
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
        /** Instance-scoped on purpose - see ClassLevelAnnotationCache's class javadoc. */
        private final ClassLevelAnnotationCache<? extends Annotation> classLevelCache;

        AnnotationAopAdvisor(Class<? extends Annotation> annotationType,
                            MethodInterceptor interceptor,
                            int order) {
            this.annotationType = annotationType;
            this.interceptor = interceptor;
            this.order = order;
            this.classLevelCache = new ClassLevelAnnotationCache<>(annotationType);
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            // Method-level first: the author named this method, wherever it is declared.
            if (method.isAnnotationPresent(annotationType)) {
                return true;
            }
            // Then class level, anchored at the declaring class rather than the bean - see
            // findClassLevelAnnotation for why the difference matters - minus the signatures a
            // bulk request must never sweep up.
            return classLevelCache.get(method) != null && !isExcludedFromClassLevel(method);
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
