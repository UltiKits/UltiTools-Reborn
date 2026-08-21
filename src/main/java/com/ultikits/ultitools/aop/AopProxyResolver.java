package com.ultikits.ultitools.aop;

import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Bean class to the class the container should instantiate for it.
     * <p>
     * {@code resolve} is called from {@code SimpleContainer.createBean}, which runs once per
     * instantiation - so for a prototype-scoped bean it ran on every {@code getBean()}, repeating
     * three hierarchy scans, re-emitting the "annotation ignored" warning that the javadoc calls a
     * startup warning, and asking ByteBuddy for a brand new proxy class each time. The answer
     * depends only on the bean class and this resolver's advisors, so it is computed once. Spring
     * caches generated proxy classes for the same reason.
     * <p>
     * Only successful resolutions are stored. A refusal re-runs and re-throws, which costs nothing
     * because it fails the module load anyway.
     * <p>
     * Instance-scoped, like the annotation caches: it holds {@code Class} references and must die
     * with the container that owns it rather than pin a module's classes for the life of the JVM.
     */
    private final Map<Class<?>, Class<?>> resolvedClasses = new ConcurrentHashMap<>();


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
        // After the mutation - see addAdvisor.
        resolvedClasses.clear();
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
        // Cleared after the mutation, never before: a resolve() interleaved between a clear and
        // its mutation would re-cache the pre-mutation answer, and nothing would clear it again.
        resolvedClasses.clear();
    }

    /**
     * Removes an advisor.
     *
     * @param advisor the advisor to remove
     * @return true if the advisor was removed
     */
    public boolean removeAdvisor(AopAdvisor advisor) {
        boolean removed = advisors.remove(advisor);
        resolvedClasses.clear();
        return removed;
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

        Class<?> memoized = resolvedClasses.get(beanClass);
        if (memoized != null) {
            return memoized;
        }

        // Checked before the advisor short-circuit below: a bean using an unavailable annotation
        // must fail even when no advisor is registered at all, which is exactly the configuration
        // that would otherwise return it unproxied.
        rejectUnavailableAnnotations(beanClass);

        if (advisors.isEmpty()) {
            resolvedClasses.put(beanClass, beanClass);
            return beanClass;
        }

        Set<Method> annotated = AopEligibility.findAopAnnotatedMethods(beanClass);
        Set<Method> intercepted = collectInterceptedMethods(beanClass);
        boolean interceptionRequested = !intercepted.isEmpty() || !annotated.isEmpty();

        // One case is fatal and the rest are not, and the line between them is whether the author
        // is being told about a method or about the class. A final class cannot be subclassed, so
        // no proxy exists at all and nothing the author writes elsewhere will change that - Spring
        // throws AopConfigException for the same shape. Every other problem is a single method the
        // proxy cannot reach; Spring ignores those, and so does this, because failing the load
        // stopped whoever extended a class rather than whoever wrote the annotation, and the
        // remedy named a file they may not own. Ignoring is not the same as silence: each one is
        // named in a warning, since an annotation that quietly does nothing is exactly what left
        // @ExceptionCatch inert from 6.2.0 to 6.3.0. See issue #309.
        List<AopEligibility.Problem> blocking = new ArrayList<>();
        // Skipped entirely when nothing asked for interception: check() would otherwise build a
        // FINAL_CLASS problem and its remedy string for every plain final bean, only for the
        // guard below to discard it.
        for (AopEligibility.Problem problem : interceptionRequested
                ? AopEligibility.check(beanClass, annotated)
                : java.util.Collections.<AopEligibility.Problem>emptyList()) {
            if (problem.getKind() == AopEligibility.Problem.Kind.FINAL_CLASS) {
                blocking.add(problem);
            } else {
                LOGGER.warning("AOP annotation ignored on " + beanClass.getName()
                        + ": " + problem + " The annotation has no effect; the module still loads.");
            }
        }
        if (!blocking.isEmpty() && interceptionRequested) {
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                    buildMessage(beanClass, blocking));
        }

        if (intercepted.isEmpty()) {
            // Every other skip in this class is named in a warning; this one used to be the
            // exception. "@ExceptionCatch class ServiceImpl extends AbstractService {}" is a shape
            // authors write, and class-level scope does not reach ancestors, so it covers nothing -
            // silence there is the failure mode this whole change exists to remove.
            for (AopAdvisor advisor : advisors) {
                Class<? extends Annotation> type = advisor.getAnnotationType();
                if (type != null && beanClass.isAnnotationPresent(type)) {
                    LOGGER.warning("@" + type.getSimpleName() + " on " + beanClass.getName()
                            + " covers no method and has no effect. A class-level annotation "
                            + "applies to the methods its own class declares and to subclasses, "
                            + "not to methods inherited from an ancestor; redeclare the method "
                            + "here, or annotate it where it is declared.");
                }
            }
            resolvedClasses.put(beanClass, beanClass);
            return beanClass;
        }

        LOGGER.fine("Creating AOP proxy class for " + beanClass.getName()
                + " with " + intercepted.size() + " intercepted method(s)");

        List<MethodInterceptor> interceptors = new ArrayList<>();
        for (AopAdvisor advisor : advisors) {
            interceptors.add(new AdvisorScopedInterceptor(advisor, beanClass));
        }
        Class<?> proxyClass = new ProxyFactory(interceptors).createProxyClass(beanClass, intercepted);
        resolvedClasses.put(beanClass, proxyClass);
        return proxyClass;
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
        // Walks the hierarchy: neither @Transactional nor @ExceptionCatch is @Inherited, so an
        // annotation on a superclass method is invisible to getDeclaredMethods() and the refusal
        // below would never fire for it. See issue #309.
        // Refuse exactly when the annotation governs at least one of the bean's methods, using
        // the same governance rule interception uses. Testing beanClass.isAnnotationPresent
        // instead made the two annotations disagree on identical shapes: an annotated class whose
        // methods are all inherited governs nothing, yet @Transactional refused it while
        // @ExceptionCatch quietly covered nothing. One rule, so one answer. See issue #309.
        for (Method method : ReflectionUtil.getAllMethods(beanClass)) {
            if (AopAdvisor.findMethodLevelAnnotation(method, type) != null
                    && AopEligibility.isProxyable(method, beanClass)) {
                // The declaring class, not the bean: the method may come from a superclass, and
                // naming the bean would point the author at a file with no such annotation. An
                // unproxyable one is left to the warning path, the same way @ExceptionCatch treats
                // it - the annotation could not have taken effect there either.
                return method.getDeclaringClass().getName() + "#" + method.getName();
            }
            // Class-level, but only where it would actually take effect. Refusing on the mere
            // presence of the annotation made @Transactional reject shapes @ExceptionCatch does
            // not cover at all - a class whose every method is unproxyable, or one whose only
            // methods are the excluded equals/hashCode/canEqual - so a module was hard-failed for
            // an annotation that could never have done anything there. COMPATIBILITY.md promises
            // module authors that one rule decides coverage for both.
            Class<?> owner = AopAdvisor.findClassLevelAnnotationOwner(method, type);
            if (owner != null && !AopAdvisor.isExcludedFromClassLevel(method)
                    && AopEligibility.isProxyable(method, beanClass)) {
                return owner.getName();
            }
        }
        return null;
    }

    /**
     * Collects methods matched by at least one advisor, across the whole inheritance hierarchy.
     * <p>
     * 收集被至少一个 advisor 匹配的方法，范围覆盖整个继承层级。
     */
    private Set<Method> collectInterceptedMethods(Class<?> beanClass) {
        Set<Method> result = new LinkedHashSet<>();
        // No synthetic filter here: ReflectionUtil.getAllMethods already drops bridge and
        // synthetic declarations. AopEligibility's shadowing rule deliberately does scan them,
        // and a redundant guard here would blur that contrast for the next reader.
        for (Method method : ReflectionUtil.getAllMethods(beanClass)) {
            for (AopAdvisor advisor : advisors) {
                if (!advisor.matches(method, beanClass)) {
                    continue;
                }
                Class<? extends Annotation> type = advisor.getAnnotationType();
                if (type != null && AopAdvisor.findMethodLevelAnnotation(method, type) != null) {
                    // Method-level: an explicit request, but still only collected when the proxy
                    // can actually reach it. resolve() warns about the ones dropped here, using
                    // AopEligibility.check for the reason and the remedy.
                    if (AopEligibility.isProxyable(method, beanClass)) {
                        result.add(method);
                    }
                    break;
                }
                // Whether a class-level annotation may sweep this signature up is the pointcut's
                // answer, given once in AopAdvisor and reused on every invocation. Proxyability is
                // filtered here because nothing upstream checks it for a bulk or pointcut match:
                // AopEligibility.check only inspects the framework's own two annotations, so an
                // unproxyable target would otherwise reach the proxy factory.
                if (AopEligibility.isProxyable(method, beanClass)) {
                    result.add(method);
                    break;
                }
                // This advisor matched but declined. Breaking here would drop a later advisor's
                // method-level annotation on the same method - an explicit request the author did
                // make - so the loop continues instead.
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
        private final Class<?> beanClass;

        AdvisorScopedInterceptor(AopAdvisor advisor, Class<?> beanClass) {
            this.advisor = advisor;
            this.beanClass = beanClass;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            // The bean class the proxy was built for, captured at resolve time, so that this
            // per-invocation re-check is given exactly what the collection-time check was given.
            // Annotation advisors no longer read it - they anchor on the declaring class - but a
            // pointcut advisor's matches() may, and an advisor that saw one class at collection
            // time and a different one here would silently step aside. See issue #309.
            if (advisor.matches(method, beanClass)) {
                return advisor.getInterceptor().invoke(invocation);
            }
            return invocation.proceed();
        }
    }
}
