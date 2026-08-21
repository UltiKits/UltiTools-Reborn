package com.ultikits.ultitools.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoizes {@link AopAdvisor#findClassLevelAnnotation(Method, Class)} for one annotation type.
 * <p>
 * The lookup walks the superclass chain calling {@code Class.getAnnotation} at each level, and it
 * runs on every intercepted invocation - from the advisor's match test and again from the
 * interceptor that reads the annotation's attributes. On a Minecraft server that is tick-rate
 * work. The answer cannot change for the life of a class, so it is computed once. This is the
 * split Spring uses between {@code AbstractFallbackTransactionAttributeSource}'s caching
 * {@code getTransactionAttribute} and its uncached {@code computeTransactionAttribute}.
 * <p>
 * Keyed on the method's <b>declaring class</b> rather than the method, because that is the only
 * input the lookup reads and it sidesteps {@link Method} identity entirely - two reflective
 * lookups of the same method produce distinct objects.
 * <p>
 * <b>Instances are deliberately not static.</b> A cache holds {@code Class} references, and a
 * static one would pin every module's classes for the lifetime of the JVM, defeating the plugin
 * class loader's release on unload. One instance per advisor or interceptor means the cache dies
 * with the container that owns it.
 * <p>
 * 把类级注解查找的结果按声明类记住。该查找在每次被拦截的调用上跑两趟，而结果在类的生命周期内
 * 不变。缓存实例有意不是 static：缓存会持有 Class 引用，静态缓存将阻止插件 ClassLoader 卸载。
 *
 * @param <A> the annotation type this cache answers for
 * @author wisdomme
 * @since 6.3.0
 */
final class ClassLevelAnnotationCache<A extends Annotation> {

    /** Stands in for "looked up, found nothing", so a miss is cached as cheaply as a hit. */
    private static final Object NONE = new Object();

    private final Class<A> annotationType;
    private final Map<Class<?>, Object> byDeclaringClass = new ConcurrentHashMap<>();

    ClassLevelAnnotationCache(Class<A> annotationType) {
        this.annotationType = annotationType;
    }

    /**
     * @param method the method being considered, may be null
     * @return the governing class-level annotation, or null
     */
    @SuppressWarnings("unchecked")
    A get(Method method) {
        if (method == null) {
            return null;
        }
        Class<?> declaring = method.getDeclaringClass();
        Object cached = byDeclaringClass.get(declaring);
        if (cached == null) {
            A found = AopAdvisor.findClassLevelAnnotation(method, annotationType);
            cached = found == null ? NONE : found;
            byDeclaringClass.put(declaring, cached);
        }
        return cached == NONE ? null : (A) cached;
    }
}
