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
     * @param method      the method to check
     * @param targetClass the bean class the proxy is built for - <b>not</b> the method's declaring
     *                    class, which for an inherited method is a superclass that does not carry
     *                    the bean's class-level annotations. Both call sites pass the same value
     *                    at collection time and at invocation time, so an advisor's two answers
     *                    cannot disagree. See issue #309.
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

        AnnotationAopAdvisor(Class<? extends Annotation> annotationType,
                            MethodInterceptor interceptor,
                            int order) {
            this.annotationType = annotationType;
            this.interceptor = interceptor;
            this.order = order;
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            // Method-level first: the author named this method, wherever it is declared.
            if (method.isAnnotationPresent(annotationType)) {
                return true;
            }
            // Then class level, anchored at the declaring class rather than the bean - see
            // findClassLevelAnnotation for why the difference matters.
            return findClassLevelAnnotation(method, annotationType) != null;
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
