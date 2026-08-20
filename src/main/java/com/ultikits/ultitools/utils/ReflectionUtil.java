package com.ultikits.ultitools.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * most specific override of each signature.
     * <p>
     * {@code Class.getDeclaredMethods()} returns only the methods a class declares itself, and
     * {@code Class.getMethods()} returns only public ones. Neither is right for annotation scanning
     * on a bean that may be an AOP proxy: the proxy declares overrides only for intercepted
     * methods, so scanning it directly loses every annotation on the rest. Walking the hierarchy
     * recovers them, and de-duplicating by signature keeps a callback from firing once per level
     * when an override repeats its parent's annotation.
     * <p>
     * Bridge and synthetic methods are skipped: they carry no author-written annotations and, being
     * compiler artifacts, are never the method a scanner means to find.
     * <p>
     * {@code Object}'s own methods are excluded.
     * <p>
     * {@code getDeclaredMethods()} 只返回类自己声明的方法，{@code getMethods()} 只返回 public
     * 方法，两者都不适合在可能是 AOP 代理的 bean 上做注解扫描：代理只为被拦截的方法声明覆盖，
     * 直接扫它会丢掉其余方法上的全部注解。见 issue #190。
     *
     * @param clazz the class to scan, may be null
     * @return the methods, subclass overrides first; empty if clazz is null
     */
    public static List<Method> getAllMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        if (clazz == null) {
            return result;
        }
        Set<String> seenSignatures = new HashSet<>();
        for (Class<?> current = clazz; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                if (seenSignatures.add(signatureOf(method))) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    /**
     * Builds a signature key that is equal for a method and the override that hides it.
     * <p>
     * {@code private} and {@code static} methods cannot be overridden - the JVM dispatches them
     * with {@code invokespecial} / {@code invokestatic}, neither of which consults a subclass - so
     * a same-named, same-parameter-list method at another hierarchy level is a distinct method, not
     * a duplicate declaration of the same one. The declaring class is folded into the key for those
     * two cases so they are never collapsed together; overridable methods keep the plain
     * name+parameters key so the subclass's override still wins.
     * <p>
     * Package-private methods sit in between. Per JLS 8.4.8.1, a package-private method <em>is</em>
     * overridden by a same-signature method in a subclass in the <b>same</b> package, but a
     * same-signature package-private method in a subclass in a <em>different</em> package is a
     * distinct method, exactly like the private/static case above. The declaring class's
     * <b>package</b> - not its full name - is folded into the key for package-private methods: two
     * hierarchy levels in the same package still collapse to one (the subclass's override wins, as
     * for any other override), while a same-named method reappearing in a different package's
     * subclass is kept as its own entry instead of silently displacing the parent's.
     * <p>
     * Public so that other JLS-8.4.8.1-sensitive scanners can ask "is this method declaration the
     * same overridable slot as that one" without re-deriving the rule -
     * {@link com.ultikits.ultitools.context.FinalContractValidator} compares two
     * {@code signatureOf} results for exactly that reason, rather than matching on name and
     * parameter types alone the way this method's callers here do.
     * <p>
     * {@code private} 和 {@code static} 方法无法被覆盖——JVM 用 {@code invokespecial} /
     * {@code invokestatic} 分派它们，都不查子类——所以另一层级上同名同参数的方法是一个独立的
     * 方法，而不是同一个方法的重复声明。为这两种情况把声明类并入 key，避免被误合并；可覆盖的
     * 方法仍用纯名称+参数的 key，保证子类的覆盖版本胜出。
     * <p>
     * 包私有（default）方法介于二者之间。根据 JLS 8.4.8.1，包私有方法只会被<b>同一个包</b>内
     * 签名相同的子类方法覆盖；不同包的子类中出现的同名同参数包私有方法则是一个独立的方法，和
     * 上面 private/static 的情形一样。为包私有方法把声明类所在的<b>包</b>（而非完整类名）并入
     * key：同包内的多个层级依然会合并成一个（子类的覆盖版本胜出，与其他可覆盖方法一致），而
     * 不同包的子类中出现的同名方法则各自保留，不会悄悄顶替父类的那一个。
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
