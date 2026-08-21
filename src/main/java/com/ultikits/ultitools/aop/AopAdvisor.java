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
            // Check method-level annotation first
            if (method.isAnnotationPresent(annotationType)) {
                return true;
            }
            // Then check class-level annotation
            return targetClass.isAnnotationPresent(annotationType);
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
