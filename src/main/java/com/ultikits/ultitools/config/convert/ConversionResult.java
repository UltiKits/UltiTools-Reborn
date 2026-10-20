package com.ultikits.ultitools.config.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal converted value and all nested diagnostics collected during that conversion.
 * The public converter contract still returns the value itself.
 * @param <T> the converted Java type
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConversionResult<T> {
    private final T value;
    private final List<ConversionFailure> failures;

    ConversionResult(T value, List<ConversionFailure> failures) {
        this.value = value;
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
    }

    /** @return the converted value */
    public T value() { return value; }
    /** @return immutable diagnostics from every nested context */
    public List<ConversionFailure> failures() { return failures; }
}
