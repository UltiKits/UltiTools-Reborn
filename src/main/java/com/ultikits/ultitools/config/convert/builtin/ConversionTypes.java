package com.ultikits.ultitools.config.convert.builtin;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;
import com.google.common.reflect.TypeToken;

/** Type resolution for reflective declarations, generic ancestors, bounds, and arrays. */
@ApiStatus.Internal
public final class ConversionTypes {
    private ConversionTypes() { }

    /**

     * @param type the declared type

     * @return its erased class after resolving bounds

     */
    public static Class<?> raw(Type type) {
        type = bound(type);
        if (type instanceof Class<?>) { return (Class<?>) type; }
        if (type instanceof ParameterizedType) { return raw(((ParameterizedType) type).getRawType()); }
        if (type instanceof GenericArrayType) { return Array.newInstance(raw(component(type)), 0).getClass(); }
        throw new IllegalArgumentException("Unsupported declared type " + type);
    }

    /**

     * @param type the declared type

     * @return its effective bound

     */
    public static Type bound(Type type) {
        Set<Type> visited = new HashSet<>();
        while (visited.add(type)) {
            if (type instanceof TypeVariable<?>) { type = ((TypeVariable<?>) type).getBounds()[0]; }
            else if (type instanceof WildcardType) {
                WildcardType wildcard = (WildcardType) type;
                type = wildcard.getLowerBounds().length == 0 ? wildcard.getUpperBounds()[0] : wildcard.getLowerBounds()[0];
            } else { return type; }
        }
        return Object.class;
    }

    /**

     * @param type an array declaration

     * @return its full component type

     */
    public static Type component(Type type) {
        type = bound(type);
        return type instanceof GenericArrayType ? ((GenericArrayType) type).getGenericComponentType()
                : ((Class<?>) type).getComponentType();
    }

    /**
     * Resolves the arguments of a generic ancestor, including concrete subclasses.
     * @param type the declared class or parameterized type
     * @param ancestor the collection/map ancestor
     * @param index the requested ancestor argument
     * @return the substituted type, or Object for a raw declaration
     */
    public static Type argument(Type type, Class<?> ancestor, int index) {
        return TypeToken.of(type).resolveType(ancestor.getTypeParameters()[index]).getType();
    }

    /**

     * @param type primitive or reference class

     * @return its wrapper or itself

     */
    public static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) { return Boolean.class; }
        if (type == byte.class) { return Byte.class; }
        if (type == short.class) { return Short.class; }
        if (type == int.class) { return Integer.class; }
        if (type == long.class) { return Long.class; }
        if (type == float.class) { return Float.class; }
        if (type == double.class) { return Double.class; }
        if (type == char.class) { return Character.class; }
        return type;
    }

}
