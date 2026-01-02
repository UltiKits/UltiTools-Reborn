package com.ultikits.ultitools.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 基本类型判断工具类
 * <p>
 * 替代 hutool ClassUtil.isBasicType() / ObjectUtil.isBasicType()
 * 
 * @author wisdomme
 * @since 7.0.0
 */
public final class BasicTypeUtil {
    
    /**
     * 基本类型及其包装类集合
     */
    private static final Set<Class<?>> BASIC_TYPES = new HashSet<>(Arrays.asList(
        // 原始类型
        boolean.class, byte.class, char.class, short.class,
        int.class, long.class, float.class, double.class, void.class,
        // 包装类型
        Boolean.class, Byte.class, Character.class, Short.class,
        Integer.class, Long.class, Float.class, Double.class, Void.class,
        // String 也视为基本类型
        String.class
    ));
    
    private BasicTypeUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 判断是否为基本类型（包括包装类型和 String）
     *
     * @param clazz 类
     * @return 是否为基本类型
     */
    public static boolean isBasicType(Class<?> clazz) {
        return clazz != null && BASIC_TYPES.contains(clazz);
    }
    
    /**
     * 判断对象是否为基本类型
     *
     * @param obj 对象
     * @return 是否为基本类型
     */
    public static boolean isBasicType(Object obj) {
        return obj != null && isBasicType(obj.getClass());
    }
}
