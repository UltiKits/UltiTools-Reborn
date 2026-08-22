package com.ultikits.ultitools.aop.crosspackage;

import com.ultikits.ultitools.annotations.ExceptionCatch;

/**
 * A generic superclass. A subclass that overrides {@code take} with a concrete type makes the
 * compiler emit a bridge, and the erased declaration here stops being {@code super}-invokable.
 *
 * @param <T> the accepted type
 */
@ExceptionCatch(silent = true, defaultValue = "class-level")
public class GenericBase<T> {

    public void take(T value) { }
}
