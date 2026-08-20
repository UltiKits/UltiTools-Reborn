package com.ultikits.ultitools.aop;

import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.exceptions.ErrorCode;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Decides which class the container should instantiate for a bean.
 * <p>
 * This replaces the former {@code AopProxyBeanPostProcessor}. Proxy creation happens
 * <b>before</b> the bean exists, so it cannot be a {@code BeanPostProcessor}: both of that
 * interface's callbacks take an already-constructed instance. The container asks this resolver
 * for a class, instantiates it through the normal constructor paths, and the resulting object is
 * the bean. There is no second instance.
 * <p>
 * 代理创建发生在 bean 存在之前，因此不适合作为 BeanPostProcessor。容器向本类索取一个类，
 * 走原有构造器路径实例化，得到的对象就是 bean，不存在第二个实例。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class AopProxyResolver {

    private static final Logger LOGGER = Logger.getLogger(AopProxyResolver.class.getName());

    private final List<AopAdvisor> advisors = new CopyOnWriteArrayList<>();

    /**
     * Annotations the framework recognises but deliberately does not implement in this release,
     * mapped to the reason. A bean carrying one is rejected rather than handed back unproxied,
     * because an unproxied bean is indistinguishable from a working annotation.
     */
    private final Map<Class<? extends Annotation>, String> unavailableAnnotations =
            new LinkedHashMap<>();

    /**
     * Declares an annotation as recognised but unavailable.
     *
     * @param type   the annotation type
     * @param reason what the module author should know, including where to follow up
     */
    public void addUnavailableAnnotation(Class<? extends Annotation> type, String reason) {
        unavailableAnnotations.put(type, reason);
    }

    /**
     * Verifies that every recognised AOP annotation is accounted for.
     * <p>
     * Each annotation must either have a registered advisor that can intercept it, or be declared
     * unavailable so that beans using it are rejected outright. An annotation that is neither is
     * the silent-failure case this framework is removing: beans carrying it would be proxied for
     * their <em>other</em> annotations while this one quietly does nothing.
     *
     * @throws ContainerException if any recognised annotation is neither served nor declared
     */
    public void validateAnnotationCoverage() {
        List<String> uncovered = new ArrayList<>();
        for (Class<? extends Annotation> annotation : AopEligibility.getAopAnnotations()) {
            if (unavailableAnnotations.containsKey(annotation)) {
                continue;
            }
            boolean served = false;
            for (AopAdvisor advisor : advisors) {
                if (annotation.equals(advisor.getAnnotationType())) {
                    served = true;
                    break;
                }
            }
            if (!served) {
                uncovered.add(annotation.getName());
            }
        }
        if (!uncovered.isEmpty()) {
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                    "AOP annotations recognised by AopEligibility but neither served by an advisor "
                            + "nor declared unavailable: " + uncovered
                            + ". Register an advisor for each, or call addUnavailableAnnotation.");
        }
    }

    /**
     * Adds an advisor to be considered during proxy resolution.
     *
     * @param advisor the advisor to add
     */
    public void addAdvisor(AopAdvisor advisor) {
        advisors.add(advisor);
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
     * Gets all registered advisors, ordered by {@link AopAdvisor#getOrder()}.
     *
     * @return a copy of the advisor list
     */
    public List<AopAdvisor> getAdvisors() {
        return new ArrayList<>(advisors);
    }

    /**
     * Resolves the class the container should instantiate for the given bean class.
     *
     * @param beanClass the declared bean class
     * @return the same class when no interception is needed, otherwise a generated subclass
     * @throws ContainerException if the class requests interception but cannot be proxied
     */
    public Class<?> resolve(Class<?> beanClass) {
        if (beanClass == null) {
            return null;
        }

        // Checked before the advisor short-circuit below: a bean using an unavailable annotation
        // must fail even when no advisor is registered at all, which is exactly the configuration
        // that would otherwise return it unproxied.
        rejectUnavailableAnnotations(beanClass);

        if (advisors.isEmpty()) {
            return beanClass;
        }

        Set<Method> intercepted = collectInterceptedMethods(beanClass);
        if (intercepted.isEmpty()) {
            return beanClass;
        }

        List<AopEligibility.Problem> problems =
                AopEligibility.check(beanClass, AopEligibility.findAopAnnotatedMethods(beanClass));
        if (!problems.isEmpty()) {
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                    buildMessage(beanClass, problems));
        }

        LOGGER.fine("Creating AOP proxy class for " + beanClass.getName()
                + " with " + intercepted.size() + " intercepted method(s)");

        List<MethodInterceptor> interceptors = new ArrayList<>();
        for (AopAdvisor advisor : advisors) {
            interceptors.add(new AdvisorScopedInterceptor(advisor));
        }
        return new ProxyFactory(interceptors).createProxyClass(beanClass, intercepted);
    }

    /**
     * Throws if the bean uses an annotation declared unavailable.
     */
    private void rejectUnavailableAnnotations(Class<?> beanClass) {
        for (Map.Entry<Class<? extends Annotation>, String> entry : unavailableAnnotations.entrySet()) {
            String location = locateAnnotation(beanClass, entry.getKey());
            if (location != null) {
                throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                        "Cannot create " + beanClass.getName() + ": " + location + " uses @"
                                + entry.getKey().getSimpleName()
                                + ", which is not available in this release.\n  " + entry.getValue());
            }
        }
    }

    /**
     * Finds where an annotation appears on the class, or null if it does not.
     *
     * @return the class name for a type-level annotation, {@code class#method} for a method-level
     *         one, or null
     */
    private String locateAnnotation(Class<?> beanClass, Class<? extends Annotation> type) {
        if (beanClass.isAnnotationPresent(type)) {
            return beanClass.getName();
        }
        for (Method method : beanClass.getDeclaredMethods()) {
            if (!method.isSynthetic() && method.isAnnotationPresent(type)) {
                return beanClass.getName() + "#" + method.getName();
            }
        }
        return null;
    }

    /**
     * Collects methods matched by at least one advisor.
     */
    private Set<Method> collectInterceptedMethods(Class<?> beanClass) {
        Set<Method> result = new LinkedHashSet<>();
        for (Method method : beanClass.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            for (AopAdvisor advisor : advisors) {
                if (advisor.matches(method, beanClass)) {
                    result.add(method);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Builds a message that names every blocking problem and its remedy.
     */
    private String buildMessage(Class<?> beanClass, List<AopEligibility.Problem> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cannot apply AOP to ").append(beanClass.getName()).append(':');
        for (AopEligibility.Problem problem : problems) {
            sb.append("\n  - ").append(problem.getKind())
              .append(" at ").append(problem.getLocation())
              .append("\n    ").append(problem.getRemedy());
        }
        return sb.toString();
    }

    /**
     * Applies an advisor's interceptor only to the methods that advisor matches.
     * <p>
     * The proxy binds one interceptor chain per class, but different advisors may match different
     * subsets of its methods. This wrapper re-checks per invocation and steps aside otherwise.
     */
    private static class AdvisorScopedInterceptor implements MethodInterceptor {

        private final AopAdvisor advisor;

        AdvisorScopedInterceptor(AopAdvisor advisor) {
            this.advisor = advisor;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            // Use the declaring class rather than the target's runtime class: under an
            // inheritance-based proxy the latter is the generated subclass. Annotations are
            // copied onto it, so both work, but the declaring class does not depend on that.
            if (advisor.matches(method, method.getDeclaringClass())) {
                return advisor.getInterceptor().invoke(invocation);
            }
            return invocation.proceed();
        }
    }
}
