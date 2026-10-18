package com.ultikits.ultitools.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ultikits.ultitools.aop.ProxyFactory;

/**
 * Reflection utility class.
 * <p>
 * Replaces hutool's ReflectUtil / AnnotationUtil.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Reflection utility requires setAccessible
public final class ReflectionUtil {

    private ReflectionUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== Field operations ====================

    /**
     * Gets all fields of a class (including superclasses).
     *
     * @param clazz the class
     * @return the field list
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            currentClass = currentClass.getSuperclass();
        }
        return fields;
    }

    /**
     * Gets all fields of a class (including superclasses).
     *
     * @param clazz the class
     * @return the field array
     */
    public static Field[] getFields(Class<?> clazz) {
        return getAllFields(clazz).toArray(new Field[0]);
    }

    /**
     * Gets a field's value.
     *
     * @param obj   the object
     * @param field the field
     * @return the field value
     */
    public static Object getFieldValue(Object obj, Field field) {
        try {
            field.setAccessible(true);
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to get field value: " + field.getName(), e);
        }
    }

    /**
     * Gets a field's value.
     *
     * @param obj       the object
     * @param fieldName the field name
     * @return the field value
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Field not found: " + fieldName);
        }
        return getFieldValue(obj, field);
    }

    /**
     * Sets a field's value.
     *
     * @param obj   the object
     * @param field the field
     * @param value the value
     */
    public static void setFieldValue(Object obj, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to set field value: " + field.getName(), e);
        }
    }

    /**
     * Sets a field's value.
     *
     * @param obj       the object
     * @param fieldName the field name
     * @param value     the value
     */
    public static void setFieldValue(Object obj, String fieldName, Object value) {
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Field not found: " + fieldName);
        }
        setFieldValue(obj, field, value);
    }

    /**
     * Gets the specified field.
     *
     * @param clazz     the class
     * @param fieldName the field name
     * @return the field, or {@code null} if not found
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    // ==================== Instance creation ====================

    /**
     * Creates an instance (using the no-arg constructor).
     *
     * @param clazz the class
     * @param <T>   the type
     * @return the instance
     */
    public static <T> T newInstance(Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException |
                 InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("Failed to create instance: " + clazz.getName(), e);
        }
    }
    
    /**
     * Creates an instance (using a parameterized constructor).
     *
     * @param clazz  the class
     * @param params the constructor parameters
     * @param <T>    the type
     * @return the instance
     */
    @SuppressWarnings("unchecked")
    public static <T> T newInstance(Class<T> clazz, Object... params) {
        if (params == null || params.length == 0) {
            return newInstance(clazz);
        }

        Class<?>[] paramTypes = new Class[params.length];
        for (int i = 0; i < params.length; i++) {
            paramTypes[i] = params[i] == null ? Object.class : params[i].getClass();
        }

        // Try an exact match
        try {
            java.lang.reflect.Constructor<T> constructor = clazz.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(params);
        } catch (NoSuchMethodException e) {
            // Fall through to a fuzzy match
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to create instance: " + clazz.getName(), e);
        }

        // Fuzzy match - find a constructor with the same parameter count and compatible types
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] ctorParamTypes = constructor.getParameterTypes();
            if (ctorParamTypes.length == params.length) {
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (params[i] != null && !isAssignable(ctorParamTypes[i], params[i].getClass())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    try {
                        constructor.setAccessible(true);
                        return (T) constructor.newInstance(params);
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to create instance: " + clazz.getName(), e);
                    }
                }
            }
        }
        
        throw new IllegalArgumentException("No suitable constructor found for: " + clazz.getName());
    }

    /**
     * Whether the type is assignable.
     */
    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (target.isAssignableFrom(source)) {
            return true;
        }
        // Handle primitive types
        if (target.isPrimitive()) {
            return getPrimitiveWrapper(target).isAssignableFrom(source);
        }
        if (source.isPrimitive()) {
            return target.isAssignableFrom(getPrimitiveWrapper(source));
        }
        return false;
    }

    /**
     * Gets the wrapper class corresponding to a primitive type.
     */
    private static Class<?> getPrimitiveWrapper(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }
    
    /**
     * Creates an instance (using the no-arg constructor, private constructors included).
     *
     * @param clazz the class
     * @param <T>   the type
     * @return the instance
     */
    public static <T> T newInstanceIfPossible(Class<T> clazz) {
        try {
            java.lang.reflect.Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Annotation operations ====================

    /**
     * Gets an annotation on a class.
     *
     * @param clazz           the class
     * @param annotationClass the annotation type
     * @param <A>             the annotation type
     * @return the annotation instance, or {@code null} if absent
     */
    public static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        return clazz.getAnnotation(annotationClass);
    }

    /**
     * Gets an annotation on a field.
     *
     * @param field           the field
     * @param annotationClass the annotation type
     * @param <A>             the annotation type
     * @return the annotation instance, or {@code null} if absent
     */
    public static <A extends Annotation> A getAnnotation(Field field, Class<A> annotationClass) {
        return field.getAnnotation(annotationClass);
    }

    /**
     * Whether a class carries the specified annotation.
     *
     * @param clazz           the class
     * @param annotationClass the annotation type
     * @return whether the annotation is present
     */
    public static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        return clazz.isAnnotationPresent(annotationClass);
    }

    /**
     * Whether a field carries the specified annotation.
     *
     * @param field           the field
     * @param annotationClass the annotation type
     * @return whether the annotation is present
     */
    public static boolean hasAnnotation(Field field, Class<? extends Annotation> annotationClass) {
        return field.isAnnotationPresent(annotationClass);
    }

    /**
     * Resolves {@code annotationType} for {@code method} without a concrete executor class: the
     * method's own declaration, then the class-level declaration on the class that declared the
     * mapping in source. Most-derived wins, the same precedence {@code SenderTypeValidator}
     * applies for {@code @CmdTarget}, and the precedent Spring's {@code @Transactional} and Spring
     * Security's {@code @PreAuthorize} both document for a class-versus-method conflict.
     * <p>
     * Convenience delegate to {@link #resolveMethodOrClassAnnotation(Method, Class, Class)} with
     * {@code executorClass} as {@code null}; that overload's javadoc is the single statement of
     * the rule, including how a {@code ProxyFactory}-generated override is mapped back to the
     * declaration its author wrote (case 36). Prefer the 3-argument overload whenever the
     * dispatching executor's concrete class is known: only it sees a class-level annotation
     * declared on a concrete subclass that inherits the mapping unoverridden (WR-02).
     *
     * @param method         the matched command mapping method
     * @param annotationType the annotation type to resolve
     * @param <A>            the annotation type
     * @return the method-level annotation if present, otherwise the class-level annotation of
     *         the class that declared the mapping in source, or {@code null} if neither declares it
     * @since 6.3.0
     */
    public static <A extends Annotation> A resolveMethodOrClassAnnotation(Method method, Class<A> annotationType) {
        return resolveMethodOrClassAnnotation(method, null, annotationType);
    }

    /**
     * Resolves {@code annotationType} for a command mapping in three steps, most-derived first:
     * <ol>
     *   <li>{@code method}'s own declaration;</li>
     *   <li>{@code executorClass}'s own class-level declaration -- the concrete executor class
     *       actually dispatching, the same class {@code PluginManager}'s load-time gate inspects
     *       through {@code executor.getClass()} (WR-02);</li>
     *   <li>the class-level declaration of the class that declared the mapping in source.</li>
     * </ol>
     * Neither {@code @CmdCD} nor {@code @UsageLimit} is {@code @Inherited}, so no step walks a
     * class's ancestors. Step 2 is what lets a class-level annotation on a concrete subclass
     * govern a mapping it inherits unoverridden; step 3 is what lets a shared abstract base that
     * declares both the mapping and the annotation govern every inheritor.
     * <p>
     * <b>Generated proxies (case 36).</b> When {@code method} is an override generated by
     * {@code ProxyFactory} (its declaring class is a proxy, by
     * {@code ProxyFactory.isProxyClass}), step 3 does not read the generated class: it reads the
     * class that declared the overridden method in source -- the most derived non-proxy
     * declaration that the override overrides, by {@link #overrides(Method, Method)}. The proxy
     * copies only the proxied class's own type annotations and the intercepted methods' own
     * annotations, so reading the generated class here would lose a base class's class-level
     * annotation for exactly the mappings that carry an AOP annotation. Steps 1 and 2 are
     * unchanged on a proxy (the copied method annotations and the copied type annotations of the
     * proxied class), so the precedence is the same for proxied and ordinary executors, for
     * inherited and for overridden mappings alike. An override declared in the concrete class is
     * governed by that class, not by its base, with or without a proxy.
     * <p>
     * <b>Consumers.</b> {@code CooldownValidator} (whether a mapping is bound, its seconds, and
     * the unresolved-binding failure), {@code UsageLockValidator} (the lock scope) and
     * {@code PluginManager}'s config-binding resolution at module load all call this method, so
     * load-time resolution and dispatch-time enforcement share this one answer.
     *
     * @param method         the matched command mapping method; may be a {@code ProxyFactory}
     *                       override
     * @param executorClass  the concrete {@code BaseCommandExecutor} class dispatching this
     *                       command -- the same class {@code PluginManager}'s load-time gate
     *                       inspects -- or {@code null} to skip step 2
     * @param annotationType the annotation type to resolve
     * @param <A>            the annotation type
     * @return the resolved annotation, or {@code null} if none of the three steps finds one
     * @since 6.3.0
     */
    public static <A extends Annotation> A resolveMethodOrClassAnnotation(Method method, @Nullable Class<?> executorClass,
            Class<A> annotationType) {
        A onMethod = method.getAnnotation(annotationType);
        if (onMethod != null) {
            return onMethod;
        }
        if (executorClass != null) {
            A onExecutorClass = executorClass.getAnnotation(annotationType);
            if (onExecutorClass != null) {
                return onExecutorClass;
            }
        }
        return sourceDeclarationOf(method).getDeclaringClass().getAnnotation(annotationType);
    }

    /**
     * The declaration a module author wrote for {@code method}: for an override generated by
     * {@code ProxyFactory}, the most derived non-proxy declaration in the proxy's superclass
     * chain that the override overrides; otherwise {@code method} itself.
     * <p>
     * Proxy identity is read from {@code ProxyFactory.isProxyClass}, its one source. Bridge and
     * synthetic methods are skipped, as in {@link #getAllMethods(Class)}. Nothing is cached: a
     * static map keyed by {@code Method} or {@code Class} would pin module class loaders after the
     * module unloads, and the walk only runs for a method declared by a proxy.
     *
     * @param method the method to map back, never null
     * @return the author's declaration, or {@code method} when it is not a proxy override or no
     *         declaration it overrides is found
     */
    private static Method sourceDeclarationOf(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        if (!ProxyFactory.isProxyClass(declaringClass)) {
            return method;
        }
        for (Class<?> current = declaringClass.getSuperclass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (ProxyFactory.isProxyClass(current)) {
                continue;
            }
            for (Method candidate : current.getDeclaredMethods()) {
                if (!candidate.isBridge() && !candidate.isSynthetic()
                        && candidate.getName().equals(method.getName())
                        && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())
                        && overrides(method, candidate)) {
                    return candidate;
                }
            }
        }
        return method;
    }

    // ==================== Method operations ====================

    /**
     * Invokes a method.
     *
     * @param obj    the object
     * @param method the method
     * @param args   the arguments
     * @param <T>    the return type
     * @return the method's return value
     */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object obj, java.lang.reflect.Method method, Object... args) {
        try {
            method.setAccessible(true);
            return (T) method.invoke(obj, args);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke method: " + method.getName(), e);
        }
    }

    /**
     * Gets the methods that satisfy a condition.
     * <p>
     * Delegates to {@link #getAllMethods(Class)} so callers get the same de-duplicated,
     * bridge/synthetic-free view of the hierarchy - a raw {@code getDeclaredMethods()} walk here
     * would double-count an overridden method (or, once a bean is AOP-proxied, could return both
     * the proxy's intercepted override and the original method as separate hits for the same
     * logical method).
     *
     * @param clazz  the class
     * @param filter the filter condition
     * @return the method array
     */
    public static java.lang.reflect.Method[] getMethods(Class<?> clazz, java.util.function.Predicate<java.lang.reflect.Method> filter) {
        java.util.List<java.lang.reflect.Method> result = new ArrayList<>();
        for (Method method : getAllMethods(clazz)) {
            if (filter == null || filter.test(method)) {
                result.add(method);
            }
        }
        return result.toArray(new java.lang.reflect.Method[0]);
    }

    /**
     * Gets all methods of a class (including superclasses).
     *
     * @param clazz the class
     * @return the method array
     */
    public static java.lang.reflect.Method[] getMethods(Class<?> clazz) {
        return getMethods(clazz, null);
    }

    /**
     * Collects every method visible on the given class, walking the hierarchy and keeping only the
     * most specific declaration of each overridable method.
     * <p>
     * {@code Class.getDeclaredMethods()} returns only the methods a class declares itself, and
     * {@code Class.getMethods()} returns only public ones. Neither is right for annotation scanning
     * on a bean that may be an AOP proxy: the proxy declares overrides only for intercepted
     * methods, so scanning it directly loses every annotation on the rest (issue #190). Walking the
     * hierarchy recovers them, and collapsing overrides keeps a callback from firing once per level
     * when an override repeats its parent's annotation.
     * <p>
     * Declarations are grouped into <em>slots</em> using {@link #overrides(Method, Method)}, and
     * each slot contributes exactly one entry: the most derived declaration, which is the one whose
     * annotations a scanner should see. A slot remembers <b>every</b> declaration folded into it,
     * not just that representative, because overriding is transitive (JLS 8.4.8.1): in
     * {@code x.A.m()} (package-private) &rarr; {@code x.B.m()} (public) &rarr; {@code y.C.m()}
     * (public), the leaf does not directly override the root - different packages - but does so
     * through the middle declaration, and all three are one method. Testing a candidate only
     * against the surviving representative would keep the root as a spurious second entry.
     * <p>
     * {@code private} and {@code static} methods never participate in overriding, so a same-named,
     * same-parameter declaration at another level always starts its own slot and both survive.
     * <p>
     * Bridge and synthetic methods are skipped: they carry no author-written annotations and, being
     * compiler artifacts, are never the method a scanner means to find.
     * <p>
     * {@code Object}'s own methods are excluded.
     *
     * @param clazz the class to scan, may be null
     * @return the methods, subclass overrides first; empty if clazz is null
     */
    public static List<Method> getAllMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        if (clazz == null) {
            return result;
        }
        // Slots are bucketed by name + parameter count purely to keep the scan cheap: two
        // declarations differing on either can never be the same method, so they never need to be
        // compared. All correctness lives in overrides(...).
        Map<String, List<List<Method>>> slotsByKey = new HashMap<>();
        for (Class<?> current = clazz; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                String key = method.getName() + '/' + method.getParameterTypes().length;
                List<List<Method>> bucket = slotsByKey.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    slotsByKey.put(key, bucket);
                }
                List<Method> slot = findSlotOverriding(bucket, method);
                if (slot == null) {
                    // Nothing already collected overrides this declaration, so it is a method in
                    // its own right. The walk runs subclass-first, so the first declaration to
                    // open a slot is also the most derived one - it becomes the representative.
                    slot = new ArrayList<>();
                    bucket.add(slot);
                    result.add(method);
                }
                slot.add(method);
            }
        }
        return result;
    }

    /**
     * Finds the slot in {@code bucket} whose declarations override {@code candidate}, or
     * {@code null} if none does.
     * <p>
     * Every declaration in a slot is tested, not just its representative - see
     * {@link #getAllMethods(Class)} for why transitivity makes that necessary.
     *
     * @param bucket    the slots sharing {@code candidate}'s name and parameter count
     * @param candidate the declaration being placed
     * @return the slot {@code candidate} belongs to, or null if it opens a new one
     */
    private static List<Method> findSlotOverriding(List<List<Method>> bucket, Method candidate) {
        for (List<Method> slot : bucket) {
            for (Method member : slot) {
                if (overrides(member, candidate)) {
                    return slot;
                }
            }
        }
        return null;
    }

    /**
     * Whether {@code sub} overrides {@code sup} per JLS 8.4.8.1.
     * <p>
     * Overriding is <b>directional</b> and is not an equivalence relation, so it cannot be
     * expressed as equality of any symmetric key - which is why this predicate exists rather than a
     * comparison against a symmetric signature key. All of the following must hold:
     * <ol>
     *   <li>same name and same parameter types, in order;</li>
     *   <li>{@code sup}'s declaring class is a <em>proper</em> supertype of {@code sub}'s;</li>
     *   <li>neither declaration is {@code private} or {@code static} - {@code private} methods are
     *       dispatched with {@code invokespecial} and are not even inherited, and {@code static}
     *       methods are <em>hidden</em> rather than overridden;</li>
     *   <li>if {@code sup} is package-private, the two declaring classes are in the same package.
     *       A {@code public} or {@code protected} {@code sup} carries no package condition.</li>
     * </ol>
     * <b>The first condition is signature equality (JLS 8.4.2), not the subsignature relation that
     * JLS 8.4.8.1 actually requires.</b> Parameter types are compared as erased {@code Class}
     * objects, so a generic declaration and an override that matches only its erasure are treated
     * as distinct methods, even though a subsignature comparison - and javac - accept them as one:
     * <pre>{@code
     * class GenBase<T>                        { public void take(T t) { } }   // erases to take(Object)
     * class GenChild extends GenBase<String>  { @Override public void take(String t) { } }
     * }</pre>
     * {@code overrides(GenChild.take, GenBase.take)} returns {@code false} here, and the two are
     * reported as distinct methods even though javac accepts the {@code @Override}.
     * <p>
     * There is deliberately no condition on {@code sub}'s own access: Java permits an override to
     * widen access, and a package-private method widened to {@code public} by a same-package
     * subclass is a genuine override.
     * <p>
     * Public so that {@link com.ultikits.ultitools.context.FinalContractValidator} shares this one
     * implementation instead of re-deriving the rule - two independent copies are how the two
     * consumers drifted apart in the first place (issue #190).
     * <p>
     * Packages are compared by name, matching how the rest of this class treats them; two
     * same-named packages defined by different class loaders are distinct runtime packages to the
     * JVM, a distinction this check does not make.
     *
     * @param sub the potentially overriding declaration, may be null
     * @param sup the potentially overridden declaration, may be null
     * @return true if {@code sub} overrides {@code sup}; false if either is null
     */
    public static boolean overrides(Method sub, Method sup) {
        if (sub == null || sup == null) {
            return false;
        }
        if (!sub.getName().equals(sup.getName())
                || !Arrays.equals(sub.getParameterTypes(), sup.getParameterTypes())) {
            return false;
        }
        int subModifiers = sub.getModifiers();
        int supModifiers = sup.getModifiers();
        if (Modifier.isPrivate(subModifiers) || Modifier.isPrivate(supModifiers)
                || Modifier.isStatic(subModifiers) || Modifier.isStatic(supModifiers)) {
            return false;
        }
        Class<?> subClass = sub.getDeclaringClass();
        Class<?> supClass = sup.getDeclaringClass();
        if (subClass == supClass || !supClass.isAssignableFrom(subClass)) {
            return false;
        }
        return !isPackagePrivate(supModifiers)
                || packageNameOf(supClass).equals(packageNameOf(subClass));
    }

    /**
     * True for default (package-private) access: neither {@code public}, {@code protected}, nor
     * {@code private}.
     * <p>
     * Public so that consumers deciding the same question share this implementation. See the note
     * on {@link #overrides(Method, Method)} for why a second copy is a hazard rather than a
     * convenience.
     *
     * @param modifiers the modifiers to test, from {@code Member#getModifiers()}
     * @return true if the modifiers describe default access
     */
    public static boolean isPackagePrivate(int modifiers) {
        return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)
                && !Modifier.isPrivate(modifiers);
    }

    /**
     * The class's package name, or the empty string for the unnamed package.
     * <p>
     * Public for the same reason as {@link #isPackagePrivate(int)}: package-private access is
     * decided here and by {@code AopEligibility}, and the two must not answer differently.
     *
     * @param clazz the class to inspect
     * @return the package name, or the empty string
     */
    public static String packageNameOf(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        return pkg == null ? "" : pkg.getName();
    }
}
