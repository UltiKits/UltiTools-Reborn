package com.ultikits.ultitools.config.convert;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Immutable conversion location, with recursive dispatch through the same module registry.
 * Paths are whole keys: a key containing a dot is never split.
 *
 * @since 6.3.0
 */
public interface ConversionContext {
    /** @return the configuration file's module-relative name */
    String file();
    /** @return an immutable list of whole path segments */
    List<String> path();
    /** @return the declared Java type, including generic arguments */
    Type declaredType();
    /**
     * Converts a nested Java value using its runtime type and the same registry.
     * @param nested the nested Java value
     * @return plain data
     * @throws ConversionException when conversion fails
     */
    Object toPlain(Object nested) throws ConversionException;
    /**
     * Converts a nested document value through the same registry.
     * @param nested the nested document value
     * @param type the declared nested Java type
     * @param <V> the result type
     * @return the converted nested value
     * @throws ConversionException when conversion fails
     */
    <V> V fromPlain(Object nested, Type type) throws ConversionException;
}
