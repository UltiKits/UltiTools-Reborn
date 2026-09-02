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
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Bumped by every change to the advisor set or the unavailable-annotation map.
     * <p>
     * Clearing the memo after a mutation closes only one of the two interleavings. The other is a
     * resolve() that read the old configuration, was overtaken by a mutation and its clear, and
     * then stored its now-stale answer - which nothing would clear again. A resolution is kept
     * only if the configuration it was computed against is still current.
     */
    private final AtomicLong configGeneration = new AtomicLong();


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
        invalidate();
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
        invalidate();
    }

    /**
     * Removes an advisor.
     *
     * @param advisor the advisor to remove
     * @return true if the advisor was removed
     */
    public boolean removeAdvisor(AopAdvisor advisor) {
        boolean removed = advisors.remove(advisor);
        invalidate();
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
        long generation = configGeneration.get();

        // One reflective hierarchy scan per resolve, handed to every consumer below that used to
        // repeat it (D-37): the unavailable-annotation locator, the intercepted-method collector,
        // AopEligibility's method-level scan, and the diagnostic pass. This alone gives no
        // compile-time guarantee - the consumers below still take a MethodScan, not a Class, but
        // nothing stops a future edit from calling getAllMethods directly again.
        // AopProxyResolverScanCountTest is the guard that actually pins the call count.
        MethodScan scan = new MethodScan(beanClass, ReflectionUtil.getAllMethods(beanClass));

        // Checked before the advisor short-circuit below: a bean using an unavailable annotation
        // must fail even when no advisor is registered at all, which is exactly the configuration
        // that would otherwise return it unproxied.
        rejectUnavailableAnnotations(scan);

        if (advisors.isEmpty()) {
            return memoize(beanClass, beanClass, generation);
        }

        Set<Method> annotated = AopEligibility.findAopAnnotatedMethods(scan);
        Set<Method> intercepted = collectInterceptedMethods(scan);
        // Two different questions, and conflating them cost the warnings once already. Whether to
        // diagnose at all depends on whether the author asked for anything; whether a final class
        // is fatal depends on whether something would actually have been proxied.
        boolean anythingAsked = !intercepted.isEmpty() || !annotated.isEmpty();
        boolean somethingWouldBeProxied = !intercepted.isEmpty();

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
        // FINAL_CLASS problem and its remedy string for every plain final bean, for nothing.
        for (AopEligibility.Problem problem : anythingAsked
                ? AopEligibility.check(beanClass, annotated)
                : java.util.Collections.<AopEligibility.Problem>emptyList()) {
            if (problem.getKind() == AopEligibility.Problem.Kind.FINAL_CLASS) {
                if (somethingWouldBeProxied) {
                    blocking.add(problem);
                } else {
                    // Final, but nothing was going to be proxied anyway, so dropping 'final' would
                    // not have made the annotations work. Say so rather than fail the load.
                    LOGGER.warning("AOP annotations on " + beanClass.getName()
                            + " have no effect: the class is final, so no proxy can be generated, "
                            + "and none of its annotated methods could be intercepted in any case.");
                }
            } else {
                LOGGER.warning("AOP annotation ignored on " + beanClass.getName()
                        + ": " + problem + " The annotation has no effect; the module still loads.");
            }
        }
        if (!blocking.isEmpty()) {
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                    buildMessage(beanClass, blocking));
        }

        if (intercepted.isEmpty()) {
            // Every other skip in this class is named in a warning; this one used to be the
            // exception. "@ExceptionCatch class ServiceImpl extends AbstractService {}" is a shape
            // authors write, and class-level scope does not reach ancestors, so it covers nothing -
            // silence there is the failure mode this whole change exists to remove.
            warnAboutClassLevelAnnotationsCoveringNothing(scan);
            return memoize(beanClass, beanClass, generation);
        }

        LOGGER.fine("Creating AOP proxy class for " + beanClass.getName()
                + " with " + intercepted.size() + " intercepted method(s)");

        List<MethodInterceptor> interceptors = new ArrayList<>();
        for (AopAdvisor advisor : advisors) {
            interceptors.add(new AdvisorScopedInterceptor(advisor, beanClass));
        }
        Class<?> proxyClass = new ProxyFactory(interceptors).createProxyClass(beanClass, intercepted);
        return memoize(beanClass, proxyClass, generation);
    }

    /** Marks every memoized resolution as computed against a configuration that no longer holds. */
    private void invalidate() {
        configGeneration.incrementAndGet();
        resolvedClasses.clear();
    }

    /**
     * Stores a resolution, but only if the configuration it was computed against is still the
     * current one. Returns the resolution either way - it is correct for this call, just not
     * necessarily for the next one.
     */
    private Class<?> memoize(Class<?> beanClass, Class<?> resolved, long generation) {
        if (configGeneration.get() == generation) {
            resolvedClasses.put(beanClass, resolved);
        }
        return resolved;
    }

    /**
     * Throws if the bean uses an annotation declared unavailable.
     */
    private void rejectUnavailableAnnotations(MethodScan scan) {
        Class<?> beanClass = scan.getBeanClass();
        for (Map.Entry<Class<? extends Annotation>, String> entry : unavailableAnnotations.entrySet()) {
            String location = locateAnnotation(scan, entry.getKey());
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
     * <p>
     * The class-level half is routed through this resolver's own per-type
     * {@link AnnotationLookupCache} (D-38) - it used to call
     * {@code AopAdvisor.findClassLevelAnnotationOwner} directly on every method of every
     * unavailable-annotation entry, uncached. Not the same instance {@code PluginManager.wireAop}
     * shares between the advisor and {@code ExceptionInterceptor}: an unavailable annotation type
     * is typically one neither of those ever sees, so this resolver owns its own per-type caches
     * rather than assuming the wiring seam already built one for it.
     *
     * @return the class name for a type-level annotation, {@code class#method} for a method-level
     *         one, or null
     */
    private String locateAnnotation(MethodScan scan, Class<? extends Annotation> type) {
        // Walks the hierarchy: neither @Transactional nor @ExceptionCatch is @Inherited, so an
        // annotation on a superclass method is invisible to getDeclaredMethods() and the refusal
        // below would never fire for it. See issue #309.
        // This asks a different question from interception, on purpose. Interception asks what
        // the proxy will cover; this asks whether the module uses an annotation this release
        // cannot honour at all, and answers fail-closed. Narrowing it to what would be covered was
        // tried and reverted: it let @Transactional on a private method load and run its writes
        // with no transaction, where @ExceptionCatch degrading to "the exception propagates" is at
        // least visible. Reach is shared with interception - a declaration this method overrides,
        // and a class-level annotation on an ancestor, both count. See issue #309.
        AnnotationLookupCache<?> cache = lookupCacheFor(type);
        for (Method method : scan.getMethods()) {
            if (AopAdvisor.findMethodLevelAnnotation(method, type) != null) {
                // The declaring class, not the bean: the method may come from a superclass, and
                // naming the bean would point the author at a file with no such annotation.
                return method.getDeclaringClass().getName() + "#" + method.getName();
            }
            Class<?> owner = cache.classLevelOwner(method);
            if (owner != null) {
                return owner.getName();
            }
        }
        return null;
    }

    /**
     * Per-annotation-type caches this resolver owns for its own class-level lookups. Instance-
     * scoped like {@link #resolvedClasses}: it holds {@code Class} references and must die with
     * the container that owns this resolver, not pin a module's classes for the life of the JVM.
     */
    private final Map<Class<? extends Annotation>, AnnotationLookupCache<?>> lookupCaches =
            new ConcurrentHashMap<>();

    private AnnotationLookupCache<?> lookupCacheFor(Class<? extends Annotation> type) {
        return lookupCaches.computeIfAbsent(type, AnnotationLookupCache::new);
    }

    /**
     * Collects methods matched by at least one advisor, across the whole inheritance hierarchy.
     */
    private Set<Method> collectInterceptedMethods(MethodScan scan) {
        Set<Method> result = new LinkedHashSet<>();
        Class<?> beanClass = scan.getBeanClass();
        // No synthetic filter here: the scan already drops bridge and synthetic declarations.
        // AopEligibility's shadowing rule deliberately does scan them, and a redundant guard here
        // would blur that contrast for the next reader.
        for (Method method : scan.getMethods()) {
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
     * Warns for a class-level annotation that ends up covering no method at all.
     * <p>
     * Every other skip in this class is named; this one used to be the exception, and
     * {@code @ExceptionCatch class ServiceImpl extends AbstractService {}} is a shape authors
     * write. The annotation is located with the same hierarchy walk everything else uses - testing
     * {@code beanClass.isAnnotationPresent} instead missed an annotation inherited from an
     * ancestor, which is the silent no-op this warning exists to remove.
     * <p>
     * The reason is derived rather than assumed. Ancestor scope is only one way to cover nothing;
     * a class whose every method is unproxyable, or whose only methods are the excluded
     * signatures, gets there too, and telling that author to "redeclare the method here" would
     * send them after methods already declared there.
     */
    private void warnAboutClassLevelAnnotationsCoveringNothing(MethodScan scan) {
        Class<?> beanClass = scan.getBeanClass();
        for (AopAdvisor advisor : advisors) {
            Class<? extends Annotation> type = advisor.getAnnotationType();
            if (type == null) {
                continue;
            }
            Class<?> owner = null;
            boolean sawUnreachable = false;
            for (Method method : scan.getMethods()) {
                Class<?> candidate = AopAdvisor.findClassLevelAnnotationOwner(method, type);
                if (candidate == null) {
                    continue;
                }
                owner = candidate;
                if (!AopAdvisor.isExcludedFromClassLevel(method)
                        && !AopEligibility.isProxyable(method, beanClass)) {
                    sawUnreachable = true;
                }
            }
            if (owner == null) {
                // The annotation is on the bean's own type but governs none of its methods, which
                // is what ancestor-only method sets look like.
                if (beanClass.isAnnotationPresent(type)) {
                    LOGGER.warning("@" + type.getSimpleName() + " on " + beanClass.getName()
                            + " covers no method and has no effect. A class-level annotation "
                            + "applies to the methods its own class declares and to subclasses, "
                            + "not to methods inherited from an ancestor; redeclare the method "
                            + "here, or annotate it where it is declared.");
                }
                continue;
            }
            LOGGER.warning("@" + type.getSimpleName() + " on " + owner.getName()
                    + " covers no method of " + beanClass.getName() + " and has no effect: "
                    + (sawUnreachable
                            ? "every method it would cover is one no inheritance proxy can reach."
                            : "the only methods it would cover are excluded from class-level "
                                    + "coverage because swallowing them would produce a silent "
                                    + "wrong answer."));
        }
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
