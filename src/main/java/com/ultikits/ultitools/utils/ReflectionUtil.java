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
     * comparison of two {@link #signatureOf(Method)} results. All of the following must hold:
     * <ol>
     *   <li>same name and same parameter types, in order;</li>
     *   <li>{@code sup}'s declaring class is a <em>proper</em> supertype of {@code sub}'s;</li>
     *   <li>neither declaration is {@code private} or {@code static} - {@code private} methods are
     *       dispatched with {@code invokespecial} and are not even inherited, and {@code static}
     *       methods are <em>hidden</em> rather than overridden;</li>
     *   <li>if {@code sup} is package-private, the two declaring classes are in the same package.
     *       A {@code public} or {@code protected} {@code sup} carries no package condition.</li>
     * </ol>
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
     * 这里提供谓词而不是比较两个 {@link #signatureOf(Method)} 结果的原因。判定条件见上方英文列表。
     * 注意对 {@code sub} 自身的访问级别<b>不设</b>任何条件：Java 允许覆盖时放宽访问权限，同包子类
     * 把包私有方法放宽为 {@code public} 仍是真正的覆盖。包按名称比较，不区分不同类加载器下的同名包。
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
     * Builds a coarse de-duplication key for a method declaration.
     * <p>
     * <b>This key does not answer "are these two declarations the same overridable method".</b>
     * Overriding is directional and not an equivalence relation, so no symmetric key can express
     * it; use {@link #overrides(Method, Method)} for the real relation. Two concrete ways this key
     * misleads: it is <em>asymmetric</em> for a visibility-widening override - a package-private
     * {@code m()} and the {@code public m()} that a same-package subclass overrides it with produce
     * <b>different</b> keys, because only the package-private one carries its package - and it
     * cannot see transitivity, so a declaration reached through an intermediate override is never
     * recognised either.
     * <p>
     * What it does give, cheaply, is a key that never collapses two declarations that are certainly
     * distinct methods: {@code private} and {@code static} declarations fold in their declaring
     * class (neither can be overridden - the JVM dispatches them with {@code invokespecial} /
     * {@code invokestatic}, neither of which consults a subclass), package-private declarations
     * fold in their declaring class's package, and everything else uses the plain name+parameters
     * key. It is safe as a pre-filter or a logging/diagnostic label; it is not safe as an override
     * test.
     * <p>
     * Kept public because it is part of this branch's published surface. Nothing in the framework
     * relies on it for override detection any more - {@link #getAllMethods(Class)} and
     * {@link com.ultikits.ultitools.context.FinalContractValidator} both route through
     * {@link #overrides(Method, Method)} instead (issue #190).
     * <p>
     * <b>这个 key 不能用来判断"两条声明是否属于同一个可覆盖的方法"。</b>覆盖是有方向的、不是等价
     * 关系，任何对称的 key 都无法表达它；真正的判定请用 {@link #overrides(Method, Method)}。它至少
     * 在两处会误导：对放宽访问权限的覆盖它是<em>不对称</em>的——包私有的 {@code m()} 与同包子类用
     * {@code public m()} 覆盖它时，两者的 key <b>不同</b>，因为只有包私有那条带上了包名；它也看不见
     * 传递性，经由中间声明才产生的覆盖关系同样识别不出来。它能廉价提供的只是"绝不会把两个确定不同
     * 的方法合并到一起"：private/static 并入声明类，包私有并入声明类所在的包，其余用纯名称+参数。
     * 作预筛选或日志标签是安全的，作覆盖判定则不然。
     *
     * @param method the method to build a key for
     * @return the signature key
     */
    public static String signatureOf(Method method) {
        StringBuilder signature = new StringBuilder();
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
            signature.append(method.getDeclaringClass().getName()).append('#');
        } else if (isPackagePrivate(modifiers)) {
            signature.append(packageNameOf(method.getDeclaringClass())).append('#');
        }
        signature.append(method.getName());
        for (Class<?> parameterType : method.getParameterTypes()) {
            signature.append('|').append(parameterType.getName());
        }
        return signature.toString();
    }

    /**
     * True for default (package-private) access: neither {@code public}, {@code protected}, nor
     * {@code private}.
     */
    private static boolean isPackagePrivate(int modifiers) {
        return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)
                && !Modifier.isPrivate(modifiers);
    }

    /**
     * The class's package name, or the empty string for the unnamed package.
     */
    private static String packageNameOf(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        return pkg == null ? "" : pkg.getName();
    }
}
