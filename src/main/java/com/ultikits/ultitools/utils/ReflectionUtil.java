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

/**
 * 反射工具类
 * <p>
 * 替代 hutool ReflectUtil / AnnotationUtil
 *
 * @author wisdomme
 * @since 6.2.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Reflection utility requires setAccessible
public final class ReflectionUtil {
    
    private ReflectionUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // ==================== 字段操作 ====================
    
    /**
     * 获取类的所有字段（包括父类）
     *
     * @param clazz 类
     * @return 字段列表
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
     * 获取类的所有字段（包括父类）
     *
     * @param clazz 类
     * @return 字段数组
     */
    public static Field[] getFields(Class<?> clazz) {
        return getAllFields(clazz).toArray(new Field[0]);
    }
    
    /**
     * 获取字段值
     *
     * @param obj   对象
     * @param field 字段
     * @return 字段值
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
     * 获取字段值
     *
     * @param obj       对象
     * @param fieldName 字段名
     * @return 字段值
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Field not found: " + fieldName);
        }
        return getFieldValue(obj, field);
    }
    
    /**
     * 设置字段值
     *
     * @param obj   对象
     * @param field 字段
     * @param value 值
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
     * 设置字段值
     *
     * @param obj       对象
     * @param fieldName 字段名
     * @param value     值
     */
    public static void setFieldValue(Object obj, String fieldName, Object value) {
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Field not found: " + fieldName);
        }
        setFieldValue(obj, field, value);
    }
    
    /**
     * 获取指定字段
     *
     * @param clazz     类
     * @param fieldName 字段名
     * @return 字段，找不到返回 null
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
    
    // ==================== 实例创建 ====================
    
    /**
     * 创建实例（使用无参构造器）
     *
     * @param clazz 类
     * @param <T>   类型
     * @return 实例
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
     * 创建实例（使用带参数的构造器）
     *
     * @param clazz  类
     * @param params 构造器参数
     * @param <T>    类型
     * @return 实例
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
        
        // 尝试精确匹配
        try {
            java.lang.reflect.Constructor<T> constructor = clazz.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(params);
        } catch (NoSuchMethodException e) {
            // 尝试模糊匹配
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to create instance: " + clazz.getName(), e);
        }
        
        // 模糊匹配 - 查找参数数量相同且类型兼容的构造器
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
     * 判断类型是否可赋值
     */
    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (target.isAssignableFrom(source)) {
            return true;
        }
        // 处理基本类型
        if (target.isPrimitive()) {
            return getPrimitiveWrapper(target).isAssignableFrom(source);
        }
        if (source.isPrimitive()) {
            return target.isAssignableFrom(getPrimitiveWrapper(source));
        }
        return false;
    }
    
    /**
     * 获取基本类型对应的包装类
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
     * 创建实例（使用无参构造器，支持私有构造器）
     *
     * @param clazz 类
     * @param <T>   类型
     * @return 实例
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
    
    // ==================== 注解操作 ====================
    
    /**
     * 获取类上的注解
     *
     * @param clazz           类
     * @param annotationClass 注解类型
     * @param <A>             注解类型
     * @return 注解实例，不存在返回 null
     */
    public static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        return clazz.getAnnotation(annotationClass);
    }
    
    /**
     * 获取字段上的注解
     *
     * @param field           字段
     * @param annotationClass 注解类型
     * @param <A>             注解类型
     * @return 注解实例，不存在返回 null
     */
    public static <A extends Annotation> A getAnnotation(Field field, Class<A> annotationClass) {
        return field.getAnnotation(annotationClass);
    }
    
    /**
     * 判断类是否有指定注解
     *
     * @param clazz           类
     * @param annotationClass 注解类型
     * @return 是否有注解
     */
    public static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        return clazz.isAnnotationPresent(annotationClass);
    }
    
    /**
     * 判断字段是否有指定注解
     *
     * @param field           字段
     * @param annotationClass 注解类型
     * @return 是否有注解
     */
    public static boolean hasAnnotation(Field field, Class<? extends Annotation> annotationClass) {
        return field.isAnnotationPresent(annotationClass);
    }

    /**
     * Resolves {@code annotationType} for {@code method}, preferring a declaration on the
     * method itself and falling back to one on the method's declaring class -- most-derived
     * wins, the same precedence {@code SenderTypeValidator} already applies for
     * {@code @CmdTarget} inside this same validator chain, and the precedent Spring's
     * {@code @Transactional} and Spring Security's {@code @PreAuthorize} both document for a
     * class-vs-method annotation conflict.
     * <p>
     * Only the method's OWN declaring class is consulted (via
     * {@code method.getDeclaringClass().getAnnotation(...)}) -- not the class's own ancestors,
     * since neither {@code @CmdCD} nor {@code @UsageLimit} is {@code @Inherited}. This matches
     * {@link #getAllMethods(Class)}'s own hierarchy walk: a method inherited from a superclass
     * without being overridden is returned with that superclass already as its
     * {@code getDeclaringClass()}, so a class-level annotation on that superclass is still found
     * without any extra ancestor walk here.
     * <p>
     * 解析 {@code method} 上的 {@code annotationType}：优先取方法自身的声明，若方法未声明则回退到
     * 方法所属声明类上的声明——方法级优先，与本验证器链中 {@code SenderTypeValidator} 对
     * {@code @CmdTarget} 已经采用的优先级完全一致，也是 Spring 的 {@code @Transactional} 与 Spring
     * Security 的 {@code @PreAuthorize} 在类级/方法级注解冲突时共同采用的先例。
     * <p>
     * 只查询方法自身声明类（通过 {@code method.getDeclaringClass().getAnnotation(...)}）——不会
     * 再向上查询该类的祖先类，因为 {@code @CmdCD} 与 {@code @UsageLimit} 均未标注
     * {@code @Inherited}。这与 {@link #getAllMethods(Class)} 自身的层级遍历一致：一个从父类继承、
     * 未被重写的方法，其 {@code getDeclaringClass()} 本就是该父类，因此父类上的类级注解无需额外的
     * 祖先遍历即可在此被发现。
     * <p>
     * Convenience delegate to {@link #resolveMethodOrClassAnnotation(Method, Class, Class)} with
     * {@code executorClass} as {@code null} -- kept for callers (and existing tests) that only
     * ever had a {@code Method} to resolve against, not the dispatching executor's concrete
     * class. Prefer the 3-argument overload when the concrete executor class is known: it also
     * checks that class's own declaration, closing WR-02 (05-REVIEW.md) -- a class-level
     * annotation declared on a concrete executor SUBCLASS, inherited by an unoverridden {@code
     * @CmdMapping} method whose {@code getDeclaringClass()} is an ancestor, is invisible to this
     * 2-argument form.
     * <p>
     * 委托给 {@link #resolveMethodOrClassAnnotation(Method, Class, Class)}，{@code executorClass}
     * 传 {@code null}——为只有 {@code Method}、拿不到分发执行器具体类的调用方（及既有测试）保留。
     * 已知具体执行器类时优先用三参数重载：它还会检查该类自身的声明，从而关闭 WR-02
     * （05-REVIEW.md）——一个只声明在具体执行器子类上的类级注解，被一个未重写、其
     * {@code getDeclaringClass()} 是祖先类的 {@code @CmdMapping} 方法继承时，这个双参数形式看不见它。
     *
     * @param method         the matched command mapping method <br> 已匹配的命令映射方法
     * @param annotationType the annotation type to resolve <br> 要解析的注解类型
     * @param <A>            the annotation type <br> 注解类型
     * @return the method-level annotation if present, otherwise the declaring class's
     *         annotation, or {@code null} if neither declares it <br> 方法级注解（若存在）；
     *         否则为声明类上的注解；两者均不存在时为 {@code null}
     * @since 6.3.0
     */
    public static <A extends Annotation> A resolveMethodOrClassAnnotation(Method method, Class<A> annotationType) {
        return resolveMethodOrClassAnnotation(method, null, annotationType);
    }

    /**
     * WR-02 (05-REVIEW.md): resolves {@code annotationType} in THREE steps, most-derived-first:
     * (1) {@code method}'s own declaration, (2) {@code executorClass}'s own class-level
     * declaration -- the CONCRETE, most-derived executor class actually loaded, the SAME class
     * {@code PluginManager}'s load-time gate inspects via {@code executor.getClass()} -- (3)
     * {@code method.getDeclaringClass()}'s class-level declaration, for a shared abstract base
     * that declares BOTH the mapping method and the annotation together.
     * <p>
     * Step (2) is what {@link #resolveMethodOrClassAnnotation(Method, Class)} (the 2-arg
     * overload) cannot do: for an inherited, unoverridden {@code @CmdMapping} method, {@code
     * method.getDeclaringClass()} is whatever ANCESTOR first declared it -- never the concrete
     * subclass, since neither {@code @CmdCD} nor {@code @UsageLimit} is {@code @Inherited}. A
     * class-level annotation declared ONLY on such a subclass previously passed {@code
     * PluginManager}'s load-time gate (which correctly checks {@code executor.getClass()}) but
     * was never found by {@code CooldownValidator}/{@code UsageLockValidator} at runtime -- the
     * gate's "fine to load" was a false assurance. Step (3) is kept as a further fallback so a
     * shared abstract base that declares both the mapping and the annotation together -- the
     * pre-WR-02 working case -- is unaffected.
     * <p>
     * {@code executorClass} is checked as a DIRECT declaration only (no ancestor walk of its
     * own): if the concrete class itself does not carry the annotation, step (3) already covers
     * the "declared on an ancestor of the mapping method" case, and there is no third distinct
     * class to consult for the same annotation type.
     *
     * @param method         the matched command mapping method <br> 已匹配的命令映射方法
     * @param executorClass  the concrete {@code BaseCommandExecutor} class dispatching this
     *                       command -- the SAME class {@code PluginManager}'s load-time gate
     *                       inspects -- or {@code null} to fall back to the pre-WR-02,
     *                       declaring-class-only resolution <br> 分发本次命令的具体
     *                       {@code BaseCommandExecutor} 类——与 {@code PluginManager} 加载时
     *                       门禁检查的是同一个类——为 {@code null} 时回退到 WR-02 之前的、
     *                       仅声明类的解析
     * @param annotationType the annotation type to resolve <br> 要解析的注解类型
     * @param <A>            the annotation type <br> 注解类型
     * @return the resolved annotation, or {@code null} if none of method, {@code executorClass},
     *         or the method's declaring class carries one <br> 解析出的注解；方法、
     *         {@code executorClass} 与方法声明类均未携带该注解时为 {@code null}
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
        return method.getDeclaringClass().getAnnotation(annotationType);
    }

    // ==================== 方法操作 ====================
    
    /**
     * 调用方法
     *
     * @param obj    对象
     * @param method 方法
     * @param args   参数
     * @param <T>    返回类型
     * @return 方法返回值
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
     * 获取满足条件的方法
     * <p>
     * Delegates to {@link #getAllMethods(Class)} so callers get the same de-duplicated,
     * bridge/synthetic-free view of the hierarchy - a raw {@code getDeclaredMethods()} walk here
     * would double-count an overridden method (or, once a bean is AOP-proxied, could return both
     * the proxy's intercepted override and the original method as separate hits for the same
     * logical method).
     *
     * @param clazz  类
     * @param filter 过滤条件
     * @return 方法数组
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
     * 获取类的所有方法（包括父类）
     *
     * @param clazz 类
     * @return 方法数组
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
     * methods, so scanning it directly loses every annotation on the rest. Walking the hierarchy
     * recovers them, and collapsing overrides keeps a callback from firing once per level when an
     * override repeats its parent's annotation.
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
     * <p>
     * {@code getDeclaredMethods()} 只返回类自己声明的方法，{@code getMethods()} 只返回 public
     * 方法，两者都不适合在可能是 AOP 代理的 bean 上做注解扫描：代理只为被拦截的方法声明覆盖，
     * 直接扫它会丢掉其余方法上的全部注解。见 issue #190。合并覆盖时以
     * {@link #overrides(Method, Method)} 判定，并保留每个槽内的<b>全部</b>声明而不只是代表——
     * 因为覆盖具有传递性，只与代表比较会漏掉链条最上游的那条声明。
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
     * <p>
     * 覆盖关系是<b>有方向</b>的，也不是等价关系，因此无法用任何对称的 key 相等来表达——这正是
     * 这里提供谓词而不是比较对称签名 key 的原因。判定条件见上方英文列表。
     * 注意对 {@code sub} 自身的访问级别<b>不设</b>任何条件：Java 允许覆盖时放宽访问权限，同包子类
     * 把包私有方法放宽为 {@code public} 仍是真正的覆盖。包按名称比较，不区分不同类加载器下的同名包。
     * <p>
     * 需要说明的是，上面第一条判定的其实是 JLS 8.4.2 的签名相等，而不是 JLS 8.4.8.1 真正要求的
     * 子签名（subsignature）关系：参数类型按擦除后的 {@code Class} 对象比较，因此泛型声明与仅靠
     * 擦除匹配的重写会被当成两个不同方法，即便子签名判定和 javac 都认可它们其实是同一个方法。
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
