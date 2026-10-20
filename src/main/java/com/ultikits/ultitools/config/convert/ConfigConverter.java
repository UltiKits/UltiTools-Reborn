package com.ultikits.ultitools.config.convert;

/**
 * Converts one Java value type to and from the configuration plain-data model.
 * Implementations must return plain data from {@link #toPlain(Object, ConversionContext)}.
 * For every value {@code x} of the declared type whose collections and arrays contain no null
 * element, {@code fromPlain(toPlain(x))} equals {@code x}. Typed collection and reference-array
 * null elements are omitted on write with one located warning per field; read behavior is unchanged.
 * Null map values and null whole fields remain plain data and round-trip.
 * For every canonical plain value {@code p} emitted by the converter ({@code p = toPlain(x)}),
 * {@code toPlain(fromPlain(p))} equals {@code p}. A converter may also accept noncanonical input
 * {@code q}; its canonical form is {@code toPlain(fromPlain(q))}, and normalization must be stable:
 * {@code fromPlain(toPlain(fromPlain(q)))} equals {@code fromPlain(q)}.
 * Equality is semantic value equality, not object identity; numeric plain values compare by value.
 * Accepted coercions, such as numeric text to a number or duplicate list elements to a set, do not
 * require preserving the noncanonical input representation. The framework relies on forward equality
 * for reload merges and leaf edits, including preservation of untouched in-memory siblings.
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
