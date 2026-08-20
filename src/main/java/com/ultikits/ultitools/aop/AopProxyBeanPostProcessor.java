package com.ultikits.ultitools.aop;

import com.ultikits.ultitools.context.BeanPostProcessor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * BeanPostProcessor that creates AOP proxies for beans that need interception.
 * <p>
 * This processor checks each bean during initialization and creates a proxy
 * if any registered {@link AopAdvisor} matches the bean's methods.
 * <p>
 * Usage example:
 * <pre>{@code
 * AopProxyBeanPostProcessor aopProcessor = new AopProxyBeanPostProcessor();
 * aopProcessor.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, txInterceptor, 100));
 * container.addBeanPostProcessor(aopProcessor);
 * }</pre>
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class AopProxyBeanPostProcessor implements BeanPostProcessor {

    private static final Logger LOGGER = Logger.getLogger(AopProxyBeanPostProcessor.class.getName());

    private final List<AopAdvisor> advisors = new CopyOnWriteArrayList<>();

    /**
     * Adds an advisor to be applied during proxy creation.
     *
     * @param advisor the advisor to add
     */
    public void addAdvisor(AopAdvisor advisor) {
        advisors.add(advisor);
        // Keep advisors sorted by order
        advisors.sort(Comparator.comparingInt(AopAdvisor::getOrder));
    }

    /**
     * Removes an advisor.
     *
     * @param advisor the advisor to remove
     * @return true if the advisor was removed
     */
    public boolean removeAdvisor(AopAdvisor advisor) {
        return advisors.remove(advisor);
    }

    /**
     * Gets all registered advisors.
     *
     * @return an unmodifiable list of advisors
     */
    public List<AopAdvisor> getAdvisors() {
        return new ArrayList<>(advisors);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean == null || advisors.isEmpty()) {
            return bean;
        }

        Class<?> beanClass = bean.getClass();

        // Skip final classes - they cannot be proxied
        if (Modifier.isFinal(beanClass.getModifiers())) {
            if (requiresProxy(beanClass)) {
                LOGGER.warning("Bean '" + beanName + "' of type " + beanClass.getName() +
                        " requires AOP but is final and cannot be proxied.");
            }
            return bean;
        }

        // Check if any advisor applies to this bean
        List<MethodInterceptor> applicableInterceptors = getApplicableInterceptors(beanClass);

        if (applicableInterceptors.isEmpty()) {
            return bean;
        }

        LOGGER.fine("Creating AOP proxy for bean '" + beanName + "' with " +
                applicableInterceptors.size() + " interceptors");

        // ProxyFactory.createProxy(Object) was removed when the proxy engine became
        // inheritance-based (issue #190): a proxy is now instantiated fresh as a subclass,
        // which a post-processor running after the bean already exists cannot retrofit onto
        // it. This class is superseded by AopProxyResolver, which resolves the proxy class
        // before the container instantiates the bean, and is deleted along with this method's
        // body in Task 4 of the #190 plan. Left as a documented no-op for one commit so the
        // tree keeps compiling.
        LOGGER.warning("Bean '" + beanName + "' needs an AOP proxy, but " +
                "AopProxyBeanPostProcessor can no longer create one after construction " +
                "(see issue #190); it will run unproxied.");
        return bean;
    }

    /**
     * Gets all interceptors that apply to the given class.
     */
    private List<MethodInterceptor> getApplicableInterceptors(Class<?> targetClass) {
        List<MethodInterceptor> result = new ArrayList<>();

        for (AopAdvisor advisor : advisors) {
            // Check if any method matches
            for (Method method : targetClass.getDeclaredMethods()) {
                if (advisor.matches(method, targetClass)) {
                    result.add(new FilteringMethodInterceptor(advisor));
                    break; // Only add each advisor once
                }
            }
        }

        return result;
    }

    /**
     * Checks if the given class requires a proxy based on any advisor.
     */
    private boolean requiresProxy(Class<?> targetClass) {
        for (AopAdvisor advisor : advisors) {
            for (Method method : targetClass.getDeclaredMethods()) {
                if (advisor.matches(method, targetClass)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Internal interceptor that only applies if the advisor matches the specific method.
     */
    private static class FilteringMethodInterceptor implements MethodInterceptor {
        private final AopAdvisor advisor;

        FilteringMethodInterceptor(AopAdvisor advisor) {
            this.advisor = advisor;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            // Check if this advisor applies to the current method
            if (advisor.matches(invocation.getMethod(), invocation.getTarget().getClass())) {
                return advisor.getInterceptor().invoke(invocation);
            }
            // Otherwise, just proceed to the next interceptor or target
            return invocation.proceed();
        }
    }
}
