package com.ultikits.ultitools.aop.crosspackage;

/**
 * A generic superclass. A subclass that overrides {@code take} with a concrete type makes the
 * compiler emit a bridge, and the erased declaration here stops being {@code super}-invokable.
 *
 * @param <T> the accepted type
 */
public class GenericBase<T> {

    public void take(T value) { }
}
