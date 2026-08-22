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
 * 把两趟注解查找的结果记住。两趟都沿父类链走，且都在每次被拦截的调用上执行，而结果在类加载后
 * 不再变化。缓存实例有意不是 static：它持有 Class 与 Method 引用，静态缓存将阻止插件
 * ClassLoader 卸载。
 *
 * @param <A> the annotation type this cache answers for
 * @author wisdomme
 * @since 6.3.0
 */
final class AnnotationLookupCache<A extends Annotation> {

    /** Stands in for "looked up, found nothing", so a miss is cached as cheaply as a hit. */
    private static final Object NONE = new Object();

    private final Class<A> annotationType;
    private final Map<Method, Object> methodLevel = new ConcurrentHashMap<>();
    private final Map<Method, Object> inherited = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> classLevel = new ConcurrentHashMap<>();

    AnnotationLookupCache(Class<A> annotationType) {
        this.annotationType = annotationType;
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

    @SuppressWarnings("unchecked")
    private A unwrap(Object cached) {
        return cached == NONE ? null : (A) cached;
    }
}
