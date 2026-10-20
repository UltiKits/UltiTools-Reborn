package com.ultikits.ultitools.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Basic type determination utility class.
 * <p>
 * Replaces hutool ClassUtil.isBasicType() / ObjectUtil.isBasicType().
 *
 * @author wisdomme
 * @since 6.2.0
 */
public final class BasicTypeUtil {

    /**
     * The set of basic types, including their wrapper types.
     */
    private static final Set<Class<?>> BASIC_TYPES = new HashSet<>(Arrays.asList(
        // Primitive types
        boolean.class, byte.class, char.class, short.class,
        int.class, long.class, float.class, double.class, void.class,
        // Wrapper types
        Boolean.class, Byte.class, Character.class, Short.class,
        Integer.class, Long.class, Float.class, Double.class, Void.class,
        // String is also treated as a basic type
        String.class
    ));

    private BasicTypeUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Determines whether the given class is a basic type (including wrapper types and String).
     *
     * @param clazz the class
     * @return whether it is a basic type
     */
    public static boolean isBasicType(Class<?> clazz) {
        return clazz != null && BASIC_TYPES.contains(clazz);
    }

    /**
     * Determines whether the given object is a basic type.
     *
     * @param obj the object
     * @return whether it is a basic type
     */
    public static boolean isBasicType(Object obj) {
        return obj != null && isBasicType(obj.getClass());
    }
}
