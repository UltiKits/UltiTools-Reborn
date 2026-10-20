package com.ultikits.ultitools.config.convert;

import java.lang.reflect.Type;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.document.PlainData;

/**
 * One refused nested input, retained independently of the successfully converted values.
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConversionFailure {
    private final ConversionException cause;
    private final Object raw;

    ConversionFailure(Object raw, ConversionException cause) {
        this.raw = PlainData.copy(raw);
        this.cause = cause;
    }

    /** @return the immutable whole path, including an element index when present */
    public List<String> path() { return cause.path(); }
    /** @return the declared type at the failed location */
    public Type declaredType() { return cause.declaredType(); }
    /** @return a defensive copy of the refused document value */
    public Object raw() { return PlainData.copy(raw); }
    /** @return the conversion failure and its location */
    public ConversionException cause() { return cause; }
}
