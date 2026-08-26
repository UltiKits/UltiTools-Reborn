package com.ultikits.ultitools.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoizes the two annotation lookups in {@link AopAdvisor} for one annotation type.
 * <p>
 * Both walk the superclass chain, and both run on every intercepted invocation - from the
 * advisor's match test and again from the interceptor that reads the annotation's attributes. On a
 * Minecraft server that is tick-rate work for answers that cannot change once the classes are
 * loaded. This is the split Spring uses between {@code AbstractFallbackTransactionAttributeSource}'s
 * caching {@code getTransactionAttribute} and its uncached {@code computeTransactionAttribute}.
 * <p>
 * The class-level cache is keyed on the method's <b>declaring class</b>, the only input that
 * lookup reads. The method-level cache has to be keyed on the method itself, because the answer
 * depends on the whole override chain; the generated proxy hands the same {@link Method} instance
 * to every invocation, so the key is cheap.
 * <p>
 * <b>Instances are deliberately not static.</b> A cache holds {@code Class} and {@code Method}
 * references, and a static one would pin every module's classes for the lifetime of the JVM,
 * defeating the plugin class loader's release on unload. One instance per advisor or interceptor
 * means the cache dies with the container that owns it.
 * <p>
 * <b>One shared instance per annotation type, not one per consumer (D-38).</b> The annotation
 * advisor and {@code ExceptionInterceptor} used to each construct their own cache for the same
 * annotation type, duplicating the class-level and inherited-method maps for no reason - the
 * answers do not depend on which consumer is asking. {@code PluginManager.wireAop} now builds one
 * instance per annotation type and injects it into both at the seam where the advisor is
 * constructed and registered. Public so that seam, in a different package, can construct and pass
 * one; this type does not exist in the released 6.2.5 artifact (D-34), so widening its visibility
 * costs nothing today.
 * <p>
 * Sharing the cache does not merge what each consumer asks it. The advisor's match collapses
 * own-and-inherited into a single presence check; {@code ExceptionInterceptor} needs
 * own-method, then class-level, then inherited-method, with class-level ranking <b>between</b> the
 * other two. Both keep asking their own question - only the memoized answers are shared.
 * <p>
 * 把两趟注解查找的结果记住。两趟都沿父类链走，且都在每次被拦截的调用上执行，而结果在类加载后
 * 不再变化。缓存实例有意不是 static：它持有 Class 与 Method 引用，静态缓存将阻止插件
 * ClassLoader 卸载。同一注解类型只有一份共享实例（D-38），而非每个消费者各自一份；
 * 共享缓存不改变两个消费者各自的问题——advisor 仍然只问「有没有」，
 * ExceptionInterceptor 仍然按方法本身、类级、继承方法的顺序依次判断。
 *
 * @param <A> the annotation type this cache answers for
 * @author wisdomme
 * @since 6.3.0
 */
public final class AnnotationLookupCache<A extends Annotation> {

    /** Stands in for "looked up, found nothing", so a miss is cached as cheaply as a hit. */
    private static final Object NONE = new Object();

    private final Class<A> annotationType;
    private final Map<Method, Object> methodLevel = new ConcurrentHashMap<>();
    private final Map<Method, Object> inherited = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> classLevel = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> classLevelOwner = new ConcurrentHashMap<>();

    /**
     * @param annotationType the annotation type this cache answers for
     */
    public AnnotationLookupCache(Class<A> annotationType) {
        this.annotationType = annotationType;
    }

    /**
     * An unshared cache for a caller that is not wired through {@code PluginManager.wireAop}'s
     * shared seam (D-38) - standalone construction, or a consumer exercised in isolation.
     * Production wiring never calls this: it builds one instance and injects it into both the
     * advisor and {@code ExceptionInterceptor} explicitly, so the two share it instead of each
     * building their own.
     *
     * @param annotationType the annotation type this cache answers for
     * @param <A>            the annotation type
     * @return a new, unshared cache instance
     */
    static <A extends Annotation> AnnotationLookupCache<A> standalone(Class<A> annotationType) {
        return new AnnotationLookupCache<>(annotationType);
    }

    /**
     * @param method the method being considered, may be null
     * @return the annotation on this method or on a declaration it overrides, or null
     */
    A ownMethod(Method method) {
        return method == null ? null : method.getAnnotation(annotationType);
    }

    /**
     * @param method the method being considered, may be null
     * @return the annotation on a declaration this method overrides, or null
     */
    A inheritedMethod(Method method) {
        if (method == null) {
            return null;
        }
        Object cached = inherited.get(method);
        if (cached == null) {
            A found = AopAdvisor.findInheritedMethodAnnotation(method, annotationType);
            cached = found == null ? NONE : found;
            inherited.put(method, cached);
        }
        return unwrap(cached);
    }

    A methodLevel(Method method) {
        if (method == null) {
            return null;
        }
        Object cached = methodLevel.get(method);
        if (cached == null) {
            A found = AopAdvisor.findMethodLevelAnnotation(method, annotationType);
            cached = found == null ? NONE : found;
            methodLevel.put(method, cached);
        }
        return unwrap(cached);
    }

    /**
     * @param method the method being considered, may be null
     * @return the governing class-level annotation, or null
     */
    A classLevel(Method method) {
        if (method == null) {
            return null;
        }
        Class<?> declaring = method.getDeclaringClass();
        Object cached = classLevel.get(declaring);
        if (cached == null) {
            A found = AopAdvisor.findClassLevelAnnotation(method, annotationType);
            cached = found == null ? NONE : found;
            classLevel.put(declaring, cached);
        }
        return unwrap(cached);
    }

    /**
     * The class whose type-level annotation governs the method, cached the same way
     * {@link #classLevel(Method)} is - same key, same walk, just returning the owner instead of
     * the annotation instance. {@code AopProxyResolver.locateAnnotation} used to call
     * {@code AopAdvisor.findClassLevelAnnotationOwner} directly here, uncached, on every method of
     * every unavailable-annotation entry (D-38).
     *
     * @param method the method being considered, may be null
     * @return the class carrying the governing class-level annotation, or null
     */
    Class<?> classLevelOwner(Method method) {
        if (method == null) {
            return null;
        }
        Class<?> declaring = method.getDeclaringClass();
        Object cached = classLevelOwner.get(declaring);
        if (cached == null) {
            Class<?> found = AopAdvisor.findClassLevelAnnotationOwner(method, annotationType);
            cached = found == null ? NONE : found;
            classLevelOwner.put(declaring, cached);
        }
        return cached == NONE ? null : (Class<?>) cached;
    }

    @SuppressWarnings("unchecked")
    private A unwrap(Object cached) {
        return cached == NONE ? null : (A) cached;
    }
}
