package com.ultikits.ultitools.config.convert;

/**
 * Converts one Java value type to and from the configuration plain-data model.
 * Implementations must return plain data from {@link #toPlain(Object, ConversionContext)}.
 * Registered converters must satisfy both round-trip equations: {@code fromPlain(toPlain(x))}
 * equals {@code x}, and {@code toPlain(fromPlain(p))} equals {@code p} for every plain value
 * {@code p} they accept. Equality is semantic value equality, not object identity; numeric
 * plain values compare by value. The framework relies on this contract for reload merges and
 * leaf edits, including preservation of untouched in-memory siblings.
 *
 * @param <T> the Java value type
 * @since 6.3.0
 */
public interface ConfigConverter<T> {
    /**
     * Converts a Java value to plain data.
     * @param value the Java value
     * @param ctx the immutable conversion location and recursive dispatcher
     * @return plain data
     * @throws ConversionException when conversion cannot preserve the value
     */
    Object toPlain(T value, ConversionContext ctx) throws ConversionException;

    /**
     * Converts plain data to a Java value.
     * @param plain the value read from the document
     * @param ctx the immutable conversion location and recursive dispatcher
     * @return the Java value
     * @throws ConversionException when the input cannot be converted
     */
    T fromPlain(Object plain, ConversionContext ctx) throws ConversionException;
}
