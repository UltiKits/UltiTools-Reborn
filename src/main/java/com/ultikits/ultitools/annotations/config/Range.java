package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a numeric config value falls within the specified range.
 * If the value is out of range, the module refuses to load naming the field, the actual value,
 * and the violated bounds - the config file is never rewritten (D-01). On a reload the reload is
 * refused instead and the running values are kept.
 * <p>
 * Since 6.3.0 (#625) the value must satisfy {@code min <= value && value <= max}, compared as a
 * {@code double}: NaN ({@code .nan} in YAML) is out of every range, and positive or negative infinity
 * ({@code .inf}, {@code -.inf}) is out of range unless the bound on that side is itself that infinity,
 * for example {@code max = Double.POSITIVE_INFINITY}.
 * <p>
 * {@code @Range} checks numbers only: a primitive number or a {@code Number} such as {@code Integer} or
 * {@code BigDecimal}.
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
public @interface Range {
    /** Minimum allowed value (inclusive). Default: -Double.MAX_VALUE */
    double min() default -Double.MAX_VALUE;

    /** Maximum allowed value (inclusive). Default: Double.MAX_VALUE */
    double max() default Double.MAX_VALUE;
}
