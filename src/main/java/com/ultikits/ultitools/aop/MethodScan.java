package com.ultikits.ultitools.aop;

import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * The result of one {@link ReflectionUtil#getAllMethods(Class)} hierarchy scan, together with the
 * bean class it was scanned for.
 * <p>
 * {@link AopProxyResolver#resolve(Class)} used to hand a bare {@code Class<?>} to each of its
 * consumers, and every one of them repeated the same reflective hierarchy walk: the unavailable-
 * annotation locator, the intercepted-method collector, the diagnostic pass, and
 * {@link AopEligibility#findAopAnnotatedMethods(Class)} - four scans of the same hierarchy on the
 * startup path, for every bean of every module. This carries the scan's own result so consumers
 * stop repeating it (D-37).
 * <p>
 * The bean class travels with the methods rather than being re-derived by each consumer, because
 * two of {@link AopEligibility}'s five rules are relative to it - a method is not intrinsically
 * unproxyable, it is unproxyable <em>from a particular subclass</em>. Passing the two separately
 * is how they would drift apart later.
 * <p>
 * Stated limitation, not papered over: this is a value object, not a capability. Nothing stops a
 * future edit from calling {@link ReflectionUtil#getAllMethods(Class)} directly again instead of
 * building or reusing a scan - the only guard is {@code AopProxyResolverScanCountTest}, which
 * fails if a rescan returns. See issue #309.
 *
 * @author wisdomme
 * @since 6.3.0
 */
final class MethodScan {

    private final Class<?> beanClass;
    private final List<Method> methods;

    /**
     * @param beanClass the class the methods were scanned for
     * @param methods   the scan result, from {@link ReflectionUtil#getAllMethods(Class)}
     */
    MethodScan(Class<?> beanClass, List<Method> methods) {
        this.beanClass = beanClass;
        this.methods = Collections.unmodifiableList(methods);
    }

    /**
     * Convenience factory for a caller that has not already scanned the class.
     * <p>
     * Exists so single-class entry points that predate {@code MethodScan} -
     * {@link AopEligibility#findAopAnnotatedMethods(Class)} among them - keep working without
     * forcing every caller to scan for itself first. {@link AopProxyResolver#resolve(Class)} does
     * not use this factory: it builds its one scan directly and hands the same instance to every
     * consumer, which is the single-pass guarantee this class exists for.
     *
     * @param beanClass the class to scan
     * @return a scan of the given class's method hierarchy
     */
    static MethodScan of(Class<?> beanClass) {
        return new MethodScan(beanClass, ReflectionUtil.getAllMethods(beanClass));
    }

    /**
     * @return the class this scan was taken for
     */
    Class<?> getBeanClass() {
        return beanClass;
    }

    /**
     * @return the scanned methods, subclass overrides first, bridge and synthetic declarations
     *         already dropped
     */
    List<Method> getMethods() {
        return methods;
    }
}
