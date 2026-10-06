package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a config value's size lies within the specified bounds. The size counted is a text's length
 * ({@code String.length()}; a {@code char} is one character), a list's or set's size, a map's number of entries and an
 * array's length (maps and arrays since 6.3.0, #631). If the size is out of bounds, the module refuses to load naming
 * the field, the counted size and the violated bounds (on a reload, the reload is refused and the running values are
 * kept) - the config file is never rewritten (D-01).
 * <p>
 * On a value type it cannot check, the module is refused at load, before the file is read, naming the field and the
 * annotation (since 6.3.0, #631; maintainer decision of 2026-10-06). So is this annotation on a field of a config class
 * that is not a {@code @ConfigEntry} setting.
 * <p>
 * It takes effect only on a field that is itself a {@code @ConfigEntry} setting of a config class, judged by that field's
 * declared type. A declared type that can hold a value the annotation checks - {@code Object}, {@code Serializable},
 * {@code Comparable}, {@code CharSequence}, {@code Number} or another supertype of a checked kind - is accepted, and the
 * value bound at load is checked; only a type that can never hold a checkable value is a declaration error. On a field of a value type, a nested class or anything a converter produces it is never checked and not
 * reported (maintainer decision of 2026-10-06): validate such fields in the module's converter.
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Size {
    /** Minimum size (inclusive). Default: 0 */
    int min() default 0;

    /** Maximum size (inclusive). Default: Integer.MAX_VALUE */
    int max() default Integer.MAX_VALUE;
}
