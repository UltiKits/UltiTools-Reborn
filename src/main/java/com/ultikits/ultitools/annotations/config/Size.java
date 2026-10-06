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
 * annotation (since 6.3.0, #631; maintainer decision of 2026-10-06). So is this annotation on a field that is not a
 * {@code @ConfigEntry} setting, or on a field of a value type reached through a setting - the setting's own class, every
 * type argument and array component (wildcards and type variables resolved as the binder resolves them) and, for each
 * class reached, its non-static, non-transient fields and supertypes, transitively, not into another config class (a
 * converter builds that value and the framework never validates its fields - validate them in the converter). The check
 * reaches at least every type the binder can bind and refuses a type it cannot walk with certainty (since 6.3.0, #633).
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
